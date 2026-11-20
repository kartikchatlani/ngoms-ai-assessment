package com.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Brownfield migration safety (ADR-0007), tested the way production experiences it: a database
 * already at V1 with live data is upgraded to V2, and a Phase 1 build (which only knows V1) can
 * still start against the upgraded schema, so a rollback is safe.
 *
 * <p>Uses Flyway directly rather than Spring, to control exactly which migrations exist.
 */
@Testcontainers(disabledWithoutDocker = true)
class MigrationUpgradeTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @TempDir
    Path phase1Migrations;

    @Test
    void upgradesAPopulatedV1DatabaseAndStaysRollbackCompatible() throws Exception {
        var ds = freshDatabase();
        var jdbc = JdbcClient.create(ds);

        // 1. Production before Phase 2: schema at V1, with live, expired and deleted links.
        Flyway.configure().dataSource(ds).target("1").load().migrate();
        jdbc.sql("""
                INSERT INTO links (code, long_url, custom_alias, created_at, expires_at, deleted_at) VALUES
                  ('live001', 'https://example.com/live', false, now() - interval '2 days', NULL, NULL),
                  ('expired', 'https://example.com/exp',  true,  now() - interval '2 days', now() - interval '1 day', NULL),
                  ('deleted', 'https://example.com/del',  true,  now() - interval '2 days', NULL, now() - interval '1 hour')
                """).update();

        // 2. Deploy Phase 2: Flyway applies V2 on startup. (Pinned to V2 since Phase 3 added V3/V4.)
        var result = Flyway.configure().dataSource(ds).target("2").load().migrate();
        assertThat(result.migrationsExecuted).isEqualTo(1);
        assertThat(result.targetSchemaVersion).isEqualTo("2");

        // Existing data is untouched...
        assertThat(jdbc.sql("SELECT code FROM links ORDER BY code").query(String.class).list())
                .containsExactly("deleted", "expired", "live001");
        // ...and clicks can be recorded against links that predate the feature, including
        // expired and deleted ones (their old stats stay queryable).
        for (String code : List.of("live001", "expired", "deleted")) {
            jdbc.sql("""
                    INSERT INTO click_events (link_id, clicked_at, referrer_host, agent_class)
                    SELECT id, now(), NULL, 'DESKTOP' FROM links WHERE code = :code""")
                    .param("code", code).update();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM click_events").query(Long.class).single()).isEqualTo(3);

        // 3. Roll back to the Phase 1 build: it only ships V1. Flyway must accept a database that
        // is *ahead* of it (applied V2 that it doesn't know) instead of refusing to start.
        Files.copy(Path.of(Objects.requireNonNull(
                        getClass().getResource("/db/migration/V1__create_links.sql")).toURI()),
                phase1Migrations.resolve("V1__create_links.sql"));
        Flyway phase1 = Flyway.configure().dataSource(ds)
                .locations("filesystem:" + phase1Migrations.toAbsolutePath())
                .load();
        assertThatCode(phase1::validate).doesNotThrowAnyException();
        assertThat(phase1.migrate().migrationsExecuted).isZero();

        // Phase 1's queries still work on the upgraded schema (expand-only migration).
        assertThat(jdbc.sql("SELECT long_url FROM links WHERE code = 'live001'").query(String.class).single())
                .isEqualTo("https://example.com/live");
    }

    /**
     * Phase 3 (ADR-0008): a populated V2 database, with clicks, goes to V4. Existing links get no
     * owner (admin-only, scenario 3 Q7), the NOT VALID foreign keys end up validated, and a Phase 2
     * build (V1 + V2 only) still starts against the result.
     */
    @Test
    void upgradesAPopulatedV2DatabaseToV4AndStaysRollbackCompatible() throws Exception {
        var ds = freshDatabase();
        var jdbc = JdbcClient.create(ds);

        // 1. Production before Phase 3: V2 with links and clicks.
        Flyway.configure().dataSource(ds).target("2").load().migrate();
        jdbc.sql("""
                INSERT INTO links (code, long_url, custom_alias, created_at) VALUES
                  ('campaign', 'https://example.com/campaign', true, now() - interval '3 days'),
                  ('random1',  'https://example.com/r',        false, now() - interval '1 day')
                """).update();
        jdbc.sql("""
                INSERT INTO click_events (link_id, clicked_at, agent_class)
                SELECT id, now(), 'MOBILE' FROM links""").update();

        // 2. Deploy Phase 3: V3 (expand) and V4 (validate) run as separate transactions.
        var result = Flyway.configure().dataSource(ds).load().migrate();
        assertThat(result.migrationsExecuted).isEqualTo(2);
        assertThat(result.targetSchemaVersion).isEqualTo("4");

        // Existing links and clicks are intact; nobody owns the old links yet.
        assertThat(jdbc.sql("SELECT count(*) FROM links WHERE owner_key_id IS NULL AND created_by IS NULL AND blocked_at IS NULL")
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM click_events").query(Long.class).single()).isEqualTo(2);
        // V4 really validated the constraints V3 added as NOT VALID.
        assertThat(jdbc.sql("""
                        SELECT bool_and(convalidated) FROM pg_constraint
                        WHERE conname IN ('fk_links_owner_key', 'fk_links_created_by', 'fk_links_deleted_by')""")
                .query(Boolean.class).single()).isTrue();

        // 3. Roll back to the Phase 2 build (ships V1 + V2 only).
        for (String file : List.of("V1__create_links.sql", "V2__create_click_events.sql")) {
            Files.copy(Path.of(Objects.requireNonNull(getClass().getResource("/db/migration/" + file)).toURI()),
                    phase1Migrations.resolve(file));
        }
        Flyway phase2 = Flyway.configure().dataSource(ds)
                .locations("filesystem:" + phase1Migrations.toAbsolutePath())
                .load();
        assertThatCode(phase2::validate).doesNotThrowAnyException();
        assertThat(phase2.migrate().migrationsExecuted).isZero();
        // Phase 2's own INSERT (no owner columns) still works: the new columns are all nullable.
        assertThatCode(() -> jdbc.sql("""
                INSERT INTO links (code, long_url, custom_alias, created_at, expires_at)
                VALUES ('after-rollback', 'https://example.com', false, now(), NULL)""").update())
                .doesNotThrowAnyException();
    }

    @Test
    void v3RejectsOwnersThatAreNotKeysAndUppercaseBlockedDomains() {
        var ds = freshDatabase();
        Flyway.configure().dataSource(ds).load().migrate();
        var jdbc = JdbcClient.create(ds);

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO links (code, long_url, created_at, owner_key_id) VALUES ('abc', 'https://example.com', now(), 999)""")
                .update()).hasMessageContaining("fk_links_owner_key");
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO blocked_domains (domain, created_at) VALUES ('Evil.Example', now())""").update())
                .hasMessageContaining("ck_blocked_domains_lower");
    }

    @Test
    void v2RejectsInvalidAgentClassesAndUnknownLinks() {
        var ds = freshDatabase();
        Flyway.configure().dataSource(ds).load().migrate();
        var jdbc = JdbcClient.create(ds);

        // Constraints are the last line of defence if a future code path skips the classifier.
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO click_events (link_id, clicked_at, agent_class) VALUES (999999, now(), 'DESKTOP')""").update())
                .hasMessageContaining("fk_click_events_link"); // no such link
        jdbc.sql("INSERT INTO links (code, long_url, created_at) VALUES ('abc', 'https://example.com', now())").update();
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO click_events (link_id, clicked_at, agent_class)
                SELECT id, now(), 'TOASTER' FROM links WHERE code = 'abc'""").update())
                .hasMessageContaining("ck_click_events_agent_class");
    }

    /** Each test starts from an empty database (tests share one container). */
    private static DriverManagerDataSource freshDatabase() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(ds).cleanDisabled(false).load().clean();
        return ds;
    }
}
