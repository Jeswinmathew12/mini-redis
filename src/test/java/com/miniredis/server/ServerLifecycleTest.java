package com.miniredis.server;

import com.miniredis.store.InMemoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Startup, shutdown, connection limits and error handling of the TCP server. */
@Timeout(60)
class ServerLifecycleTest {

    private static final int READ_TIMEOUT_MILLIS = 3_000;
    private static final int CONNECT_TIMEOUT_MILLIS = 300;

    private static Server startServer(InMemoryStore store, Server.Options options) throws IOException {
        Server server = new Server(0, store, options);
        server.start();
        return server;
    }

    private static Server startServer() throws IOException {
        return startServer(new InMemoryStore(), Server.Options.defaults());
    }

    private static final class Client implements AutoCloseable {
        final Socket socket;
        final BufferedReader in;

        Client(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        }

        String send(String line) throws IOException {
            socket.getOutputStream().write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return in.readLine();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for: " + what);
            }
            Thread.sleep(10);
        }
    }

    // Shutdown

    @Test
    void stopDisconnectsEveryConnectedClient() throws Exception {
        Server server = startServer();
        List<Client> clients = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                Client client = new Client(server.getPort());
                assertEquals("OK", client.send("SET k" + i + " v")); // handler is definitely running
                clients.add(client);
            }

            server.stop();

            for (Client client : clients) {
                assertNull(readUntilClosed(client), "server should have closed the connection");
            }
        } finally {
            for (Client client : clients) {
                client.close();
            }
        }
    }

    /** Returns null when the peer closed the connection; fails if it just stays open. */
    private static String readUntilClosed(Client client) throws IOException {
        try {
            return client.in.readLine();
        } catch (SocketTimeoutException stillOpen) {
            throw new AssertionError("connection was never closed by the server", stillOpen);
        } catch (IOException reset) {
            return null; // a reset also means the server side is gone
        }
    }

    @Test
    void stopEndsTheAcceptLoopAndReleasesAllClients() throws Exception {
        Server server = startServer();
        try (Client client = new Client(server.getPort())) {
            client.send("SET a 1");
            assertEquals(1, server.activeClientCount());
        }

        server.stop();

        assertFalse(server.isAcceptLoopAlive());
        assertEquals(0, server.activeClientCount());
    }

    @Test
    void stopIsSafeToCallTwiceAndBeforeStart() throws Exception {
        new Server(0, new InMemoryStore()).stop(); // never started

        Server server = startServer();
        server.stop();
        server.stop();
    }

    @Test
    void newConnectionsAreRefusedAfterStop() throws Exception {
        Server server = startServer();
        int port = server.getPort();
        server.stop();

        assertThrows(IOException.class, () -> new Socket("127.0.0.1", port).close());
    }

    @Test
    void stoppingWhileClientsAreConnectingNeitherLeaksSocketsNorKillsThreads() throws Exception {
        List<Throwable> uncaught = new CopyOnWriteArrayList<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> uncaught.add(error));
        try {
            for (int round = 0; round < 40; round++) {
                raceConnectionsAgainstStop(uncaught);
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
        assertTrue(uncaught.isEmpty(), "a thread died during shutdown: " + uncaught);
    }

    private static void raceConnectionsAgainstStop(List<Throwable> uncaught) throws Exception {
        Server server = startServer();
        int port = server.getPort();
        AtomicBoolean keepConnecting = new AtomicBoolean(true);
        List<Socket> connected = new CopyOnWriteArrayList<>();

        List<Thread> connectors = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Thread t = new Thread(() -> {
                while (keepConnecting.get()) {
                    try {
                        Socket socket = new Socket();
                        socket.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MILLIS);
                        socket.setSoTimeout(READ_TIMEOUT_MILLIS);
                        connected.add(socket);
                    } catch (IOException refused) {
                        return; // the server is gone, or the backlog was full for a moment
                    }
                }
            });
            connectors.add(t);
            t.start();
        }

        Thread.sleep(5);
        long stopStart = System.nanoTime();
        server.stop();
        long stopMillis = (System.nanoTime() - stopStart) / 1_000_000;
        assertTrue(stopMillis < 2_000,
                "stop() took " + stopMillis + " ms under connection load; it should not have to wait out its timeouts");
        keepConnecting.set(false);
        for (Thread t : connectors) {
            t.join();
        }

        assertEquals(0, server.activeClientCount(), "clients still tracked after stop");
        assertFalse(server.isAcceptLoopAlive());

        // No connection may still be served. Reading alone is not enough: on
        // Linux, a client whose handshake finishes just as the listener closes
        // can be dropped by the kernel without a word, and a client that only
        // reads then waits forever even though the server did nothing wrong.
        // Sending a line settles it. A dropped connection is answered with a
        // reset, and a properly closed one gives EOF or a reset. Only a
        // connection the server accepted and left open either replies (its
        // handler is still running) or stays silent until the timeout.
        for (Socket socket : connected) {
            try {
                socket.getOutputStream().write("GET k\r\n".getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                int first = socket.getInputStream().read();
                assertEquals(-1, first, "a connection was still being served after stop()");
            } catch (SocketTimeoutException leaked) {
                throw new AssertionError("server leaked an accepted connection", leaked);
            } catch (IOException reset) {
                // reset or broken pipe: the server side is gone, which is what we want
            } finally {
                socket.close();
            }
        }
    }

    // Connection limit

    @Test
    void clientsBeyondTheLimitAreToldSoAndDisconnected() throws Exception {
        Server server = startServer(new InMemoryStore(),
                new Server.Options(InetAddress.getLoopbackAddress(), Server.DEFAULT_MAX_LINE_BYTES, 2));
        try (Client first = new Client(server.getPort());
             Client second = new Client(server.getPort())) {
            assertEquals("OK", first.send("SET a 1"));
            assertEquals("OK", second.send("SET b 2"));

            try (Client third = new Client(server.getPort())) {
                assertEquals("ERR max clients reached", third.in.readLine());
                assertNull(readUntilClosed(third));
            }

            assertEquals("1", first.send("GET a"), "existing clients are unaffected");
        } finally {
            server.stop();
        }
    }

    @Test
    void aSlotFreedByADisconnectCanBeUsedAgain() throws Exception {
        Server server = startServer(new InMemoryStore(),
                new Server.Options(InetAddress.getLoopbackAddress(), Server.DEFAULT_MAX_LINE_BYTES, 1));
        try {
            try (Client only = new Client(server.getPort())) {
                assertEquals("OK", only.send("SET a 1"));
            }
            awaitTrue(() -> server.activeClientCount() == 0, "the slot to be released");

            try (Client next = new Client(server.getPort())) {
                assertEquals("1", next.send("GET a"));
            }
        } finally {
            server.stop();
        }
    }

    // Errors inside command execution

    @Test
    void anUnexpectedErrorIsReportedAndTheConnectionSurvives() throws Exception {
        InMemoryStore flaky = new InMemoryStore() {
            @Override
            public Optional<String> get(String key) {
                if (key.equals("boom")) {
                    throw new IllegalStateException("simulated bug");
                }
                return super.get(key);
            }
        };
        Server server = startServer(flaky, Server.Options.defaults());
        try (Client client = new Client(server.getPort())) {
            assertEquals("ERR internal error", client.send("GET boom"));

            assertEquals("OK", client.send("SET a 1"));
            assertEquals("1", client.send("GET a"));
        } finally {
            server.stop();
        }
    }

    // Configuration

    @Test
    void defaultsListenOnLoopbackOnly() {
        assertTrue(Server.Options.defaults().bindAddress().isLoopbackAddress());
    }

    @Test
    void optionsRejectNonsense() {
        InetAddress any = InetAddress.getLoopbackAddress();
        assertThrows(IllegalArgumentException.class, () -> new Server.Options(null, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Server.Options(any, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Server.Options(any, 1, 0));
    }

    @Test
    void portIsUnavailableBeforeStart() {
        assertThrows(IllegalStateException.class, () -> new Server(0, new InMemoryStore()).getPort());
    }
}
