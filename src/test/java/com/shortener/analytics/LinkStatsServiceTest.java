package com.shortener.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shortener.analytics.ClickRepository.DailyCount;
import com.shortener.analytics.ClickRepository.ReferrerCount;
import com.shortener.analytics.ClickRepository.Totals;
import com.shortener.analytics.ClickRepository.WindowSummary;
import com.shortener.link.Caller;
import com.shortener.link.InvalidRequestException;
import com.shortener.link.Link;
import com.shortener.link.LinkNotFoundException;
import com.shortener.link.LinkService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LinkStatsServiceTest {

    // Late evening UTC: the window must use UTC days, not the machine's local date.
    private static final Instant NOW = Instant.parse("2026-11-19T23:30:00Z");

    private static final Caller OWNER = Caller.key(1, false);

    private final LinkService links = mock(LinkService.class);
    private final ClickRepository clicks = mock(ClickRepository.class);
    private final LinkStatsService service = new LinkStatsService(links, clicks, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(links.get(eq("Ab3xY9z"), eq(OWNER))).thenReturn(new Link(7, "Ab3xY9z", "https://example.com", false, NOW, null, null));
        when(clicks.totals(7)).thenReturn(new Totals(12, Instant.parse("2026-11-19T20:00:00Z")));
        when(clicks.windowSummary(eq(7L), any(), any()))
                .thenReturn(new WindowSummary(5, 2, Map.of(AgentClass.DESKTOP, 3L, AgentClass.MOBILE, 1L,
                        AgentClass.BOT, 1L, AgentClass.UNKNOWN, 0L)));
        when(clicks.topReferrers(eq(7L), any(), any(), anyInt())).thenReturn(List.of(new ReferrerCount("t.co", 3)));
    }

    @Test
    void windowCoversTheLastNUtcDaysIncludingToday() {
        when(clicks.daily(anyLong(), any(), any())).thenReturn(List.of());

        LinkStats stats = service.stats("Ab3xY9z", 7, OWNER);

        assertThat(stats.from()).isEqualTo(LocalDate.parse("2026-11-13"));
        assertThat(stats.to()).isEqualTo(LocalDate.parse("2026-11-19"));
        // Query bounds are [start of first day, start of tomorrow), in UTC.
        verify(clicks).daily(7, Instant.parse("2026-11-13T00:00:00Z"), Instant.parse("2026-11-20T00:00:00Z"));
    }

    @Test
    void dailySeriesIsZeroFilledAndOrdered() {
        when(clicks.daily(anyLong(), any(), any())).thenReturn(List.of(
                new DailyCount(LocalDate.parse("2026-11-17"), 4),
                new DailyCount(LocalDate.parse("2026-11-19"), 1)));

        LinkStats stats = service.stats("Ab3xY9z", 3, OWNER);

        assertThat(stats.daily()).containsExactly(
                new DailyCount(LocalDate.parse("2026-11-17"), 4),
                new DailyCount(LocalDate.parse("2026-11-18"), 0),
                new DailyCount(LocalDate.parse("2026-11-19"), 1));
    }

    @Test
    void combinesTotalsWindowAndBreakdowns() {
        when(clicks.daily(anyLong(), any(), any())).thenReturn(List.of());

        LinkStats stats = service.stats("Ab3xY9z", null, OWNER);

        assertThat(stats.code()).isEqualTo("Ab3xY9z");
        assertThat(stats.totalClicks()).isEqualTo(12);
        assertThat(stats.lastClickedAt()).isEqualTo(Instant.parse("2026-11-19T20:00:00Z"));
        assertThat(stats.windowClicks()).isEqualTo(5);
        assertThat(stats.withoutReferrer()).isEqualTo(2);
        assertThat(stats.topReferrers()).containsExactly(new ReferrerCount("t.co", 3));
        assertThat(stats.agents()).containsEntry(AgentClass.DESKTOP, 3L);
        assertThat(stats.daily()).hasSize(LinkStatsService.DEFAULT_DAYS);
        verify(clicks).topReferrers(eq(7L), any(), any(), eq(LinkStatsService.TOP_REFERRERS));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 366})
    void rejectsWindowOutsideOneToAYear(int days) {
        assertThatThrownBy(() -> service.stats("Ab3xY9z", days, OWNER))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("days must be between 1 and 365");
        verifyNoInteractions(clicks);
    }

    @Test
    void acceptsTheFullYear() {
        when(clicks.daily(anyLong(), any(), any())).thenReturn(List.of());
        assertThat(service.stats("Ab3xY9z", 365, OWNER).daily()).hasSize(365);
    }

    @Test
    void unknownOrDeletedLinkIs404AndSkipsTheAnalyticsQueries() {
        // LinkService.get already hides deleted links; stats reuses that rule.
        when(links.get(eq("gone123"), eq(OWNER))).thenThrow(new LinkNotFoundException("gone123"));

        assertThatThrownBy(() -> service.stats("gone123", 7, OWNER)).isInstanceOf(LinkNotFoundException.class);
        verifyNoInteractions(clicks);
    }

    private static Instant any() {
        return org.mockito.ArgumentMatchers.any(Instant.class);
    }
}
