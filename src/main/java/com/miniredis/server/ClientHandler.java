package com.miniredis.server;

import com.miniredis.protocol.Command;
import com.miniredis.protocol.CommandParser;
import com.miniredis.protocol.InvalidCommandException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Serves one connected client: read a line, parse it, execute it, write the
 * reply, repeat until the client disconnects. Runs on its own thread.
 */
class ClientHandler implements Runnable {

    private final Socket socket;
    private final CommandParser parser;
    private final CommandExecutor executor;
    private final int maxLineBytes;

    ClientHandler(Socket socket, CommandParser parser, CommandExecutor executor, int maxLineBytes) {
        this.socket = socket;
        this.parser = parser;
        this.executor = executor;
        this.maxLineBytes = maxLineBytes;
    }

    @Override
    public void run() {
        try (socket;
             BufferedWriter out = new BufferedWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            BoundedLineReader in = new BoundedLineReader(socket.getInputStream(), maxLineBytes);
            while (true) {
                String line;
                try {
                    line = in.readLine();
                } catch (BoundedLineReader.LineTooLongException e) {
                    // The rest of the oversized line is still in the stream, so
                    // there is no way to find the next command boundary: hang up.
                    write(out, "ERR line too long");
                    return;
                }
                if (line == null) {
                    return;
                }
                write(out, respondTo(line));
            }
        } catch (IOException e) {
            // Client vanished mid-conversation or the server is shutting down.
            // Either way this client is done; nothing else is affected.
        }
    }

    private String respondTo(String line) {
        try {
            Command command = parser.parse(line);
            return executor.execute(command);
        } catch (InvalidCommandException e) {
            return "ERR " + e.getMessage();
        }
    }

    private static void write(BufferedWriter out, String response) throws IOException {
        out.write(response);
        out.write("\r\n");
        out.flush();
    }
}
