package com.caseware.pendingupdates.fanout;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The gate in front of the slow dependency. Two independent mechanisms:
 *
 * <ol>
 *   <li><b>A resizable permit pool</b> capping concurrent loads. Assumption A2 says the cap belongs
 *       to the team that owns the dependency, so {@link #setLimit(int)} changes it at runtime --
 *       a hard-coded constant would force our deploy cycle onto their capacity decisions.</li>
 *   <li><b>A shared cooldown</b> applied when the dependency reports overload. Backing off only the
 *       unit that got rejected is the wrong response: every unit shares one downstream, so the
 *       cooldown must gate all of them.</li>
 * </ol>
 *
 * <p>Thread-safe. Permits are acquired in fair order so a shrinking pool cannot starve waiters.
 */
public final class CapacityBudget {

    /** {@code reducePermits} is protected on {@link Semaphore}; this exposes it for shrinking. */
    private static final class ResizableSemaphore extends Semaphore {
        private static final long serialVersionUID = 1L;
        ResizableSemaphore(int permits) { super(permits, true); }
        void shrink(int by) { reducePermits(by); }
    }

    /** Held for the duration of one downstream call; released on close. */
    public interface Permit extends AutoCloseable {
        @Override void close();
    }

    private final ResizableSemaphore permits;
    private final AtomicLong cooldownUntilMillis = new AtomicLong(0);
    private final LongSupplier clockMillis;
    private final Sleeper sleeper;

    private final Object limitLock = new Object();
    private volatile int limit;

    public CapacityBudget(int initialLimit) {
        this(initialLimit, System::currentTimeMillis, Sleeper.real());
    }

    CapacityBudget(int initialLimit, LongSupplier clockMillis, Sleeper sleeper) {
        if (initialLimit < 1) throw new IllegalArgumentException("limit must be >= 1");
        this.permits = new ResizableSemaphore(initialLimit);
        this.limit = initialLimit;
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    public int limit() { return limit; }

    public int inUse() { return Math.max(0, limit - permits.availablePermits()); }

    /**
     * Changes the cap at runtime. Shrinking does not interrupt in-flight calls -- it withholds
     * future permits until the pool drains to the new size, so we give capacity back without
     * abandoning work already accepted.
     */
    public void setLimit(int newLimit) {
        if (newLimit < 1) throw new IllegalArgumentException("limit must be >= 1");
        synchronized (limitLock) {
            int delta = newLimit - limit;
            if (delta > 0) permits.release(delta);
            else if (delta < 0) permits.shrink(-delta);
            limit = newLimit;
        }
    }

    /** Records an overload signal. Extends, never shortens, an active cooldown. */
    public void enterCooldown(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        long until = clockMillis.getAsLong() + Math.max(0L, duration.toMillis());
        cooldownUntilMillis.accumulateAndGet(until, Math::max);
    }

    public boolean inCooldown() { return cooldownUntilMillis.get() > clockMillis.getAsLong(); }

    /** Waits out any cooldown, then takes a permit. Blocking here is the backpressure. */
    public Permit acquire() throws InterruptedException {
        awaitCooldown();
        permits.acquire();
        AtomicBoolean released = new AtomicBoolean(false);
        return () -> {
            if (released.compareAndSet(false, true)) permits.release();
        };
    }

    private void awaitCooldown() throws InterruptedException {
        while (true) {
            long remaining = cooldownUntilMillis.get() - clockMillis.getAsLong();
            if (remaining <= 0) return;
            sleeper.sleep(Duration.ofMillis(remaining));
        }
    }
}
