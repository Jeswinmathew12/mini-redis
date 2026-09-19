package com.miniredis.store;

import java.util.Optional;

/**
 * Contract for the key-value storage engine, kept separate from any
 * particular implementation so later stages (expiry, LRU eviction) can
 * introduce new implementations without changing calling code.
 */
public interface Store {

    /**
     * Creates the key or overwrites its existing value.
     *
     * @throws NullPointerException if key or value is null
     */
    void set(String key, String value);

    /**
     * Returns the value for the key, or an empty Optional if the key does
     * not exist. Optional forces callers to handle "missing" explicitly
     * instead of risking a NullPointerException.
     *
     * @throws NullPointerException if key is null
     */
    Optional<String> get(String key);

    /**
     * Removes the key and its value.
     *
     * @return true if the key existed and was removed, false if it was
     *         not present (deleting a missing key is not an error)
     * @throws NullPointerException if key is null
     */
    boolean del(String key);

    /**
     * Returns true if the key currently exists in the store.
     *
     * @throws NullPointerException if key is null
     */
    boolean exists(String key);
}
