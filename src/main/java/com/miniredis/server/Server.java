package com.miniredis.server;

import com.miniredis.protocol.CommandParser;
import com.miniredis.store.Store;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Accepts TCP connections and hands each one to its own {@link ClientHandler}
 * thread. All clients share one {@link Store}.
 */
public class Server {

    private final int requestedPort;
    private final CommandParser parser = new CommandParser();
    private final CommandExecutor executor;
    private final ExecutorService clientThreads = Executors.newCachedThreadPool();
    private final Set<Socket> openClients = ConcurrentHashMap.newKeySet();

    private volatile ServerSocket serverSocket;

    /** @param port port to listen on; 0 picks any free port (useful in tests) */
    public Server(int port, Store store) {
        this.requestedPort = port;
        this.executor = new CommandExecutor(store);
    }

    /** Binds the port and starts accepting clients in the background. */
    public synchronized void start() throws IOException {
        if (serverSocket != null) {
            throw new IllegalStateException("server already started");
        }
        serverSocket = new ServerSocket(requestedPort);
        Thread acceptThread = new Thread(this::acceptLoop, "accept-loop");
        acceptThread.start();
    }

    /** The port actually bound; differs from the requested port when 0 was requested. */
    public int getPort() {
        return serverSocket.getLocalPort();
    }

    /** Stops accepting, disconnects all clients, and releases threads. */
    public synchronized void stop() {
        if (serverSocket == null) {
            return;
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        for (Socket client : openClients) {
            try {
                client.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        clientThreads.shutdown();
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                openClients.add(client);
                clientThreads.execute(() -> {
                    try {
                        new ClientHandler(client, parser, executor).run();
                    } finally {
                        openClients.remove(client);
                    }
                });
            } catch (IOException e) {
                // accept() fails when stop() closes the server socket; that is our exit signal.
                if (serverSocket.isClosed()) {
                    return;
                }
            }
        }
    }
}
