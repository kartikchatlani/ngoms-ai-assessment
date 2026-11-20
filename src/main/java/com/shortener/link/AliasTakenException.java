package com.shortener.link;

/** Requested custom alias is already in use (409). */
public class AliasTakenException extends RuntimeException {

    public AliasTakenException(String alias) {
        super("Alias '" + alias + "' is already taken");
    }
}
