package com.miniredis.store;

/**
 * Daemon thread that reclaims expired keys nobody has touched.
 *
 * <p>Pacing follows Redis's adaptive cycle. Each pass examines a bounded
 * number of entries, so a large keyspace never causes a long stall. If a
 * pass finds plenty of expired keys the next pass runs immediately, on the
 * assumption more garbage is waiting; otherwise the thread sleeps, so an
 * idle server burns effectively no CPU.
 */
public class ExpirationSweeper implements AutoCloseable {

    /** Entries examined per pass. Small enough that one pass is never slow. */
    static final int KEYS_PER_CYCLE = 20;

    /** Re-run immediately when this fraction of a sample was expired. */
    private static final double BUSY_THRESHOLD = 0.25;

    private final InMemoryStore store;
    private final long idleSleepMillis;
    private final Thread thread;
    private volatile boolean running = true;

    public ExpirationSweeper(InMemoryStore store) {
        this(store, 100);
    }

    public ExpirationSweeper(InMemoryStore store, long idleSleepMillis) {
        this.store = store;
        this.idleSleepMillis = idleSleepMillis;
        this.thread = new Thread(this::loop, "expiration-sweeper");
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    private void loop() {
        while (running) {
            int removed;
            try {
                removed = store.sweepExpired(KEYS_PER_CYCLE);
            } catch (RuntimeException e) {
                // A sweep failure must never kill the thread and strand
                // every future expiry; back off and try the next pass.
                removed = 0;
            }

            if (removed >= KEYS_PER_CYCLE * BUSY_THRESHOLD) {
                continue; // keyspace looks dirty, keep going without sleeping
            }

            try {
                Thread.sleep(idleSleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
    }
}
