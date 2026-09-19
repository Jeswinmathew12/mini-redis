package com.miniredis.store;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Clock whose time only moves when a test moves it. Expiry tests then run
 * instantly and deterministically instead of sleeping and hoping.
 */
final class MutableClock extends Clock {

    private volatile Instant now;

    MutableClock() {
        this.now = Instant.parse("2026-01-01T00:00:00Z");
    }

    void advanceSeconds(long seconds) {
        now = now.plus(Duration.ofSeconds(seconds));
    }

    void advanceMillis(long millis) {
        now = now.plus(Duration.ofMillis(millis));
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
