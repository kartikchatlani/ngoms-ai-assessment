package com.shortener.link;

/** No link has ever had this code, or it was deleted and is hidden from management APIs (404). */
public class LinkNotFoundException extends RuntimeException {

    public LinkNotFoundException(String code) {
        super("No link with code '" + code + "'");
    }
}
