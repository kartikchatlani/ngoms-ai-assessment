package com.shortener.config;

import com.shortener.auth.ApiKeyService;
import com.shortener.web.ApiKeyAuthenticationFilter;
import com.shortener.web.ProblemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Who may call what (ADR-0008). Stateless API-key authentication; no sessions, no cookies, so
 * CSRF protection (which defends cookie-authenticated browsers) does not apply.
 *
 * <pre>
 *   GET|HEAD /{code}                  anyone            the redirect: "normal people" (Q3)
 *   POST     /api/v1/links            anyone            anonymous creation stays, rate-limited (Q1)
 *   /api/v1/links/** (everything else) any valid key   ownership checked in LinkService (404 if not yours)
 *   /api/v1/admin/**                  admin key         403 for other keys
 *   /actuator/health/**, /info        anyone            load balancers and orchestrators
 *   /actuator/** (metrics)            admin key         metrics reveal traffic and abuse patterns
 *   anything else                     denied
 * </pre>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, ApiKeyService keys, ProblemWriter problems) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                // Spring Security's default Cache-Control would override the redirect's own
                // "no-store" decision (ADR-0004); every response here sets what it needs.
                .headers(headers -> headers.cacheControl(cache -> cache.disable()))
                .addFilterBefore(new ApiKeyAuthenticationFilter(keys, problems), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/links").permitAll()
                        .requestMatchers("/api/v1/links", "/api/v1/links/**").hasRole("CLIENT")
                        .requestMatchers(HttpMethod.GET, "/{code:[A-Za-z0-9_-]{3,32}}").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/{code:[A-Za-z0-9_-]{3,32}}").permitAll()
                        // Spring Boot forwards errors to /error; it must render, not be re-secured.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) -> {
                            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                            problems.write(response, 401, "Authentication required",
                                    "Send an API key as 'Authorization: Bearer <key>'", "AUTH_REQUIRED");
                        })
                        .accessDeniedHandler((request, response, ex) -> problems.write(response, 403,
                                "Forbidden", "This API key is not allowed to do that", "FORBIDDEN")));
        return http.build();
    }
}
