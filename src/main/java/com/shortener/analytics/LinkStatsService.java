package com.shortener.analytics;

import com.shortener.analytics.ClickRepository.DailyCount;
import com.shortener.analytics.ClickRepository.Totals;
import com.shortener.analytics.ClickRepository.WindowSummary;
import com.shortener.link.InvalidRequestException;
import com.shortener.link.Link;
import com.shortener.link.LinkService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Read side of click analytics (ADR-0007). Aggregates raw events at query time, by UTC day.
 * Stats are eventually consistent: a click shows up once the writer has flushed it, normally
 * within milliseconds.
 */
@Service
public class LinkStatsService {

    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 365;
    static final int TOP_REFERRERS = 10;

    private final LinkService links;
    private final ClickRepository clicks;
    private final Clock clock;

    public LinkStatsService(LinkService links, ClickRepository clicks, Clock clock) {
        this.links = links;
        this.clicks = clicks;
        this.clock = clock;
    }

    /**
     * Same visibility rule as {@link LinkService#get}: expired links have stats, deleted or
     * unknown codes are 404.
     *
     * @param days window length ending today (UTC), 1 to 365; null = 30
     */
    public LinkStats stats(String code, Integer days) {
        int window = days == null ? DEFAULT_DAYS : days;
        if (window < 1 || window > MAX_DAYS) {
            throw new InvalidRequestException("days", "must be between 1 and " + MAX_DAYS);
        }
        Link link = links.get(code);

        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate first = today.minusDays(window - 1L);
        Instant from = first.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        Totals totals = clicks.totals(link.id());
        WindowSummary summary = clicks.windowSummary(link.id(), from, to);
        List<DailyCount> daily = zeroFill(first, today, clicks.daily(link.id(), from, to));

        return new LinkStats(link.code(), totals.clicks(), totals.lastClickedAt(), first, today,
                summary.clicks(), daily, clicks.topReferrers(link.id(), from, to, TOP_REFERRERS),
                summary.withoutReferrer(), summary.agents());
    }

    /** A chart needs every day, including the quiet ones the GROUP BY leaves out. */
    static List<DailyCount> zeroFill(LocalDate first, LocalDate last, List<DailyCount> sparse) {
        Map<LocalDate, Long> byDay = sparse.stream()
                .collect(Collectors.toMap(DailyCount::date, DailyCount::clicks));
        List<DailyCount> out = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            out.add(new DailyCount(d, byDay.getOrDefault(d, 0L)));
        }
        return out;
    }
}
