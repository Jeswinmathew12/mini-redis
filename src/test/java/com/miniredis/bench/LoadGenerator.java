package com.miniredis.bench;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Closed-loop load generator for a running mini-redis: each client thread has
 * its own connection and sends one command at a time, waiting for the reply
 * before sending the next. Not a unit test (the name does not end in Test, so
 * Surefire skips it); run it through scripts/benchmark.sh.
 *
 * <p>Keys are chosen uniformly from a fixed key space. Latency is the time
 * from writing a command to reading its reply, measured in the client.
 */
public final class LoadGenerator {

    private static final int BUCKET_MICROS = 5;
    private static final int BUCKETS = 20_000; // 5 us buckets up to 100 ms; slower ops go in the last bucket

    private record Config(String host, int port, int clients, int warmupSeconds, int measureSeconds,
                          int readPercent, int keySpace, int valueBytes) {
    }

    private static final class Worker implements Runnable {
        final Config config;
        final long seed;
        final int[] histogram = new int[BUCKETS];
        long ops;
        long gets;
        long hits;
        long errorReplies;
        long maxNanos;
        volatile boolean connectionFailed;

        volatile boolean running = true;
        volatile boolean measuring;

        Worker(Config config, long seed) {
            this.config = config;
            this.seed = seed;
        }

        @Override
        public void run() {
            String value = "x".repeat(config.valueBytes());
            Random random = new Random(seed);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(config.host(), config.port()), 5_000);
                socket.setTcpNoDelay(true);
                OutputStream out = socket.getOutputStream();
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder command = new StringBuilder(64 + config.valueBytes());

                while (running) {
                    int key = random.nextInt(config.keySpace());
                    boolean read = random.nextInt(100) < config.readPercent();
                    command.setLength(0);
                    if (read) {
                        command.append("GET key").append(key);
                    } else {
                        command.append("SET key").append(key).append(' ').append(value);
                    }
                    command.append("\r\n");

                    long start = System.nanoTime();
                    out.write(command.toString().getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    String reply = in.readLine();
                    long elapsed = System.nanoTime() - start;

                    if (reply == null) {
                        connectionFailed = true;
                        return;
                    }
                    if (!measuring) {
                        continue;
                    }
                    ops++;
                    maxNanos = Math.max(maxNanos, elapsed);
                    histogram[(int) Math.min(BUCKETS - 1, elapsed / 1_000 / BUCKET_MICROS)]++;
                    if (reply.startsWith("ERR")) {
                        errorReplies++;
                    }
                    if (read) {
                        gets++;
                        if (!reply.equals("(nil)")) {
                            hits++;
                        }
                    }
                }
            } catch (IOException e) {
                connectionFailed = true;
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Config config = parse(args);
        prefill(config);

        List<Worker> workers = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < config.clients(); i++) {
            Worker worker = new Worker(config, 1_000L + i);
            workers.add(worker);
            Thread thread = new Thread(worker, "load-client-" + i);
            threads.add(thread);
            thread.start();
        }

        Thread.sleep(config.warmupSeconds() * 1_000L);
        long measureStart = System.nanoTime();
        for (Worker w : workers) {
            w.measuring = true;
        }
        Thread.sleep(config.measureSeconds() * 1_000L);
        for (Worker w : workers) {
            w.measuring = false;
            w.running = false;
        }
        double seconds = (System.nanoTime() - measureStart) / 1e9;
        for (Thread t : threads) {
            t.join(10_000);
        }

        report(config, workers, seconds);
    }

    private static void report(Config config, List<Worker> workers, double seconds) throws IOException {
        long[] merged = new long[BUCKETS];
        long ops = 0;
        long gets = 0;
        long hits = 0;
        long errors = 0;
        long maxNanos = 0;
        int failed = 0;
        for (Worker w : workers) {
            for (int i = 0; i < BUCKETS; i++) {
                merged[i] += w.histogram[i];
            }
            ops += w.ops;
            gets += w.gets;
            hits += w.hits;
            errors += w.errorReplies;
            maxNanos = Math.max(maxNanos, w.maxNanos);
            if (w.connectionFailed) {
                failed++;
            }
        }
        String info = fetchInfo(config);
        System.out.printf(
                "clients=%-4d read=%d%% keys=%-6d | ops/s=%-9.0f p50=%-6dus p95=%-6dus p99=%-6dus max=%.1fms"
                        + " | hit=%s errors=%d failedClients=%d | %s%n",
                config.clients(), config.readPercent(), config.keySpace(),
                ops / seconds,
                percentileMicros(merged, ops, 0.50), percentileMicros(merged, ops, 0.95),
                percentileMicros(merged, ops, 0.99), maxNanos / 1e6,
                gets == 0 ? "n/a" : String.format("%.1f%%", 100.0 * hits / gets),
                errors, failed, info);
    }

    /** Upper edge of the bucket holding the given percentile. */
    private static long percentileMicros(long[] merged, long total, double fraction) {
        if (total == 0) {
            return 0;
        }
        long target = (long) Math.ceil(total * fraction);
        long seen = 0;
        for (int i = 0; i < BUCKETS; i++) {
            seen += merged[i];
            if (seen >= target) {
                return (long) (i + 1) * BUCKET_MICROS;
            }
        }
        return (long) BUCKETS * BUCKET_MICROS;
    }

    private static void prefill(Config config) throws IOException {
        String value = "x".repeat(config.valueBytes());
        try (Socket socket = new Socket(config.host(), config.port())) {
            OutputStream out = socket.getOutputStream();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            for (int k = 0; k < config.keySpace(); k++) {
                out.write(("SET key" + k + " " + value + "\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                in.readLine();
            }
        }
    }

    private static String fetchInfo(Config config) throws IOException {
        try (Socket socket = new Socket(config.host(), config.port())) {
            socket.getOutputStream().write("INFO\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String line = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
            return line == null ? "INFO unavailable" : line;
        }
    }

    private static Config parse(String[] args) {
        String host = "127.0.0.1";
        int port = 6380;
        int clients = 50;
        int warmup = 3;
        int measure = 10;
        int read = 80;
        int keys = 10_000;
        int valueBytes = 32;
        for (int i = 0; i + 1 < args.length; i += 2) {
            String value = args[i + 1];
            switch (args[i]) {
                case "--host" -> host = value;
                case "--port" -> port = Integer.parseInt(value);
                case "--clients" -> clients = Integer.parseInt(value);
                case "--warmup" -> warmup = Integer.parseInt(value);
                case "--seconds" -> measure = Integer.parseInt(value);
                case "--read-percent" -> read = Integer.parseInt(value);
                case "--keys" -> keys = Integer.parseInt(value);
                case "--value-bytes" -> valueBytes = Integer.parseInt(value);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        return new Config(host, port, clients, warmup, measure, read, keys, valueBytes);
    }
}
