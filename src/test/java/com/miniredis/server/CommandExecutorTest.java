package com.miniredis.server;

import com.miniredis.protocol.Command;
import com.miniredis.protocol.CommandType;
import com.miniredis.store.InMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandExecutorTest {

    private CommandExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new CommandExecutor(new InMemoryStore());
    }

    private String run(CommandType type, String... args) {
        return executor.execute(new Command(type, List.of(args)));
    }

    @Test
    void setReturnsOkAndGetReturnsValue() {
        assertEquals("OK", run(CommandType.SET, "name", "Jeswin"));
        assertEquals("Jeswin", run(CommandType.GET, "name"));
    }

    @Test
    void getMissingKeyReturnsNil() {
        assertEquals("(nil)", run(CommandType.GET, "missing"));
    }

    @Test
    void delRemovesKey() {
        run(CommandType.SET, "name", "Jeswin");

        assertEquals("OK", run(CommandType.DEL, "name"));
        assertEquals("(nil)", run(CommandType.GET, "name"));
    }

    @Test
    void delMissingKeyReturnsNil() {
        assertEquals("(nil)", run(CommandType.DEL, "missing"));
    }
}
