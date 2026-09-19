package com.miniredis.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryStoreTest {

    private Store store;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
    }

    // SET

    @Test
    void setCreatesNewKey() {
        store.set("name", "redis");

        assertEquals(Optional.of("redis"), store.get("name"));
    }

    @Test
    void setOverwritesExistingValue() {
        store.set("name", "redis");
        store.set("name", "mini-redis");

        assertEquals(Optional.of("mini-redis"), store.get("name"));
    }

    @Test
    void setKeepsKeysIndependent() {
        store.set("a", "1");
        store.set("b", "2");

        assertEquals(Optional.of("1"), store.get("a"));
        assertEquals(Optional.of("2"), store.get("b"));
    }

    @Test
    void setAllowsEmptyStringKeyAndValue() {
        store.set("", "");

        assertEquals(Optional.of(""), store.get(""));
    }

    // GET

    @Test
    void getReturnsEmptyForMissingKey() {
        assertEquals(Optional.empty(), store.get("missing"));
    }

    // DEL

    @Test
    void delRemovesExistingKeyAndReturnsTrue() {
        store.set("name", "redis");

        assertTrue(store.del("name"));
        assertEquals(Optional.empty(), store.get("name"));
    }

    @Test
    void delOnMissingKeyReturnsFalseAndDoesNotThrow() {
        assertFalse(store.del("missing"));
    }

    @Test
    void delTwiceReturnsFalseTheSecondTime() {
        store.set("name", "redis");

        assertTrue(store.del("name"));
        assertFalse(store.del("name"));
    }

    @Test
    void delOnlyRemovesTheGivenKey() {
        store.set("a", "1");
        store.set("b", "2");

        store.del("a");

        assertEquals(Optional.of("2"), store.get("b"));
    }

    @Test
    void keyCanBeSetAgainAfterDelete() {
        store.set("name", "old");
        store.del("name");
        store.set("name", "new");

        assertEquals(Optional.of("new"), store.get("name"));
    }

    // EXISTS

    @Test
    void existsReflectsKeyPresence() {
        assertFalse(store.exists("name"));

        store.set("name", "redis");
        assertTrue(store.exists("name"));

        store.del("name");
        assertFalse(store.exists("name"));
    }

    // Null handling

    @Test
    void nullKeyOrValueIsRejected() {
        assertThrows(NullPointerException.class, () -> store.set(null, "v"));
        assertThrows(NullPointerException.class, () -> store.set("k", null));
        assertThrows(NullPointerException.class, () -> store.get(null));
        assertThrows(NullPointerException.class, () -> store.del(null));
        assertThrows(NullPointerException.class, () -> store.exists(null));
    }
}
