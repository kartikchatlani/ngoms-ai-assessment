package com.shortener.link;

/**
 * Every generation attempt collided. At 7 chars this should be practically impossible, so it
 * signals either a near-full code space or a broken generator: an operational alarm (503).
 */
public class CodeSpaceExhaustedException extends RuntimeException {

    public CodeSpaceExhaustedException(int attempts) {
        super("Could not allocate a unique code after " + attempts + " attempts");
    }
}
