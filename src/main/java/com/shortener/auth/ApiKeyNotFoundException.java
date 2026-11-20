package com.shortener.auth;

/** No API key with this id (maps to 404). */
public class ApiKeyNotFoundException extends RuntimeException {

    public ApiKeyNotFoundException(long id) {
        super("API key " + id + " not found");
    }
}
