package com.shortener.analytics;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Data access for click events: batched writes, aggregate reads (ADR-0006, ADR-0007). */
@Repository
public class ClickRepository {

    private static final int[] INSERT_TYPES = {Types.BIGINT, Types.TIMESTAMP_WITH_TIMEZONE, Types.VARCHAR, Types.VARCHAR};

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public ClickRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Totals(long clicks, Instant lastClickedAt) {
    }

    public record DailyCount(LocalDate date, long clicks) {
    }

    public record ReferrerCount(String host, long clicks) {
    }

    /** Clicks in a window, broken down by client type, plus how many had no usable referrer. */
    public record WindowSummary(long clicks, long withoutReferrer, Map<AgentClass, Long> agents) {
    }

    /** One JDBC batch (one round trip) for the whole list; JdbcClient has no batch API. */
    public void insertBatch(List<ClickEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO click_events (link_id, clicked_at, referrer_host, agent_class) VALUES (?, ?, ?, ?)",
                events.stream().map(e -> new Object[] {
                        e.linkId(), e.clickedAt().atOffset(ZoneOffset.UTC), e.referrerHost(), e.agentClass().name()})
                        .toList(),
                INSERT_TYPES);
    }

    /** All-time totals for one link. */
    public Totals totals(long linkId) {
        return jdbc.sql("SELECT count(*) AS clicks, max(clicked_at) AS last_clicked FROM click_events WHERE link_id = :id")
                .param("id", linkId)
                .query((rs, n) -> new Totals(rs.getLong("clicks"), instant(rs, "last_clicked")))
                .single();
    }

    /** Clicks per UTC day in [from, to); days with no clicks are absent (the caller zero-fills). */
    public List<DailyCount> daily(long linkId, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT (clicked_at AT TIME ZONE 'UTC')::date AS day, count(*) AS clicks
                        FROM click_events
                        WHERE link_id = :id AND clicked_at >= :from AND clicked_at < :to
                        GROUP BY day
                        ORDER BY day
                        """)
                .param("id", linkId)
                .param("from", odt(from), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("to", odt(to), Types.TIMESTAMP_WITH_TIMEZONE)
                .query((rs, n) -> new DailyCount(rs.getObject("day", LocalDate.class), rs.getLong("clicks")))
                .list();
    }

    /** Most common referrer hosts in [from, to), most clicks first; ties broken by host for stable output. */
    public List<ReferrerCount> topReferrers(long linkId, Instant from, Instant to, int limit) {
        return jdbc.sql("""
                        SELECT referrer_host, count(*) AS clicks
                        FROM click_events
                        WHERE link_id = :id AND clicked_at >= :from AND clicked_at < :to
                          AND referrer_host IS NOT NULL
                        GROUP BY referrer_host
                        ORDER BY clicks DESC, referrer_host
                        LIMIT :limit
                        """)
                .param("id", linkId)
                .param("from", odt(from), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("to", odt(to), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("limit", limit)
                .query((rs, n) -> new ReferrerCount(rs.getString("referrer_host"), rs.getLong("clicks")))
                .list();
    }

    /** One pass over the window instead of a query per breakdown. */
    public WindowSummary windowSummary(long linkId, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT count(*) AS clicks,
                               count(*) FILTER (WHERE referrer_host IS NULL)   AS without_referrer,
                               count(*) FILTER (WHERE agent_class = 'DESKTOP') AS desktop,
                               count(*) FILTER (WHERE agent_class = 'MOBILE')  AS mobile,
                               count(*) FILTER (WHERE agent_class = 'BOT')     AS bot,
                               count(*) FILTER (WHERE agent_class = 'UNKNOWN') AS unknown
                        FROM click_events
                        WHERE link_id = :id AND clicked_at >= :from AND clicked_at < :to
                        """)
                .param("id", linkId)
                .param("from", odt(from), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("to", odt(to), Types.TIMESTAMP_WITH_TIMEZONE)
                .query((rs, n) -> {
                    Map<AgentClass, Long> agents = new EnumMap<>(AgentClass.class);
                    agents.put(AgentClass.DESKTOP, rs.getLong("desktop"));
                    agents.put(AgentClass.MOBILE, rs.getLong("mobile"));
                    agents.put(AgentClass.BOT, rs.getLong("bot"));
                    agents.put(AgentClass.UNKNOWN, rs.getLong("unknown"));
                    return new WindowSummary(rs.getLong("clicks"), rs.getLong("without_referrer"), agents);
                })
                .single();
    }

    private static OffsetDateTime odt(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
