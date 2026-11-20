package com.shortener.link;

/**
 * Who is making a request, as the domain sees it (ADR-0008). Deliberately free of Spring
 * Security types, so the domain doesn't depend on how authentication happens.
 *
 * @param keyId API key id, or null for an anonymous caller
 * @param admin admin keys can manage every link
 */
public record Caller(Long keyId, boolean admin) {

    public static final Caller ANONYMOUS = new Caller(null, false);

    public static Caller key(long keyId, boolean admin) {
        return new Caller(keyId, admin);
    }

    public boolean isAnonymous() {
        return keyId == null;
    }

    /**
     * Owners manage their own links and admins manage all. Anonymous callers manage nothing, and
     * links with no owner (anonymous or pre-Phase 3) are admin-only.
     */
    public boolean canManage(Link link) {
        return admin || (keyId != null && keyId.equals(link.ownerKeyId()));
    }
}
