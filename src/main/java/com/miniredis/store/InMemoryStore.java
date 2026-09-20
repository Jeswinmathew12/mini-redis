package com.miniredis.store;

import java.time.Clock;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory implementation of {@link Store}.
 *
 * <p><b>Concurrency: one lock guards everything.</b> The key map and the
 * expiry index are plain, non-thread-safe collections that are only touched
 * while holding {@link #lock}. Each public operation is therefore atomic with
 * respect to every other, including the background sweeper, so "check whether
 * it expired, then remove it" can never race with a concurrent write. The
 * price is that reads no longer run in parallel; every operation is O(1) or
 * O(log n) and network I/O dominates, so the lock is short-held and cheap.
 * This is also the shape LRU eviction needs, since a read must reorder shared
 * recency state.
 *
 * <p><b>Expiry.</b> Keys with a TTL are also listed in a deadline-ordered
 * index. Expired keys are reclaimed two ways: lazily, when an operation
 * touches one, and actively, by {@link ExpirationSweeper} calling
 * {@link #sweepExpired}, which pops from the front of that index. The sweeper
 * therefore does work proportional to what expired and never scans live keys.
 */
public class InMemoryStore implements Store {

    /** Deadline for a key with no TTL; such keys are not in the expiry index. */
    private static final long NO_EXPIRY = Long.MAX_VALUE;

    /** A stored key. Mutated only while holding the store lock. */
    private static final class Node {
        final String key;
        String value;
        long expiresAtMillis = NO_EXPIRY;

        Node(String key, String value) {
            this.key = key;
            this.value = value;
        }

        boolean isExpiredAt(long nowMillis) {
            return nowMillis >= expiresAtMillis;
        }
    }

    /** Entry in the expiry index: ordered by deadline, then key, so it is unique per key. */
    private record ExpiryKey(long deadlineMillis, String key) implements Comparable<ExpiryKey> {
        @Override
        public int compareTo(ExpiryKey other) {
            int byDeadline = Long.compare(deadlineMillis, other.deadlineMillis);
            return byDeadline != 0 ? byDeadline : key.compareTo(other.key);
        }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final HashMap<String, Node> data = new HashMap<>();
    private final TreeSet<ExpiryKey> expiryIndex = new TreeSet<>();
    private final Clock clock;

    public InMemoryStore() {
        this(Clock.systemUTC());
    }

    /** Test seam: lets tests move time without sleeping. */
    public InMemoryStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void set(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        lock.lock();
        try {
            Node node = data.get(key);
            if (node == null) {
                node = new Node(key, value);
                data.put(key, node);
            } else {
                node.value = value;
            }
            // A plain SET always produces a key that lives forever.
            setDeadline(node, NO_EXPIRY);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<String> get(String key) {
        Objects.requireNonNull(key, "key");
        lock.lock();
        try {
            Node node = liveNode(key, clock.millis());
            return node == null ? Optional.empty() : Optional.of(node.value);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean del(String key) {
        Objects.requireNonNull(key, "key");
        lock.lock();
        try {
            Node node = data.get(key);
            if (node == null) {
                return false;
            }
            // An expired entry was already logically absent, so report false.
            boolean wasLive = !node.isExpiredAt(clock.millis());
            remove(node);
            return wasLive;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean exists(String key) {
        Objects.requireNonNull(key, "key");
        lock.lock();
        try {
            return liveNode(key, clock.millis()) != null;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean expire(String key, long seconds) {
        Objects.requireNonNull(key, "key");
        if (seconds <= 0) {
            return del(key);
        }
        lock.lock();
        try {
            long now = clock.millis();
            Node node = liveNode(key, now);
            if (node == null) {
                return false;
            }
            setDeadline(node, deadlineFrom(now, seconds));
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes up to {@code budget} expired entries, earliest deadline first.
     * The bound keeps each call short so the lock is never held for long.
     *
     * @return how many entries were removed
     */
    int sweepExpired(int budget) {
        lock.lock();
        try {
            long now = clock.millis();
            int removed = 0;
            while (removed < budget && !expiryIndex.isEmpty()) {
                ExpiryKey next = expiryIndex.first();
                if (next.deadlineMillis() > now) {
                    break; // everything after this is later still
                }
                remove(data.get(next.key()));
                removed++;
            }
            return removed;
        } finally {
            lock.unlock();
        }
    }

    /** Number of entries still held, expired-but-unswept ones included. */
    int physicalSize() {
        lock.lock();
        try {
            return data.size();
        } finally {
            lock.unlock();
        }
    }

    /** Number of keys currently tracked for expiry; equals the number of keys with a TTL. */
    int expiryIndexSize() {
        lock.lock();
        try {
            return expiryIndex.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the key's node if it is live at {@code nowMillis}. An expired
     * node is removed on the spot and null is returned. Caller holds the lock.
     */
    private Node liveNode(String key, long nowMillis) {
        Node node = data.get(key);
        if (node == null) {
            return null;
        }
        if (node.isExpiredAt(nowMillis)) {
            remove(node);
            return null;
        }
        return node;
    }

    /** Removes the node from the map and the expiry index. Caller holds the lock. */
    private void remove(Node node) {
        setDeadline(node, NO_EXPIRY);
        data.remove(node.key);
    }

    /**
     * Changes a node's deadline, keeping the expiry index in step. The old
     * index entry is removed eagerly, so the index holds exactly one entry per
     * key that has a TTL and never accumulates stale ones. Caller holds the lock.
     */
    private void setDeadline(Node node, long deadlineMillis) {
        if (node.expiresAtMillis != NO_EXPIRY) {
            expiryIndex.remove(new ExpiryKey(node.expiresAtMillis, node.key));
        }
        node.expiresAtMillis = deadlineMillis;
        if (deadlineMillis != NO_EXPIRY) {
            expiryIndex.add(new ExpiryKey(deadlineMillis, node.key));
        }
    }

    /** Saturates instead of overflowing on absurdly large TTLs. */
    private static long deadlineFrom(long nowMillis, long seconds) {
        try {
            return Math.addExact(nowMillis, Math.multiplyExact(seconds, 1000L));
        } catch (ArithmeticException overflow) {
            return NO_EXPIRY;
        }
    }
}
