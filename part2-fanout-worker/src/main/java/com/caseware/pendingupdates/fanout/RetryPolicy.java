package com.caseware.pendingupdates.fanout;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Capped exponential backoff with full jitter.
 *
 * <p>Jitter is not decoration. A publish can fail thousands of units against the same dependency at
 * the same moment; without jitter they retry in lockstep and the retry storm is worse than the
 * original failure. Full jitter (uniform over {@code [0, backoff]}) spreads them.
 *
 * <p>{@code maxAttempts} is deliberately small. Each attempt costs ~60 seconds of a scarce shared
 * resource, so persistent failures belong in the dead-letter queue, not in a retry loop.
 */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

    public RetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (baseDelay == null || baseDelay.isNegative()) throw new IllegalArgumentException("baseDelay must be >= 0");
        if (maxDelay == null || maxDelay.isNegative()) throw new IllegalArgumentException("maxDelay must be >= 0");
        if (maxDelay.compareTo(baseDelay) < 0) throw new IllegalArgumentException("maxDelay must be >= baseDelay");
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(4, Duration.ofSeconds(2), Duration.ofMinutes(2));
    }

    /** No waiting, for tests that exercise retry logic without wall-clock cost. */
    public static RetryPolicy noDelay(int maxAttempts) {
        return new RetryPolicy(maxAttempts, Duration.ZERO, Duration.ZERO);
    }

    /**
     * Delay before the attempt following {@code completedAttempt} (1-based).
     * Doubling is computed iteratively against the cap, so it cannot overflow.
     */
    public Duration delayFor(int completedAttempt, RandomGenerator random) {
        if (completedAttempt < 1) throw new IllegalArgumentException("completedAttempt must be >= 1");
        long cap = maxDelay.toMillis();
        long backoff = Math.min(baseDelay.toMillis(), cap);
        for (int i = 1; i < completedAttempt && backoff < cap; i++) {
            backoff = Math.min(backoff * 2, cap);
        }
        if (backoff <= 0) return Duration.ZERO;
        return Duration.ofMillis(random.nextLong(backoff + 1));
    }
}
