package com.shortener.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ClickClassifierTest {

    @ParameterizedTest
    @CsvSource({
            "https://news.ycombinator.com/item?id=1,   news.ycombinator.com",
            "https://Sub.Example.COM/path?token=secret, sub.example.com",  // lower-cased; path and query dropped
            "http://example.com:8080/x,                 example.com",
            "android-app://com.google.android.gm/,      com.google.android.gm",
            "'  https://example.com/  ',                example.com"       // surrounding whitespace tolerated
    })
    void keepsOnlyTheLowerCasedHost(String referer, String expectedHost) {
        assertThat(ClickClassifier.referrerHost(referer)).isEqualTo(expectedHost);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not a url", "/relative/path", "https://", "http://exa mple.com/", "::::"})
    void unusableReferrersBecomeNull(String referer) {
        assertThat(ClickClassifier.referrerHost(referer)).isNull();
    }

    @org.junit.jupiter.api.Test
    void oversizedReferrersAreIgnored() {
        assertThat(ClickClassifier.referrerHost("https://" + "a".repeat(300) + ".com/")).isNull();
        assertThat(ClickClassifier.referrerHost("https://example.com/" + "x".repeat(5000))).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36 | DESKTOP",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 Version/18.0 Safari/605.1.15 | DESKTOP",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 | MOBILE",
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36 | MOBILE",
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html) | BOT",
            "Mozilla/5.0 (Linux; Android 6.0.1; Nexus 5X) Mobile Safari (compatible; Googlebot/2.1) | BOT",
            "facebookexternalhit/1.1 | BOT",
            "Slackbot-LinkExpanding 1.0 | BOT",
            "curl/8.10.1 | BOT",
            "python-requests/2.32 | BOT",
            "Java/21.0.5 | BOT"
    })
    void classifiesUserAgents(String userAgent, AgentClass expected) {
        assertThat(ClickClassifier.agentClass(userAgent)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Java-http-client/21.0.12", "Apache-HttpClient/5.4 (Java/21)", "okhttp/4.12.0"})
    void scriptedHttpClientsAreBots_regressionBug002(String userAgent) {
        // The first pattern only matched "httpclient", so hyphenated client names counted as DESKTOP.
        assertThat(ClickClassifier.agentClass(userAgent)).isEqualTo(AgentClass.BOT);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void missingUserAgentIsUnknown(String userAgent) {
        assertThat(ClickClassifier.agentClass(userAgent)).isEqualTo(AgentClass.UNKNOWN);
    }
}
