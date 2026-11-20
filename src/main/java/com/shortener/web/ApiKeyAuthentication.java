package com.shortener.web;

import com.shortener.auth.ApiKey;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** A request authenticated by a valid API key. The principal is the {@link ApiKey}; there are no credentials kept. */
public class ApiKeyAuthentication extends AbstractAuthenticationToken {

    private final ApiKey key;

    public ApiKeyAuthentication(ApiKey key) {
        super(key.admin()
                ? List.of(new SimpleGrantedAuthority("ROLE_CLIENT"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.of(new SimpleGrantedAuthority("ROLE_CLIENT")));
        this.key = key;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null; // the secret is never kept in memory beyond the lookup
    }

    @Override
    public ApiKey getPrincipal() {
        return key;
    }
}
