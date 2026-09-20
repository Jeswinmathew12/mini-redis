package com.miniredis.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The LRU contract documented on {@link Store}, one rule per test. */
class LruEvictionTest {

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
    }

    private InMemoryStore storeWithCapacity(int maxKeys) {
        return new InMemoryStore(clock, maxKeys);
    }

    private static void assertOrder(InMemoryStore store, String... mostRecentFirst) {
        assertEquals(List.of(mostRecentFirst), store.keysMostRecentFirst());
        store.checkInvariants();
    }

    // Eviction order

    @Test
    void evictsTheLeastRecentlyUsedKey() {
        InMemoryStore store = storeWithCapacity(3);
        store.set("a", "1");
        store.set("b", "2");
        store.set("c", "3");

        store.set("d", "4");

        assertEquals(Optional.empty(), store.get("a"));
        assertOrder(store, "d", "c", "b");
        assertEquals(1, store.evictions());
    }

    @Test
    void aGetHitProtectsTheKeyFromEviction() {
        InMemoryStore store = storeWithCapacity(3);
        store.set("a", "1");
        store.set("b", "2");
        store.set("c", "3");

        store.get("a");
        store.set("d", "4");

        assertEquals(Optional.of("1"), store.get("a"));
        assertEquals(Optional.empty(), store.get("b"), "b became the least recently used");
    }

    @Test
    void overwritingCountsAsAUse() {
        InMemoryStore store = storeWithCapacity(3);
        store.set("a", "1");
        store.set("b", "2");
        store.set("c", "3");

        store.set("a", "again");
        store.set("d", "4");

        assertEquals(Optional.of("again"), store.get("a"));
        assertEquals(Optional.empty(), store.get("b"));
    }

    @Test
    void overwritingAtCapacityNeverEvicts() {
        InMemoryStore store = storeWithCapacity(3);
        store.set("a", "1");
        store.set("b", "2");
        store.set("c", "3");

        store.set("c", "new");
        store.set("a", "new");
        store.set("b", "new");

        assertOrder(store, "b", "a", "c");
        assertEquals(0, store.evictions());
    }

    @Test
    void theNewKeyIsAlwaysStoredAndIsMostRecent() {
        InMemoryStore store = storeWithCapacity(1);
        store.set("a", "1");

        store.set("b", "2");

        assertEquals(Optional.of("2"), store.get("b"));
        assertEquals(Optional.empty(), store.get("a"));
        assertOrder(store, "b");
    }

    // What does NOT count as a use

    @Test
    void delExistsExpireAndMissesDoNotChangeRecency() {
        InMemoryStore store = storeWithCapacity(3);
        store.set("a", "1");
        store.set("b", "2");
        store.set("c", "3");
        assertOrder(store, "c", "b", "a");

        store.exists("a");
        store.expire("a", 100);
        store.get("missing");
        store.del("missing");
        store.expire("missing", 10);

        assertOrder(store, "c", "b", "a");
        store.set("d", "4");
        assertEquals(Optional.empty(), store.get("a"), "a was still the least recently used");
    }

    // Removal frees capacity

    @Test
    void deletingAKeyFreesItsSlot() {
        InMemoryStore store = storeWithCapacity(2);
        store.set("a", "1");
        store.set("b", "2");

        store.del("a");
        store.set("c", "3");

        assertOrder(store, "c", "b");
        assertEquals(0, store.evictions());
    }

    @Test
    void nonPositiveExpireIsADeleteNotAnEviction() {
        InMemoryStore store = storeWithCapacity(2);
        store.set("a", "1");
        store.set("b", "2");

        assertTrue(store.expire("a", 0));

        assertOrder(store, "b");
        assertEquals(0, store.evictions());
        assertEquals(0, store.expirations());
    }

    // An evicted key is simply absent

    @Test
    void anEvictedKeyBehavesLikeOneThatWasNeverSet() {
        InMemoryStore store = storeWithCapacity(1);
        store.set("a", "1");
        store.set("b", "2");

        assertEquals(Optional.empty(), store.get("a"));
        assertFalse(store.exists("a"));
        assertFalse(store.del("a"));
        assertFalse(store.expire("a", 10));
    }

    @Test
    void anEvictedKeyCanBeSetAgain() {
        InMemoryStore store = storeWithCapacity(1);
        store.set("a", "1");
        store.set("b", "2");

        store.set("a", "back");

        assertEquals(Optional.of("back"), store.get("a"));
        assertOrder(store, "a");
    }

    @Test
    void evictingAKeyWithATtlRemovesItFromTheExpiryIndex() {
        InMemoryStore store = storeWithCapacity(1);
        store.set("a", "1");
        store.expire("a", 100);
        assertEquals(1, store.expiryIndexSize());

        store.set("b", "2");

        assertEquals(0, store.expiryIndexSize());
        store.checkInvariants();
    }

    // Expired entries are reclaimed before live keys are evicted

    @Test
    void anExpiredKeyIsReclaimedInsteadOfEvictingALiveOne() {
        InMemoryStore store = storeWithCapacity(3);
        store.set("a", "1");
        store.set("b", "2");
        store.set("c", "3");
        store.expire("c", 5); // c is the MOST recently used, so LRU would spare it
        clock.advanceSeconds(10);

        store.set("d", "4");

        assertOrder(store, "d", "b", "a");
        assertEquals(0, store.evictions(), "nothing live was evicted");
        assertEquals(1, store.expirations());
    }

    @Test
    void expiredButUnreclaimedKeysStillCountTowardCapacity() {
        InMemoryStore store = storeWithCapacity(2);
        store.set("a", "1");
        store.set("b", "2");
        store.expire("a", 5);
        clock.advanceSeconds(10);

        assertEquals(2, store.physicalSize(), "a is dead but has not been reclaimed");
    }

    @Test
    void reclaimsTheEarliestExpiredKeyFirstWhenSeveralHaveExpired() {
        InMemoryStore store = storeWithCapacity(3);
        store.set("a", "1");
        store.set("b", "2");
        store.set("c", "3");
        store.expire("a", 20);
        store.expire("b", 10);
        store.expire("c", 30);
        clock.advanceSeconds(60);

        store.set("d", "4");

        assertEquals(List.of("d", "c", "a"), store.keysMostRecentFirst(), "b expired first");
        store.checkInvariants();
    }

    @Test
    void evictionAndExpiryAreCountedSeparately() {
        InMemoryStore store = storeWithCapacity(2);
        store.set("a", "1");
        store.set("b", "2");
        store.set("c", "3");            // evicts a
        store.expire("c", 5);
        clock.advanceSeconds(10);
        store.get("c");                 // lazy expiry of c

        assertEquals(1, store.evictions());
        assertEquals(1, store.expirations());
    }

    @Test
    void theSweeperCountsAsExpiryNotEviction() {
        InMemoryStore store = storeWithCapacity(5);
        for (int i = 0; i < 3; i++) {
            store.set("k" + i, "v");
            store.expire("k" + i, 5);
        }
        clock.advanceSeconds(10);

        assertEquals(3, store.sweepExpired(20));

        assertEquals(3, store.expirations());
        assertEquals(0, store.evictions());
        store.checkInvariants();
    }

    @Test
    void overwritingAnExpiredButUnreclaimedKeyNeitherEvictsNorKeepsTheOldTtl() {
        InMemoryStore store = storeWithCapacity(2);
        store.set("a", "1");
        store.set("b", "2");
        store.expire("a", 5);
        clock.advanceSeconds(10);

        store.set("a", "revived");

        assertEquals(Optional.of("revived"), store.get("a"));
        assertEquals(Optional.of("2"), store.get("b"));
        assertEquals(0, store.evictions());
        clock.advanceSeconds(1_000);
        assertEquals(Optional.of("revived"), store.get("a"), "SET cleared the old TTL");
    }

    // Capacity configuration

    @Test
    void anUnlimitedStoreNeverEvicts() {
        InMemoryStore store = new InMemoryStore(clock);
        for (int i = 0; i < 10_000; i++) {
            store.set("k" + i, "v");
        }

        assertEquals(10_000, store.physicalSize());
        assertEquals(0, store.evictions());
    }

    @Test
    void zeroMeansUnlimitedAndNegativeIsRejected() {
        InMemoryStore unlimited = storeWithCapacity(InMemoryStore.UNLIMITED);
        for (int i = 0; i < 100; i++) {
            unlimited.set("k" + i, "v");
        }
        assertEquals(100, unlimited.physicalSize());

        assertThrows(IllegalArgumentException.class, () -> storeWithCapacity(-1));
    }

    @Test
    void sizeNeverExceedsCapacityAcrossManyInserts() {
        InMemoryStore store = storeWithCapacity(50);
        for (int i = 0; i < 5_000; i++) {
            store.set("k" + i, "v");
            assertTrue(store.physicalSize() <= 50);
        }

        assertEquals(50, store.physicalSize());
        assertEquals(4_950, store.evictions());
        store.checkInvariants();
    }
}
