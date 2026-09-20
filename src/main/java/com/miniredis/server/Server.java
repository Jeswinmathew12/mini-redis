package com.miniredis.server;

import com.miniredis.protocol.CommandParser;
import com.miniredis.store.Store;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Accepts TCP connections and hands each one to its own {@link ClientHandler}
 * thread. All clients share one {@link Store}.
 */
public class Server {

    /** Longest command line a client may send, in bytes. */
    public static final int DEFAULT_MAX_LINE_BYTES = 1024 * 1024;

    /** Most simultaneous connections; extra clients are told so and disconnected. */
    public static final int DEFAULT_MAX_CLIENTS = 1000;

    /**
     * Connections the OS may queue while the accept loop is busy. Too small and
     * a burst of clients connecting at once is refused; a load test with 200
     * simultaneous clients lost 23 of them at the old value of 50. The OS may
     * cap this. 511 is Redis's default.
     */
    private static final int LISTEN_BACKLOG = 511;

    private static final System.Logger LOG = System.getLogger(Server.class.getName());
    private static final long SHUTDOWN_WAIT_SECONDS = 3;
    private static final long ACCEPT_ERROR_BACKOFF_MILLIS = 50;

    /**
     * @param bindAddress interface to listen on. Loopback keeps the server
     *                    reachable only from this machine; use a wildcard
     *                    address such as 0.0.0.0 to accept remote clients
     *                    (needed inside a container).
     */
    public record Options(InetAddress bindAddress, int maxLineBytes, int maxClients) {

        public Options {
            if (bindAddress == null) {
                throw new IllegalArgumentException("bindAddress must not be null");
            }
            if (maxLineBytes <= 0) {
                throw new IllegalArgumentException("maxLineBytes must be positive");
            }
            if (maxClients <= 0) {
                throw new IllegalArgumentException("maxClients must be positive");
            }
        }

        public static Options defaults() {
            return new Options(InetAddress.getLoopbackAddress(), DEFAULT_MAX_LINE_BYTES,
                    DEFAULT_MAX_CLIENTS);
        }
    }

    private final int requestedPort;
    private final Options options;
    private final CommandParser parser = new CommandParser();
    private final CommandExecutor executor;
    private final AtomicInteger threadCounter = new AtomicInteger();
    private final ExecutorService clientThreads = Executors.newCachedThreadPool(
            task -> new Thread(task, "client-handler-" + threadCounter.incrementAndGet()));
    private final Set<Socket> openClients = ConcurrentHashMap.newKeySet();
    private final AtomicInteger activeClients = new AtomicInteger();

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile boolean stopped;
    private int boundPort;

    /** @param port port to listen on; 0 picks any free port (useful in tests) */
    public Server(int port, Store store) {
        this(port, store, Options.defaults());
    }

    /** @param maxLineBytes clients sending a longer line get an error and are disconnected */
    public Server(int port, Store store, int maxLineBytes) {
        this(port, store, new Options(InetAddress.getLoopbackAddress(), maxLineBytes, DEFAULT_MAX_CLIENTS));
    }

    public Server(int port, Store store, Options options) {
        this.requestedPort = port;
        this.options = options;
        this.executor = new CommandExecutor(store);
    }

    /** Binds the port and starts accepting clients in the background. */
    public synchronized void start() throws IOException {
        if (serverSocket != null) {
            throw new IllegalStateException("server already started");
        }
        ServerSocket socket = new ServerSocket(requestedPort, LISTEN_BACKLOG, options.bindAddress());
        serverSocket = socket;
        boundPort = socket.getLocalPort();
        Thread thread = new Thread(this::acceptLoop, "accept-loop");
        acceptThread = thread;
        thread.start();
    }

    /** The port actually bound; differs from the requested port when 0 was requested. */
    public synchronized int getPort() {
        if (serverSocket == null) {
            throw new IllegalStateException("server not started");
        }
        return boundPort;
    }

    /**
     * Stops accepting, disconnects all clients, and waits briefly for the
     * accept loop and client threads to finish. Safe to call more than once.
     */
    public synchronized void stop() {
        if (serverSocket == null || stopped) {
            return;
        }
        // Order matters: closing the server socket first lets the accept loop
        // notice (see admit) that any client it accepts from now on is late.
        stopped = true;
        closeQuietly(serverSocket);
        for (Socket client : openClients) {
            closeQuietly(client);
        }
        clientThreads.shutdown();
        try {
            acceptThread.join(TimeUnit.SECONDS.toMillis(SHUTDOWN_WAIT_SECONDS));
            if (!clientThreads.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                LOG.log(System.Logger.Level.WARNING, "client threads did not finish within "
                        + SHUTDOWN_WAIT_SECONDS + "s of shutdown");
                clientThreads.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Connections currently being served. Zero after {@link #stop()} unless something leaked. */
    int activeClientCount() {
        return activeClients.get();
    }

    boolean isAcceptLoopAlive() {
        Thread thread = acceptThread;
        return thread != null && thread.isAlive();
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            Socket client;
            try {
                client = serverSocket.accept();
            } catch (IOException e) {
                // accept() fails when stop() closes the server socket; that is our exit signal.
                if (serverSocket.isClosed()) {
                    return;
                }
                // Something else (for example out of file descriptors). Back off so
                // a persistent failure cannot turn this loop into a busy spin.
                LOG.log(System.Logger.Level.WARNING, "accept failed; retrying", e);
                sleepQuietly(ACCEPT_ERROR_BACKOFF_MILLIS);
                continue;
            }
            admit(client);
        }
    }

    /** Starts a handler for the client, or closes it if the server cannot take it. */
    private void admit(Socket client) {
        if (activeClients.incrementAndGet() > options.maxClients()) {
            activeClients.decrementAndGet();
            rejectTooManyClients(client);
            return;
        }
        openClients.add(client);

        // stop() closes the server socket before it walks openClients. If the
        // socket is already closed here, stop() may have missed this client, so
        // close it ourselves instead of leaking it.
        if (serverSocket.isClosed()) {
            release(client);
            return;
        }

        try {
            // Lets the OS eventually notice a peer that vanished without closing.
            client.setKeepAlive(true);
            clientThreads.execute(() -> {
                try {
                    new ClientHandler(client, parser, executor, options.maxLineBytes()).run();
                } finally {
                    release(client);
                }
            });
        } catch (RejectedExecutionException | IOException e) {
            // Rejected: the pool was shut down by a concurrent stop().
            if (!(e instanceof RejectedExecutionException)) {
                LOG.log(System.Logger.Level.WARNING, "could not set up client connection", e);
            }
            release(client);
        }
    }

    private void release(Socket client) {
        openClients.remove(client);
        closeQuietly(client);
        activeClients.decrementAndGet();
    }

    private void rejectTooManyClients(Socket client) {
        LOG.log(System.Logger.Level.WARNING,
                "rejecting connection: {0} clients already connected", options.maxClients());
        try {
            client.getOutputStream().write("ERR max clients reached\r\n".getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
        } catch (IOException ignored) {
            // the client is being turned away anyway
        } finally {
            closeQuietly(client);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
