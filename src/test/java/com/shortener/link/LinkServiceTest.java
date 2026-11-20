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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

    private final LinkRepository repository = mock(LinkRepository.class);
    private final CodeGenerator generator = mock(CodeGenerator.class);
    private final LinkService service = new LinkService(repository, generator,
            new UrlPolicy(TestFixtures.props()), new AliasPolicy(), TestFixtures.screener(),
            Clock.fixed(NOW, ZoneOffset.UTC), TestFixtures.props());

    // Phase 3 made the caller a required parameter. These Phase 1 tests are about link rules,
    // not permissions, so they run as admin (no restrictions, as in Phase 1) or anonymous for
    // create. Ownership has its own tests in LinkOwnershipTest.
    private static final Caller ADMIN = Caller.key(99, true);

    // ---- create ---------------------------------------------------------------------------

    @Test
    void createsLinkWithGeneratedCode() {
        when(generator.generate()).thenReturn("Ab3xY9z");
        when(repository.insertIfCodeAbsent(any())).thenAnswer(inv -> Optional.of(stored(inv.getArgument(0))));

        Link link = service.create(new LinkService.CreateCommand("https://example.com", null, null), Caller.ANONYMOUS);

        assertThat(link.code()).isEqualTo("Ab3xY9z");
        assertThat(link.customAlias()).isFalse();
        assertThat(link.createdAt()).isEqualTo(NOW);
    }

    @Test
    void retriesOnCollisionUntilAFreeCodeIsFound() {
        when(generator.generate()).thenReturn("taken01", "taken02", "free003");
        when(repository.insertIfCodeAbsent(any()))
                .thenReturn(Optional.empty(), Optional.empty())
                .thenAnswer(inv -> Optional.of(stored(inv.getArgument(0))));

        Link link = service.create(new LinkService.CreateCommand("https://example.com", null, null), Caller.ANONYMOUS);

        assertThat(link.code()).isEqualTo("free003");
        verify(repository, times(3)).insertIfCodeAbsent(any());
    }

    @Test
    void givesUpAfterMaxAttempts() {
        when(generator.generate()).thenReturn("taken01");
        when(repository.insertIfCodeAbsent(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(new LinkService.CreateCommand("https://example.com", null, null), Caller.ANONYMOUS))
                .isInstanceOf(CodeSpaceExhaustedException.class);
        verify(repository, times(5)).insertIfCodeAbsent(any());
    }

    @Test
    void neverIssuesAReservedWordAsAGeneratedCode() {
        when(generator.generate()).thenReturn("favicon", "Ok12345");
        when(repository.insertIfCodeAbsent(any())).thenAnswer(inv -> Optional.of(stored(inv.getArgument(0))));

        Link link = service.create(new LinkService.CreateCommand("https://example.com", null, null), Caller.ANONYMOUS);

        assertThat(link.code()).isEqualTo("Ok12345");
        verify(repository, times(1)).insertIfCodeAbsent(any());
    }

    @Test
    void createsWithCustomAliasWithoutCallingGenerator() {
        when(repository.insertIfCodeAbsent(any())).thenAnswer(inv -> Optional.of(stored(inv.getArgument(0))));

        Link link = service.create(new LinkService.CreateCommand("https://example.com", "my-promo", null), Caller.ANONYMOUS);

        assertThat(link.code()).isEqualTo("my-promo");
        assertThat(link.customAlias()).isTrue();
        verify(generator, never()).generate();
    }

    @Test
    void customAliasConflictIsReportedNotRetried() {
        when(repository.insertIfCodeAbsent(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(new LinkService.CreateCommand("https://example.com", "my-promo", null), Caller.ANONYMOUS))
                .isInstanceOf(AliasTakenException.class);
        verify(repository, times(1)).insertIfCodeAbsent(any());
    }

    @Test
    void rejectsExpiryInThePastOrNow() {
        assertThatThrownBy(() -> service.create(new LinkService.CreateCommand("https://example.com", null, NOW), Caller.ANONYMOUS))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("expiresAt");
        verify(repository, never()).insertIfCodeAbsent(any());
    }

    @Test
    void rejectsExpiryBeyondStorableRange_regressionBug001() {
        Instant tooFar = Instant.parse("+100000-01-01T00:00:00Z");
        assertThatThrownBy(() -> service.create(new LinkService.CreateCommand("https://example.com", null, tooFar), Caller.ANONYMOUS))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("expiresAt");
        verify(repository, never()).insertIfCodeAbsent(any());
    }

    @Test
    void acceptsExpiryAtTheUpperBound() {
        when(generator.generate()).thenReturn("Ab3xY9z");
        when(repository.insertIfCodeAbsent(any())).thenAnswer(inv -> Optional.of(stored(inv.getArgument(0))));

        assertThatCode(() -> service.create(
                new LinkService.CreateCommand("https://example.com", null, LinkService.MAX_EXPIRY), Caller.ANONYMOUS))
                .doesNotThrowAnyException();
    }

    @Test
    void passesExpiryThrough() {
        Instant expiry = NOW.plusSeconds(3600);
        when(generator.generate()).thenReturn("Ab3xY9z");
        when(repository.insertIfCodeAbsent(any())).thenAnswer(inv -> Optional.of(stored(inv.getArgument(0))));

        service.create(new LinkService.CreateCommand("https://example.com", null, expiry), Caller.ANONYMOUS);

        ArgumentCaptor<NewLink> captor = ArgumentCaptor.forClass(NewLink.class);
        verify(repository).insertIfCodeAbsent(captor.capture());
        assertThat(captor.getValue().expiresAt()).isEqualTo(expiry);
    }

    @Test
    void invalidUrlNeverReachesTheDatabase() {
        assertThatThrownBy(() -> service.create(new LinkService.CreateCommand("javascript:alert(1)", null, null), Caller.ANONYMOUS))
                .isInstanceOf(InvalidRequestException.class);
        verify(repository, never()).insertIfCodeAbsent(any());
    }

    // ---- resolve / get -------------------------------------------------------------------

    @Test
    void resolvesActiveLink() {
        when(repository.findByCode("abc1234")).thenReturn(Optional.of(link("abc1234", null, null)));
        assertThat(service.resolve("abc1234").longUrl()).isEqualTo("https://example.com");
    }

    @Test
    void resolveUnknownIsNotFound() {
        when(repository.findByCode("nope123")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolve("nope123")).isInstanceOf(LinkNotFoundException.class);
    }

    @Test
    void resolveExpiredIsGone() {
        when(repository.findByCode("abc1234")).thenReturn(Optional.of(link("abc1234", NOW.minusSeconds(1), null)));
        assertThatThrownBy(() -> service.resolve("abc1234"))
                .isInstanceOfSatisfying(LinkGoneException.class, e -> assertThat(e.status()).isEqualTo(LinkStatus.EXPIRED));
    }

    @Test
    void resolveDeletedIsGone() {
        when(repository.findByCode("abc1234")).thenReturn(Optional.of(link("abc1234", null, NOW.minusSeconds(1))));
        assertThatThrownBy(() -> service.resolve("abc1234"))
                .isInstanceOfSatisfying(LinkGoneException.class, e -> assertThat(e.status()).isEqualTo(LinkStatus.DELETED));
    }

    @Test
    void getShowsExpiredButHidesDeleted() {
        when(repository.findByCode("expired")).thenReturn(Optional.of(link("expired", NOW.minusSeconds(1), null)));
        when(repository.findByCode("deleted")).thenReturn(Optional.of(link("deleted", null, NOW.minusSeconds(1))));

        assertThat(service.get("expired", ADMIN).code()).isEqualTo("expired");
        assertThatThrownBy(() -> service.get("deleted", ADMIN)).isInstanceOf(LinkNotFoundException.class);
    }

    // ---- list ----------------------------------------------------------------------------

    @Test
    void listReturnsCursorWhenMorePagesExist() {
        // Repository returns limit+1 rows (ids 10..8) -> page of 2 plus a cursor pointing below id 9.
        when(repository.findLivePage(null, Long.MAX_VALUE, 3)).thenReturn(links(10, 9, 8));

        LinkService.Page page = service.list(null, 2, ADMIN);

        assertThat(page.items()).extracting(Link::id).containsExactly(10L, 9L);
        assertThat(page.nextCursor()).isNotNull();
        assertThat(LinkService.decodeCursor(page.nextCursor())).isEqualTo(9L);
    }

    @Test
    void listLastPageHasNoCursor() {
        when(repository.findLivePage(null, 9L, 3)).thenReturn(links(8, 7));

        LinkService.Page page = service.list(LinkService.encodeCursor(9), 2, ADMIN);

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void listValidatesLimitAndCursor() {
        assertThatThrownBy(() -> service.list(null, 0, ADMIN)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.list(null, 101, ADMIN)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.list("not-base64!!", 10, ADMIN)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.list(LinkService.encodeCursor(-5), 10, ADMIN)).isInstanceOf(InvalidRequestException.class);
        verify(repository, never()).findLivePage(any(), anyLong(), anyInt());
    }

    // ---- delete --------------------------------------------------------------------------

    @Test
    void deleteIsIdempotent() {
        when(repository.softDelete(eq("abc1234"), any(), any())).thenReturn(false);
        when(repository.findByCode("abc1234")).thenReturn(Optional.of(link("abc1234", null, NOW)));

        assertThatCode(() -> service.delete("abc1234", ADMIN)).doesNotThrowAnyException();
    }

    @Test
    void deleteUnknownIsNotFound() {
                when(repository.findByCode("nope123")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("nope123", ADMIN)).isInstanceOf(LinkNotFoundException.class);
    }

    // ---- helpers -------------------------------------------------------------------------

    private static Link stored(NewLink n) {
        return new Link(1, n.code(), n.longUrl(), n.customAlias(), n.createdAt(), n.expiresAt(), null);
    }

    private static Link link(String code, Instant expiresAt, Instant deletedAt) {
        return new Link(1, code, "https://example.com", false, NOW.minusSeconds(3600), expiresAt, deletedAt);
    }

    private static List<Link> links(long... ids) {
        return LongStream.of(ids)
                .mapToObj(id -> new Link(id, "code" + id + "xx", "https://example.com", false, NOW, null, null))
                .toList();
    }
}
