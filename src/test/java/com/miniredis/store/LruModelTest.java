package com.miniredis.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs long random sequences of operations against the real store and against
 * a deliberately naive reference model of the contract on {@link Store}, and
 * requires them to agree after every single step: same results, same keys in
 * the same recency order, same counters, and internally consistent structures.
 *
 * <p>The model is a few lines of list and map code with no cleverness, so it
 * is easy to check by eye. Sequences are seeded, so a failure names the seed
 * and step and can be replayed.
 */
@Timeout(60)
class LruModelTest {

    private static final int KEYS = 12;
    private static final int STEPS = 20_000;

    /** The contract, implemented as simply as possible. Most recent key first. */
    private static final class Model {
        final int capacity;
        final List<String> order = new ArrayList<>();
        final Map<String, String> values = new HashMap<>();
        final Map<String, Long> deadlines = new HashMap<>();
        long evictions;
        long expirations;

        Model(int capacity) {
            this.capacity = capacity;
        }

        boolean isExpired(String key, long now) {
            Long deadline = deadlines.get(key);
            return deadline != null && now >= deadline;
        }

        void drop(String key) {
            order.remove(key);
            values.remove(key);
            deadlines.remove(key);
        }

        void touch(String key) {
            order.remove(key);
            order.add(0, key);
        }

        String earliestExpired(long now) {
            String best = null;
            for (String key : values.keySet()) {
                if (!isExpired(key, now)) {
                    continue;
                }
                if (best == null
                        || deadlines.get(key) < deadlines.get(best)
                        || (deadlines.get(key).equals(deadlines.get(best)) && key.compareTo(best) < 0)) {
                    best = key;
                }
            }
            return best;
        }

        void set(String key, String value, long now) {
            if (values.containsKey(key)) {
                values.put(key, value);
                deadlines.remove(key);
                touch(key);
                return;
            }
            while (capacity > 0 && values.size() >= capacity) {
                String expired = earliestExpired(now);
                if (expired != null) {
                    drop(expired);
                    expirations++;
                } else {
                    drop(order.get(order.size() - 1));
                    evictions++;
                }
            }
            values.put(key, value);
            order.add(0, key);
        }

        Optional<String> get(String key, long now) {
            if (!values.containsKey(key)) {
                return Optional.empty();
            }
            if (isExpired(key, now)) {
                drop(key);
                expirations++;
                return Optional.empty();
            }
            touch(key);
            return Optional.of(values.get(key));
        }

        boolean del(String key, long now) {
            if (!values.containsKey(key)) {
                return false;
            }
            boolean live = !isExpired(key, now);
            drop(key);
            if (!live) {
                expirations++;
            }
            return live;
        }

        boolean exists(String key, long now) {
            if (!values.containsKey(key)) {
                return false;
            }
            if (isExpired(key, now)) {
                drop(key);
                expirations++;
                return false;
            }
            return true;
        }

        boolean expire(String key, long seconds, long now) {
            if (seconds <= 0) {
                return del(key, now);
            }
            if (!exists(key, now)) {
                return false;
            }
            deadlines.put(key, now + seconds * 1000);
            return true;
        }

        int sweep(int budget, long now) {
            int removed = 0;
            while (removed < budget) {
                String expired = earliestExpired(now);
                if (expired == null) {
                    break;
                }
                drop(expired);
                expirations++;
                removed++;
            }
            return removed;
        }
    }

    @Test
    void storeAgreesWithTheReferenceModelOnRandomOperationSequences() {
        int[] capacities = {0, 1, 2, 3, 5, 8};
        for (int capacity : capacities) {
            for (long seed = 1; seed <= 4; seed++) {
                runSequence(capacity, seed);
            }
        }
    }

    private void runSequence(int capacity, long seed) {
        MutableClock clock = new MutableClock();
        InMemoryStore store = new InMemoryStore(clock, capacity);
        Model model = new Model(capacity);
        Random random = new Random(seed);

        for (int step = 0; step < STEPS; step++) {
            String key = "k" + random.nextInt(KEYS);
            long now = clock.millis();
            String op;
            int pick = random.nextInt(100);

            if (pick < 30) {
                String value = "v" + step;
                op = "set " + key + " " + value;
                store.set(key, value);
                model.set(key, value, now);
            } else if (pick < 60) {
                op = "get " + key;
                assertEquals(model.get(key, now), store.get(key), context(capacity, seed, step, op));
            } else if (pick < 68) {
                op = "del " + key;
                assertEquals(model.del(key, now), store.del(key), context(capacity, seed, step, op));
            } else if (pick < 73) {
                op = "exists " + key;
                assertEquals(model.exists(key, now), store.exists(key), context(capacity, seed, step, op));
            } else if (pick < 85) {
                long seconds = random.nextInt(22) - 1; // includes 0 and -1
                op = "expire " + key + " " + seconds;
                assertEquals(model.expire(key, seconds, now), store.expire(key, seconds),
                        context(capacity, seed, step, op));
            } else if (pick < 90) {
                int budget = 1 + random.nextInt(4);
                op = "sweep " + budget;
                assertEquals(model.sweep(budget, now), store.sweepExpired(budget),
                        context(capacity, seed, step, op));
            } else {
                int seconds = 1 + random.nextInt(15);
                op = "advance " + seconds + "s";
                clock.advanceSeconds(seconds);
            }

            String where = context(capacity, seed, step, op);
            assertEquals(model.order, store.keysMostRecentFirst(), where + ": recency order");
            assertEquals(model.evictions, store.evictions(), where + ": evictions");
            assertEquals(model.expirations, store.expirations(), where + ": expirations");
            assertEquals(model.deadlines.size(), store.expiryIndexSize(), where + ": TTL keys");
            try {
                store.checkInvariants();
            } catch (IllegalStateException e) {
                throw new AssertionError(where + ": " + e.getMessage(), e);
            }
        }
    }

    private static String context(int capacity, long seed, int step, String op) {
        return "capacity=" + capacity + " seed=" + seed + " step=" + step + " op=[" + op + "]";
    }
}
