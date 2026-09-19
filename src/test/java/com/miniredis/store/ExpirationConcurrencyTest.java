package com.miniredis.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Races between expiry and ordinary operations. These target the specific
 * window where one thread decides a key is expired while another replaces
 * it with a fresh value.
 */
@Timeout(60)
class ExpirationConcurrencyTest {

    private static final int ROUNDS = 3_000;

    private MutableClock clock;
    private InMemoryStore store;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        store = new InMemoryStore(clock);
    }

    @Test
    void lazyExpiryMustNotDeleteAValueWrittenAfterIt() throws Exception {
        int lost = runRacingRounds(() -> store.get("k"));
        assertEquals(0, lost,
                "a GET that observed an expired entry deleted a newer SET "
                        + lost + " times; conditional remove is missing");
    }

    @Test
    void sweeperMustNotDeleteAValueWrittenAfterIt() throws Exception {
        int lost = runRacingRounds(() -> store.sweepExpired(8));
        assertEquals(0, lost,
                "the sweeper deleted a newer SET " + lost + " times");
    }

    @Test
    void existsMustNotDeleteAValueWrittenAfterIt() throws Exception {
        int lost = runRacingRounds(() -> store.exists("k"));
        assertEquals(0, lost, "EXISTS deleted a newer SET " + lost + " times");
    }

    /**
     * Each round arms an already-expired key, then races the given reclaiming
     * action against a fresh SET. The SET clears the TTL, so afterwards the
     * key must always be readable.
     *
     * @return how many rounds lost the freshly written value
     */
    private int runRacingRounds(Runnable reclaimer) throws Exception {
        // Three participants: the writer, the reclaimer, and this thread.
        CyclicBarrier startLine = new CyclicBarrier(3);
        AtomicInteger lost = new AtomicInteger();

        Thread writer = new Thread(() -> {
            try {
                for (int round = 0; round < ROUNDS; round++) {
                    startLine.await();
                    store.set("k", "fresh");
                    startLine.await();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread reclaimerThread = new Thread(() -> {
            try {
                for (int round = 0; round < ROUNDS; round++) {
                    startLine.await();
                    reclaimer.run();
                    startLine.await();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        writer.start();
        reclaimerThread.start();

        for (int round = 0; round < ROUNDS; round++) {
            // Arm an expired entry before the pair is released.
            store.set("k", "stale");
            store.expire("k", 1);
            clock.advanceSeconds(2);

            startLine.await(); // release both
            startLine.await(); // wait for both

            if (store.get("k").isEmpty()) {
                lost.incrementAndGet();
            }
        }

        writer.join();
        reclaimerThread.join();
        return lost.get();
    }

    @Test
    void concurrentExpireAndSetNeverResurrectAStaleValue() throws Exception {
        Set<String> observed = ConcurrentHashMap.newKeySet();

        for (int round = 0; round < 500; round++) {
            store.set("k", "stale");
            CyclicBarrier gate = new CyclicBarrier(2);

            Thread expirer = new Thread(() -> {
                await(gate);
                store.expire("k", 1);
            });
            Thread setter = new Thread(() -> {
                await(gate);
                store.set("k", "fresh");
            });

            expirer.start();
            setter.start();
            expirer.join();
            setter.join();

            clock.advanceSeconds(5);
            store.get("k").ifPresent(observed::add);
        }

        // "stale" must never survive: every round overwrote it before the clock moved.
        assertTrue(observed.stream().allMatch("fresh"::equals),
                "a stale value survived a concurrent SET: " + observed);
    }

    @Test
    void manyThreadsMixingAllOperationsKeepTheStoreConsistent() throws Exception {
        int threads = 8;
        int opsPerThread = 20_000;
        Thread[] workers = new Thread[threads];
        Set<String> unexpected = ConcurrentHashMap.newKeySet();

        for (int t = 0; t < threads; t++) {
            final int id = t;
            workers[t] = new Thread(() -> {
                for (int i = 0; i < opsPerThread; i++) {
                    String key = "shared" + (i % 16);
                    switch (i % 4) {
                        case 0 -> store.set(key, "v" + id);
                        case 1 -> store.expire(key, 1);
                        case 2 -> store.del(key);
                        default -> store.get(key).ifPresent(v -> {
                            if (!v.startsWith("v")) {
                                unexpected.add(v);
                            }
                        });
                    }
                }
            });
            workers[t].start();
        }

        Thread ticker = new Thread(() -> {
            for (int i = 0; i < 2_000; i++) {
                clock.advanceMillis(5);
                store.sweepExpired(20);
            }
        });
        ticker.start();

        for (Thread w : workers) {
            w.join();
        }
        ticker.join();

        assertTrue(unexpected.isEmpty(), "readers saw corrupted values: " + unexpected);

        // Surviving keys are legitimate: a SET that lands last clears the TTL.
        // What must hold is that expiring them all reclaims every entry.
        for (int i = 0; i < 16; i++) {
            store.expire("shared" + i, 1);
        }
        clock.advanceSeconds(60);
        for (int pass = 0; pass < 50 && store.physicalSize() > 0; pass++) {
            store.sweepExpired(64);
        }

        assertEquals(0, store.physicalSize(), "store should be fully reclaimable");
        for (int i = 0; i < 16; i++) {
            assertEquals(Optional.empty(), store.get("shared" + i));
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
