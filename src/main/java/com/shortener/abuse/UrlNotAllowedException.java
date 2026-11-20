package com.shortener.abuse;

/**
 * The destination failed screening (maps to 400 URL_NOT_ALLOWED). The client always gets the
 * same generic message; the specific rule is logged, so abusers can't probe which rule caught
 * them or which domains are listed (ADR-0010).
 */
public class UrlNotAllowedException extends RuntimeException {

    private final UrlScreener.Verdict verdict;

    public UrlNotAllowedException(UrlScreener.Verdict verdict) {
        super("This destination is not allowed");
        this.verdict = verdict;
    }

    public UrlScreener.Verdict verdict() {
        return verdict;
    }
}
