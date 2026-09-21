package com.caseware.pendingupdates.fanout;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;

/**
 * Fans a template publish out across the engagements created from that template, recording each
 * one's current version in the index via the slow (~60s) load dependency.
 *
 * <h2>What this component is for</h2>
 * In steady state the pending-update indicator is served by a read-time join and needs no
 * per-engagement work at all (see the design document). This worker exists for the two jobs that
 * genuinely require the expensive call: <b>backfill</b> of the 800,000 engagements that predate the
 * feature, and continuous <b>reconciliation</b> sampling that measures whether the derived index has
 * drifted. It is the single caller of that dependency, so its capacity budget is enforced and
 * observed in one place.
 *
 * <h2>Design decisions</h2>
 * <ul>
 *   <li><b>Two separate gates.</b> An <i>admission</i> semaphore bounds how many units are in flight
 *       (bounding memory and parked threads); the {@link CapacityBudget} bounds how many are calling
 *       downstream. Conflating them would mean a unit sleeping between retries still occupies scarce
 *       downstream capacity.</li>
 *   <li><b>Permits wrap only the call.</b> A permit is taken immediately before {@code load} and
 *       released immediately after, never held across a backoff sleep.</li>
 *   <li><b>Virtual threads.</b> The work is ~60s of blocking I/O per unit, so a platform thread per
 *       unit would be almost entirely idle. Concurrency is limited by the budget, not by pool size.</li>
 *   <li><b>Streamed work list.</b> Units are enumerated lazily and admitted under backpressure, so a
 *       publish covering 20,000+ engagements never materialises.</li>
 *   <li><b>One bad unit cannot stall a publish.</b> Failures are classified, retried where that can
 *       help, and dead-lettered otherwise; the fan-out proceeds.</li>
 * </ul>
 *
 * <p>Instances are thread-safe and reusable; concurrent runs of <em>different</em> publishes are
 * supported and share one capacity budget. A second concurrent run of the <em>same</em> publishId is
 * rejected, since it would be pure duplicated work.
 */
public final class TemplatePublishFanoutWorker implements AutoCloseable {

    /** Tunables, separated from collaborators to keep the constructor readable. */
    public record Options(
            RetryPolicy retryPolicy,
            FanoutMetrics metrics,
            Sleeper sleeper,
            RandomGenerator random,
            Clock clock,
            int inFlightMultiplier,
            Duration drainPollInterval) {

        public Options {
            Objects.requireNonNull(retryPolicy, "retryPolicy");
            Objects.requireNonNull(metrics, "metrics");
            Objects.requireNonNull(sleeper, "sleeper");
            Objects.requireNonNull(random, "random");
            Objects.requireNonNull(clock, "clock");
            if (inFlightMultiplier < 1) throw new IllegalArgumentException("inFlightMultiplier must be >= 1");
            Objects.requireNonNull(drainPollInterval, "drainPollInterval");
        }

        public static Options defaults() {
            return new Options(RetryPolicy.defaults(), FanoutMetrics.noop(), Sleeper.real(),
                    RandomGenerator.getDefault(), Clock.systemUTC(), 4, Duration.ofSeconds(1));
        }

        public Options withRetryPolicy(RetryPolicy p) {
            return new Options(p, metrics, sleeper, random, clock, inFlightMultiplier, drainPollInterval);
        }

        public Options withMetrics(FanoutMetrics m) {
            return new Options(retryPolicy, m, sleeper, random, clock, inFlightMultiplier, drainPollInterval);
        }

        public Options withSleeper(Sleeper s) {
            return new Options(retryPolicy, metrics, s, random, clock, inFlightMultiplier, drainPollInterval);
        }
    }

    private final EngagementDirectory directory;
    private final EngagementLoader loader;
    private final TemplateIndexWriter index;
    private final ProgressStore progress;
    private final DeadLetterSink deadLetters;
    private final CapacityBudget budget;
    private final Options options;

    private final Map<String, Run> activeRuns = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public TemplatePublishFanoutWorker(
            EngagementDirectory directory,
            EngagementLoader loader,
            TemplateIndexWriter index,
            ProgressStore progress,
            DeadLetterSink deadLetters,
            CapacityBudget budget,
            Options options) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.index = Objects.requireNonNull(index, "index");
        this.progress = Objects.requireNonNull(progress, "progress");
        this.deadLetters = Objects.requireNonNull(deadLetters, "deadLetters");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.options = Objects.requireNonNull(options, "options");
    }

    /**
     * Processes one publish, returning when every admitted unit has reached a terminal state.
     *
     * <p>Safe to call again with the same {@link PublishEvent}: units already recorded in the
     * {@link ProgressStore} are skipped, so redelivery costs an enumeration pass rather than a
     * second ~60s call per engagement.
     *
     * @throws InterruptedException if the caller is interrupted while awaiting admission or drain
     */
    public FanoutReport run(PublishEvent event) throws InterruptedException {
        Objects.requireNonNull(event, "event");
        if (closed.get()) throw new IllegalStateException("worker is closed");

        Run run = new Run();
        if (activeRuns.putIfAbsent(event.publishId(), run) != null) {
            throw new IllegalStateException("publish already in progress: " + event.publishId());
        }

        long startNanos = System.nanoTime();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        run.pool = pool;
        boolean exhausted = false;
        try {
            Semaphore admission = new Semaphore(budget.limit() * options.inFlightMultiplier(), true);
            try (Stream<EngagementRef> work = directory.engagementsUsing(event.templateId())) {
                Iterator<EngagementRef> units = work.iterator();
                while (units.hasNext()) {
                    if (run.cancelled.get()) break;
                    EngagementRef ref = units.next();
                    run.enumerated.increment();

                    // Cheap check first: never spend a permit on work already done.
                    if (progress.isComplete(event.publishId(), ref.id())) {
                        run.skipped.increment();
                        options.metrics().unitSkipped();
                        continue;
                    }

                    admission.acquire();
                    try {
                        pool.execute(() -> {
                            try {
                                processUnit(event, ref, run);
                            } finally {
                                admission.release();
                            }
                        });
                    } catch (RejectedExecutionException rejected) {
                        admission.release();
                        run.abandoned.increment();
                        break;
                    }
                }
                exhausted = !units.hasNext() && !run.cancelled.get();
            }
        } finally {
            drain(pool, run);
            activeRuns.remove(event.publishId(), run);
        }

        return new FanoutReport(
                event.publishId(),
                run.enumerated.sum(),
                run.skipped.sum(),
                run.completed.sum(),
                run.diverged.sum(),
                run.staleIgnored.sum(),
                run.retried.sum(),
                run.deadLettered.sum(),
                run.abandoned.sum(),
                run.cancelled.get(),
                exhausted,
                Duration.ofNanos(System.nanoTime() - startNanos));
    }

    /**
     * Stops an in-progress publish and interrupts its in-flight loads.
     *
     * <p>Exists because a published version can be <em>withdrawn</em>: once that happens the
     * remaining work is not merely unnecessary, it is work toward an answer we no longer want to
     * show. Completed units stay completed and recorded -- they are observations of engagement
     * state, which withdrawal does not invalidate.
     *
     * @return false if no such publish is running
     */
    public boolean cancel(String publishId) {
        Run run = activeRuns.get(Objects.requireNonNull(publishId, "publishId"));
        if (run == null) return false;
        run.cancelled.set(true);
        ExecutorService pool = run.pool;
        if (pool != null) pool.shutdownNow();
        return true;
    }

    public boolean isRunning(String publishId) { return activeRuns.containsKey(publishId); }

    /** Cancels every active run. Does not wait; each {@link #run} returns on its own drain. */
    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            activeRuns.keySet().forEach(this::cancel);
        }
    }

    // ----------------------------------------------------------------------------------------

    // The permit resource is intentionally unreferenced in its try body: holding it for the
    // duration of the downstream call IS its purpose.
    @SuppressWarnings("try")
    private void processUnit(PublishEvent event, EngagementRef ref, Run run) {
        int attempt = 0;
        while (true) {
            if (run.cancelled.get() || Thread.currentThread().isInterrupted()) {
                run.abandoned.increment();
                return;
            }
            attempt++;
            try {
                LoadedEngagement observed;
                try (CapacityBudget.Permit permit = budget.acquire()) {
                    options.metrics().capacityInUse(budget.inUse(), budget.limit());
                    observed = loader.load(ref);
                }
                applyObservation(event, ref, observed, run);
                return;

            } catch (DownstreamException.Permanent permanent) {
                // No retry: a minute of shared capacity to fail identically buys nothing.
                deadLetter(event, ref, attempt, permanent, run);
                return;

            } catch (DownstreamException.Overloaded overloaded) {
                // Global, not per-unit: everyone shares this dependency.
                budget.enterCooldown(overloaded.retryAfter());
                options.metrics().overloadSignalled(overloaded.retryAfter());
                if (!backoff(event, ref, attempt, overloaded, run)) return;

            } catch (DownstreamException.Transient transientFailure) {
                if (!backoff(event, ref, attempt, transientFailure, run)) return;

            } catch (DownstreamException other) {
                // Unreachable for the sealed hierarchy above; kept so a future subtype fails safe.
                deadLetter(event, ref, attempt, other, run);
                return;

            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                run.abandoned.increment();
                return;

            } catch (RuntimeException bug) {
                // Our defect, not the dependency's. Park it rather than retry it.
                deadLetter(event, ref, attempt, bug, run);
                return;
            }
        }
    }

    private void applyObservation(PublishEvent event, EngagementRef ref, LoadedEngagement observed, Run run) {
        switch (index.recordObservation(observed)) {
            case TemplateIndexWriter.IndexWriteOutcome.Applied applied -> {
                boolean diverged = applied.previousVersion()
                        .filter(previous -> !previous.equals(observed.currentVersion()))
                        .isPresent();
                if (diverged) run.diverged.increment();
                run.completed.increment();
                options.metrics().unitCompleted(diverged);
            }
            case TemplateIndexWriter.IndexWriteOutcome.IgnoredStale ignored -> {
                // Counts as done on purpose. Our observation lost to a newer one, which means the
                // index is already ahead of us; re-reading would burn a minute to lose again.
                run.staleIgnored.increment();
                run.completed.increment();
                options.metrics().unitStaleIgnored();
            }
        }
        // Marked only after the write is durable, so a crash in between re-does the unit rather
        // than skipping it. Duplicated work is recoverable; a silently skipped unit is not.
        progress.markComplete(event.publishId(), ref.id());
    }

    /** @return true to attempt again, false if the unit is finished (dead-lettered or interrupted) */
    private boolean backoff(PublishEvent event, EngagementRef ref, int attempt, Exception cause, Run run) {
        if (attempt >= options.retryPolicy().maxAttempts()) {
            deadLetter(event, ref, attempt, cause, run);
            return false;
        }
        run.retried.increment();
        options.metrics().unitRetried();
        try {
            options.sleeper().sleep(options.retryPolicy().delayFor(attempt, options.random()));
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            run.abandoned.increment();
            return false;
        }
    }

    private void deadLetter(PublishEvent event, EngagementRef ref, int attempts, Exception cause, Run run) {
        run.deadLettered.increment();
        options.metrics().unitDeadLettered();
        deadLetters.accept(new DeadLetterSink.FanoutFailure(
                event.publishId(), ref, attempts,
                String.valueOf(cause.getMessage()),
                cause.getClass().getSimpleName(),
                options.clock().instant()));
    }

    /** Waits for admitted tasks to finish. Cancelled runs are interrupted rather than awaited. */
    private void drain(ExecutorService pool, Run run) throws InterruptedException {
        if (run.cancelled.get()) pool.shutdownNow();
        else pool.shutdown();
        long pollMillis = Math.max(1L, options.drainPollInterval().toMillis());
        while (!pool.awaitTermination(pollMillis, TimeUnit.MILLISECONDS)) {
            if (run.cancelled.get()) pool.shutdownNow();
        }
    }

    private static final class Run {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile ExecutorService pool;
        final LongAdder enumerated = new LongAdder();
        final LongAdder skipped = new LongAdder();
        final LongAdder completed = new LongAdder();
        final LongAdder diverged = new LongAdder();
        final LongAdder staleIgnored = new LongAdder();
        final LongAdder retried = new LongAdder();
        final LongAdder deadLettered = new LongAdder();
        final LongAdder abandoned = new LongAdder();
    }
}
