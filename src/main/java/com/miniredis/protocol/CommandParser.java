package com.miniredis.protocol;

import java.util.List;
import java.util.Locale;

/**
 * Turns one line of text into a {@link Command}. Command names are
 * case-insensitive; keys and values are case-sensitive. For SET, everything
 * after the key is the value, so values may contain spaces.
 */
public final class CommandParser {

    public Command parse(String line) throws InvalidCommandException {
        if (line == null || line.isBlank()) {
            throw new InvalidCommandException("empty command");
        }

        String[] nameAndRest = splitFirstToken(line.strip());
        String name = nameAndRest[0];
        String rest = nameAndRest[1];

        CommandType type;
        try {
            type = CommandType.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidCommandException("unknown command '" + name + "'");
        }

        return switch (type) {
            case SET -> parseSet(rest);
            case GET, DEL -> parseSingleKey(type, rest);
            case EXPIRE -> parseExpire(rest);
        };
    }

    private Command parseSet(String rest) throws InvalidCommandException {
        String[] keyAndValue = splitFirstToken(rest);
        if (keyAndValue[0].isEmpty() || keyAndValue[1].isEmpty()) {
            throw wrongArgs(CommandType.SET);
        }
        return new Command(CommandType.SET, List.of(keyAndValue[0], keyAndValue[1]));
    }

    private Command parseSingleKey(CommandType type, String rest) throws InvalidCommandException {
        if (rest.isEmpty() || containsWhitespace(rest)) {
            throw wrongArgs(type);
        }
        return new Command(type, List.of(rest));
    }

    private Command parseExpire(String rest) throws InvalidCommandException {
        String[] keyAndSeconds = splitFirstToken(rest);
        String key = keyAndSeconds[0];
        String seconds = keyAndSeconds[1];
        if (key.isEmpty() || seconds.isEmpty() || containsWhitespace(seconds)) {
            throw wrongArgs(CommandType.EXPIRE);
        }
        // Reject here so the executor can trust the argument is numeric.
        try {
            Long.parseLong(seconds);
        } catch (NumberFormatException e) {
            throw new InvalidCommandException("value is not an integer or out of range");
        }
        return new Command(CommandType.EXPIRE, List.of(key, seconds));
    }

    /** Splits at the first whitespace; the remainder is stripped and may be empty. */
    private static String[] splitFirstToken(String s) {
        int i = 0;
        while (i < s.length() && !Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return new String[] {s.substring(0, i), s.substring(i).strip()};
    }

    private static boolean containsWhitespace(String s) {
        return s.chars().anyMatch(Character::isWhitespace);
    }

    private static InvalidCommandException wrongArgs(CommandType type) {
        return new InvalidCommandException("wrong number of arguments for '" + type + "'");
    }
}
