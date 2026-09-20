package com.miniredis.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Many threads hammer a tiny store, so nearly every SET evicts and every GET
 * reorders the list. The invariant checker runs on its own thread the whole
 * time; it takes the store lock, so it sees a consistent snapshot even while
 * workers are mid-flight, which catches corruption when it happens instead of
 * only at the end.
 */
@Timeout(90)
class LruConcurrencyTest {

    private static final int CAPACITY = 8;
    private static final int KEYS = 32;
    private static final int WORKERS = 8;
    private static final int OPS_PER_WORKER = 30_000;

    private final MutableClock clock = new MutableClock();
    private final InMemoryStore store = new InMemoryStore(clock, CAPACITY);

    @Test
    void structuresStayConsistentAndBoundedUnderConcurrentLoad() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Set<String> corrupted = ConcurrentHashMap.newKeySet();
        AtomicBoolean workersDone = new AtomicBoolean();
        CountDownLatch start = new CountDownLatch(1);

        Thread[] workers = new Thread[WORKERS];
        for (int w = 0; w < WORKERS; w++) {
            final int id = w;
            workers[w] = new Thread(() -> {
                Random random = new Random(id);
                try {
                    start.await();
                    for (int i = 0; i < OPS_PER_WORKER; i++) {
                        String key = "k" + random.nextInt(KEYS);
                        switch (random.nextInt(10)) {
                            case 0, 1, 2, 3 -> store.set(key, "v" + id);
                            case 4, 5, 6 -> store.get(key).ifPresent(v -> {
                                if (!v.startsWith("v")) {
                                    corrupted.add(v);
                                }
                            });
                            case 7 -> store.del(key);
                            case 8 -> store.expire(key, 1 + random.nextInt(3));
                            default -> store.exists(key);
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            workers[w].start();
        }

        Thread ticker = new Thread(() -> {
            try {
                start.await();
                while (!workersDone.get()) {
                    clock.advanceMillis(400);
                    store.sweepExpired(4);
                    Thread.yield();
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        ticker.start();

        Thread auditor = new Thread(() -> {
            try {
                start.await();
                while (!workersDone.get()) {
                    store.checkInvariants();
                    assertTrue(store.physicalSize() <= CAPACITY, "store grew past its capacity");
                    Thread.yield();
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        auditor.start();

        start.countDown();
        for (Thread worker : workers) {
            worker.join();
        }
        workersDone.set(true);
        ticker.join();
        auditor.join();

        assertNull(failure.get(), "a thread failed: " + failure.get());
        assertTrue(corrupted.isEmpty(), "readers saw corrupted values: " + corrupted);
        store.checkInvariants();
        assertTrue(store.physicalSize() <= CAPACITY);
    }

    @Test
    void racingWritersOfBrandNewKeysNeverExceedCapacityAndEvictExactlyTheOverflow() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] writers = new Thread[WORKERS];

        for (int w = 0; w < WORKERS; w++) {
            final int id = w;
            writers[w] = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 20_000; i++) {
                        String key = "t" + id + "-" + i; // always a brand-new key
                        store.set(key, "x");
                        assertTrue(store.physicalSize() <= CAPACITY);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            writers[w].start();
        }

        start.countDown();
        for (Thread writer : writers) {
            writer.join();
        }

        assertNull(failure.get(), "a writer failed: " + failure.get());
        store.checkInvariants();
        assertEquals(CAPACITY, store.physicalSize());
        assertEquals(WORKERS * 20_000L - CAPACITY, store.evictions());
    }
}
