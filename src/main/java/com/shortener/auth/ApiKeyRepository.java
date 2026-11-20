package com.shortener.auth;

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

/** Data access for API keys. Lookups are by hash only; the raw key never reaches the database. */
@Repository
public class ApiKeyRepository {

    private static final String COLUMNS = "id, name, key_prefix, admin, created_at, revoked_at";

    private static final RowMapper<ApiKey> MAPPER = (rs, n) -> new ApiKey(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("key_prefix"),
            rs.getBoolean("admin"),
            instant(rs, "created_at"),
            instant(rs, "revoked_at"));

    private final JdbcClient jdbc;

    public ApiKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ApiKey insert(String name, String keyHash, String prefix, boolean admin, Instant now) {
        return jdbc.sql("""
                        INSERT INTO api_keys (name, key_hash, key_prefix, admin, created_at)
                        VALUES (:name, :hash, :prefix, :admin, :now)
                        RETURNING %s""".formatted(COLUMNS))
                .param("name", name)
                .param("hash", keyHash)
                .param("prefix", prefix)
                .param("admin", admin)
                .param("now", now.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(MAPPER)
                .single();
    }

    /** Idempotent bootstrap insert: does nothing if this exact key already exists. */
    public void insertIfHashAbsent(String name, String keyHash, String prefix, boolean admin, Instant now) {
        jdbc.sql("""
                        INSERT INTO api_keys (name, key_hash, key_prefix, admin, created_at)
                        VALUES (:name, :hash, :prefix, :admin, :now)
                        ON CONFLICT (key_hash) DO NOTHING""")
                .param("name", name)
                .param("hash", keyHash)
                .param("prefix", prefix)
                .param("admin", admin)
                .param("now", now.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    /** Active (not revoked) key with this hash. */
    public Optional<ApiKey> findActiveByHash(String keyHash) {
        return jdbc.sql("SELECT %s FROM api_keys WHERE key_hash = :hash AND revoked_at IS NULL".formatted(COLUMNS))
                .param("hash", keyHash)
                .query(MAPPER)
                .optional();
    }

    public Optional<ApiKey> findById(long id) {
        return jdbc.sql("SELECT %s FROM api_keys WHERE id = :id".formatted(COLUMNS))
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public List<ApiKey> findAll() {
        return jdbc.sql("SELECT %s FROM api_keys ORDER BY id".formatted(COLUMNS)).query(MAPPER).list();
    }

    /** @return true if this call revoked it; false if it was already revoked or doesn't exist */
    public boolean revoke(long id, Instant now) {
        return jdbc.sql("UPDATE api_keys SET revoked_at = :now WHERE id = :id AND revoked_at IS NULL")
                .param("now", now.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", id)
                .update() == 1;
    }

    /** Revokes active keys with this name other than the given hash; returns how many. */
    public int revokeByNameExceptHash(String name, String keepHash, Instant now) {
        return jdbc.sql("""
                        UPDATE api_keys SET revoked_at = :now
                        WHERE name = :name AND key_hash <> :hash AND revoked_at IS NULL""")
                .param("now", now.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("name", name)
                .param("hash", keepHash)
                .update();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
