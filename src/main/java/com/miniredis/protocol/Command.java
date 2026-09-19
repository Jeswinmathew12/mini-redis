package com.miniredis.protocol;

import java.util.List;

/**
 * A parsed, validated client command. The parser guarantees the argument
 * count matches the command type: SET has [key, value], GET and DEL have [key].
 */
public record Command(CommandType type, List<String> args) {
}
