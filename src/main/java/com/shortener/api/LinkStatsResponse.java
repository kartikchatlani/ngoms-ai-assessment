package com.shortener.api;

import com.shortener.analytics.AgentClass;
import com.shortener.analytics.LinkStats;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Public representation of a link's click statistics. Dates are UTC days. */
public record LinkStatsResponse(
        String code,
        long totalClicks,
        Instant lastClickedAt,
        Window window,
        List<Day> daily,
        List<Referrer> topReferrers,
        long withoutReferrer,
        Map<AgentClass, Long> agents) {

    public record Window(LocalDate from, LocalDate to, long clicks) {
    }

    public record Day(LocalDate date, long clicks) {
    }

    public record Referrer(String host, long clicks) {
    }

    static LinkStatsResponse from(LinkStats s) {
        return new LinkStatsResponse(
                s.code(),
                s.totalClicks(),
                s.lastClickedAt(),
                new Window(s.from(), s.to(), s.windowClicks()),
                s.daily().stream().map(d -> new Day(d.date(), d.clicks())).toList(),
                s.topReferrers().stream().map(r -> new Referrer(r.host(), r.clicks())).toList(),
                s.withoutReferrer(),
                s.agents());
    }
}
