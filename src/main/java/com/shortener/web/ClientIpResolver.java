package com.shortener.web;

import com.shortener.config.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Works out the client's IP address for per-IP rate limiting (ADR-0009).
 *
 * <p>{@code X-Forwarded-For} is set by the client unless a proxy overwrites it, so trusting it
 * blindly lets anyone dodge the limit by sending a new fake IP each time. It is only believed
 * when the request comes from a configured trusted proxy, and then we take the right-most entry
 * that isn't one of our proxies: everything to the left of that was written by the client.
 */
@Component
public class ClientIpResolver {

    private final Set<String> trustedProxies;

    public ClientIpResolver(SecurityProperties props) {
        this.trustedProxies = props.trustedProxies().stream().map(String::strip)
                .filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank() || !trustedProxies.contains(remote)) {
            return remote;
        }
        String[] hops = forwarded.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].strip();
            if (!hop.isEmpty() && !trustedProxies.contains(hop)) {
                return hop;
            }
        }
        return remote; // every hop is one of our proxies
    }
}
