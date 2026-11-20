package com.shortener.analytics;

/** Coarse client type derived from the User-Agent. The raw User-Agent is never stored (ADR-0007). */
public enum AgentClass {
    DESKTOP,
    MOBILE,
    /** Crawlers, link-preview fetchers and scripted clients (curl, HTTP libraries). */
    BOT,
    /** No User-Agent header. */
    UNKNOWN
}
