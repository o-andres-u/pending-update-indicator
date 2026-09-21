package com.caseware.pendingupdates.fanout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CapacityBudgetTest {

    @Test
    @DisplayName("permits are bounded by the limit and returned on close")
    void permitsAreBounded() throws Exception {
        CapacityBudget budget = new CapacityBudget(2);
        CapacityBudget.Permit a = budget.acquire();
        CapacityBudget.Permit b = budget.acquire();

        assertEquals(2, budget.inUse());
        a.close();
        assertEquals(1, budget.inUse());
        b.close();
        assertEquals(0, budget.inUse());
    }

    @Test
    @DisplayName("closing a permit twice releases capacity only once")
    void permitCloseIsIdempotent() throws Exception {
        CapacityBudget budget = new CapacityBudget(1);
        CapacityBudget.Permit permit = budget.acquire();
        permit.close();
        permit.close();

        assertEquals(1, budget.limit());
        assertEquals(0, budget.inUse());
        // If the double close had leaked a permit, two concurrent holders would now be possible.
        CapacityBudget.Permit only = budget.acquire();
        assertEquals(1, budget.inUse());
        only.close();
    }

    @Test
    @DisplayName("raising the limit admits more callers immediately")
    void limitCanGrow() throws Exception {
        CapacityBudget budget = new CapacityBudget(1);
        CapacityBudget.Permit held = budget.acquire();
        budget.setLimit(3);

        CapacityBudget.Permit second = budget.acquire();
        CapacityBudget.Permit third = budget.acquire();
        assertEquals(3, budget.inUse());

        held.close(); second.close(); third.close();
    }

    @Test
    @DisplayName("shrinking withholds future permits without interrupting in-flight work")
    void limitCanShrink() throws Exception {
        CapacityBudget budget = new CapacityBudget(3);
        CapacityBudget.Permit a = budget.acquire();
        CapacityBudget.Permit b = budget.acquire();
        CapacityBudget.Permit c = budget.acquire();

        budget.setLimit(1);
        assertEquals(1, budget.limit());

        a.close(); b.close();   // returns 2 of the 3, but the pool owes 2 back to the shrink
        assertFalse(tryAcquireWithin(budget, Duration.ofMillis(120)),
                "no permit should be available until the pool drains to the new size");

        c.close();
        assertTrue(tryAcquireWithin(budget, Duration.ofMillis(500)));
    }

    @Test
    @DisplayName("cooldown gates every caller, not just the one that was rejected")
    void cooldownIsShared() throws Exception {
        AtomicLong now = new AtomicLong(0);
        List<Duration> slept = new CopyOnWriteArrayList<>();
        Sleeper recording = duration -> { slept.add(duration); now.addAndGet(duration.toMillis()); };
        CapacityBudget budget = new CapacityBudget(4, now::get, recording);

        budget.enterCooldown(Duration.ofMillis(300));
        assertTrue(budget.inCooldown());

        budget.acquire().close();

        assertEquals(List.of(Duration.ofMillis(300)), slept, "acquire must wait out the shared cooldown");
        assertFalse(budget.inCooldown());
    }

    @Test
    @DisplayName("overlapping overload signals extend the cooldown, never shorten it")
    void cooldownTakesTheLatestDeadline() {
        AtomicLong now = new AtomicLong(1_000);
        CapacityBudget budget = new CapacityBudget(1, now::get, Sleeper.none());

        budget.enterCooldown(Duration.ofMillis(500));
        budget.enterCooldown(Duration.ofMillis(100));   // a shorter signal must not win

        now.set(1_400);
        assertTrue(budget.inCooldown(), "the longer deadline must still apply");
        now.set(1_600);
        assertFalse(budget.inCooldown());
    }

    @Test
    @DisplayName("invalid limits are rejected")
    void rejectsInvalidLimits() {
        assertThrows(IllegalArgumentException.class, () -> new CapacityBudget(0));
        assertThrows(IllegalArgumentException.class, () -> new CapacityBudget(2).setLimit(0));
    }

    private static boolean tryAcquireWithin(CapacityBudget budget, Duration timeout) throws InterruptedException {
        Thread attempt = new Thread(() -> {
            try { budget.acquire(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        attempt.start();
        attempt.join(timeout.toMillis());
        boolean acquired = !attempt.isAlive();
        attempt.interrupt();
        attempt.join();
        return acquired;
    }
}
