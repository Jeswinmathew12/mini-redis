package com.miniredis;

import com.miniredis.server.Server;
import com.miniredis.store.ExpirationSweeper;
import com.miniredis.store.InMemoryStore;

import java.io.IOException;

public class Main {

    private static final int DEFAULT_PORT = 6380;

    public static void main(String[] args) throws IOException {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port: " + args[0]);
                System.exit(1);
            }
        }

        InMemoryStore store = new InMemoryStore();
        ExpirationSweeper sweeper = new ExpirationSweeper(store);
        sweeper.start();

        Server server = new Server(port, store);
        server.start();
        System.out.println("mini-redis listening on port " + server.getPort());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            sweeper.close();
        }));
    }
}
