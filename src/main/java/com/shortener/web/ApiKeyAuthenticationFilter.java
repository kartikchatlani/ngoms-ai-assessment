package com.shortener.web;

import com.shortener.auth.ApiKey;
import com.shortener.auth.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads {@code Authorization: Bearer <api key>} (ADR-0008).
 *
 * <ul>
 *   <li>No header: the request continues as anonymous. The authorization rules decide whether
 *       that's allowed (creating a link and redirects are; managing links isn't).</li>
 *   <li>A header with an unknown, revoked or malformed key: 401 straight away. Falling back to
 *       anonymous instead would hide a client's broken configuration and quietly give it the
 *       much lower anonymous rate limit.</li>
 * </ul>
 *
 * <p>Not a {@code @Component}: Spring Boot would also register it as a plain servlet filter and
 * it would run twice. It is created by SecurityConfig and lives only in the security chain.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final ApiKeyService keys;
    private final ProblemWriter problems;

    public ApiKeyAuthenticationFilter(ApiKeyService keys, ProblemWriter problems) {
        this.keys = keys;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            chain.doFilter(request, response);
            return;
        }
        Optional<ApiKey> key = header.regionMatches(true, 0, BEARER, 0, BEARER.length())
                ? keys.authenticate(header.substring(BEARER.length()).strip())
                : Optional.empty();
        if (key.isEmpty()) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
            problems.write(response, 401, "Invalid API key",
                    "The API key is unknown, revoked or malformed", "INVALID_API_KEY");
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthentication(key.get()));
        // Every log line for this request says which key made it (prefix only, never the secret).
        MDC.put("apiKey", key.get().prefix());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("apiKey");
        }
    }
}
