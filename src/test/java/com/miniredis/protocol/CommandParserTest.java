package com.miniredis.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandParserTest {

    private final CommandParser parser = new CommandParser();

    @Test
    void parsesSet() throws Exception {
        assertEquals(new Command(CommandType.SET, List.of("name", "Jeswin")),
                parser.parse("SET name Jeswin"));
    }

    @Test
    void parsesGet() throws Exception {
        assertEquals(new Command(CommandType.GET, List.of("name")), parser.parse("GET name"));
    }

    @Test
    void parsesDel() throws Exception {
        assertEquals(new Command(CommandType.DEL, List.of("name")), parser.parse("DEL name"));
    }

    @Test
    void commandNamesAreCaseInsensitiveButKeysAreNot() throws Exception {
        assertEquals(new Command(CommandType.GET, List.of("Name")), parser.parse("get Name"));
    }

    @Test
    void setValueMayContainSpaces() throws Exception {
        assertEquals(new Command(CommandType.SET, List.of("name", "Jeswin Mathew")),
                parser.parse("SET name Jeswin Mathew"));
    }

    @Test
    void extraWhitespaceAroundTokensIsIgnored() throws Exception {
        assertEquals(new Command(CommandType.SET, List.of("k", "v")),
                parser.parse("  SET    k    v  "));
    }

    @Test
    void rejectsNullEmptyAndBlankLines() {
        assertThrows(InvalidCommandException.class, () -> parser.parse(null));
        assertThrows(InvalidCommandException.class, () -> parser.parse(""));
        assertThrows(InvalidCommandException.class, () -> parser.parse("   "));
    }

    @Test
    void rejectsUnknownCommand() {
        InvalidCommandException e =
                assertThrows(InvalidCommandException.class, () -> parser.parse("FLY away"));
        assertEquals("unknown command 'FLY'", e.getMessage());
    }

    @Test
    void rejectsWrongArgumentCounts() {
        assertThrows(InvalidCommandException.class, () -> parser.parse("SET"));
        assertThrows(InvalidCommandException.class, () -> parser.parse("SET onlykey"));
        assertThrows(InvalidCommandException.class, () -> parser.parse("GET"));
        assertThrows(InvalidCommandException.class, () -> parser.parse("GET a b"));
        assertThrows(InvalidCommandException.class, () -> parser.parse("DEL"));
        assertThrows(InvalidCommandException.class, () -> parser.parse("DEL a b"));
    }
}
