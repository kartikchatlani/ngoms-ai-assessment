package com.shortener.abuse;

/** An admin supplied something that isn't a blockable domain name (maps to 400). */
public class InvalidDomainException extends RuntimeException {

    public InvalidDomainException() {
        super("domain must be a host name with at least one dot, such as evil.example");
    }
}
