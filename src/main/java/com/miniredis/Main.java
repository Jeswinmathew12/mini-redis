package com.miniredis;

import com.miniredis.server.Server;
import com.miniredis.store.ExpirationSweeper;
import com.miniredis.store.InMemoryStore;

import java.io.IOException;

public class Main {

    static final int DEFAULT_PORT = 6380;
    private static final String USAGE = "usage: Main [port [maxKeys]]  (maxKeys 0 = unlimited)";

    /** Command-line settings. {@code maxKeys == 0} means no capacity limit. */
    record Options(int port, int maxKeys) {
    }

    /** @throws IllegalArgumentException with a message fit to show the user */
    static Options parseArgs(String[] args) {
        if (args.length > 2) {
            throw new IllegalArgumentException("too many arguments");
        }
        int port = DEFAULT_PORT;
        int maxKeys = InMemoryStore.UNLIMITED;
        if (args.length >= 1) {
            port = parseInt(args[0], "port");
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("port must be between 0 and 65535: " + port);
            }
        }
        if (args.length == 2) {
            maxKeys = parseInt(args[1], "maxKeys");
            if (maxKeys < 0) {
                throw new IllegalArgumentException("maxKeys must not be negative: " + maxKeys);
            }
        }
        return new Options(port, maxKeys);
    }

    private static int parseInt(String text, String name) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " is not a number: " + text);
        }
    }

    public static void main(String[] args) throws IOException {
        Options options;
        try {
            options = parseArgs(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println(USAGE);
            System.exit(1);
            return;
        }

        InMemoryStore store = new InMemoryStore(options.maxKeys());
        ExpirationSweeper sweeper = new ExpirationSweeper(store);
        sweeper.start();

        Server server = new Server(options.port(), store);
        server.start();
        System.out.println("mini-redis listening on port " + server.getPort()
                + (options.maxKeys() == InMemoryStore.UNLIMITED
                        ? ", no key limit"
                        : ", max " + options.maxKeys() + " keys (LRU eviction)"));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            sweeper.close();
        }));
    }
}
