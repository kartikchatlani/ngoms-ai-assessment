package com.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class LinkTest {

    private static final Instant T = Instant.parse("2026-11-18T12:00:00Z");

    @Test
    void neverExpiringLinkIsActive() {
        assertThat(link(null, null).statusAt(T.plusSeconds(1_000_000))).isEqualTo(LinkStatus.ACTIVE);
    }

    @Test
    void expiryIsInclusiveAtTheBoundary() {
        Link link = link(T, null);
        assertThat(link.statusAt(T.minusMillis(1))).isEqualTo(LinkStatus.ACTIVE);
        assertThat(link.statusAt(T)).isEqualTo(LinkStatus.EXPIRED);
    }

    @Test
    void deletedWinsOverExpired() {
        assertThat(link(T, T).statusAt(T.plusSeconds(10))).isEqualTo(LinkStatus.DELETED);
    }

    private static Link link(Instant expiresAt, Instant deletedAt) {
        return new Link(1, "abc1234", "https://example.com", false, T.minusSeconds(60), expiresAt, deletedAt);
    }
}
