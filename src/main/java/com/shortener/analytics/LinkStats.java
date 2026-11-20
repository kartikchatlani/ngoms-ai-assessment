package com.shortener.analytics;

import com.shortener.analytics.ClickRepository.DailyCount;
import com.shortener.analytics.ClickRepository.ReferrerCount;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Click statistics for one link.
 *
 * @param totalClicks   all-time count
 * @param lastClickedAt null if never clicked
 * @param from          first UTC day of the window (inclusive)
 * @param to            last UTC day of the window (inclusive, today)
 * @param windowClicks  clicks within the window
 * @param daily         one entry per day in the window, oldest first, zero-filled
 */
public record LinkStats(
        String code,
        long totalClicks,
        Instant lastClickedAt,
        LocalDate from,
        LocalDate to,
        long windowClicks,
        List<DailyCount> daily,
        List<ReferrerCount> topReferrers,
        long withoutReferrer,
        Map<AgentClass, Long> agents) {
}
