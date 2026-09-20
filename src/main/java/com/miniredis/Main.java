package com.miniredis;

import com.miniredis.server.Server;
import com.miniredis.store.ExpirationSweeper;
import com.miniredis.store.InMemoryStore;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

public class Main {

    static final int DEFAULT_PORT = 6380;
    static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";
    private static final String USAGE =
            "usage: Main [port [maxKeys [bindAddress]]]\n"
                    + "  maxKeys      0 = unlimited (default)\n"
                    + "  bindAddress  default 127.0.0.1 (this machine only); use 0.0.0.0 to accept "
                    + "remote clients, e.g. inside a container";

    /** Command-line settings. {@code maxKeys == 0} means no capacity limit. */
    record Options(int port, int maxKeys, String bindAddress) {
    }

    /** @throws IllegalArgumentException with a message fit to show the user */
    static Options parseArgs(String[] args) {
        if (args.length > 3) {
            throw new IllegalArgumentException("too many arguments");
        }
        int port = DEFAULT_PORT;
        int maxKeys = InMemoryStore.UNLIMITED;
        String bindAddress = DEFAULT_BIND_ADDRESS;
        if (args.length >= 1) {
            port = parseInt(args[0], "port");
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("port must be between 0 and 65535: " + port);
            }
        }
        if (args.length >= 2) {
            maxKeys = parseInt(args[1], "maxKeys");
            if (maxKeys < 0) {
                throw new IllegalArgumentException("maxKeys must not be negative: " + maxKeys);
            }
        }
        if (args.length == 3) {
            bindAddress = args[2];
            resolve(bindAddress); // fail now, with a clear message, rather than at bind time
        }
        return new Options(port, maxKeys, bindAddress);
    }

    private static InetAddress resolve(String address) {
        try {
            return InetAddress.getByName(address);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("unknown bind address: " + address);
        }
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

        Server server = new Server(options.port(), store, new Server.Options(
                resolve(options.bindAddress()), Server.DEFAULT_MAX_LINE_BYTES, Server.DEFAULT_MAX_CLIENTS));
        server.start();
        System.out.println("mini-redis listening on " + options.bindAddress() + ":" + server.getPort()
                + (options.maxKeys() == InMemoryStore.UNLIMITED
                        ? ", no key limit"
                        : ", max " + options.maxKeys() + " keys (LRU eviction)"));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            sweeper.close();
        }));
    }
}
