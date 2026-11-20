package com.shortener.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shortener.abuse.CreateRateLimiter;
import com.shortener.abuse.RateLimitedException;
import com.shortener.abuse.UrlNotAllowedException;
import com.shortener.abuse.UrlScreener;
import com.shortener.analytics.ClickRecorder;
import com.shortener.auth.ApiKey;
import com.shortener.auth.ApiKeyService;
import com.shortener.config.AppConfig;
import com.shortener.config.SecurityConfig;
import com.shortener.link.Caller;
import com.shortener.link.Link;
import com.shortener.link.LinkService;
import com.shortener.web.ClientIpResolver;
import com.shortener.web.ProblemWriter;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The access rules of SecurityConfig (ADR-0008), the error contract for 401/403/429 and
 * screening, and the admin API, with services mocked.
 */
@WebMvcTest(controllers = {LinkController.class, RedirectController.class, AdminController.class})
@Import({AppConfig.class, SecurityConfig.class, ProblemWriter.class, ClientIpResolver.class})
class SecurityWebTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final ApiKey CLIENT = new ApiKey(1, "marketing", "sk_client", false, NOW, null);
    private static final ApiKey ADMIN = new ApiKey(2, "ops", "sk_admin1", true, NOW, null);

    @Autowired
    MockMvc mvc;

    @MockitoBean
    LinkService links;

    @MockitoBean
    ClickRecorder clicks;

    @MockitoBean
    ApiKeyService keys;

    @MockitoBean
    CreateRateLimiter rateLimiter;

    @MockitoBean
    UrlScreener screener;

    @BeforeEach
    void setUp() {
        when(keys.authenticate("sk_client_secret")).thenReturn(Optional.of(CLIENT));
        when(keys.authenticate("sk_admin_secret")).thenReturn(Optional.of(ADMIN));
        when(links.statusOf(any())).thenAnswer(inv -> inv.<Link>getArgument(0).statusAt(NOW));
    }

    // ---- 401: who are you? -----------------------------------------------------------------

    @Test
    void managementWithoutAKeyIs401ProblemWithBearerChallenge() throws Exception {
        for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[] {
                get("/api/v1/links"), get("/api/v1/links/abc1234"), delete("/api/v1/links/abc1234")}) {
            mvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.errorCode").value("AUTH_REQUIRED"));
        }
        verifyNoInteractions(links);
    }

    @Test
    void anInvalidKeyIs401EvenWhereAnonymousWouldBeAllowed() throws Exception {
        // Falling back to anonymous would hide a broken client config (and give it the low limit).
        mvc.perform(post("/api/v1/links").header("Authorization", "Bearer sk_revoked_or_typo")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""))
                .andExpect(jsonPath("$.errorCode").value("INVALID_API_KEY"));
        verifyNoInteractions(links);
    }

    @Test
    void nonBearerSchemesAreRejected() throws Exception {
        mvc.perform(get("/api/v1/links").header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_API_KEY"));
    }

    @Test
    void bearerSchemeIsCaseInsensitive() throws Exception {
        when(links.list(any(), any(), any())).thenReturn(new LinkService.Page(java.util.List.of(), null));
        mvc.perform(get("/api/v1/links").header("Authorization", "bearer sk_client_secret")).andExpect(status().isOk());
    }

    // ---- what anonymous callers can still do ------------------------------------------------

    @Test
    void redirectsNeedNoKey() throws Exception {
        when(links.resolve("Ab3xY9z")).thenReturn(new Link(1, "Ab3xY9z", "https://example.com", false, NOW, null, null));
        mvc.perform(get("/Ab3xY9z")).andExpect(status().isFound());
    }

    @Test
    void anonymousCreateIsAllowedAndLimitedPerIp() throws Exception {
        when(links.create(any(), any())).thenReturn(new Link(1, "Ab3xY9z", "https://example.com", false, NOW, null, null));

        mvc.perform(post("/api/v1/links").with(remoteAddr("203.0.113.7"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isCreated());

        verify(rateLimiter).check(isNull(), eq(false), eq("203.0.113.7"));
        verify(links).create(any(), eq(Caller.ANONYMOUS));
    }

    @Test
    void createWithAKeyIsLimitedPerKeyAndOwnedByIt() throws Exception {
        when(links.create(any(), any())).thenReturn(new Link(1, "Ab3xY9z", "https://example.com", false, NOW, null, null));

        mvc.perform(post("/api/v1/links").with(key("sk_client_secret"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isCreated());

        verify(rateLimiter).check(eq(1L), eq(false), anyString());
        verify(links).create(any(), eq(Caller.key(1, false)));
    }

    @Test
    void rateLimitedIs429WithRetryAfter() throws Exception {
        doThrow(new RateLimitedException(360)).when(rateLimiter).check(any(), anyBoolean(), any());

        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "360"))
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"));
        verifyNoInteractions(links);
    }

    @Test
    void everyScreeningRuleGivesTheSameGenericResponse() throws Exception {
        // Distinguishable responses would let abusers map out the blocklist.
        when(links.create(any(), any()))
                .thenThrow(new UrlNotAllowedException(UrlScreener.Verdict.BLOCKED_DOMAIN))
                .thenThrow(new UrlNotAllowedException(UrlScreener.Verdict.URL_SHORTENER));

        String blocked = createBody("https://evil.example");
        String shortener = createBody("https://bit.ly/x");

        org.assertj.core.api.Assertions.assertThat(blocked).isEqualTo(shortener).contains("URL_NOT_ALLOWED")
                .doesNotContain("BLOCKED_DOMAIN").doesNotContain("SHORTENER");
    }

    // ---- 403: admin only ---------------------------------------------------------------------

    @Test
    void adminEndpointsAre403ForClientKeysAnd401WithoutAKey() throws Exception {
        mvc.perform(get("/api/v1/admin/keys").with(key("sk_client_secret")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/admin/keys")).andExpect(status().isUnauthorized());
        verifyNoInteractions(screener);
    }

    @Test
    void adminCanIssueAKeyAndTheSecretIsReturnedOnce() throws Exception {
        when(keys.issue("marketing", false)).thenReturn(new ApiKeyService.IssuedKey(
                new ApiKey(5, "marketing", "sk_Ab3xY9", false, NOW, null), "sk_Ab3xY9zFullSecretValue000000000"));
        when(keys.list()).thenReturn(java.util.List.of(new ApiKey(5, "marketing", "sk_Ab3xY9", false, NOW, null)));

        mvc.perform(post("/api/v1/admin/keys").with(key("sk_admin_secret"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"marketing\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/admin/keys/5"))
                .andExpect(jsonPath("$.key").value("sk_Ab3xY9zFullSecretValue000000000"))
                .andExpect(jsonPath("$.prefix").value("sk_Ab3xY9"));
        // Listing never shows secrets.
        mvc.perform(get("/api/v1/admin/keys").with(key("sk_admin_secret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].prefix").value("sk_Ab3xY9"))
                .andExpect(jsonPath("$[0].key").doesNotExist());
    }

    @Test
    void theBootstrapAdminNameIsReserved() throws Exception {
        mvc.perform(post("/api/v1/admin/keys").with(key("sk_admin_secret"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Bootstrap-Admin\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        verify(keys, never()).issue(any(), anyBoolean());
    }

    @Test
    void adminTakedownAndReassignment() throws Exception {
        when(keys.get(1)).thenReturn(CLIENT);

        mvc.perform(put("/api/v1/admin/links/phish01/block").with(key("sk_admin_secret"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"phishing report #123\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(put("/api/v1/admin/links/promo01/owner").with(key("sk_admin_secret"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keyId\":1}"))
                .andExpect(status().isNoContent());

        verify(links).block("phish01", "phishing report #123");
        verify(links).assignOwner("promo01", 1L);
    }

    @Test
    void blockingADomainRecordsWhichAdminDidIt() throws Exception {
        when(screener.block("evil.example", "phishing", 2L)).thenReturn("evil.example");

        mvc.perform(post("/api/v1/admin/blocked-domains").with(key("sk_admin_secret"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"domain\":\"evil.example\",\"reason\":\"phishing\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/admin/blocked-domains/evil.example"));
        verify(screener).block("evil.example", "phishing", 2L);
    }

    // ---- default deny ------------------------------------------------------------------------

    @Test
    void unknownPathsAreDeniedNotRouted() throws Exception {
        mvc.perform(get("/wp-login.php")).andExpect(status().isUnauthorized());
        mvc.perform(get("/wp-login.php").with(key("sk_client_secret"))).andExpect(status().isForbidden());
    }

    // ---- helpers -----------------------------------------------------------------------------

    private String createBody(String url) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url + "\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private static RequestPostProcessor key(String secret) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + secret);
            return request;
        };
    }

    private static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}
