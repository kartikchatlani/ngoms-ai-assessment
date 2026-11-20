package com.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import com.shortener.link.LinkRepository;
import com.shortener.link.NewLink;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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
 * Scenario 3 end to end: each symptom in the request, reproduced against the real stack and
 * shown to be fixed. Real HTTP, real Spring Security chain, real PostgreSQL with V1-V4 applied.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.base-url=http://sho.rt",
        "app.security.admin-key=" + AbuseIntegrationTest.ADMIN_KEY,
        "app.abuse.anonymous-creates-per-hour=3"})
class AbuseIntegrationTest {

    static final String ADMIN_KEY = "sk_integration_test_admin_key";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    LinkRepository repository;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private String marketing;
    private String other;
    private long marketingId;

    @BeforeEach
    void issueKeys() throws Exception {
        JsonNode m = json.readTree(call("POST", "/api/v1/admin/keys", ADMIN_KEY, "{\"name\":\"marketing\"}").body());
        marketing = m.get("key").asString();
        marketingId = m.get("id").asLong();
        other = json.readTree(call("POST", "/api/v1/admin/keys", ADMIN_KEY, "{\"name\":\"someone-else\"}").body())
                .get("key").asString();
    }

    // ---- symptom 3: "a campaign link disappeared, nobody on the team deleted it" -------------

    @Test
    void anotherKeyCannotSeeOrDeleteTheCampaignLink() throws Exception {
        String code = create(marketing, "{\"url\":\"https://example.com/campaign\",\"alias\":\"spring-sale\"}");

        assertThat(call("DELETE", "/api/v1/links/" + code, other, null).statusCode()).isEqualTo(404);
        assertThat(call("DELETE", "/api/v1/links/" + code, null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/" + code, null, null).statusCode()).as("still redirects").isEqualTo(302);

        // The owner can, and the audit trail now answers "who deleted it?".
        assertThat(call("DELETE", "/api/v1/links/" + code, marketing, null).statusCode()).isEqualTo(204);
        Long deletedBy = jdbc.sql("SELECT deleted_by FROM links WHERE code = :c").param("c", code).query(Long.class).single();
        assertThat(deletedBy).isEqualTo(marketingId);
    }

    @Test
    void someoneElsesLinkLooksExactlyLikeAMissingOne() throws Exception {
        String owned = create(marketing, "{\"url\":\"https://example.com/owned\"}");
        String missing = "zzzMissing9";

        for (String path : List.of("/api/v1/links/%s", "/api/v1/links/%s/stats")) {
            var notYours = call("GET", path.formatted(owned), other, null);
            var notThere = call("GET", path.formatted(missing), other, null);
            assertThat(notYours.statusCode()).isEqualTo(404).isEqualTo(notThere.statusCode());
            // Identical apart from the code itself, which the caller supplied anyway.
            assertThat(notYours.body().replace(owned, "CODE")).isEqualTo(notThere.body().replace(missing, "CODE"));
            assertThat(headerNames(notYours)).isEqualTo(headerNames(notThere));
        }
    }

    @Test
    void listingShowsOnlyYourOwnLinksAndAdminSeesAll() throws Exception {
        String mine = create(marketing, "{\"url\":\"https://example.com/m\"}");
        String theirs = create(other, "{\"url\":\"https://example.com/o\"}");

        assertThat(codes(marketing)).contains(mine).doesNotContain(theirs);
        assertThat(codes(other)).contains(theirs).doesNotContain(mine);
        assertThat(codes(ADMIN_KEY)).contains(mine, theirs);
        assertThat(call("GET", "/api/v1/links", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void preExistingLinksAreAdminOnlyUntilAssigned() throws Exception {
        // A link created before Phase 3: no owner (scenario 3, Q7).
        repository.insertIfCodeAbsent(new NewLink("old-campaign", "https://example.com/old", true, Instant.now(), null));

        assertThat(call("GET", "/api/v1/links/old-campaign", marketing, null).statusCode()).isEqualTo(404);
        assertThat(call("PUT", "/api/v1/admin/links/old-campaign/owner", ADMIN_KEY, "{\"keyId\":" + marketingId + "}")
                .statusCode()).isEqualTo(204);
        assertThat(call("GET", "/api/v1/links/old-campaign", marketing, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/links/old-campaign/stats", marketing, null).statusCode()).isEqualTo(200);
    }

    // ---- symptom 2: "the table grew faster than traffic; someone is scripting it" ------------

    @Test
    void anonymousScriptsAreRateLimitedAndCantDodgeItWithAFakeForwardedFor() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(create(null, "{\"url\":\"https://example.com/a" + i + "\"}")).isNotNull();
        }
        var limited = call("POST", "/api/v1/links", null, "{\"url\":\"https://example.com/a3\"}");
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).hasValueSatisfying(v -> assertThat(Long.parseLong(v)).isPositive());
        assertThat(json.readTree(limited.body()).get("errorCode").asString()).isEqualTo("RATE_LIMITED");

        var spoofed = http.send(HttpRequest.newBuilder(uri("/api/v1/links"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", "198.51.100." + System.nanoTime() % 250)
                .POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"https://example.com/a4\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(spoofed.statusCode()).as("X-Forwarded-For is not trusted by default").isEqualTo(429);

        // Normal people with a key are unaffected; and redirects are never limited.
        assertThat(call("POST", "/api/v1/links", marketing, "{\"url\":\"https://example.com/k\"}").statusCode()).isEqualTo(201);
    }

    // ---- symptom 1: "Chrome warning; links to fake login pages" ------------------------------

    @Test
    void blockingADomainDisablesExistingLinksImmediatelyAndStopsNewOnes() throws Exception {
        String phish = create(marketing, "{\"url\":\"https://login.phish-demo.example/account\"}");
        assertThat(call("GET", "/" + phish, null, null).statusCode()).isEqualTo(302);

        assertThat(call("POST", "/api/v1/admin/blocked-domains", ADMIN_KEY,
                "{\"domain\":\"phish-demo.example\",\"reason\":\"user reports\"}").statusCode()).isEqualTo(201);

        var gone = call("GET", "/" + phish, null, null);
        assertThat(gone.statusCode()).isEqualTo(410);
        assertThat(json.readTree(gone.body()).get("errorCode").asString()).isEqualTo("LINK_BLOCKED");
        assertThat(json.readTree(call("GET", "/api/v1/links/" + phish, marketing, null).body()).get("status").asString())
                .isEqualTo("BLOCKED");
        var refused = call("POST", "/api/v1/links", marketing, "{\"url\":\"https://phish-demo.example/x\"}");
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json.readTree(refused.body()).get("errorCode").asString()).isEqualTo("URL_NOT_ALLOWED");

        // Mistakes can be undone.
        assertThat(call("DELETE", "/api/v1/admin/blocked-domains/phish-demo.example", ADMIN_KEY, null).statusCode()).isEqualTo(204);
        assertThat(call("GET", "/" + phish, null, null).statusCode()).isEqualTo(302);
    }

    @Test
    void adminTakedownOfASingleLinkCantBeUndoneByItsOwner() throws Exception {
        String code = create(marketing, "{\"url\":\"https://example.com/reported\"}");

        assertThat(call("PUT", "/api/v1/admin/links/" + code + "/block", ADMIN_KEY, "{\"reason\":\"report #42\"}")
                .statusCode()).isEqualTo(204);
        assertThat(call("GET", "/" + code, null, null).statusCode()).isEqualTo(410);
        assertThat(call("DELETE", "/api/v1/admin/links/" + code + "/block", marketing, null).statusCode()).isEqualTo(403);
        // The takedown reason is internal: the owner sees the status, not the note.
        String body = call("GET", "/api/v1/links/" + code, marketing, null).body();
        assertThat(body).contains("\"status\":\"BLOCKED\"").doesNotContain("report #42");
    }

    @Test
    void linksToOtherShortenersAreRefused() throws Exception {
        assertThat(call("POST", "/api/v1/links", marketing, "{\"url\":\"https://bit.ly/3abcdef\"}").statusCode()).isEqualTo(400);
    }

    // ---- keys and operations -----------------------------------------------------------------

    @Test
    void revokedKeysStopWorkingImmediately() throws Exception {
        JsonNode issued = json.readTree(call("POST", "/api/v1/admin/keys", ADMIN_KEY, "{\"name\":\"temp\"}").body());
        String temp = issued.get("key").asString();
        assertThat(call("GET", "/api/v1/links", temp, null).statusCode()).isEqualTo(200);

        assertThat(call("DELETE", "/api/v1/admin/keys/" + issued.get("id").asLong(), ADMIN_KEY, null).statusCode()).isEqualTo(204);

        var res = call("GET", "/api/v1/links", temp, null);
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(json.readTree(res.body()).get("errorCode").asString()).isEqualTo("INVALID_API_KEY");
    }

    @Test
    void theBootstrapAdminExistsAndOnlyItsHashIsStored() throws Exception {
        JsonNode keys = json.readTree(call("GET", "/api/v1/admin/keys", ADMIN_KEY, null).body());
        assertThat(keys.findValuesAsString("name")).contains("bootstrap-admin");
        assertThat(keys.toString()).doesNotContain(ADMIN_KEY);
        Long plaintextRows = jdbc.sql("SELECT count(*) FROM api_keys WHERE key_hash = :k").param("k", ADMIN_KEY)
                .query(Long.class).single();
        assertThat(plaintextRows).isZero();
    }

    @Test
    void metricsAreAdminOnlyButHealthStaysPublic() throws Exception {
        assertThat(call("GET", "/actuator/health/readiness", null, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/actuator/metrics", null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/actuator/metrics", marketing, null).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/actuator/metrics", ADMIN_KEY, null).statusCode()).isEqualTo(200);
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** @return the new link's code */
    private String create(String key, String body) throws Exception {
        var res = call("POST", "/api/v1/links", key, body);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        return json.readTree(res.body()).get("code").asString();
    }

    private List<String> codes(String key) throws Exception {
        return json.readTree(call("GET", "/api/v1/links?limit=100", key, null).body()).get("items").findValuesAsString("code");
    }

    private HttpResponse<String> call(String method, String path, String key, String body) throws Exception {
        var b = HttpRequest.newBuilder(uri(path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (key != null) {
            b.header("Authorization", "Bearer " + key);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> headerNames(HttpResponse<?> res) {
        return res.headers().map().keySet().stream().map(String::toLowerCase).sorted().toList();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
