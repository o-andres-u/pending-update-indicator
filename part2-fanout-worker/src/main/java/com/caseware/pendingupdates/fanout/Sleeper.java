package com.caseware.pendingupdates.fanout;

import java.time.Duration;

/** Indirection over sleeping, so backoff and cooldown are testable without real waiting. */
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    static Sleeper real() {
        return duration -> {
            long millis = duration.toMillis();
            if (millis > 0) Thread.sleep(millis);
        };
    }

    /** Returns immediately. Used by tests to exercise retry paths without wall-clock cost. */
    static Sleeper none() {
        return duration -> { };
    }
}
