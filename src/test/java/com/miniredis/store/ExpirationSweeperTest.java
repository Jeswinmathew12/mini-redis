package com.miniredis.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The background thread itself; the reclaiming logic is covered in ExpirationTest. */
@Timeout(30)
class ExpirationSweeperTest {

    private static void awaitTrue(BooleanSupplier condition, long timeoutMillis, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for: " + what);
            }
            Thread.sleep(5);
        }
    }

    private static boolean sweeperThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().equals("expiration-sweeper") && t.isAlive());
    }

    private static void expireMany(InMemoryStore store, MutableClock clock, int count) {
        for (int i = 0; i < count; i++) {
            store.set("k" + i, "v");
            store.expire("k" + i, 5);
        }
        clock.advanceSeconds(10);
    }

    @Test
    void reclaimsExpiredKeysNobodyTouches() throws Exception {
        MutableClock clock = new MutableClock();
        InMemoryStore store = new InMemoryStore(clock);
        expireMany(store, clock, 100);
        assertEquals(100, store.physicalSize());

        try (ExpirationSweeper sweeper = new ExpirationSweeper(store, 5)) {
            sweeper.start();
            awaitTrue(() -> store.physicalSize() == 0, 5_000, "background reclaim");
        }
    }

    @Test
    void aLargeBurstIsDrainedWithoutWaitingOneSleepPerBatch() throws Exception {
        MutableClock clock = new MutableClock();
        InMemoryStore store = new InMemoryStore(clock);
        expireMany(store, clock, 3_000);

        // 3000 keys is 150 batches of 20. Sleeping a full second between batches
        // would take minutes; back-to-back passes finish in a blink.
        try (ExpirationSweeper sweeper = new ExpirationSweeper(store, 1_000)) {
            sweeper.start();
            awaitTrue(() -> store.physicalSize() == 0, 5_000, "the burst to drain");
        }
    }

    @Test
    void keepsRunningAfterASweepFails() throws Exception {
        MutableClock clock = new MutableClock();
        AtomicInteger calls = new AtomicInteger();
        InMemoryStore store = new InMemoryStore(clock) {
            @Override
            int sweepExpired(int budget) {
                if (calls.incrementAndGet() <= 3) {
                    throw new IllegalStateException("simulated sweep failure");
                }
                return super.sweepExpired(budget);
            }
        };
        expireMany(store, clock, 10);

        try (ExpirationSweeper sweeper = new ExpirationSweeper(store, 5)) {
            sweeper.start();
            awaitTrue(() -> store.physicalSize() == 0, 5_000, "recovery after failed sweeps");
        }
        assertTrue(calls.get() > 3, "the thread must have retried after failing");
    }

    @Test
    void closeStopsTheThreadPromptlyEvenWhileItSleeps() throws Exception {
        InMemoryStore store = new InMemoryStore(new MutableClock());
        ExpirationSweeper sweeper = new ExpirationSweeper(store, 60_000);
        sweeper.start();
        awaitTrue(ExpirationSweeperTest::sweeperThreadAlive, 2_000, "the thread to start");

        long start = System.nanoTime();
        sweeper.close();
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertFalse(sweeperThreadAlive(), "thread should be gone once close() returns");
        assertTrue(millis < 1_500, "close() took " + millis + " ms");
    }

    @Test
    void aNeverEndingBacklogStillLeavesGapsForClients() throws Exception {
        // A store that always claims a full batch was reclaimed looks like an
        // endless mass expiry. The sweeper must still pause now and then, or it
        // would hold the store lock back-to-back and starve clients.
        List<Long> callTimes = Collections.synchronizedList(new ArrayList<>());
        InMemoryStore alwaysBusy = new InMemoryStore(new MutableClock()) {
            @Override
            int sweepExpired(int budget) {
                callTimes.add(System.nanoTime());
                return budget;
            }
        };

        try (ExpirationSweeper sweeper = new ExpirationSweeper(alwaysBusy, 200)) {
            sweeper.start();
            Thread.sleep(900);
        }

        long longestGapMillis = 0;
        List<Long> snapshot;
        synchronized (callTimes) {
            snapshot = new ArrayList<>(callTimes);
        }
        for (int i = 1; i < snapshot.size(); i++) {
            longestGapMillis = Math.max(longestGapMillis, (snapshot.get(i) - snapshot.get(i - 1)) / 1_000_000);
        }
        assertTrue(longestGapMillis >= 150,
                "expected the sweeper to sleep between bursts; longest gap was " + longestGapMillis + " ms");
    }
}
