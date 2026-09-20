package com.miniredis.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Expiry semantics, driven by a clock the test controls. */
class ExpirationTest {

    private MutableClock clock;
    private InMemoryStore store;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        store = new InMemoryStore(clock);
    }

    @Test
    void keySurvivesRightUpToItsDeadline() {
        store.set("session123", "Jeswin");
        store.expire("session123", 10);

        clock.advanceSeconds(9);
        assertEquals(Optional.of("Jeswin"), store.get("session123"));

        clock.advanceMillis(999);
        assertEquals(Optional.of("Jeswin"), store.get("session123"));
    }

    @Test
    void keyDisappearsOnceDeadlineIsReached() {
        store.set("session123", "Jeswin");
        store.expire("session123", 10);

        clock.advanceSeconds(10);

        assertEquals(Optional.empty(), store.get("session123"));
        assertFalse(store.exists("session123"));
    }

    @Test
    void expireOnMissingKeyReportsFailure() {
        assertFalse(store.expire("nope", 10));
    }

    @Test
    void expireOnLiveKeyReportsSuccess() {
        store.set("k", "v");
        assertTrue(store.expire("k", 10));
    }

    @Test
    void expireOnAlreadyExpiredKeyReportsFailureAndDoesNotResurrect() {
        store.set("k", "v");
        store.expire("k", 5);
        clock.advanceSeconds(5);

        assertFalse(store.expire("k", 100));
        assertEquals(Optional.empty(), store.get("k"));
    }

    @Test
    void secondExpireReplacesTheFirstDeadline() {
        store.set("k", "v");
        store.expire("k", 10);
        store.expire("k", 30);

        clock.advanceSeconds(20);
        assertEquals(Optional.of("v"), store.get("k"));

        clock.advanceSeconds(10);
        assertEquals(Optional.empty(), store.get("k"));
    }

    @Test
    void nonPositiveTtlDeletesImmediately() {
        store.set("zero", "v");
        store.set("negative", "v");

        assertTrue(store.expire("zero", 0));
        assertTrue(store.expire("negative", -5));

        assertEquals(Optional.empty(), store.get("zero"));
        assertEquals(Optional.empty(), store.get("negative"));
    }

    @Test
    void absurdlyLargeTtlSaturatesInsteadOfOverflowing() {
        store.set("k", "v");
        assertTrue(store.expire("k", Long.MAX_VALUE));

        clock.advanceSeconds(1_000_000);
        assertEquals(Optional.of("v"), store.get("k"));
    }

    // Re-setting a key and its expiration state

    @Test
    void settingAKeyAgainClearsItsTtl() {
        store.set("k", "first");
        store.expire("k", 10);

        store.set("k", "second");

        clock.advanceSeconds(60);
        assertEquals(Optional.of("second"), store.get("k"),
                "SET must clear the previous TTL, not inherit it");
    }

    @Test
    void reusingAnExpiredKeyNameStartsClean() {
        store.set("k", "old");
        store.expire("k", 5);
        clock.advanceSeconds(10);

        store.set("k", "new");

        clock.advanceSeconds(60);
        assertEquals(Optional.of("new"), store.get("k"));
    }

    // Deletion clears expiry metadata

    @Test
    void deleteRemovesExpiryMetadataSoKeyCanBeReused() {
        store.set("k", "v");
        store.expire("k", 10);
        assertTrue(store.del("k"));

        store.set("k", "fresh");
        clock.advanceSeconds(60);
        assertEquals(Optional.of("fresh"), store.get("k"),
                "a deleted key must not carry its old deadline into the next SET");
    }

    @Test
    void deletingAnExpiredKeyReportsFalse() {
        store.set("k", "v");
        store.expire("k", 5);
        clock.advanceSeconds(10);

        assertFalse(store.del("k"), "an expired key was already logically absent");
    }

    @Test
    void keysWithoutTtlNeverExpire() {
        store.set("permanent", "v");

        clock.advanceSeconds(10_000_000);
        assertEquals(Optional.of("v"), store.get("permanent"));
    }

    @Test
    void expireRejectsNullKey() {
        assertThrows(NullPointerException.class, () -> store.expire(null, 10));
    }

    // Reclaiming must never destroy a value written after the expiry was observed

    @Test
    void getDoesNotReclaimAValueWrittenAfterItObservedExpiry() {
        HookedClock hooked = new HookedClock(clock);
        InMemoryStore s = new InMemoryStore(hooked);
        s.set("k", "stale");
        s.expire("k", 5);
        clock.advanceSeconds(10);

        // The SET lands after get() has seen the expired entry, before it reclaims.
        hooked.onNextRead(() -> s.set("k", "fresh"));

        assertEquals(Optional.empty(), s.get("k"), "the read itself saw the expired entry");
        assertEquals(Optional.of("fresh"), s.get("k"), "the newer SET must survive the reclaim");
    }

    @Test
    void existsDoesNotReclaimAValueWrittenAfterItObservedExpiry() {
        HookedClock hooked = new HookedClock(clock);
        InMemoryStore s = new InMemoryStore(hooked);
        s.set("k", "stale");
        s.expire("k", 5);
        clock.advanceSeconds(10);

        hooked.onNextRead(() -> s.set("k", "fresh"));

        assertFalse(s.exists("k"));
        assertEquals(Optional.of("fresh"), s.get("k"));
    }

    @Test
    void reclaimStillRemovesAnEntryThatIsGenuinelyExpired() {
        store.set("k", "v");
        store.expire("k", 5);
        clock.advanceSeconds(10);

        store.get("k");

        assertEquals(0, store.physicalSize());
    }

    // Memory reclamation

    @Test
    void readingAnExpiredKeyReclaimsItsMemory() {
        store.set("k", "v");
        store.expire("k", 5);
        clock.advanceSeconds(10);
        assertEquals(1, store.physicalSize(), "not yet reclaimed before access");

        store.get("k");

        assertEquals(0, store.physicalSize(), "lazy expiry should free the entry");
    }

    @Test
    void sweeperReclaimsKeysNobodyTouches() {
        for (int i = 0; i < 50; i++) {
            store.set("k" + i, "v");
            store.expire("k" + i, 5);
        }
        clock.advanceSeconds(10);
        assertEquals(50, store.physicalSize());

        // Budget smaller than the keyspace: repeated passes must finish the job.
        for (int pass = 0; pass < 20 && store.physicalSize() > 0; pass++) {
            store.sweepExpired(20);
        }

        assertEquals(0, store.physicalSize());
    }

    @Test
    void sweeperLeavesLiveKeysAlone() {
        store.set("live", "v");
        store.set("doomed", "v");
        store.expire("doomed", 5);
        clock.advanceSeconds(10);

        for (int pass = 0; pass < 10; pass++) {
            store.sweepExpired(20);
        }

        assertEquals(Optional.of("v"), store.get("live"));
        assertEquals(1, store.physicalSize());
    }

    @Test
    void sweepOnEmptyStoreIsHarmless() {
        assertEquals(0, store.sweepExpired(20));
    }
}
