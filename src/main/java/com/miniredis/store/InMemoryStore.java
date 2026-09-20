package com.miniredis.store;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory implementation of {@link Store} with TTL expiry and optional LRU
 * eviction.
 *
 * <p><b>Three structures, one lock.</b> A hash map finds a key, a doubly
 * linked list orders keys by recency, and a deadline-ordered index lists keys
 * that have a TTL. All three are plain collections touched only while holding
 * {@link #lock}, so each public operation is atomic with respect to every
 * other, including the background sweeper. "Check whether it expired, then
 * remove it" and "find the least recently used key, then evict it" can never
 * race with a concurrent write. Reads take the lock too, because a read
 * reorders the recency list; every operation is short, so the lock is cheap.
 *
 * <p><b>One node per key.</b> A {@link Node} sits in the map, in the list, and
 * (if it has a TTL) in the expiry index. {@link #remove} is the only place a
 * node leaves, and it removes it from all three, so they cannot drift apart.
 * {@link #checkInvariants} verifies this and is used by the tests.
 *
 * <p><b>Costs.</b> Map and list operations are O(1) (nodes carry their own
 * links, so unlinking a known node never searches). Keeping the expiry index
 * in step is O(log T) where T is the number of keys with a TTL.
 *
 * <p><b>Expiry</b> reclaims lazily, when an operation touches an expired key,
 * and actively, by {@link ExpirationSweeper} calling {@link #sweepExpired}.
 *
 * <p><b>Eviction</b> follows the contract on {@link Store}: with a capacity, a
 * new key first reclaims an expired entry if any exists, and only otherwise
 * evicts the least recently used key.
 */
public class InMemoryStore implements Store {

    /** Pass as {@code maxKeys} for a store with no capacity limit. */
    public static final int UNLIMITED = 0;

    /** Deadline for a key with no TTL; such keys are not in the expiry index. */
    private static final long NO_EXPIRY = Long.MAX_VALUE;

    /** A stored key. Mutated only while holding the store lock. */
    private static final class Node {
        final String key;
        String value;
        long expiresAtMillis = NO_EXPIRY;
        Node prev;
        Node next;

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

    // Fair (first come, first served) on purpose. Load-tested with 50 clients on
    // this machine, a non-fair lock gave about 30% more throughput but p99
    // latency of ~12 ms and worst-case waits of seconds, because a thread that
    // just released the lock can win it again ahead of threads that have been
    // waiting. Fair gave p99 of ~2 ms and no wait over ~60 ms. See the README.
    private final ReentrantLock lock = new ReentrantLock(true);
    private final HashMap<String, Node> data = new HashMap<>();
    private final TreeSet<ExpiryKey> expiryIndex = new TreeSet<>();
    private final Clock clock;
    private final int maxKeys;

    /** Sentinels: {@code head.next} is the most recently used node, {@code tail.prev} the least. */
    private final Node head = new Node(null, null);
    private final Node tail = new Node(null, null);

    private long evictions;
    private long expirations;

    public InMemoryStore() {
        this(Clock.systemUTC(), UNLIMITED);
    }

    /** Test seam: lets tests move time without sleeping. */
    public InMemoryStore(Clock clock) {
        this(clock, UNLIMITED);
    }

    /** @param maxKeys most keys to hold, or {@link #UNLIMITED} */
    public InMemoryStore(int maxKeys) {
        this(Clock.systemUTC(), maxKeys);
    }

    public InMemoryStore(Clock clock, int maxKeys) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxKeys < 0) {
            throw new IllegalArgumentException("maxKeys must not be negative: " + maxKeys);
        }
        this.maxKeys = maxKeys;
        head.next = tail;
        tail.prev = head;
    }

    @Override
    public void set(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        lock.lock();
        try {
            Node node = data.get(key);
            if (node == null) {
                makeRoomForNewKey();
                node = new Node(key, value);
                data.put(key, node);
                linkFirst(node);
            } else {
                // Overwriting never grows the store, so it never evicts.
                node.value = value;
                touch(node);
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
            if (node == null) {
                return Optional.empty();
            }
            touch(node);
            return Optional.of(node.value);
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
            if (node.isExpiredAt(clock.millis())) {
                removeExpired(node);
                return false;
            }
            remove(node);
            return true;
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

    @Override
    public Stats stats() {
        lock.lock();
        try {
            return new Stats(data.size(), maxKeys, evictions, expirations);
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
            while (removed < budget && reclaimEarliestExpired(now)) {
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

    /** Keys removed to make room. Does not include keys that expired. */
    long evictions() {
        lock.lock();
        try {
            return evictions;
        } finally {
            lock.unlock();
        }
    }

    /** Keys removed because their TTL elapsed. Does not include evictions. */
    long expirations() {
        lock.lock();
        try {
            return expirations;
        } finally {
            lock.unlock();
        }
    }

    /** Stored keys, most recently used first. Expired-but-unreclaimed keys are included. */
    List<String> keysMostRecentFirst() {
        lock.lock();
        try {
            List<String> keys = new ArrayList<>(data.size());
            for (Node n = head.next; n != tail; n = n.next) {
                keys.add(n.key);
            }
            return keys;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Verifies that the map, the recency list and the expiry index agree, and
     * that no node is linked twice or lost.
     *
     * @throws IllegalStateException describing the first inconsistency found
     */
    void checkInvariants() {
        lock.lock();
        try {
            Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            int withTtl = 0;
            Node prev = head;
            Node n = head.next;
            while (n != tail) {
                require(n != null, "list ends without reaching the tail sentinel");
                require(n.prev == prev, "broken back-link at key " + n.key);
                require(seen.add(n), "node linked twice or list has a cycle at key " + n.key);
                require(data.get(n.key) == n, "list node not in map: " + n.key);
                if (n.expiresAtMillis != NO_EXPIRY) {
                    withTtl++;
                    require(expiryIndex.contains(new ExpiryKey(n.expiresAtMillis, n.key)),
                            "key with a TTL missing from the expiry index: " + n.key);
                }
                prev = n;
                n = n.next;
            }
            require(tail.prev == prev, "tail back-link does not point at the last node");
            require(seen.size() == data.size(),
                    "list has " + seen.size() + " nodes but map has " + data.size());
            require(expiryIndex.size() == withTtl,
                    "expiry index has " + expiryIndex.size() + " entries but " + withTtl
                            + " keys have a TTL");
            require(maxKeys == UNLIMITED || data.size() <= maxKeys,
                    "store holds " + data.size() + " keys, over its capacity of " + maxKeys);
        } finally {
            lock.unlock();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
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
            removeExpired(node);
            return null;
        }
        return node;
    }

    /**
     * Frees one slot if the store is full. An expired entry is reclaimed in
     * preference to evicting a live key. Caller holds the lock.
     */
    private void makeRoomForNewKey() {
        if (maxKeys == UNLIMITED || data.size() < maxKeys) {
            return;
        }
        // The store is never over capacity between operations, so it holds
        // exactly maxKeys entries here and freeing one slot is enough.
        if (!reclaimEarliestExpired(clock.millis())) {
            evictLeastRecentlyUsed();
        }
    }

    /** Removes the expired entry with the earliest deadline, if any. Caller holds the lock. */
    private boolean reclaimEarliestExpired(long nowMillis) {
        if (expiryIndex.isEmpty()) {
            return false;
        }
        ExpiryKey next = expiryIndex.first();
        if (next.deadlineMillis() > nowMillis) {
            return false; // everything after this is later still
        }
        removeExpired(data.get(next.key()));
        return true;
    }

    /** Caller holds the lock and guarantees the store is not empty. */
    private void evictLeastRecentlyUsed() {
        remove(tail.prev);
        evictions++;
    }

    private void removeExpired(Node node) {
        remove(node);
        expirations++;
    }

    /**
     * The one place a node leaves the store: unlinks it from the list, drops
     * its expiry index entry, and removes it from the map. Caller holds the lock.
     */
    private void remove(Node node) {
        unlink(node);
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

    /** Marks an already-linked node most recently used. Caller holds the lock. */
    private void touch(Node node) {
        if (head.next != node) {
            unlink(node);
            linkFirst(node);
        }
    }

    private void linkFirst(Node node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    private void unlink(Node node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = null;
        node.next = null;
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
