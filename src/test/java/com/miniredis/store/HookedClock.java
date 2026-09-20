package com.miniredis.store;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wraps a clock and lets a test run code inside the next {@link #millis()}
 * call. The store reads the clock between "I saw this entry" and "I act on
 * it", so the hook lets a test place a competing write at exactly that point
 * instead of hoping two threads collide.
 */
final class HookedClock extends Clock {

    private final Clock base;
    private final AtomicReference<Runnable> next = new AtomicReference<>();

    HookedClock(Clock base) {
        this.base = base;
    }

    /** Runs once, on the next clock read only. */
    void onNextRead(Runnable hook) {
        next.set(hook);
    }

    @Override
    public long millis() {
        Runnable hook = next.getAndSet(null);
        if (hook != null) {
            hook.run();
        }
        return base.millis();
    }

    @Override
    public Instant instant() {
        return base.instant();
    }

    @Override
    public ZoneId getZone() {
        return base.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
