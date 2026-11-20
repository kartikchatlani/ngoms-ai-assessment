package com.shortener.auth;

import com.shortener.config.SecurityProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Issues, authenticates and revokes API keys (ADR-0008).
 *
 * <p>Keys look like {@code sk_} + 32 base62 characters (about 190 bits from a CSPRNG). Only the
 * SHA-256 of the full key is stored. A slow password hash (bcrypt, Argon2) exists to protect
 * low-entropy human passwords from brute force; for a 190-bit random secret a fast hash is just
 * as safe, and it keeps authentication cheap on every request.
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    static final String KEY_PREFIX = "sk_";
    static final int RANDOM_LENGTH = 32;
    static final int DISPLAY_PREFIX_LENGTH = 9; // "sk_" + 6 characters
    /** Longer than any key we issue, so absurd headers are rejected before hashing. */
    static final int MAX_PRESENTED_LENGTH = 128;
    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final ApiKeyRepository repository;
    private final Clock clock;
    private final SecurityProperties props;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(ApiKeyRepository repository, Clock clock, SecurityProperties props) {
        this.repository = repository;
        this.clock = clock;
        this.props = props;
    }

    /** A newly issued key. {@code secret} is shown once, in the create response, and never again. */
    public record IssuedKey(ApiKey key, String secret) {
    }

    public IssuedKey issue(String name, boolean admin) {
        String secret = newSecret();
        ApiKey key = repository.insert(name, hash(secret), displayPrefix(secret), admin, clock.instant());
        log.info("API key issued id={} name={} prefix={} admin={}", key.id(), name, key.prefix(), admin);
        return new IssuedKey(key, secret);
    }

    /** @return the active key for this secret, or empty if unknown, revoked or malformed */
    public Optional<ApiKey> authenticate(String presented) {
        if (presented == null || presented.isBlank() || presented.length() > MAX_PRESENTED_LENGTH) {
            return Optional.empty();
        }
        return repository.findActiveByHash(hash(presented));
    }

    public List<ApiKey> list() {
        return repository.findAll();
    }

    /** Idempotent: revoking an already-revoked key succeeds; an unknown id is a 404. */
    public void revoke(long id) {
        if (!repository.revoke(id, clock.instant()) && repository.findById(id).isEmpty()) {
            throw new ApiKeyNotFoundException(id);
        }
        log.info("API key revoked id={}", id);
    }

    public ApiKey get(long id) {
        return repository.findById(id).orElseThrow(() -> new ApiKeyNotFoundException(id));
    }

    /**
     * Makes sure the configured bootstrap admin key exists. Runs once the app is ready, after
     * Flyway has created the table. The key itself comes from the environment and is never stored
     * or logged, only its hash and display prefix.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureBootstrapAdmin() {
        String adminKey = props.adminKey();
        if (adminKey == null || adminKey.isBlank()) {
            log.warn("No APP_ADMIN_KEY configured: admin endpoints are unreachable until one is set");
            return;
        }
        if (adminKey.length() < 20 || adminKey.length() > MAX_PRESENTED_LENGTH) {
            throw new IllegalStateException("APP_ADMIN_KEY must be 20 to " + MAX_PRESENTED_LENGTH + " characters");
        }
        String hash = hash(adminKey);
        repository.insertIfHashAbsent(BOOTSTRAP_NAME, hash, displayPrefix(adminKey), true, clock.instant());
        // Rotating APP_ADMIN_KEY must actually retire the old key, not leave two admins.
        int retired = repository.revokeByNameExceptHash(BOOTSTRAP_NAME, hash, clock.instant());
        if (retired > 0) {
            log.info("Revoked {} previous bootstrap admin key(s) after APP_ADMIN_KEY rotation", retired);
        }
    }

    static final String BOOTSTRAP_NAME = "bootstrap-admin";

    // ---- helpers ---------------------------------------------------------------------------

    private String newSecret() {
        StringBuilder sb = new StringBuilder(KEY_PREFIX);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    static String displayPrefix(String secret) {
        return secret.substring(0, Math.min(DISPLAY_PREFIX_LENGTH, secret.length()));
    }

    static String hash(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
