package com.shortener.link;

/** The code existed but is expired, deleted or blocked (410). Distinct from 404 so clients stop retrying. */
public class LinkGoneException extends RuntimeException {

    private final LinkStatus status;

    public LinkGoneException(String code, LinkStatus status) {
        super("Link '" + code + "' is " + status.name().toLowerCase());
        this.status = status;
    }

    public LinkStatus status() {
        return status;
    }
}
