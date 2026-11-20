package com.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import com.shortener.analytics.ClickRecorder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Click analytics end to end: real redirects over HTTP, the background writer, the V2 schema
 * and the stats queries against real PostgreSQL. {@link ClickRecorder#awaitIdle} makes the
 * asynchronous write deterministic without sleeps.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.base-url=http://sho.rt")
class AnalyticsIntegrationTest {

    private static final String CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36";
    private static final String IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    ClickRecorder recorder;

    @Autowired
    JdbcClient jdbc;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void redirectsAreCountedAndBrokenDown() throws Exception {
        String code = create("https://example.com/campaign");

        redirect(code, "https://news.ycombinator.com/item?id=1", CHROME);
        redirect(code, "https://news.ycombinator.com/item?id=2", IPHONE);
        redirect(code, "https://t.co/abc", "curl/8.10.1");
        redirect(code, null, null);
        assertThat(send("HEAD", "/" + code, null, null).statusCode()).isEqualTo(302); // not a click
        flush();

        JsonNode stats = stats(code, "");
        assertThat(stats.get("totalClicks").asLong()).isEqualTo(4);
        assertThat(stats.get("lastClickedAt").isNull()).isFalse();
        assertThat(stats.get("window").get("clicks").asLong()).isEqualTo(4);
        assertThat(stats.get("window").get("to").asString()).isEqualTo(LocalDate.now(ZoneOffset.UTC).toString());
        assertThat(stats.get("daily")).hasSize(30);
        assertThat(stats.get("daily").get(29).get("clicks").asLong()).isEqualTo(4);
        assertThat(stats.get("topReferrers").get(0).get("host").asString()).isEqualTo("news.ycombinator.com");
        assertThat(stats.get("topReferrers").get(0).get("clicks").asLong()).isEqualTo(2);
        assertThat(stats.get("topReferrers").get(1).get("host").asString()).isEqualTo("t.co");
        assertThat(stats.get("withoutReferrer").asLong()).isEqualTo(1);
        JsonNode agents = stats.get("agents");
        assertThat(agents.get("DESKTOP").asLong()).isEqualTo(1);
        assertThat(agents.get("MOBILE").asLong()).isEqualTo(1);
        assertThat(agents.get("BOT").asLong()).isEqualTo(1);
        assertThat(agents.get("UNKNOWN").asLong()).isEqualTo(1);
    }

    @Test
    void onlyTheReferrerHostIsStored() throws Exception {
        String code = create("https://example.com/private");
        redirect(code, "https://mail.example.com/inbox?session=SECRET-TOKEN", CHROME);
        flush();

        List<String> stored = jdbc.sql("""
                        SELECT ce.referrer_host FROM click_events ce JOIN links l ON l.id = ce.link_id
                        WHERE l.code = :code""")
                .param("code", code).query(String.class).list();
        assertThat(stored).containsExactly("mail.example.com");
    }

    @Test
    void neverClickedLinkHasEmptyStats() throws Exception {
        String code = create("https://example.com/quiet");

        JsonNode stats = stats(code, "?days=7");
        assertThat(stats.get("totalClicks").asLong()).isZero();
        assertThat(stats.get("lastClickedAt").isNull()).isTrue();
        assertThat(stats.get("daily")).hasSize(7);
        assertThat(stats.get("topReferrers")).isEmpty();
    }

    @Test
    void concurrentRedirectsAreAllCounted() throws Exception {
        String code = create("https://example.com/viral");
        int clicks = 500;
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < clicks; i++) {
            calls.add(() -> send("GET", "/" + code, null, CHROME).statusCode());
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(32)) {
            for (Future<Integer> f : pool.invokeAll(calls)) {
                assertThat(f.get()).isEqualTo(302);
            }
        }
        flush();

        assertThat(stats(code, "").get("totalClicks").asLong()).isEqualTo(clicks);
    }

    @Test
    void statsFollowTheLinkVisibilityRules() throws Exception {
        String code = create("https://example.com/soon-deleted");
        redirect(code, null, CHROME);
        flush();
        assertThat(send("DELETE", "/api/v1/links/" + code, null, null).statusCode()).isEqualTo(204);

        // Deleted links are 404 everywhere in the management API, stats included.
        assertThat(send("GET", "/api/v1/links/" + code + "/stats", null, null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/v1/links/nope1234/stats", null, null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/v1/links/" + create("https://example.com") + "/stats?days=400", null, null)
                .statusCode()).isEqualTo(400);
        // Redirects to a deleted link (410) are not clicks.
        assertThat(send("GET", "/" + code, null, CHROME).statusCode()).isEqualTo(410);
        flush();
        long stored = jdbc.sql("SELECT count(*) FROM click_events ce JOIN links l ON l.id = ce.link_id WHERE l.code = :code")
                .param("code", code).query(Long.class).single();
        assertThat(stored).isEqualTo(1);
    }

    @Test
    void clickMetricsAreExposed() throws Exception {
        String code = create("https://example.com/metrics");
        redirect(code, null, CHROME);
        flush();

        var res = send("GET", "/actuator/metrics/shortener.clicks?tag=outcome:recorded", null, null);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(json.readTree(res.body()).get("measurements").get(0).get("value").asDouble()).isGreaterThanOrEqualTo(1);
    }

    // ---- helpers -------------------------------------------------------------------------

    private void flush() throws InterruptedException {
        assertThat(recorder.awaitIdle(Duration.ofSeconds(10))).as("clicks written").isTrue();
    }

    private String create(String url) throws Exception {
        var res = http.send(HttpRequest.newBuilder(uri("/api/v1/links"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"" + url + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(201);
        return json.readTree(res.body()).get("code").asString();
    }

    private void redirect(String code, String referer, String userAgent) throws Exception {
        assertThat(send("GET", "/" + code, referer, userAgent).statusCode()).isEqualTo(302);
    }

    private JsonNode stats(String code, String query) throws Exception {
        var res = send("GET", "/api/v1/links/" + code + "/stats" + query, null, null);
        assertThat(res.statusCode()).isEqualTo(200);
        return json.readTree(res.body());
    }

    private HttpResponse<String> send(String method, String path, String referer, String userAgent) throws Exception {
        var b = HttpRequest.newBuilder(uri(path)).method(method, HttpRequest.BodyPublishers.noBody());
        if (referer != null) {
            b.header("Referer", referer);
        }
        // The JDK client sends "Java-http-client/21" by default, which we classify as BOT.
        b.header("User-Agent", userAgent == null ? "" : userAgent);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
