package com.shortener.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.shortener.config.SecurityProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** The X-Forwarded-For rules of ADR-0009 (scenario 3, Q8). */
class ClientIpResolverTest {

    @Test
    void ignoresForwardedForByDefaultSoClientsCantSpoofTheirIp() {
        var resolver = new ClientIpResolver(new SecurityProperties("", List.of()));

        assertThat(resolver.resolve(request("203.0.113.7", "1.2.3.4"))).isEqualTo("203.0.113.7");
    }

    @Test
    void ignoresForwardedForFromAnUntrustedSource() {
        var resolver = new ClientIpResolver(new SecurityProperties("", List.of("10.0.0.2")));

        assertThat(resolver.resolve(request("203.0.113.7", "1.2.3.4"))).isEqualTo("203.0.113.7");
    }

    @Test
    void behindATrustedProxyUsesTheRightMostUntrustedHop() {
        var resolver = new ClientIpResolver(new SecurityProperties("", List.of("10.0.0.2", "10.0.0.3")));

        // The client wrote "6.6.6.6" itself; our proxies appended the real client and themselves.
        assertThat(resolver.resolve(request("10.0.0.2", "6.6.6.6, 198.51.100.4, 10.0.0.3"))).isEqualTo("198.51.100.4");
    }

    @Test
    void fallsBackToTheSocketAddressWhenEveryHopIsOurs() {
        var resolver = new ClientIpResolver(new SecurityProperties("", List.of("10.0.0.2")));

        assertThat(resolver.resolve(request("10.0.0.2", "10.0.0.2"))).isEqualTo("10.0.0.2");
        assertThat(resolver.resolve(request("10.0.0.2", null))).isEqualTo("10.0.0.2");
    }

    private static MockHttpServletRequest request(String remoteAddr, String forwardedFor) {
        var r = new MockHttpServletRequest();
        r.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            r.addHeader("X-Forwarded-For", forwardedFor);
        }
        return r;
    }
}
