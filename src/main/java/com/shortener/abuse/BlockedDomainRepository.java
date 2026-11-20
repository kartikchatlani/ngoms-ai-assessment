package com.shortener.abuse;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BlockedDomainRepository {

    private static final RowMapper<BlockedDomain> MAPPER = (rs, n) -> {
        OffsetDateTime created = rs.getObject("created_at", OffsetDateTime.class);
        long by = rs.getLong("created_by");
        return new BlockedDomain(rs.getString("domain"), rs.getString("reason"), created.toInstant(),
                rs.wasNull() ? null : by);
    };

    private final JdbcClient jdbc;

    public BlockedDomainRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<BlockedDomain> findAll() {
        return jdbc.sql("SELECT domain, reason, created_at, created_by FROM blocked_domains ORDER BY domain")
                .query(MAPPER).list();
    }

    /** Idempotent: blocking an already-blocked domain keeps the original entry. */
    public void insertIfAbsent(String domain, String reason, Instant now, Long createdBy) {
        jdbc.sql("""
                        INSERT INTO blocked_domains (domain, reason, created_at, created_by)
                        VALUES (:domain, :reason, :now, :by)
                        ON CONFLICT (domain) DO NOTHING""")
                .param("domain", domain)
                .param("reason", reason)
                .param("now", now.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("by", createdBy, Types.BIGINT)
                .update();
    }

    /** @return true if it was blocked before this call */
    public boolean delete(String domain) {
        return jdbc.sql("DELETE FROM blocked_domains WHERE domain = :domain").param("domain", domain).update() == 1;
    }
}
