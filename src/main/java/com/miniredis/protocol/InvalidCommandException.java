package com.miniredis.protocol;

/**
 * Thrown when a client sends a line that is not a valid command. The message
 * is safe to send back to the client.
 */
public class InvalidCommandException extends Exception {

    public InvalidCommandException(String message) {
        super(message);
    }
}
