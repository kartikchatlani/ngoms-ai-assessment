package com.shortener.analytics;

import java.time.Instant;

/**
 * One counted redirect, already reduced to what we are willing to store (ADR-0007).
 *
 * @param referrerHost lower-case host of the Referer header, or null if absent or unparseable
 */
public record ClickEvent(long linkId, Instant clickedAt, String referrerHost, AgentClass agentClass) {
}
