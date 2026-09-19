package com.miniredis.store;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link Store} backed by a hash map.
 *
 * ConcurrentHashMap is used instead of plain HashMap so that individual
 * operations stay safe once multiple client threads exist (a later stage).
 * It behaves like HashMap for single-threaded use, but rejects null keys
 * and values, which is why this class validates them explicitly.
 */
public class InMemoryStore implements Store {

    private final ConcurrentHashMap<String, String> data = new ConcurrentHashMap<>();

    @Override
    public void set(String key, String value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");
        data.put(key, value);
    }

    @Override
    public Optional<String> get(String key) {
        requireNonNull(key, "key");
        return Optional.ofNullable(data.get(key));
    }

    @Override
    public boolean del(String key) {
        requireNonNull(key, "key");
        return data.remove(key) != null;
    }

    @Override
    public boolean exists(String key) {
        requireNonNull(key, "key");
        return data.containsKey(key);
    }

    private static void requireNonNull(Object arg, String name) {
        if (arg == null) {
            throw new NullPointerException(name + " must not be null");
        }
    }
}
