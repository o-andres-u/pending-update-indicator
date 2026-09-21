package com.caseware.pendingupdates.fanout;

import java.time.Duration;

/**
 * Observability hooks. Kept as an interface rather than a metrics-library dependency so this
 * component stays free of infrastructure choices.
 */
public interface FanoutMetrics {

    void unitSkipped();

    /** @param diverged the index held a different version than we observed (reconciliation signal) */
    void unitCompleted(boolean diverged);

    void unitStaleIgnored();

    void unitRetried();

    void unitDeadLettered();

    /** Emitted on every permit acquisition so saturation of the A2 budget is visible. */
    void capacityInUse(int inUse, int limit);

    void overloadSignalled(Duration retryAfter);

    static FanoutMetrics noop() {
        return new FanoutMetrics() {
            @Override public void unitSkipped() { }
            @Override public void unitCompleted(boolean diverged) { }
            @Override public void unitStaleIgnored() { }
            @Override public void unitRetried() { }
            @Override public void unitDeadLettered() { }
            @Override public void capacityInUse(int inUse, int limit) { }
            @Override public void overloadSignalled(Duration retryAfter) { }
        };
    }
}
