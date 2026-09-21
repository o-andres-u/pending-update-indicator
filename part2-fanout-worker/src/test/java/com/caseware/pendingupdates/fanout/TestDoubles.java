package com.caseware.pendingupdates.fanout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/** In-memory collaborators. No frameworks, no mocking library -- the contracts are small enough. */
final class TestDoubles {

    private TestDoubles() { }

    static EngagementRef ref(String id) { return new EngagementRef(new EngagementId(id), "firm-1", "us"); }

    static PublishEvent publish(String publishId, String templateId, String version) {
        return new PublishEvent(publishId, new TemplateId(templateId), new TemplateVersion(version),
                java.time.Instant.parse("2026-03-01T00:00:00Z"));
    }

    /** A directory over a fixed id list, served lazily so laziness itself can be asserted. */
    static final class StaticDirectory implements EngagementDirectory {
        private final List<EngagementRef> refs;
        final AtomicInteger yielded = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();

        StaticDirectory(int count) {
            this.refs = IntStream.range(0, count).mapToObj(i -> ref("eng-" + i)).toList();
        }

        @Override public Stream<EngagementRef> engagementsUsing(TemplateId templateId) {
            return refs.stream().peek(r -> yielded.incrementAndGet()).onClose(closed::incrementAndGet);
        }
    }

    /** Scriptable loader that also tracks observed concurrency against the capacity budget. */
    static final class FakeLoader implements EngagementLoader {
        private final Map<String, Integer> failuresRemaining = new ConcurrentHashMap<>();
        private final Set<String> permanentFailures = ConcurrentHashMap.newKeySet();
        private final Map<String, Integer> overloadsRemaining = new ConcurrentHashMap<>();
        private final Map<String, Long> sequences = new ConcurrentHashMap<>();
        final List<String> loadedIds = new CopyOnWriteArrayList<>();
        final AtomicInteger concurrent = new AtomicInteger();
        final AtomicInteger maxConcurrent = new AtomicInteger();
        private volatile Duration latency = Duration.ZERO;
        private volatile TemplateVersion version = new TemplateVersion("v1");
        private volatile Runnable beforeLoad = () -> { };

        FakeLoader withLatency(Duration d) { this.latency = d; return this; }
        /** Hook so a test can observe the moment a load starts, without subclassing. */
        FakeLoader onBeforeLoad(Runnable hook) { this.beforeLoad = hook; return this; }
        FakeLoader withVersion(String v) { this.version = new TemplateVersion(v); return this; }
        FakeLoader failTransiently(String id, int times) { failuresRemaining.put(id, times); return this; }
        FakeLoader failPermanently(String id) { permanentFailures.add(id); return this; }
        FakeLoader overload(String id, int times) { overloadsRemaining.put(id, times); return this; }
        FakeLoader withSequence(String id, long seq) { sequences.put(id, seq); return this; }

        @Override public LoadedEngagement load(EngagementRef ref) throws DownstreamException, InterruptedException {
            String id = ref.id().value();
            beforeLoad.run();
            int now = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            try {
                if (!latency.isZero()) Thread.sleep(latency.toMillis());

                if (permanentFailures.contains(id)) {
                    throw new DownstreamException.Permanent("engagement gone: " + id);
                }
                Integer overloads = overloadsRemaining.get(id);
                if (overloads != null && overloads > 0) {
                    overloadsRemaining.put(id, overloads - 1);
                    throw new DownstreamException.Overloaded("over capacity", Duration.ofMillis(5));
                }
                Integer failures = failuresRemaining.get(id);
                if (failures != null && failures > 0) {
                    failuresRemaining.put(id, failures - 1);
                    throw new DownstreamException.Transient("timeout loading " + id);
                }
                loadedIds.add(id);
                return new LoadedEngagement(ref.id(), new TemplateId("tpl-a"), version,
                        sequences.getOrDefault(id, 100L));
            } finally {
                concurrent.decrementAndGet();
            }
        }

        long loadAttemptsFor(String id) { return loadedIds.stream().filter(id::equals).count(); }
    }

    /** Index with the real monotonic-sequence guard, which is what makes replays safe. */
    static final class InMemoryIndex implements TemplateIndexWriter {
        private record Entry(TemplateVersion version, long sequence) { }
        private final Map<String, Entry> byEngagement = new ConcurrentHashMap<>();
        final AtomicInteger writes = new AtomicInteger();

        void seed(String engagementId, String version, long sequence) {
            byEngagement.put(engagementId, new Entry(new TemplateVersion(version), sequence));
        }

        @Override public synchronized IndexWriteOutcome recordObservation(LoadedEngagement observed) {
            writes.incrementAndGet();
            String key = observed.id().value();
            Entry existing = byEngagement.get(key);
            if (existing != null && existing.sequence() > observed.sourceSequence()) {
                return new IndexWriteOutcome.IgnoredStale(existing.sequence());
            }
            byEngagement.put(key, new Entry(observed.currentVersion(), observed.sourceSequence()));
            return new IndexWriteOutcome.Applied(
                    Optional.ofNullable(existing).map(Entry::version));
        }

        Optional<TemplateVersion> versionOf(String engagementId) {
            return Optional.ofNullable(byEngagement.get(engagementId)).map(Entry::version);
        }
    }

    static final class InMemoryProgress implements ProgressStore {
        private final Set<String> done = ConcurrentHashMap.newKeySet();

        @Override public boolean isComplete(String publishId, EngagementId id) {
            return done.contains(publishId + "/" + id.value());
        }

        @Override public void markComplete(String publishId, EngagementId id) {
            done.add(publishId + "/" + id.value());
        }

        int size() { return done.size(); }
    }

    static final class RecordingDeadLetters implements DeadLetterSink {
        final List<FanoutFailure> failures = new CopyOnWriteArrayList<>();
        @Override public void accept(FanoutFailure failure) { failures.add(failure); }
        List<String> ids() {
            List<String> out = new ArrayList<>();
            failures.forEach(f -> out.add(f.ref().id().value()));
            return out;
        }
    }

    /** Options wired for fast tests: no real sleeping, deterministic jitter. */
    static TemplatePublishFanoutWorker.Options fastOptions(int maxAttempts) {
        return TemplatePublishFanoutWorker.Options.defaults()
                .withRetryPolicy(RetryPolicy.noDelay(maxAttempts))
                .withSleeper(Sleeper.none());
    }
}
