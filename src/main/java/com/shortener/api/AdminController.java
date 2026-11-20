package com.shortener.api;

import com.shortener.abuse.BlockedDomain;
import com.shortener.abuse.UrlScreener;
import com.shortener.auth.ApiKey;
import com.shortener.auth.ApiKeyService;
import com.shortener.link.LinkService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only operations (SecurityConfig: {@code /api/v1/admin/**} needs an admin key).
 * Keys (ADR-0008), takedown and the domain blocklist (ADR-0010), and link reassignment.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final ApiKeyService keys;
    private final LinkService links;
    private final UrlScreener screener;

    public AdminController(ApiKeyService keys, LinkService links, UrlScreener screener) {
        this.keys = keys;
        this.links = links;
        this.screener = screener;
    }

    // ---- API keys ----------------------------------------------------------------------------

    public record CreateKeyRequest(
            // "bootstrap-admin" is reserved: startup revokes other keys with that name (ApiKeyService).
            @NotBlank @Size(max = 100) @Pattern(regexp = "(?i)(?!bootstrap-admin$).*", message = "is reserved")
            String name,
            // Boxed: omitting "admin" means a normal client key. A primitive boolean would make
            // Jackson 3 reject the request, since missing primitives are not defaulted to false.
            Boolean admin) {

        boolean isAdmin() {
            return Boolean.TRUE.equals(admin);
        }
    }

    /** Never contains the secret. */
    public record KeyResponse(long id, String name, String prefix, boolean admin, Instant createdAt, Instant revokedAt) {
        static KeyResponse from(ApiKey k) {
            return new KeyResponse(k.id(), k.name(), k.prefix(), k.admin(), k.createdAt(), k.revokedAt());
        }
    }

    /** The only response that ever contains {@code key}: store it now, it can't be shown again. */
    public record IssuedKeyResponse(long id, String name, String prefix, boolean admin, Instant createdAt, String key) {
    }

    @PostMapping("/keys")
    public ResponseEntity<IssuedKeyResponse> createKey(@Valid @RequestBody CreateKeyRequest request) {
        ApiKeyService.IssuedKey issued = keys.issue(request.name().strip(), request.isAdmin());
        ApiKey k = issued.key();
        return ResponseEntity.created(URI.create("/api/v1/admin/keys/" + k.id()))
                .body(new IssuedKeyResponse(k.id(), k.name(), k.prefix(), k.admin(), k.createdAt(), issued.secret()));
    }

    @GetMapping("/keys")
    public List<KeyResponse> listKeys() {
        return keys.list().stream().map(KeyResponse::from).toList();
    }

    @DeleteMapping("/keys/{id}")
    public ResponseEntity<Void> revokeKey(@PathVariable long id) {
        keys.revoke(id);
        return ResponseEntity.noContent().build();
    }

    // ---- links -------------------------------------------------------------------------------

    public record BlockRequest(@NotBlank @Size(max = 200) String reason) {
    }

    public record AssignOwnerRequest(@NotNull Long keyId) {
    }

    @PutMapping("/links/{code}/block")
    public ResponseEntity<Void> blockLink(@PathVariable String code, @Valid @RequestBody BlockRequest request) {
        links.block(code, request.reason());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/links/{code}/block")
    public ResponseEntity<Void> unblockLink(@PathVariable String code) {
        links.unblock(code);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/links/{code}/owner")
    public ResponseEntity<Void> assignOwner(@PathVariable String code, @Valid @RequestBody AssignOwnerRequest request) {
        ApiKey owner = keys.get(request.keyId()); // 404 KEY_NOT_FOUND if it doesn't exist
        links.assignOwner(code, owner.id());
        return ResponseEntity.noContent().build();
    }

    // ---- domain blocklist --------------------------------------------------------------------

    public record BlockDomainRequest(@NotBlank @Size(max = 253) String domain, @Size(max = 200) String reason) {
    }

    @GetMapping("/blocked-domains")
    public List<BlockedDomain> listBlockedDomains() {
        return screener.list();
    }

    /** Takes effect on the next redirect: every existing link to the domain starts returning 410. */
    @PostMapping("/blocked-domains")
    public ResponseEntity<Void> blockDomain(@Valid @RequestBody BlockDomainRequest request,
                                            @AuthenticationPrincipal ApiKey admin) {
        String domain = screener.block(request.domain(), request.reason(), admin.id());
        return ResponseEntity.created(URI.create("/api/v1/admin/blocked-domains/" + domain)).build();
    }

    @DeleteMapping("/blocked-domains/{domain}")
    public ResponseEntity<Void> unblockDomain(@PathVariable String domain) {
        screener.unblock(domain);
        return ResponseEntity.noContent().build();
    }
}
