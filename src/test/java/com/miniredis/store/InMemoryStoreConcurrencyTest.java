package com.miniredis.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hammers one shared store from many threads at once. A plain HashMap fails
 * these tests (lost entries, or corrupted internal state).
 */
@Timeout(30)
class InMemoryStoreConcurrencyTest {

    private static final int THREADS = 8;

    private final Store store = new InMemoryStore();

    @Test
    void concurrentSetsOnDistinctKeysAreAllRetained() throws Exception {
        int keysPerThread = 5_000;

        runInParallel(THREADS, threadId -> {
            for (int i = 0; i < keysPerThread; i++) {
                store.set("t" + threadId + "-k" + i, "v" + i);
            }
        });

        for (int t = 0; t < THREADS; t++) {
            for (int i = 0; i < keysPerThread; i++) {
                assertEquals(Optional.of("v" + i), store.get("t" + t + "-k" + i));
            }
        }
    }

    @Test
    void readersOnlyEverSeeValuesThatWereActuallyWritten() throws Exception {
        Set<String> allowed = ConcurrentHashMap.newKeySet();
        for (int t = 0; t < THREADS; t++) {
            allowed.add("from-thread-" + t);
        }
        Set<String> unexpected = ConcurrentHashMap.newKeySet();

        runInParallel(THREADS * 2, id -> {
            boolean writer = id % 2 == 0;
            int threadId = id / 2;
            for (int i = 0; i < 20_000; i++) {
                if (writer) {
                    if (i % 3 == 0) {
                        store.del("hot");
                    } else {
                        store.set("hot", "from-thread-" + threadId);
                    }
                } else {
                    store.get("hot").ifPresent(v -> {
                        if (!allowed.contains(v)) {
                            unexpected.add(v);
                        }
                    });
                }
            }
        });

        assertTrue(unexpected.isEmpty(), "readers saw values nobody wrote: " + unexpected);
    }

    @Test
    void racingDeletesOfOneKeySucceedExactlyOnce() throws Exception {
        for (int round = 0; round < 200; round++) {
            store.set("contested", "x");
            AtomicInteger successes = new AtomicInteger();

            runInParallel(THREADS, threadId -> {
                if (store.del("contested")) {
                    successes.incrementAndGet();
                }
            });

            assertEquals(1, successes.get(), "round " + round);
            assertEquals(Optional.empty(), store.get("contested"));
        }
    }

    /** Starts all workers on a latch so they really do contend, then waits for them. */
    private static void runInParallel(int workers, Task task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int id = 0; id < workers; id++) {
                final int workerId = id;
                futures.add(pool.submit(() -> {
                    start.await();
                    task.run(workerId);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private interface Task {
        void run(int workerId) throws Exception;
    }
}
