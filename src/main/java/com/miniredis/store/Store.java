package com.miniredis.store;

import java.util.Optional;

/**
 * Contract for the key-value storage engine, kept separate from any
 * particular implementation so later stages (LRU eviction) can introduce
 * new implementations without changing calling code.
 *
 * Keys may carry a time-to-live. An expired key behaves exactly as if it
 * were absent, whether or not it has physically been removed yet.
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
}
