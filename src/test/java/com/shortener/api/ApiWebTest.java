package com.shortener.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shortener.analytics.ClickRecorder;
import com.shortener.abuse.CreateRateLimiter;
import com.shortener.auth.ApiKey;
import com.shortener.auth.ApiKeyService;
import com.shortener.config.AppConfig;
import com.shortener.config.SecurityConfig;
import com.shortener.web.ClientIpResolver;
import com.shortener.web.ProblemWriter;
import com.shortener.link.AliasTakenException;
import com.shortener.link.CodeSpaceExhaustedException;
import com.shortener.link.InvalidRequestException;
import com.shortener.link.Link;
import com.shortener.link.LinkGoneException;
import com.shortener.link.LinkNotFoundException;
import com.shortener.link.LinkService;
import com.shortener.link.LinkStatus;
import com.shortener.web.RequestIdFilter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * HTTP contract tests: status codes, headers, JSON shape and error format, with the service
 * mocked. Fast (no DB), so they cover every error branch; the integration test covers wiring.
 */
@WebMvcTest(controllers = {LinkController.class, RedirectController.class})
@Import({AppConfig.class, RequestIdFilter.class, SecurityConfig.class, ProblemWriter.class, ClientIpResolver.class})
class ApiWebTest {

    private static final Instant CREATED = Instant.parse("2026-11-18T12:00:00Z");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    LinkService service;

    // Added in Phase 2: RedirectController now records clicks.
    @MockitoBean
    ClickRecorder clicks;

    // Added in Phase 3: management endpoints need a key (ADR-0008); create is rate-limited.
    // Authentication and limits have their own tests in SecurityWebTest.
    @MockitoBean
    ApiKeyService keys;

    @MockitoBean
    CreateRateLimiter rateLimiter;

    static final RequestPostProcessor CLIENT_KEY = request -> {
        request.addHeader("Authorization", "Bearer sk_test_client_key");
        return request;
    };

    @BeforeEach
    void phase3Wiring() {
        when(keys.authenticate("sk_test_client_key"))
                .thenReturn(Optional.of(new ApiKey(1, "client", "sk_test_c", false, CREATED, null)));
        // statusOf is real logic on the service; the mock would otherwise return null.
        when(service.statusOf(any())).thenAnswer(inv -> inv.<Link>getArgument(0).statusAt(Instant.now()));
    }

    // ---- create ---------------------------------------------------------------------------

    @Test
    void createReturns201WithLocationAndBody() throws Exception {
        when(service.create(any(), any())).thenReturn(link("Ab3xY9z", null));

        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/links/Ab3xY9z"))
                .andExpect(jsonPath("$.code").value("Ab3xY9z"))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/Ab3xY9z"))
                .andExpect(jsonPath("$.longUrl").value("https://example.com"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").value("2026-11-18T12:00:00Z"))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void createWithMissingUrlIs400WithFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("url"));
        verifyNoInteractions(service);
    }

    @Test
    void malformedJsonIs400ProblemNot500() throws Exception {
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void badExpiryFormatIs400() throws Exception {
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\",\"expiresAt\":\"tomorrow\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void domainValidationErrorIs400WithField() throws Exception {
        when(service.create(any(), any())).thenThrow(new InvalidRequestException("url", "must use http or https"));

        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"ftp://example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("url"))
                .andExpect(jsonPath("$.detail").value("url must use http or https"));
    }

    @Test
    void aliasTakenIs409() throws Exception {
        when(service.create(any(), any())).thenThrow(new AliasTakenException("promo"));

        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\",\"alias\":\"promo\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ALIAS_TAKEN"));
    }

    @Test
    void exhaustionIs503() throws Exception {
        when(service.create(any(), any())).thenThrow(new CodeSpaceExhaustedException(5));

        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("CODE_SPACE_EXHAUSTED"));
    }

    @Test
    void databaseDownIs503WithRetryAfter() throws Exception {
        when(service.create(any(), any())).thenThrow(new DataAccessResourceFailureException("connection refused"));

        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.errorCode").value("STORAGE_UNAVAILABLE"))
                // Internal details must not leak to clients.
                .andExpect(jsonPath("$.detail").value("Please retry shortly"));
    }

    @Test
    void unexpectedErrorIs500WithoutLeakingDetails() throws Exception {
        when(service.create(any(), any())).thenThrow(new IllegalStateException("secret internal state"));

        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
    }

    // ---- read / list / delete -----------------------------------------------------------

    @Test
    void getReturnsLinkIncludingExpiredStatus() throws Exception {
        when(service.get(eq("old1234"), any())).thenReturn(link("old1234", CREATED.plusSeconds(1)));

        mvc.perform(get("/api/v1/links/old1234").with(CLIENT_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    void getUnknownIs404() throws Exception {
        when(service.get(eq("nope123"), any())).thenThrow(new LinkNotFoundException("nope123"));

        mvc.perform(get("/api/v1/links/nope123").with(CLIENT_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("LINK_NOT_FOUND"));
    }

    @Test
    void listReturnsItemsAndCursor() throws Exception {
        when(service.list(isNull(), eq(2), any())).thenReturn(new LinkService.Page(
                List.of(link("aaa1111", null), link("bbb2222", null)), "OQ"));

        mvc.perform(get("/api/v1/links").with(CLIENT_KEY).param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.nextCursor").value("OQ"));
    }

    @Test
    void listWithNonNumericLimitIs400() throws Exception {
        mvc.perform(get("/api/v1/links").with(CLIENT_KEY).param("limit", "lots"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteIs204() throws Exception {
        mvc.perform(delete("/api/v1/links/abc1234").with(CLIENT_KEY)).andExpect(status().isNoContent());
    }

    @Test
    void deleteUnknownIs404() throws Exception {
        doThrow(new LinkNotFoundException("nope123")).when(service).delete(eq("nope123"), any());
        mvc.perform(delete("/api/v1/links/nope123").with(CLIENT_KEY)).andExpect(status().isNotFound());
    }

    // ---- redirect -----------------------------------------------------------------------

    @Test
    void redirectIs302WithNoStore() throws Exception {
        when(service.resolve("Ab3xY9z")).thenReturn(link("Ab3xY9z", null));

        mvc.perform(get("/Ab3xY9z"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void redirectPercentEncodesNonAsciiDestination() throws Exception {
        // HTTP headers are ISO-8859-1 on the wire; raw UTF-8 here would be mangled by Tomcat.
        when(service.resolve("utf8abc")).thenReturn(
                new Link(1, "utf8abc", "https://example.com/café", false, CREATED, null, null));

        mvc.perform(get("/utf8abc"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/caf%C3%A9"));
    }

    @Test
    void headRedirectWorksForLinkCheckers() throws Exception {
        when(service.resolve("Ab3xY9z")).thenReturn(link("Ab3xY9z", null));

        mvc.perform(head("/Ab3xY9z"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com"));
    }

    @Test
    void redirectUnknownIs404() throws Exception {
        when(service.resolve("nope123")).thenThrow(new LinkNotFoundException("nope123"));
        mvc.perform(get("/nope123")).andExpect(status().isNotFound());
    }

    @Test
    void redirectExpiredIs410() throws Exception {
        when(service.resolve("old1234")).thenThrow(new LinkGoneException("old1234", LinkStatus.EXPIRED));

        mvc.perform(get("/old1234"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.errorCode").value("LINK_EXPIRED"));
    }

    @Test
    void redirectRecordsAClickWithRefererAndUserAgent() throws Exception {
        when(service.resolve("Ab3xY9z")).thenReturn(link("Ab3xY9z", null));

        mvc.perform(get("/Ab3xY9z").header("Referer", "https://news.example/post/1").header("User-Agent", "Mozilla/5.0"))
                .andExpect(status().isFound());
        verify(clicks).record(1L, "https://news.example/post/1", "Mozilla/5.0");
    }

    @Test
    void headRedirectIsNotCountedAsAClick() throws Exception {
        when(service.resolve("Ab3xY9z")).thenReturn(link("Ab3xY9z", null));

        mvc.perform(head("/Ab3xY9z")).andExpect(status().isFound());
        verify(clicks, never()).record(anyLong(), any(), any());
    }

    @Test
    void failedRedirectsAreNotCounted() throws Exception {
        when(service.resolve("nope123")).thenThrow(new LinkNotFoundException("nope123"));
        when(service.resolve("old1234")).thenThrow(new LinkGoneException("old1234", LinkStatus.DELETED));

        mvc.perform(get("/nope123")).andExpect(status().isNotFound());
        mvc.perform(get("/old1234")).andExpect(status().isGone());
        verify(clicks, never()).record(anyLong(), any(), any());
    }

    @Test
    void pathsThatCannotBeCodesNeverReachTheService() throws Exception {
        // Phase 3: was 404. Security is default-deny (ADR-0008), so paths that match no rule are
        // refused before routing; anonymous callers get 401.
        mvc.perform(get("/favicon.ico")).andExpect(status().isUnauthorized());
        mvc.perform(get("/ab")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    // ---- cross-cutting ------------------------------------------------------------------

    @Test
    void everyResponseCarriesARequestId() throws Exception {
        when(service.get(eq("abc1234"), any())).thenReturn(link("abc1234", null));

        mvc.perform(get("/api/v1/links/abc1234").with(CLIENT_KEY))
                .andExpect(header().string(RequestIdFilter.HEADER, matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void inboundRequestIdIsPropagatedOnlyIfSafe() throws Exception {
        when(service.get(eq("abc1234"), any())).thenReturn(link("abc1234", null));

        mvc.perform(get("/api/v1/links/abc1234").with(CLIENT_KEY).header(RequestIdFilter.HEADER, "lb-trace-42"))
                .andExpect(header().string(RequestIdFilter.HEADER, "lb-trace-42"));
        mvc.perform(get("/api/v1/links/abc1234").with(CLIENT_KEY).header(RequestIdFilter.HEADER, "bad id\nwith newline"))
                .andExpect(header().string(RequestIdFilter.HEADER, matchesPattern("[0-9a-f-]{36}")));
    }

    private static Link link(String code, Instant expiresAt) {
        return new Link(1, code, "https://example.com", false, CREATED, expiresAt, null);
    }
}
