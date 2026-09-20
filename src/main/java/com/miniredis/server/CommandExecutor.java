package com.miniredis.server;

import com.miniredis.protocol.Command;
import com.miniredis.store.Store;

import java.util.List;

/**
 * Runs a parsed {@link Command} against a {@link Store} and returns the text
 * reply. Contains no socket code, so it can be tested without networking.
 */
public class CommandExecutor {

    static final String OK = "OK";
    static final String NIL = "(nil)";

    private final Store store;

    public CommandExecutor(Store store) {
        this.store = store;
    }

    public String execute(Command command) {
        List<String> args = command.args();
        return switch (command.type()) {
            case SET -> {
                store.set(args.get(0), args.get(1));
                yield OK;
            }
            case GET -> store.get(args.get(0)).orElse(NIL);
            case DEL -> store.del(args.get(0)) ? OK : NIL;
            // The parser has already validated that args.get(1) is numeric.
            case EXPIRE -> store.expire(args.get(0), Long.parseLong(args.get(1))) ? OK : NIL;
            case DBSIZE -> String.valueOf(store.stats().keys());
            case INFO -> info(store.stats());
        };
    }

    /** One line, so it fits the line-based protocol. {@code max_keys=0} means unlimited. */
    private static String info(Store.Stats stats) {
        return "keys=" + stats.keys()
                + " max_keys=" + stats.maxKeys()
                + " evictions=" + stats.evictions()
                + " expirations=" + stats.expirations();
    }
}
