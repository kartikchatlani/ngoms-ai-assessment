package com.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shortener.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class UrlPolicyTest {

    private final UrlPolicy policy = new UrlPolicy(TestFixtures.props());

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com",
            "http://example.com/path?q=1&r=two#frag",
            "HTTPS://EXAMPLE.COM/upper",
            "https://sub.domain.example.co.uk:8443/a/b",
            "https://example.com/%E2%9C%93-encoded",
            "http://192.0.2.10/literal-ip"
    })
    void acceptsOrdinaryWebUrls(String url) {
        assertThat(policy.validate(url)).isEqualTo(url);
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(policy.validate("  https://example.com  ")).isEqualTo("https://example.com");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsBlank(String url) {
        assertRejected(url, "must not be blank");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "javascript:alert(1)            | must use http or https",
            "file:///etc/passwd             | must use http or https",
            "ftp://example.com/file         | must use http or https",
            "example.com                    | must use http or https",
            "https:///path-only             | must include a valid host",
            "https://exa mple.com           | is not a valid URI",
            "https://user:pw@example.com    | must not contain credentials",
            "https://google.com@evil.com    | must not contain credentials",
            "http://sho.rt/abc1234          | must not point at this shortener",
            "https://SHO.RT/abc1234         | must not point at this shortener"
    })
    void rejectsUnsafeOrMalformed(String url, String reason) {
        assertRejected(url, reason);
    }

    @Test
    void rejectsOverlongUrls() {
        var strict = new UrlPolicy(new AppProperties(TestFixtures.BASE_URL, 7, 5, 64, 100));
        String url = "https://example.com/" + "a".repeat(64);
        assertThatThrownBy(() -> strict.validate(url))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("at most 64");
    }

    private void assertRejected(String url, String reason) {
        assertThatThrownBy(() -> policy.validate(url))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining(reason)
                .extracting(e -> ((InvalidRequestException) e).field()).isEqualTo("url");
    }
}
