package com.shortener.link;

/** A request field violates a business rule (maps to 400). */
public class InvalidRequestException extends RuntimeException {

    private final String field;

    public InvalidRequestException(String field, String reason) {
        super(field + " " + reason);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
