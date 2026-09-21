package com.caseware.pendingupdates.fanout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private final RandomGenerator fixed = new RandomGenerator() {
        @Override public long nextLong() { return 0; }
        @Override public long nextLong(long bound) { return bound - 1; }   // always the ceiling
    };

    @Test
    @DisplayName("backoff doubles per attempt and is capped")
    void backoffDoublesUpToCap() {
        RetryPolicy policy = new RetryPolicy(10, Duration.ofSeconds(2), Duration.ofSeconds(16));

        // The fixed generator always returns the ceiling of the jitter window, so the observed
        // delay is exactly the backoff for that attempt.
        assertEquals(2_000, policy.delayFor(1, fixed).toMillis());
        assertEquals(4_000, policy.delayFor(2, fixed).toMillis());
        assertEquals(8_000, policy.delayFor(3, fixed).toMillis());
        assertEquals(16_000, policy.delayFor(4, fixed).toMillis());
        assertEquals(16_000, policy.delayFor(9, fixed).toMillis(), "must stay capped");
    }

    @Test
    @DisplayName("a very large attempt number cannot overflow the doubling")
    void doesNotOverflow() {
        RetryPolicy policy = new RetryPolicy(100, Duration.ofSeconds(1), Duration.ofMinutes(5));
        Duration delay = policy.delayFor(99, fixed);

        assertTrue(delay.compareTo(Duration.ofMinutes(5)) <= 0, "got " + delay);
        assertTrue(delay.toMillis() >= 0);
    }

    @Test
    @DisplayName("jitter spreads retries instead of aligning them")
    void appliesFullJitter() {
        RetryPolicy policy = new RetryPolicy(5, Duration.ofSeconds(4), Duration.ofSeconds(30));
        RandomGenerator random = RandomGenerator.getDefault();

        Set<Long> observed = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            long millis = policy.delayFor(3, random).toMillis();
            assertTrue(millis >= 0 && millis <= 16_000, "outside [0, backoff]: " + millis);
            observed.add(millis);
        }
        assertTrue(observed.size() > 50, "jitter produced only " + observed.size() + " distinct delays");
    }

    @Test
    @DisplayName("the no-delay policy never sleeps")
    void noDelayPolicyIsZero() {
        RetryPolicy policy = RetryPolicy.noDelay(3);
        assertEquals(Duration.ZERO, policy.delayFor(1, fixed));
        assertEquals(Duration.ZERO, policy.delayFor(3, fixed));
    }

    @Test
    @DisplayName("invalid policies are rejected at construction")
    void validatesArguments() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, Duration.ZERO, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(3, Duration.ofSeconds(10), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(3, Duration.ofSeconds(-1), Duration.ofSeconds(1)));
    }
}
