package com.miniredis.server;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundedLineReaderTest {

    private static BoundedLineReader reader(String input, int max) {
        return new BoundedLineReader(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), max);
    }

    @Test
    void readsLinesTerminatedByLfOrCrLf() throws IOException {
        BoundedLineReader in = reader("one\ntwo\r\nthree\n", 100);

        assertEquals("one", in.readLine());
        assertEquals("two", in.readLine());
        assertEquals("three", in.readLine());
        assertNull(in.readLine());
    }

    @Test
    void emptyLinesAreReturnedAsEmptyStrings() throws IOException {
        BoundedLineReader in = reader("\n\r\nx\n", 100);

        assertEquals("", in.readLine());
        assertEquals("", in.readLine());
        assertEquals("x", in.readLine());
    }

    @Test
    void finalLineWithoutTerminatorIsStillReturned() throws IOException {
        BoundedLineReader in = reader("SET a 1\nSET half", 100);

        assertEquals("SET a 1", in.readLine());
        assertEquals("SET half", in.readLine());
        assertNull(in.readLine());
    }

    @Test
    void emptyStreamReturnsNull() throws IOException {
        assertNull(reader("", 100).readLine());
    }

    @Test
    void lineExactlyAtTheLimitIsAccepted() throws IOException {
        assertEquals("12345", reader("12345\n", 5).readLine());
    }

    @Test
    void lineOneByteOverTheLimitIsRejected() {
        BoundedLineReader in = reader("123456\n", 5);

        assertThrows(BoundedLineReader.LineTooLongException.class, in::readLine);
    }

    @Test
    void unterminatedInputLongerThanTheLimitIsRejectedInsteadOfBuffered() {
        BoundedLineReader in = reader("x".repeat(10_000), 64);

        assertThrows(BoundedLineReader.LineTooLongException.class, in::readLine);
    }

    @Test
    void limitIsMeasuredInBytesNotCharacters() {
        // Three characters, six UTF-8 bytes.
        BoundedLineReader in = reader("ééé\n", 5);

        assertThrows(BoundedLineReader.LineTooLongException.class, in::readLine);
    }

    @Test
    void multiByteCharactersDecodeCorrectly() throws IOException {
        assertEquals("héllo wörld ✓", reader("héllo wörld ✓\n", 100).readLine());
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThrows(IllegalArgumentException.class, () -> reader("x", 0));
    }
}
