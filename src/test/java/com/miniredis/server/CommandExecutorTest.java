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

    @Test
    void expireOnLiveKeyReturnsOk() {
        run(CommandType.SET, "session123", "Jeswin");

        assertEquals("OK", run(CommandType.EXPIRE, "session123", "10"));
        assertEquals("Jeswin", run(CommandType.GET, "session123"));
    }

    @Test
    void expireOnMissingKeyReturnsNil() {
        assertEquals("(nil)", run(CommandType.EXPIRE, "missing", "10"));
    }

    @Test
    void expireWithZeroSecondsDeletesImmediately() {
        run(CommandType.SET, "k", "v");

        assertEquals("OK", run(CommandType.EXPIRE, "k", "0"));
        assertEquals("(nil)", run(CommandType.GET, "k"));
    }

    @Test
    void dbsizeCountsStoredKeys() {
        assertEquals("0", run(CommandType.DBSIZE));

        run(CommandType.SET, "a", "1");
        run(CommandType.SET, "b", "2");
        assertEquals("2", run(CommandType.DBSIZE));

        run(CommandType.DEL, "a");
        assertEquals("1", run(CommandType.DBSIZE));
    }

    @Test
    void infoReportsSizeCapacityAndCountersOnOneLine() {
        executor = new CommandExecutor(new InMemoryStore(2));
        run(CommandType.SET, "a", "1");
        run(CommandType.SET, "b", "2");
        assertEquals("keys=2 max_keys=2 evictions=0 expirations=0", run(CommandType.INFO));

        run(CommandType.SET, "c", "3"); // evicts a

        assertEquals("keys=2 max_keys=2 evictions=1 expirations=0", run(CommandType.INFO));
    }

    @Test
    void infoOnAnUnlimitedStoreReportsZeroMaxKeys() {
        assertEquals("keys=0 max_keys=0 evictions=0 expirations=0", run(CommandType.INFO));
    }

    @Test
    void lruEvictionIsVisibleThroughTheExecutor() {
        executor = new CommandExecutor(new InMemoryStore(2));
        run(CommandType.SET, "a", "1");
        run(CommandType.SET, "b", "2");
        run(CommandType.GET, "a");
        run(CommandType.SET, "c", "3");

        assertEquals("(nil)", run(CommandType.GET, "b"));
        assertEquals("1", run(CommandType.GET, "a"));
    }
}
