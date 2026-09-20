package com.miniredis.server;

import com.miniredis.store.InMemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Starts a real server on a free port and talks to it over real sockets. */
@Timeout(10)
class ServerIntegrationTest {

    private Server server;

    @BeforeEach
    void startServer() throws IOException {
        server = new Server(0, new InMemoryStore());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private class TestClient implements AutoCloseable {
        private final Socket socket;
        final BufferedReader in;
        final PrintWriter out;

        TestClient() throws IOException {
            socket = new Socket("localhost", server.getPort());
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
        }

        String send(String line) throws IOException {
            out.print(line + "\r\n");
            out.flush();
            return in.readLine();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @Test
    void setGetDelRoundTrip() throws IOException {
        try (TestClient client = new TestClient()) {
            assertEquals("OK", client.send("SET name Jeswin"));
            assertEquals("Jeswin", client.send("GET name"));
            assertEquals("OK", client.send("DEL name"));
            assertEquals("(nil)", client.send("GET name"));
        }
    }

    @Test
    void malformedCommandsGetErrorsAndConnectionStaysOpen() throws IOException {
        try (TestClient client = new TestClient()) {
            assertEquals("ERR unknown command 'HELLO'", client.send("HELLO"));
            assertEquals("ERR wrong number of arguments for 'GET'", client.send("GET"));
            assertEquals("ERR empty command", client.send(""));
            assertEquals("OK", client.send("SET a 1"));
            assertEquals("1", client.send("GET a"));
        }
    }

    @Test
    void expireOverTheWireHidesTheKeyOnceItElapses() throws Exception {
        try (TestClient client = new TestClient()) {
            assertEquals("OK", client.send("SET session123 Jeswin"));
            assertEquals("OK", client.send("EXPIRE session123 1"));
            assertEquals("Jeswin", client.send("GET session123"));

            Thread.sleep(1_200);

            assertEquals("(nil)", client.send("GET session123"));
        }
    }

    @Test
    void expireReportsNilForUnknownKeyAndRejectsBadSeconds() throws IOException {
        try (TestClient client = new TestClient()) {
            assertEquals("(nil)", client.send("EXPIRE ghost 10"));
            assertEquals("ERR value is not an integer or out of range",
                    client.send("EXPIRE k soon"));
        }
    }

    @Test
    void clientsShareTheSameStore() throws IOException {
        try (TestClient first = new TestClient(); TestClient second = new TestClient()) {
            assertEquals("OK", first.send("SET shared yes"));
            assertEquals("yes", second.send("GET shared"));
        }
    }

    @Test
    void serverKeepsWorkingAfterClientDisconnects() throws IOException {
        try (TestClient leaving = new TestClient()) {
            leaving.send("SET k v");
        }

        try (TestClient next = new TestClient()) {
            assertEquals("v", next.send("GET k"));
        }
    }

    @Test
    void abruptDisconnectMidCommandDoesNotAffectOtherClients() throws IOException {
        try (TestClient healthy = new TestClient()) {
            Socket rude = new Socket("localhost", server.getPort());
            rude.getOutputStream().write("SET half-a-comm".getBytes(StandardCharsets.UTF_8));
            rude.close();

            assertEquals("OK", healthy.send("SET ok 1"));
        }
    }

    @Test
    void oversizedLineIsRejectedAndThatClientIsDisconnected() throws Exception {
        server.stop();
        server = new Server(0, new InMemoryStore(), 64);
        server.start();

        try (TestClient healthy = new TestClient(); TestClient abusive = new TestClient()) {
            assertEquals("OK", abusive.send("SET small ok"));

            abusive.out.print("SET big " + "x".repeat(500) + "\r\n");
            abusive.out.flush();
            assertEquals("ERR line too long", abusive.in.readLine());
            assertNull(abusive.in.readLine(), "server should close the connection");

            assertEquals("OK", healthy.send("SET other 1"));
            assertEquals("ok", healthy.send("GET small"));
        }
    }

    @Test
    void unterminatedFloodIsCutOffWithoutBufferingIt() throws Exception {
        server.stop();
        server = new Server(0, new InMemoryStore(), 1024);
        server.start();

        try (Socket flood = new Socket("localhost", server.getPort());
             TestClient healthy = new TestClient()) {
            flood.getOutputStream().write("x".repeat(5_000).getBytes(StandardCharsets.UTF_8));
            flood.getOutputStream().flush();

            BufferedReader reply = new BufferedReader(
                    new InputStreamReader(flood.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("ERR line too long", reply.readLine());

            assertEquals("OK", healthy.send("SET still works"));
        }
    }

    @Test
    void handlesManyConcurrentClients() throws Exception {
        int clients = 20;
        Thread[] threads = new Thread[clients];
        String[] results = new String[clients];

        for (int i = 0; i < clients; i++) {
            final int id = i;
            threads[i] = new Thread(() -> {
                try (TestClient client = new TestClient()) {
                    client.send("SET key" + id + " value" + id);
                    results[id] = client.send("GET key" + id);
                } catch (IOException e) {
                    results[id] = "IO error: " + e.getMessage();
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join();
        }

        for (int i = 0; i < clients; i++) {
            assertEquals("value" + i, results[i]);
        }
    }

    @Test
    void concurrentClientsDoingManyOperationsNeverSeeEachOthersPrivateKeys() throws Exception {
        int clients = 10;
        int opsPerClient = 200;
        Thread[] threads = new Thread[clients];
        String[] failures = new String[clients];

        for (int i = 0; i < clients; i++) {
            final int id = i;
            threads[i] = new Thread(() -> {
                try (TestClient client = new TestClient()) {
                    for (int op = 0; op < opsPerClient; op++) {
                        String key = "c" + id + "-" + op;
                        client.send("SET " + key + " " + op);
                        client.send("SET shared from-" + id);
                        String mine = client.send("GET " + key);
                        String shared = client.send("GET shared");
                        client.send("DEL " + key);

                        if (!String.valueOf(op).equals(mine)) {
                            failures[id] = "private key wrong: " + mine;
                            return;
                        }
                        if (!shared.startsWith("from-")) {
                            failures[id] = "shared key corrupted: " + shared;
                            return;
                        }
                    }
                } catch (IOException e) {
                    failures[id] = "IO error: " + e.getMessage();
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join();
        }

        for (int i = 0; i < clients; i++) {
            assertNull(failures[i], "client " + i);
        }
    }
}
