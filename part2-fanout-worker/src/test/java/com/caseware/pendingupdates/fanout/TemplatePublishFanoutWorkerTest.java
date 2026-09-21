package com.caseware.pendingupdates.fanout;

import static com.caseware.pendingupdates.fanout.TestDoubles.fastOptions;
import static com.caseware.pendingupdates.fanout.TestDoubles.publish;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.caseware.pendingupdates.fanout.TestDoubles.FakeLoader;
import com.caseware.pendingupdates.fanout.TestDoubles.InMemoryIndex;
import com.caseware.pendingupdates.fanout.TestDoubles.InMemoryProgress;
import com.caseware.pendingupdates.fanout.TestDoubles.RecordingDeadLetters;
import com.caseware.pendingupdates.fanout.TestDoubles.StaticDirectory;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class TemplatePublishFanoutWorkerTest {

    private final StaticDirectory directory = new StaticDirectory(20);
    private final FakeLoader loader = new FakeLoader();
    private final InMemoryIndex index = new InMemoryIndex();
    private final InMemoryProgress progress = new InMemoryProgress();
    private final RecordingDeadLetters deadLetters = new RecordingDeadLetters();

    private TemplatePublishFanoutWorker worker(CapacityBudget budget, int maxAttempts) {
        return new TemplatePublishFanoutWorker(directory, loader, index, progress, deadLetters,
                budget, fastOptions(maxAttempts));
    }

    @Test
    @DisplayName("every enumerated unit reaches a terminal state and the report balances")
    void happyPath() throws Exception {
        try (var worker = worker(new CapacityBudget(4), 3)) {
            FanoutReport report = worker.run(publish("pub-1", "tpl-a", "v2"));

            assertEquals(20, report.enumerated());
            assertEquals(20, report.completed());
            assertEquals(0, report.deadLettered());
            assertTrue(report.balanced(), report.summary());
            assertTrue(report.workListExhausted());
            assertEquals(1, directory.closed.get(), "work-list stream must be closed");
        }
    }

    @Test
    @DisplayName("redelivering the same publish skips completed units instead of reloading them")
    void idempotentOnRedelivery() throws Exception {
        PublishEvent event = publish("pub-1", "tpl-a", "v2");
        try (var worker = worker(new CapacityBudget(4), 3)) {
            worker.run(event);
            assertEquals(20, loader.loadedIds.size());

            FanoutReport second = worker.run(event);

            assertEquals(20, second.enumerated());
            assertEquals(20, second.skipped());
            assertEquals(0, second.completed());
            assertTrue(second.balanced());
            assertEquals(20, loader.loadedIds.size(), "no engagement may be loaded twice");
        }
    }

    @Test
    @DisplayName("a different publishId is independent work, even over the same engagements")
    void progressIsScopedToPublish() throws Exception {
        try (var worker = worker(new CapacityBudget(4), 3)) {
            worker.run(publish("pub-1", "tpl-a", "v2"));
            FanoutReport other = worker.run(publish("pub-2", "tpl-a", "v3"));

            assertEquals(20, other.completed());
            assertEquals(0, other.skipped());
        }
    }

    @Test
    @DisplayName("concurrent downstream calls never exceed the capacity budget")
    void respectsCapacityBudget() throws Exception {
        loader.withLatency(Duration.ofMillis(25));
        try (var worker = worker(new CapacityBudget(3), 3)) {
            worker.run(publish("pub-1", "tpl-a", "v2"));

            assertTrue(loader.maxConcurrent.get() <= 3,
                    "observed " + loader.maxConcurrent.get() + " concurrent loads, budget was 3");
            assertTrue(loader.maxConcurrent.get() > 1, "test is meaningless if it never parallelised");
        }
    }

    @Test
    @DisplayName("lowering the budget mid-flight is honoured without dropping work")
    void budgetCanShrinkAtRuntime() throws Exception {
        StaticDirectory big = new StaticDirectory(60);
        FakeLoader slow = new FakeLoader().withLatency(Duration.ofMillis(15));
        CapacityBudget budget = new CapacityBudget(8);
        var worker = new TemplatePublishFanoutWorker(big, slow, index, progress, deadLetters,
                budget, fastOptions(3));
        try (worker) {
            Thread shrink = new Thread(() -> {
                try { Thread.sleep(60); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                budget.setLimit(2);
            });
            shrink.start();
            FanoutReport report = worker.run(publish("pub-shrink", "tpl-a", "v2"));
            shrink.join();

            assertEquals(2, budget.limit());
            assertEquals(60, report.completed(), "shrinking must not lose units: " + report.summary());
            assertTrue(report.balanced());
        }
    }

    @Test
    @DisplayName("transient failures are retried and then succeed")
    void retriesTransientFailures() throws Exception {
        loader.failTransiently("eng-3", 2);
        try (var worker = worker(new CapacityBudget(4), 4)) {
            FanoutReport report = worker.run(publish("pub-1", "tpl-a", "v2"));

            assertEquals(20, report.completed());
            assertEquals(2, report.retried());
            assertEquals(0, report.deadLettered());
            assertEquals(1, loader.loadAttemptsFor("eng-3"), "should have succeeded exactly once");
        }
    }

    @Test
    @DisplayName("permanent failures are dead-lettered on the first attempt, not retried")
    void doesNotRetryPermanentFailures() throws Exception {
        loader.failPermanently("eng-7");
        try (var worker = worker(new CapacityBudget(4), 4)) {
            FanoutReport report = worker.run(publish("pub-1", "tpl-a", "v2"));

            assertEquals(19, report.completed());
            assertEquals(1, report.deadLettered());
            assertEquals(0, report.retried(), "a permanent failure must not consume retry capacity");
            assertEquals(java.util.List.of("eng-7"), deadLetters.ids());
            assertEquals(1, deadLetters.failures.get(0).attempts());
            assertTrue(report.balanced());
        }
    }

    @Test
    @DisplayName("one unit exhausting its retries does not stall the publish")
    void deadLettersAfterExhaustingRetries() throws Exception {
        loader.failTransiently("eng-5", 99);
        try (var worker = worker(new CapacityBudget(4), 3)) {
            FanoutReport report = worker.run(publish("pub-1", "tpl-a", "v2"));

            assertEquals(19, report.completed());
            assertEquals(1, report.deadLettered());
            assertEquals(2, report.retried());
            assertEquals(3, deadLetters.failures.get(0).attempts());
            assertTrue(report.balanced());
        }
    }

    @Test
    @DisplayName("an overload signal cools the shared budget down, then work resumes")
    void overloadTriggersSharedCooldown() throws Exception {
        loader.overload("eng-2", 1);
        CapacityBudget budget = new CapacityBudget(4);
        try (var worker = worker(budget, 3)) {
            FanoutReport report = worker.run(publish("pub-1", "tpl-a", "v2"));

            assertEquals(20, report.completed());
            assertEquals(1, report.retried());
            assertEquals(0, report.deadLettered());
        }
    }

    @Test
    @DisplayName("a stale observation is ignored by the index but still completes the unit")
    void staleObservationDoesNotLoop() throws Exception {
        index.seed("eng-4", "v9", 500L);       // index already ahead of what we will observe
        loader.withSequence("eng-4", 100L);
        try (var worker = worker(new CapacityBudget(4), 3)) {
            FanoutReport report = worker.run(publish("pub-1", "tpl-a", "v2"));

            assertEquals(1, report.staleIgnored());
            assertEquals(20, report.completed(), "stale must be terminal, not retried forever");
            assertEquals(0, report.deadLettered());
            assertEquals("v9", index.versionOf("eng-4").orElseThrow().value(),
                    "newer state must not be regressed by an older observation");
        }
    }

    @Test
    @DisplayName("re-observing a changed version is reported as divergence")
    void reportsDivergenceOnReconciliation() throws Exception {
        index.seed("eng-1", "v1", 10L);        // index thinks v1 ...
        loader.withVersion("v5").withSequence("eng-1", 99L);   // ... reality is v5
        try (var worker = worker(new CapacityBudget(4), 3)) {
            FanoutReport report = worker.run(publish("pub-1", "tpl-a", "v2"));

            assertEquals(1, report.diverged(), report.summary());
            assertEquals(20, report.completed());
        }
    }

    @Test
    @DisplayName("cancelling a withdrawn publish stops further loads and keeps the report balanced")
    void cancellationStopsWork() throws Exception {
        StaticDirectory big = new StaticDirectory(400);
        CountDownLatch started = new CountDownLatch(1);
        FakeLoader blocking = new FakeLoader()
                .withLatency(Duration.ofMillis(50))
                .onBeforeLoad(started::countDown);
        var worker = new TemplatePublishFanoutWorker(big, blocking, index, progress, deadLetters,
                new CapacityBudget(2), fastOptions(3));
        try (worker) {
            Thread canceller = new Thread(() -> {
                try {
                    started.await();
                    Thread.sleep(30);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                worker.cancel("pub-withdrawn");
            });
            canceller.start();
            FanoutReport report = worker.run(publish("pub-withdrawn", "tpl-a", "v2"));
            canceller.join();

            assertTrue(report.cancelled(), report.summary());
            assertFalse(report.workListExhausted(), "should have stopped before the end");
            assertTrue(report.enumerated() < 400, "enumeration must stop early: " + report.summary());
            assertTrue(report.balanced(), report.summary());
        }
    }

    @Test
    @DisplayName("running the same publishId concurrently is rejected as duplicated work")
    void rejectsConcurrentDuplicatePublish() throws Exception {
        StaticDirectory big = new StaticDirectory(200);
        FakeLoader slow = new FakeLoader().withLatency(Duration.ofMillis(20));
        var worker = new TemplatePublishFanoutWorker(big, slow, index, progress, deadLetters,
                new CapacityBudget(2), fastOptions(3));
        try (worker) {
            PublishEvent event = publish("pub-dup", "tpl-a", "v2");
            Thread first = new Thread(() -> {
                try { worker.run(event); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            first.start();
            while (!worker.isRunning("pub-dup")) Thread.onSpinWait();

            assertThrows(IllegalStateException.class, () -> worker.run(event));

            worker.cancel("pub-dup");
            first.join();
        }
    }

    @Test
    @DisplayName("the work list is consumed lazily, never materialised")
    void enumeratesLazily() throws Exception {
        StaticDirectory big = new StaticDirectory(500);
        FakeLoader slow = new FakeLoader().withLatency(Duration.ofMillis(10));
        var worker = new TemplatePublishFanoutWorker(big, slow, index, progress, deadLetters,
                new CapacityBudget(2), fastOptions(3));
        try (worker) {
            Thread canceller = new Thread(() -> {
                try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                worker.cancel("pub-lazy");
            });
            canceller.start();
            worker.run(publish("pub-lazy", "tpl-a", "v2"));
            canceller.join();

            assertTrue(big.yielded.get() < 500,
                    "directory yielded " + big.yielded.get() + " of 500; admission backpressure not applied");
        }
    }
}
