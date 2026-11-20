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

    private static final String COLUMNS = "id, code, long_url, custom_alias, created_at, expires_at, deleted_at, "
            + "owner_key_id, blocked_at, blocked_reason";

    private static final RowMapper<Link> MAPPER = (rs, rowNum) -> new Link(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("long_url"),
            rs.getBoolean("custom_alias"),
            instant(rs, "created_at"),
            instant(rs, "expires_at"),
            instant(rs, "deleted_at"),
            nullableLong(rs, "owner_key_id"),
            instant(rs, "blocked_at"),
            rs.getString("blocked_reason"));

    private final JdbcClient jdbc;

    public LinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Atomically inserts the link unless its code is already taken (by a live OR deleted link).
     * Uses {@code ON CONFLICT DO NOTHING} so a collision is a normal empty result rather than an
     * exception, and so two concurrent requests for the same alias cannot both succeed.
     * The creating key is recorded both as owner (can change) and as created_by (never changes).
     *
     * @return the stored link, or empty if the code already exists
     */
    public Optional<Link> insertIfCodeAbsent(NewLink link) {
        return jdbc.sql("""
                        INSERT INTO links (code, long_url, custom_alias, created_at, expires_at, owner_key_id, created_by)
                        VALUES (:code, :longUrl, :customAlias, :createdAt, :expiresAt, :owner, :owner)
                        ON CONFLICT (code) DO NOTHING
                        RETURNING %s
                        """.formatted(COLUMNS))
                .param("code", link.code())
                .param("longUrl", link.longUrl())
                .param("customAlias", link.customAlias())
                .param("createdAt", odt(link.createdAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("expiresAt", odt(link.expiresAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("owner", link.ownerKeyId(), Types.BIGINT)
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
     * @param ownerKeyId only this key's links; null = all links (admin)
     * @param beforeId   exclusive upper bound on id; {@link Long#MAX_VALUE} for the first page
     */
    public List<Link> findLivePage(Long ownerKeyId, long beforeId, int limit) {
        // Two statements rather than "(:owner IS NULL OR owner_key_id = :owner)": that form can
        // stop PostgreSQL using the per-owner index once it switches to a generic plan.
        String where = ownerKeyId == null ? "" : "AND owner_key_id = :owner";
        var spec = jdbc.sql("""
                        SELECT %s FROM links
                        WHERE deleted_at IS NULL AND id < :beforeId %s
                        ORDER BY id DESC
                        LIMIT :limit
                        """.formatted(COLUMNS, where))
                .param("beforeId", beforeId)
                .param("limit", limit);
        if (ownerKeyId != null) {
            spec = spec.param("owner", ownerKeyId);
        }
        return spec.query(MAPPER).list();
    }

    /**
     * @param byKeyId recorded as deleted_by, so "who deleted it?" has an answer
     * @return true if a live link was deleted by this call; false if absent or already deleted
     */
    public boolean softDelete(String code, Instant now, Long byKeyId) {
        return jdbc.sql("""
                        UPDATE links SET deleted_at = :now, deleted_by = :by
                        WHERE code = :code AND deleted_at IS NULL""")
                .param("now", odt(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("by", byKeyId, Types.BIGINT)
                .param("code", code)
                .update() == 1;
    }

    /** Takedown. Keeps the first reason if blocked twice. @return false if no such link */
    public boolean block(String code, Instant now, String reason) {
        return jdbc.sql("""
                        UPDATE links SET blocked_at = COALESCE(blocked_at, :now),
                                         blocked_reason = COALESCE(blocked_reason, :reason)
                        WHERE code = :code""")
                .param("now", odt(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("reason", reason)
                .param("code", code)
                .update() == 1;
    }

    /** @return false if no such link */
    public boolean unblock(String code) {
        return jdbc.sql("UPDATE links SET blocked_at = NULL, blocked_reason = NULL WHERE code = :code")
                .param("code", code)
                .update() == 1;
    }

    /** Hands a link to another key; created_by is untouched. @return false if no such link */
    public boolean assignOwner(String code, long ownerKeyId) {
        return jdbc.sql("UPDATE links SET owner_key_id = :owner WHERE code = :code")
                .param("owner", ownerKeyId)
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

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
