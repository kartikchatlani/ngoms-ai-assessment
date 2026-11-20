package com.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import com.shortener.link.LinkRepository;
import com.shortener.link.NewLink;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end over real HTTP against a real PostgreSQL (Testcontainers), with Flyway migrations
 * applied exactly as in production. Verifies what mocks cannot: SQL, constraints, ON CONFLICT
 * concurrency behaviour and the wiring of every layer.
 *
 * <p>Skipped automatically when Docker is not available (see docs/testing.md).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.base-url=http://sho.rt",
        // Phase 3: these tests are about link behaviour, not abuse controls (AbuseIntegrationTest).
        "app.security.admin-key=" + LinkApiIntegrationTest.ADMIN_KEY,
        "app.abuse.anonymous-creates-per-hour=1000000",
        "app.abuse.key-creates-per-hour=1000000"})
class LinkApiIntegrationTest {

    static final String ADMIN_KEY = "sk_integration_test_admin_key";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    LinkRepository repository;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER) // we are testing the redirect itself
            .build();

    /** Phase 3: management needs a key, so each test acts as a freshly issued client key. */
    private String key;
    private long keyId;

    @BeforeEach
    void issueKey() throws Exception {
        var res = http.send(HttpRequest.newBuilder(uri("/api/v1/admin/keys"))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + ADMIN_KEY)
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"integration\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode issued = json.readTree(res.body());
        key = issued.get("key").asString();
        keyId = issued.get("id").asLong();
    }

    @Test
    void fullLifecycle_create_get_redirect_list_delete() throws Exception {
        // create
        var created = post("{\"url\":\"https://example.com/a/very/long/path?x=1\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(created.body());
        String code = body.get("code").asString();
        assertThat(code).matches("[0-9A-Za-z]{7}");
        assertThat(body.get("shortUrl").asString()).isEqualTo("http://sho.rt/" + code);
        assertThat(created.headers().firstValue("Location")).hasValue("/api/v1/links/" + code);

        // get
        assertThat(send("GET", "/api/v1/links/" + code).statusCode()).isEqualTo(200);

        // redirect
        var redirect = send("GET", "/" + code);
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(redirect.headers().firstValue("Location")).hasValue("https://example.com/a/very/long/path?x=1");

        // list contains it
        JsonNode page = json.readTree(send("GET", "/api/v1/links?limit=100").body());
        assertThat(page.get("items").findValuesAsString("code")).contains(code);

        // delete -> redirect is 410, management is 404, delete again is still 204
        assertThat(send("DELETE", "/api/v1/links/" + code).statusCode()).isEqualTo(204);
        assertThat(send("GET", "/" + code).statusCode()).isEqualTo(410);
        assertThat(send("GET", "/api/v1/links/" + code).statusCode()).isEqualTo(404);
        assertThat(send("DELETE", "/api/v1/links/" + code).statusCode()).isEqualTo(204);
    }

    @Test
    void deletedAliasCannotBeReclaimed() throws Exception {
        assertThat(post("{\"url\":\"https://example.com\",\"alias\":\"reclaim-me\"}").statusCode()).isEqualTo(201);
        assertThat(send("DELETE", "/api/v1/links/reclaim-me").statusCode()).isEqualTo(204);

        // Someone else must not be able to take over a code that is already printed on flyers.
        assertThat(post("{\"url\":\"https://evil.example\",\"alias\":\"reclaim-me\"}").statusCode()).isEqualTo(409);
    }

    @Test
    void concurrentRequestsForSameAliasProduceExactlyOneWinner() throws Exception {
        int threads = 16;
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            calls.add(() -> post("{\"url\":\"https://example.com\",\"alias\":\"race-alias\"}").statusCode());
        }
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (Future<Integer> f : pool.invokeAll(calls)) {
                statuses.add(f.get());
            }
        }
        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
    }

    @Test
    void generatedCodesAreUniqueAcrossManyCreates() throws Exception {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            var res = post("{\"url\":\"https://example.com/" + i + "\"}");
            assertThat(res.statusCode()).isEqualTo(201);
            codes.add(json.readTree(res.body()).get("code").asString());
        }
        assertThat(codes).hasSize(200);
    }

    @Test
    void expiredLinkIsGone() throws Exception {
        // Expired links cannot be created via the API (expiresAt must be in the future), so seed one.
        Instant created = Instant.now().minusSeconds(120);
        repository.insertIfCodeAbsent(new NewLink("expired-1", "https://example.com", true, created, created.plusSeconds(60), keyId));

        assertThat(send("GET", "/expired-1").statusCode()).isEqualTo(410);
        JsonNode link = json.readTree(send("GET", "/api/v1/links/expired-1").body());
        assertThat(link.get("status").asString()).isEqualTo("EXPIRED");
    }

    @Test
    void expiryBoundsAreSafeForPostgres_regressionBug001() throws Exception {
        // The largest accepted expiry must actually be storable...
        assertThat(post("{\"url\":\"https://example.com\",\"expiresAt\":\"9999-12-31T23:59:59Z\"}").statusCode())
                .isEqualTo(201);
        // ...and anything beyond is a 400, never a 500 from the database.
        assertThat(post("{\"url\":\"https://example.com\",\"expiresAt\":\"+100000-01-01T00:00:00Z\"}").statusCode())
                .isEqualTo(400);
    }

    @Test
    void paginationWalksAllLinksWithoutDuplicates() throws Exception {
        for (int i = 0; i < 7; i++) {
            post("{\"url\":\"https://example.com/page-" + i + "\"}");
        }
        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            String path = "/api/v1/links?limit=3" + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = json.readTree(send("GET", path).body());
            for (JsonNode item : page.get("items")) {
                assertThat(seen.add(item.get("code").asString())).as("no duplicates across pages").isTrue();
            }
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
            pages++;
        } while (cursor != null && pages < 1000);
        assertThat(seen.size()).isGreaterThanOrEqualTo(7);
    }

    @Test
    void healthAndReadinessAreUp() throws Exception {
        assertThat(send("GET", "/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(send("GET", "/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    @Test
    void invalidInputIsRejectedWithProblemDetails() throws Exception {
        var res = post("{\"url\":\"javascript:alert(1)\"}");
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).startsWith("application/problem+json"));
    }

    // ---- helpers -------------------------------------------------------------------------

    private HttpResponse<String> post(String jsonBody) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/api/v1/links"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).method(method, HttpRequest.BodyPublishers.noBody())
                        .header("Authorization", "Bearer " + key).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
