package com.shortener.link;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Data access for links, written as explicit SQL (ADR-0002).
 */
@Repository
public class LinkRepository {

    private static final String COLUMNS = "id, code, long_url, custom_alias, created_at, expires_at, deleted_at";

    private static final RowMapper<Link> MAPPER = (rs, rowNum) -> new Link(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("long_url"),
            rs.getBoolean("custom_alias"),
            instant(rs, "created_at"),
            instant(rs, "expires_at"),
            instant(rs, "deleted_at"));

    private final JdbcClient jdbc;

    public LinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Atomically inserts the link unless its code is already taken (by a live OR deleted link).
     * Uses {@code ON CONFLICT DO NOTHING} so a collision is a normal empty result rather than an
     * exception, and so two concurrent requests for the same alias cannot both succeed.
     *
     * @return the stored link, or empty if the code already exists
     */
    public Optional<Link> insertIfCodeAbsent(NewLink link) {
        return jdbc.sql("""
                        INSERT INTO links (code, long_url, custom_alias, created_at, expires_at)
                        VALUES (:code, :longUrl, :customAlias, :createdAt, :expiresAt)
                        ON CONFLICT (code) DO NOTHING
                        RETURNING %s
                        """.formatted(COLUMNS))
                .param("code", link.code())
                .param("longUrl", link.longUrl())
                .param("customAlias", link.customAlias())
                .param("createdAt", odt(link.createdAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("expiresAt", odt(link.expiresAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(MAPPER)
                .optional();
    }

    /** Finds a link by code, including soft-deleted ones (callers decide what deleted means). */
    public Optional<Link> findByCode(String code) {
        return jdbc.sql("SELECT %s FROM links WHERE code = :code".formatted(COLUMNS))
                .param("code", code)
                .query(MAPPER)
                .optional();
    }

    /**
     * Keyset page of live links, newest first.
     *
     * @param beforeId exclusive upper bound on id; {@link Long#MAX_VALUE} for the first page
     */
    public List<Link> findLivePage(long beforeId, int limit) {
        return jdbc.sql("""
                        SELECT %s FROM links
                        WHERE deleted_at IS NULL AND id < :beforeId
                        ORDER BY id DESC
                        LIMIT :limit
                        """.formatted(COLUMNS))
                .param("beforeId", beforeId)
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }

    /** @return true if a live link was deleted by this call; false if absent or already deleted */
    public boolean softDelete(String code, Instant now) {
        return jdbc.sql("UPDATE links SET deleted_at = :now WHERE code = :code AND deleted_at IS NULL")
                .param("now", odt(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("code", code)
                .update() == 1;
    }

    private static OffsetDateTime odt(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
