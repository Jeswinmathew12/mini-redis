package com.miniredis.store;

import java.util.Optional;

/**
 * Contract for the key-value storage engine, kept separate from any
 * particular implementation so later stages (LRU eviction) can introduce
 * new implementations without changing calling code.
 *
 * Keys may carry a time-to-live. An expired key behaves exactly as if it
 * were absent, whether or not it has physically been removed yet.
 *
 * <h2>Capacity and LRU eviction</h2>
 *
 * A store may be configured with a maximum number of keys (see
 * {@link InMemoryStore}). Without one it is unbounded. With one, these rules
 * apply:
 *
 * <ul>
 *   <li><b>What counts as a use.</b> A {@code get} that finds a live key and
 *       a {@code set} (create or overwrite) mark that key most recently used.
 *       {@code del}, {@code exists}, {@code expire} and a {@code get} miss do
 *       not change recency.</li>
 *   <li><b>Capacity is a key count.</b> Entries that have expired but not yet
 *       been reclaimed still occupy a slot until they are reclaimed.</li>
 *   <li><b>Overwriting never evicts.</b> A {@code set} on a key that is
 *       already stored does not grow the store, so nothing is removed.</li>
 *   <li><b>Making room.</b> A {@code set} of a new key into a full store first
 *       reclaims expired entries; only if it is still full does it evict the
 *       least recently used live key. The new key is always stored.</li>
 *   <li><b>Evicted means absent.</b> An evicted key behaves exactly like a key
 *       that was never set: {@code get} is empty, {@code del} is false.
 *       Eviction and expiry are counted separately.</li>
 *   <li><b>{@code expire} with a non-positive TTL is a delete</b>, not an
 *       eviction.</li>
 *   <li><b>Recency is a total order</b> given by the order in which operations
 *       take effect, so which key is evicted is deterministic.</li>
 * </ul>
 */
public interface Store {

    /**
     * Creates the key or overwrites its existing value, and clears any TTL
     * the key had. A plain SET always produces a key that lives forever.
     *
     * @throws NullPointerException if key or value is null
     */
    void set(String key, String value);

    /**
     * Returns the value for the key, or an empty Optional if the key does
     * not exist or has expired.
     *
     * @throws NullPointerException if key is null
     */
    Optional<String> get(String key);

    /**
     * Removes the key, its value, and its expiration metadata.
     *
     * @return true if a live key was removed, false if it was absent or
     *         already expired (deleting a missing key is not an error)
     * @throws NullPointerException if key is null
     */
    boolean del(String key);

    /**
     * Returns true if the key exists and has not expired.
     *
     * @throws NullPointerException if key is null
     */
    boolean exists(String key);

    /**
     * Schedules the key to expire after the given number of seconds,
     * replacing any TTL it already had. A non-positive TTL deletes the key
     * immediately, matching Redis.
     *
     * @return true if a live key was found and its TTL applied, false if
     *         the key was absent or already expired
     * @throws NullPointerException if key is null
     */
    boolean expire(String key, long seconds);

    /**
     * A consistent snapshot of the store's size and counters, all read at the
     * same instant.
     *
     * @param keys        keys currently held; includes keys that have expired
     *                    but not yet been reclaimed
     * @param maxKeys     the capacity, or 0 if unlimited
     * @param evictions   keys removed to make room, since the store was created
     * @param expirations keys removed because their TTL elapsed, since the
     *                    store was created
     */
    record Stats(int keys, int maxKeys, long evictions, long expirations) {
    }

    Stats stats();
}
