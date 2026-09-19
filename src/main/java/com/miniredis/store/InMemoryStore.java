package com.miniredis.store;

import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory implementation of {@link Store} backed by a hash map.
 *
 * <p>Value and expiry deadline are held together in one immutable
 * {@link Entry}, so a single map write replaces both atomically. Keeping
 * them in two separate maps would make SET a two-step update that a reader
 * could observe half-finished.
 *
 * <p>Expired keys are reclaimed two ways: lazily, when an operation happens
 * to touch one, and actively, by {@link ExpirationSweeper} calling
 * {@link #sweepExpired}. Lazy alone would leak keys nobody ever reads.
 */
public class InMemoryStore implements Store {

    /** Deadline for a key with no TTL; always compares as "not yet reached". */
    private static final long NO_EXPIRY = Long.MAX_VALUE;

    /**
     * A value plus its absolute expiry deadline. Immutable, so publishing a
     * reference publishes a consistent pair.
     */
    private record Entry(String value, long expiresAtMillis) {

        boolean isExpiredAt(long nowMillis) {
            return nowMillis >= expiresAtMillis;
        }
    }

    private final ConcurrentHashMap<String, Entry> data = new ConcurrentHashMap<>();
    private final Clock clock;

    /** Guards {@link #sweepCursor} only; deliberately not taken by get/set/del. */
    private final Object sweepLock = new Object();

    /** Round-robin position, so successive sweeps cover different keys. */
    private Iterator<Map.Entry<String, Entry>> sweepCursor;

    public InMemoryStore() {
        this(Clock.systemUTC());
    }

    /** Test seam: lets tests move time without sleeping. */
    public InMemoryStore(Clock clock) {
        this.clock = requireNonNull(clock, "clock");
    }

    @Override
    public void set(String key, String value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");
        // Writing a fresh Entry clears any previous TTL in the same atomic write.
        data.put(key, new Entry(value, NO_EXPIRY));
    }

    @Override
    public Optional<String> get(String key) {
        requireNonNull(key, "key");
        Entry entry = data.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.isExpiredAt(clock.millis())) {
            dropIfUnchanged(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public boolean del(String key) {
        requireNonNull(key, "key");
        Entry removed = data.remove(key);
        // An expired entry was already logically absent, so report false.
        return removed != null && !removed.isExpiredAt(clock.millis());
    }

    @Override
    public boolean exists(String key) {
        requireNonNull(key, "key");
        Entry entry = data.get(key);
        if (entry == null) {
            return false;
        }
        if (entry.isExpiredAt(clock.millis())) {
            dropIfUnchanged(key, entry);
            return false;
        }
        return true;
    }

    @Override
    public boolean expire(String key, long seconds) {
        requireNonNull(key, "key");
        long now = clock.millis();

        if (seconds <= 0) {
            return del(key);
        }

        long deadline = deadlineFrom(now, seconds);
        AtomicBoolean applied = new AtomicBoolean(false);

        // computeIfPresent runs atomically for this key, so a concurrent SET
        // cannot be lost between reading the old value and writing the new TTL.
        data.computeIfPresent(key, (k, existing) -> {
            if (existing.isExpiredAt(now)) {
                return null; // already dead: drop it, and report no TTL applied
            }
            applied.set(true);
            return new Entry(existing.value(), deadline);
        });

        return applied.get();
    }

    /**
     * Examines up to {@code budget} entries and removes those that have
     * expired, resuming where the previous call stopped.
     *
     * @return how many entries were removed
     */
    int sweepExpired(int budget) {
        synchronized (sweepLock) {
            long now = clock.millis();
            int removed = 0;

            for (int examined = 0; examined < budget; examined++) {
                if (sweepCursor == null || !sweepCursor.hasNext()) {
                    if (data.isEmpty()) {
                        return removed;
                    }
                    // Weakly consistent iterator: safe to hold across writes.
                    sweepCursor = data.entrySet().iterator();
                    if (!sweepCursor.hasNext()) {
                        return removed;
                    }
                }

                Map.Entry<String, Entry> candidate = sweepCursor.next();
                if (candidate.getValue().isExpiredAt(now)
                        && dropIfUnchanged(candidate.getKey(), candidate.getValue())) {
                    removed++;
                }
            }
            return removed;
        }
    }

    /** Number of entries still held, expired-but-unswept ones included. */
    int physicalSize() {
        return data.size();
    }

    /**
     * Removes the key only if it still maps to {@code expected}. Guards
     * against deleting a value another thread wrote after we read ours.
     */
    private boolean dropIfUnchanged(String key, Entry expected) {
        return data.remove(key, expected);
    }

    /** Saturates instead of overflowing on absurdly large TTLs. */
    private static long deadlineFrom(long nowMillis, long seconds) {
        try {
            return Math.addExact(nowMillis, Math.multiplyExact(seconds, 1000L));
        } catch (ArithmeticException overflow) {
            return NO_EXPIRY;
        }
    }

    private static <T> T requireNonNull(T arg, String name) {
        if (arg == null) {
            throw new NullPointerException(name + " must not be null");
        }
        return arg;
    }
}
