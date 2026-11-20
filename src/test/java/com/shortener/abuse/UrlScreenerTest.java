package com.shortener.abuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shortener.config.AbuseProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlScreenerTest {

    private final BlockedDomainRepository repo = mock(BlockedDomainRepository.class);
    private final UrlScreener screener = new UrlScreener(repo, Clock.systemUTC(),
            new AbuseProperties(10, 1000, List.of("bit.ly", "T.co."), 30));

    @Test
    void matchesTheDomainAndEverySubdomainButNotLookalikes() {
        blocklist("evil.example");

        assertThat(screener.isBlocked("evil.example")).isTrue();
        assertThat(screener.isBlocked("login.evil.example")).isTrue();
        assertThat(screener.isBlocked("a.b.c.evil.example")).isTrue();
        assertThat(screener.isBlocked("EVIL.Example.")).isTrue(); // case and trailing dot
        assertThat(screener.isBlocked("notevil.example")).isFalse();
        assertThat(screener.isBlocked("evil.example.com")).isFalse();
        assertThat(screener.isBlocked("example")).isFalse();
        assertThat(screener.isBlocked(null)).isFalse();
    }

    @Test
    void shortenersAreRefusedOnCreateOnlyAndConfigIsNormalised() {
        blocklist();
        assertThat(screener.screenForCreate("bit.ly")).isEqualTo(UrlScreener.Verdict.URL_SHORTENER);
        assertThat(screener.screenForCreate("t.co")).isEqualTo(UrlScreener.Verdict.URL_SHORTENER); // "T.co." in config
        // Links to shorteners created before Phase 3 keep working: not malicious in themselves.
        assertThat(screener.isBlocked("bit.ly")).isFalse();
        assertThat(screener.screenForCreate("example.com")).isEqualTo(UrlScreener.Verdict.ALLOWED);
    }

    @Test
    void blocklistWinsOverTheShortenerRule() {
        blocklist("bit.ly");
        assertThat(screener.screenForCreate("bit.ly")).isEqualTo(UrlScreener.Verdict.BLOCKED_DOMAIN);
    }

    @Test
    void aFailedRefreshKeepsThePreviousBlocklist() {
        blocklist("evil.example");
        when(repo.findAll()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

        screener.refresh();

        // A database blip must never silently unblock phishing domains.
        assertThat(screener.isBlocked("evil.example")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"com", "localhost", "", "  ", "evil..example", "-evil.example", "evil.example/path",
            "https://evil.example", "evil example.com", "*.evil.example"})
    void rejectsThingsThatAreNotBlockableDomains(String domain) {
        assertThatThrownBy(() -> UrlScreener.validDomain(domain)).isInstanceOf(InvalidDomainException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"evil.example", "EVIL.Example.", "xn--bcher-kva.example", "203.0.113.9", "a-b.c-d.example"})
    void acceptsDomainsAndNormalisesThem(String domain) {
        assertThat(UrlScreener.validDomain(domain)).isEqualTo(domain.toLowerCase().replaceAll("\\.$", ""));
    }

    private void blocklist(String... domains) {
        when(repo.findAll()).thenReturn(java.util.Arrays.stream(domains)
                .map(d -> new BlockedDomain(d, null, Instant.EPOCH, null)).toList());
        screener.refresh();
    }
}
