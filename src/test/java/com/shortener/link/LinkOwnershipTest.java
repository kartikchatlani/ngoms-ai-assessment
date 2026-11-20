package com.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shortener.abuse.UrlNotAllowedException;
import com.shortener.abuse.UrlScreener;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Phase 3 rules in LinkService (ADR-0008, ADR-0010): who can manage which link, and screening
 * of destinations on create and on redirect.
 */
class LinkOwnershipTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final Caller MARKETING = Caller.key(1, false);
    private static final Caller OTHER = Caller.key(2, false);
    private static final Caller ADMIN = Caller.key(3, true);

    private final LinkRepository repository = mock(LinkRepository.class);
    private final CodeGenerator generator = mock(CodeGenerator.class);
    private final LinkService service = service(TestFixtures.screener("evil.example"));

    // ---- ownership -------------------------------------------------------------------------

    @Test
    void createRecordsTheCallersKeyAsOwner() {
        when(generator.generate()).thenReturn("Ab3xY9z");
        when(repository.insertIfCodeAbsent(any())).thenAnswer(inv -> Optional.of(stored(inv.getArgument(0))));

        service.create(new LinkService.CreateCommand("https://example.com", null, null), MARKETING);

        ArgumentCaptor<NewLink> captor = ArgumentCaptor.forClass(NewLink.class);
        verify(repository).insertIfCodeAbsent(captor.capture());
        assertThat(captor.getValue().ownerKeyId()).isEqualTo(1L);
    }

    @Test
    void anonymousLinksHaveNoOwner() {
        when(generator.generate()).thenReturn("Ab3xY9z");
        when(repository.insertIfCodeAbsent(any())).thenAnswer(inv -> Optional.of(stored(inv.getArgument(0))));

        service.create(new LinkService.CreateCommand("https://example.com", null, null), Caller.ANONYMOUS);

        ArgumentCaptor<NewLink> captor = ArgumentCaptor.forClass(NewLink.class);
        verify(repository).insertIfCodeAbsent(captor.capture());
        assertThat(captor.getValue().ownerKeyId()).isNull();
    }

    @Test
    void ownerAndAdminCanManageOthersCannot() {
        when(repository.findByCode("promo01")).thenReturn(Optional.of(owned("promo01", 1L)));

        assertThat(service.get("promo01", MARKETING).code()).isEqualTo("promo01");
        assertThat(service.get("promo01", ADMIN).code()).isEqualTo("promo01");
        assertThatThrownBy(() -> service.get("promo01", OTHER)).isInstanceOf(LinkNotFoundException.class);
        assertThatThrownBy(() -> service.get("promo01", Caller.ANONYMOUS)).isInstanceOf(LinkNotFoundException.class);
    }

    @Test
    void someoneElsesLinkIsIndistinguishableFromAMissingOne() {
        when(repository.findByCode("promo01")).thenReturn(Optional.of(owned("promo01", 1L)));
        when(repository.findByCode("missing")).thenReturn(Optional.empty());

        // Same exception type and message shape: nothing reveals that promo01 exists and is owned.
        assertThatThrownBy(() -> service.get("promo01", OTHER))
                .isExactlyInstanceOf(LinkNotFoundException.class).hasMessage(new LinkNotFoundException("promo01").getMessage());
        assertThatThrownBy(() -> service.get("missing", OTHER))
                .isExactlyInstanceOf(LinkNotFoundException.class).hasMessage(new LinkNotFoundException("missing").getMessage());
    }

    @Test
    void anotherKeyCannotDeleteTheLink_symptom3() {
        when(repository.findByCode("promo01")).thenReturn(Optional.of(owned("promo01", 1L)));

        assertThatThrownBy(() -> service.delete("promo01", OTHER)).isInstanceOf(LinkNotFoundException.class);
        verify(repository, never()).softDelete(any(), any(), any());
    }

    @Test
    void deleteRecordsWhoDeletedIt() {
        when(repository.findByCode("promo01")).thenReturn(Optional.of(owned("promo01", 1L)));
        when(repository.softDelete(eq("promo01"), any(), any())).thenReturn(true);

        service.delete("promo01", MARKETING);

        verify(repository).softDelete("promo01", NOW, 1L);
    }

    @Test
    void ownerlessLinksAreAdminOnly() {
        // Anonymous links, and every link created before Phase 3 (scenario 3, Q7).
        when(repository.findByCode("legacy1")).thenReturn(Optional.of(owned("legacy1", null)));

        assertThatThrownBy(() -> service.get("legacy1", MARKETING)).isInstanceOf(LinkNotFoundException.class);
        assertThatCode(() -> service.get("legacy1", ADMIN)).doesNotThrowAnyException();
    }

    @Test
    void listIsScopedToTheCallersKeyAndAdminSeesAll() {
        when(repository.findLivePage(any(), anyLong(), anyInt())).thenReturn(List.of());

        service.list(null, 10, MARKETING);
        service.list(null, 10, ADMIN);

        verify(repository).findLivePage(1L, Long.MAX_VALUE, 11);
        verify(repository).findLivePage(null, Long.MAX_VALUE, 11);
    }

    @Test
    void anonymousListingIsRefusedEvenIfTheWebLayerWereMisconfigured() {
        assertThatThrownBy(() -> service.list(null, 10, Caller.ANONYMOUS)).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).findLivePage(any(), anyLong(), anyInt());
    }

    // ---- screening -------------------------------------------------------------------------

    @Test
    void blockedDomainsAndTheirSubdomainsAreRefusedOnCreate() {
        for (String url : List.of("https://evil.example/login", "https://secure.login.evil.example/", "https://EVIL.example./x")) {
            assertThatThrownBy(() -> service.create(new LinkService.CreateCommand(url, null, null), MARKETING))
                    .as(url)
                    .isInstanceOfSatisfying(UrlNotAllowedException.class,
                            e -> assertThat(e.verdict()).isEqualTo(UrlScreener.Verdict.BLOCKED_DOMAIN));
        }
        verify(repository, never()).insertIfCodeAbsent(any());
    }

    @Test
    void otherShortenersAreRefusedOnCreate() {
        assertThatThrownBy(() -> service.create(new LinkService.CreateCommand("https://bit.ly/3xyz", null, null), MARKETING))
                .isInstanceOfSatisfying(UrlNotAllowedException.class,
                        e -> assertThat(e.verdict()).isEqualTo(UrlScreener.Verdict.URL_SHORTENER));
    }

    @Test
    void lookalikeDomainsAreNotCaughtByTheSuffixMatch() {
        // "notevil.example" ends with "evil.example" as a string, but is a different domain.
        when(generator.generate()).thenReturn("Ab3xY9z");
        when(repository.insertIfCodeAbsent(any())).thenAnswer(inv -> Optional.of(stored(inv.getArgument(0))));

        assertThatCode(() -> service.create(new LinkService.CreateCommand("https://notevil.example/", null, null), MARKETING))
                .doesNotThrowAnyException();
    }

    @Test
    void existingLinksToANewlyBlockedDomainStopRedirecting() {
        // Created before the domain was blocked: stored state is ACTIVE.
        when(repository.findByCode("phish01")).thenReturn(Optional.of(
                new Link(7, "phish01", "https://login.evil.example/", false, NOW.minusSeconds(60), null, null, 1L, null, null)));

        assertThatThrownBy(() -> service.resolve("phish01"))
                .isInstanceOfSatisfying(LinkGoneException.class, e -> assertThat(e.status()).isEqualTo(LinkStatus.BLOCKED));
        // ...and its owner sees BLOCKED too, not a misleading ACTIVE.
        assertThat(service.statusOf(service.get("phish01", MARKETING))).isEqualTo(LinkStatus.BLOCKED);
    }

    @Test
    void takenDownLinkIsGoneAndStatusSaysBlocked() {
        when(repository.findByCode("down001")).thenReturn(Optional.of(
                new Link(8, "down001", "https://example.com/", false, NOW.minusSeconds(60), null, null, 1L, NOW, "phishing")));

        assertThatThrownBy(() -> service.resolve("down001"))
                .isInstanceOfSatisfying(LinkGoneException.class, e -> assertThat(e.status()).isEqualTo(LinkStatus.BLOCKED));
    }

    @Test
    void adminOperationsOnUnknownLinksAre404() {
        when(repository.block(any(), any(), any())).thenReturn(false);
        when(repository.unblock(any())).thenReturn(false);
        when(repository.assignOwner(any(), anyLong())).thenReturn(false);

        assertThatThrownBy(() -> service.block("nope123", "x")).isInstanceOf(LinkNotFoundException.class);
        assertThatThrownBy(() -> service.unblock("nope123")).isInstanceOf(LinkNotFoundException.class);
        assertThatThrownBy(() -> service.assignOwner("nope123", 1)).isInstanceOf(LinkNotFoundException.class);
    }

    // ---- helpers ---------------------------------------------------------------------------

    private LinkService service(UrlScreener screener) {
        return new LinkService(repository, generator, new UrlPolicy(TestFixtures.props()), new AliasPolicy(),
                screener, Clock.fixed(NOW, ZoneOffset.UTC), TestFixtures.props());
    }

    private static Link stored(NewLink n) {
        return new Link(1, n.code(), n.longUrl(), n.customAlias(), n.createdAt(), n.expiresAt(), null, n.ownerKeyId(), null, null);
    }

    private static Link owned(String code, Long owner) {
        return new Link(5, code, "https://example.com", true, NOW.minusSeconds(3600), null, null, owner, null, null);
    }
}
