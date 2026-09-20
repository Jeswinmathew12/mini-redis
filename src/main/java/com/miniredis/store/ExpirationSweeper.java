package com.miniredis.store;

import java.util.concurrent.TimeUnit;

/**
 * Daemon thread that reclaims expired keys nobody has touched.
 *
 * <p>Each pass reclaims at most a bounded number of expired entries, taken
 * from the front of the store's deadline-ordered index, so the lock is never
 * held long and live keys are never scanned. If a pass finds plenty of
 * expired keys the next pass runs immediately, on the assumption more garbage
 * is waiting, but only for a short burst: after {@link #MAX_BUSY_MILLIS} the
 * thread sleeps regardless, so a mass expiry cannot keep the store's lock
 * busy at the expense of clients. When idle it sleeps, so an idle server
 * burns effectively no CPU.
 */
public class ExpirationSweeper implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ExpirationSweeper.class.getName());

    /** Most entries reclaimed per pass. Small enough that one pass is never slow. */
    static final int KEYS_PER_CYCLE = 20;

    /** Re-run immediately when a pass used at least this fraction of its budget. */
    private static final double BUSY_THRESHOLD = 0.25;

    /** Longest run of back-to-back passes before the thread must sleep. */
    static final long MAX_BUSY_MILLIS = 25;

    private static final long CLOSE_WAIT_MILLIS = 2_000;

    private final InMemoryStore store;
    private final long idleSleepMillis;
    private final Thread thread;
    private volatile boolean running = true;
    private boolean lastPassFailed;

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
        long maxBusyNanos = TimeUnit.MILLISECONDS.toNanos(MAX_BUSY_MILLIS);
        int busyThreshold = (int) (KEYS_PER_CYCLE * BUSY_THRESHOLD);

        while (running) {
            long burstStart = System.nanoTime();
            int removed;
            do {
                removed = sweepOnce();
            } while (running
                    && removed >= busyThreshold
                    && System.nanoTime() - burstStart < maxBusyNanos);

            try {
                Thread.sleep(idleSleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private int sweepOnce() {
        try {
            int removed = store.sweepExpired(KEYS_PER_CYCLE);
            lastPassFailed = false;
            return removed;
        } catch (RuntimeException e) {
            // A sweep failure must never kill the thread and strand every
            // future expiry; back off and try the next pass. Log only when
            // failure starts, so a persistent fault cannot flood the log.
            if (!lastPassFailed) {
                LOG.log(System.Logger.Level.ERROR, "expiration sweep failed; will keep retrying", e);
            }
            lastPassFailed = true;
            return 0;
        }
    }

    /** Stops the thread and waits briefly for it to finish. */
    @Override
    public void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join(CLOSE_WAIT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
