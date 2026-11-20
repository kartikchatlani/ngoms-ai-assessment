package com.shortener.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shortener.config.SecurityProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ApiKeyServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private final ApiKeyRepository repo = mock(ApiKeyRepository.class);

    @Test
    void issuedKeysAreRandomPrefixedAndOnlyTheirHashIsStored() {
        ApiKeyService service = service("");
        when(repo.insert(anyString(), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> new ApiKey(1, inv.getArgument(0), inv.getArgument(2), inv.getArgument(3), NOW, null));

        Set<String> secrets = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            secrets.add(service.issue("marketing", false).secret());
        }
        assertThat(secrets).hasSize(50).allMatch(s -> s.matches("sk_[0-9A-Za-z]{32}"));

        String secret = service.issue("marketing", false).secret();
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> prefix = ArgumentCaptor.forClass(String.class);
        verify(repo, org.mockito.Mockito.atLeastOnce()).insert(eq("marketing"), hash.capture(), prefix.capture(), eq(false), eq(NOW));
        assertThat(hash.getValue()).isEqualTo(ApiKeyService.hash(secret)).hasSize(64).doesNotContain(secret);
        assertThat(prefix.getValue()).isEqualTo(secret.substring(0, 9));
    }

    @Test
    void authenticatesByHash() {
        ApiKeyService service = service("");
        ApiKey key = new ApiKey(7, "marketing", "sk_abcdef", false, NOW, null);
        when(repo.findActiveByHash(ApiKeyService.hash("sk_the_real_secret_value"))).thenReturn(Optional.of(key));

        assertThat(service.authenticate("sk_the_real_secret_value")).contains(key);
        assertThat(service.authenticate("sk_a_wrong_secret_value")).isEmpty();
    }

    @Test
    void malformedKeysAreRejectedWithoutTouchingTheDatabase() {
        ApiKeyService service = service("");

        assertThat(service.authenticate(null)).isEmpty();
        assertThat(service.authenticate("  ")).isEmpty();
        assertThat(service.authenticate("x".repeat(129))).isEmpty();
        verifyNoInteractions(repo);
    }

    @Test
    void hashIsStableSha256() {
        // Known SHA-256 test vector, so the stored format can't change silently.
        assertThat(ApiKeyService.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void bootstrapAdminIsCreatedFromConfigAndRotationRevokesTheOldOne() {
        ApiKeyService service = service("sk_local_dev_admin_change_me");

        service.ensureBootstrapAdmin();

        String hash = ApiKeyService.hash("sk_local_dev_admin_change_me");
        verify(repo).insertIfHashAbsent("bootstrap-admin", hash, "sk_local_", true, NOW);
        verify(repo).revokeByNameExceptHash("bootstrap-admin", hash, NOW);
    }

    @Test
    void noBootstrapAdminWhenNotConfigured() {
        service("").ensureBootstrapAdmin();
        verify(repo, never()).insertIfHashAbsent(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void tooShortBootstrapKeyFailsStartup() {
        assertThatThrownBy(() -> service("short").ensureBootstrapAdmin()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void revokeIsIdempotentButUnknownIdsAre404() {
        ApiKeyService service = service("");
        when(repo.revoke(eq(1L), any())).thenReturn(false);
        when(repo.findById(1L)).thenReturn(Optional.of(new ApiKey(1, "old", "sk_abcdef", false, NOW, NOW)));
        when(repo.revoke(eq(2L), any())).thenReturn(false);
        when(repo.findById(2L)).thenReturn(Optional.empty());

        service.revoke(1);
        assertThatThrownBy(() -> service.revoke(2)).isInstanceOf(ApiKeyNotFoundException.class);
    }

    private ApiKeyService service(String adminKey) {
        return new ApiKeyService(repo, Clock.fixed(NOW, ZoneOffset.UTC), new SecurityProperties(adminKey, List.of()));
    }
}
