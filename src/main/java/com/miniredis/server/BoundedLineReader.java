package com.miniredis.server;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads newline-terminated UTF-8 lines, refusing to buffer more than a fixed
 * number of bytes per line. {@code BufferedReader.readLine()} has no such
 * limit, so one client streaming bytes without a newline could exhaust the
 * heap of the whole server.
 *
 * <p>Lines end at {@code \n}; a trailing {@code \r} is dropped. The limit
 * counts the bytes before the {@code \n}, including that {@code \r}.
 */
final class BoundedLineReader {

    /** The current line exceeded the limit; the stream cannot be resynchronised. */
    static final class LineTooLongException extends IOException {
        LineTooLongException(int maxLineBytes) {
            super("line exceeds " + maxLineBytes + " bytes");
        }
    }

    private final InputStream in;
    private final int maxLineBytes;

    BoundedLineReader(InputStream in, int maxLineBytes) {
        if (maxLineBytes <= 0) {
            throw new IllegalArgumentException("maxLineBytes must be positive");
        }
        this.in = new BufferedInputStream(in);
        this.maxLineBytes = maxLineBytes;
    }

    /**
     * @return the next line without its terminator, or null at end of stream.
     *         A final line with no terminator is returned as a line.
     * @throws LineTooLongException if the line is longer than the limit
     */
    String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                return decode(line);
            }
            if (line.size() >= maxLineBytes) {
                throw new LineTooLongException(maxLineBytes);
            }
            line.write(b);
        }
        return line.size() == 0 ? null : decode(line);
    }

    private static String decode(ByteArrayOutputStream line) {
        byte[] bytes = line.toByteArray();
        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\r') {
            length--;
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }
}
