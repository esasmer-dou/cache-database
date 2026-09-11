package com.reactor.cachedb.spring.boot.snapshot;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit opt-in evidence against disposable local/provider CI databases, never production. */
@EnabledIfSystemProperty(named = "cachedb.snapshot.providers", matches = "true")
class SnapshotProvidersIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void bindsScalarFiltersWithoutDriverSpecificApplicationCode(String provider) throws Exception {
        try (Fixture fixture = new Fixture(provider)) {
            String flagType =
                    switch (provider) {
                        case "postgres" -> "BOOLEAN";
                        case "mssql" -> "BIT";
                        default -> "NUMBER(1)";
                    };
            fixture.execute("ALTER TABLE " + fixture.child + " ADD active " + flagType);
            fixture.execute(
                    "ALTER TABLE "
                            + fixture.child
                            + " ADD created_at "
                            + ("mssql".equals(provider) ? "DATETIME2" : "TIMESTAMP"));
            fixture.execute(
                    "ALTER TABLE "
                            + fixture.child
                            + " ADD token "
                            + ("postgres".equals(provider) ? "UUID" : "VARCHAR(36)"));
            var token = UUID.randomUUID();
            var instant = java.time.Instant.parse("2026-01-01T12:30:00Z");
            try (var connection = fixture.source.getConnection();
                    var statement =
                            connection.prepareStatement(
                                    "UPDATE "
                                            + fixture.child
                                            + " SET active=?, created_at=?, token=?")) {
                if ("oracle".equals(provider)) statement.setInt(1, 1);
                else statement.setBoolean(1, true);
                statement.setTimestamp(2, java.sql.Timestamp.from(instant));
                statement.setObject(3, "postgres".equals(provider) ? token : token.toString());
                statement.executeUpdate();
            }
            if ("oracle".equals(provider)) fixture.awaitOracleFixtureVisible();
            var roots =
                    SnapshotSource.entity(
                                    "roots",
                                    new com.reactor.cachedb.core.model.SourceMapping<String>(
                                            fixture.child,
                                            List.of("id", "active", "created_at", "token"),
                                            row -> {
                                                assertThat(
                                                                com.reactor.cachedb.core.model
                                                                        .SourceValues.read(
                                                                        row,
                                                                        "active",
                                                                        Boolean.class))
                                                        .isTrue();
                                                assertThat(
                                                                com.reactor.cachedb.core.model
                                                                        .SourceValues.read(
                                                                        row, "token", UUID.class))
                                                        .isEqualTo(token);
                                                return row.get("id").toString();
                                            }))
                            .where(
                                    SnapshotPredicate.eq("active", true)
                                            .and(SnapshotPredicate.eq("token", token))
                                            .and(SnapshotPredicate.eq("created_at", instant))
                                            .and(
                                                    SnapshotPredicate.eq(
                                                            "id", java.math.BigInteger.ONE)));
            var plan =
                    new SnapshotPlan<>(
                            "catalog",
                            roots,
                            id -> id,
                            SnapshotPlan.inputs(roots),
                            String.class,
                            rows -> id -> List.of(id));
            try (var jobs = fixture.jobs(plan, SnapshotSettings.defaults())) {
                assertThat(jobs.refresh("catalog", true).submittedRows()).isEqualTo(1);
            }
            fixture.assertPoolReset();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void declarativeSubqueryAndRelationPublishEquivalentPayload(String provider) throws Exception {
        try (Fixture fixture = new Fixture(provider)) {
            var roots =
                    SnapshotSource.entity(
                                    "roots",
                                    new com.reactor.cachedb.core.model.SourceMapping<String>(
                                            fixture.root,
                                            List.of("id", "payload"),
                                            row -> row.get("id").toString()))
                            .where(SnapshotPredicate.eq("payload", "root"));
            var links =
                    SnapshotRelation.strings("links", fixture.child, "id", "payload")
                            .where(
                                    SnapshotPredicate.in("id", roots.select("id"))
                                            .and(SnapshotPredicate.eq("payload", "old")));
            var plan =
                    new SnapshotPlan<>(
                            "catalog",
                            roots,
                            id -> id,
                            SnapshotPlan.inputs(roots, links),
                            String.class,
                            rows -> {
                                var values = rows.lists(links);
                                var reverse = rows.membership(links.reverse());
                                assertThat(reverse.containsAll("old", List.of("1"))).isTrue();
                                return values::get;
                            });
            try (var job = fixture.jobs(plan, SnapshotSettings.defaults())) {
                job.refresh("catalog", true);
                assertThat(job.repository("catalog").findById("1").orElseThrow().payload())
                        .isEqualTo("[\"old\"]");
                fixture.execute("UPDATE " + fixture.child + " SET payload='new'");
                var emptyPlan =
                        new SnapshotPlan<>(
                                "catalog",
                                roots,
                                id -> id,
                                SnapshotPlan.inputs(roots, links),
                                String.class,
                                rows -> rows.lists(links)::get);
                try (var empty = fixture.jobs(emptyPlan, SnapshotSettings.defaults())) {
                    empty.refresh("catalog", true);
                    assertThat(empty.repository("catalog").findById("1").orElseThrow().payload())
                            .isEqualTo("[]");
                }
            }
            fixture.assertPoolReset();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    @SuppressWarnings("unchecked")
    void generatedEntitySourcePreservesColumnAliasesAcrossProviders(String provider)
            throws Exception {
        try (Fixture fixture = new Fixture(provider)) {
            com.reactor.cachedb.core.model.EntityMetadata<String, String> metadata =
                    org.mockito.Mockito.mock(com.reactor.cachedb.core.model.EntityMetadata.class);
            com.reactor.cachedb.core.model.EntityCodec<String> codec =
                    org.mockito.Mockito.mock(com.reactor.cachedb.core.model.EntityCodec.class);
            org.mockito.Mockito.when(metadata.tableName()).thenReturn(fixture.root);
            org.mockito.Mockito.when(metadata.columns()).thenReturn(List.of("id", "payload"));
            org.mockito.Mockito.when(codec.fromColumns(org.mockito.ArgumentMatchers.anyMap()))
                    .thenAnswer(
                            call ->
                                    ((Map<String, Object>) call.getArgument(0))
                                            .get("id")
                                            .toString());
            var roots = SnapshotSource.entity("roots", metadata, codec, "");
            var plan =
                    new SnapshotPlan<>(
                            "catalog",
                            roots,
                            id -> id,
                            List.of(roots),
                            String.class,
                            rows -> id -> List.of(id));
            try (var job = fixture.jobs(plan, SnapshotSettings.defaults())) {
                job.refresh("catalog", true);
                assertThat(job.repository("catalog").findById("1").orElseThrow().payload())
                        .isEqualTo("[\"1\"]");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void preservesOneSnapshotAcrossConcurrentSourceMutation(String provider) throws Exception {
        try (Fixture fixture = new Fixture(provider)) {
            AtomicBoolean changed = new AtomicBoolean();
            var roots =
                    fixture.source(
                            "roots",
                            fixture.root,
                            row -> {
                                if (changed.compareAndSet(false, true))
                                    fixture.execute(
                                            "UPDATE " + fixture.child + " SET payload='new'");
                                return row.get("id").toString();
                            });
            var children =
                    fixture.source("children", fixture.child, row -> row.get("payload").toString());
            var plan =
                    new SnapshotPlan<>(
                            "catalog",
                            roots,
                            id -> id,
                            List.of(roots, children),
                            String.class,
                            rows -> id -> rows.get(children));
            try (var job = fixture.jobs(plan, SnapshotSettings.defaults())) {
                job.refresh("catalog", true);
                assertThat(job.repository("catalog").findById("1").orElseThrow().payload())
                        .isEqualTo("[\"old\"]");
                job.refresh("catalog", true);
                assertThat(job.repository("catalog").findById("1").orElseThrow().payload())
                        .isEqualTo("[\"new\"]");
            }
            fixture.assertPoolReset();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void failedBudgetPreservesPublishedCatalogAndRestoresPool(String provider) throws Exception {
        try (Fixture fixture = new Fixture(provider)) {
            var plan = fixture.plan();
            try (var good = fixture.jobs(plan, SnapshotSettings.defaults());
                    var limited =
                            fixture.jobs(
                                    plan,
                                    SnapshotContractTest.settings(
                                            Map.of("max-source-size", "1B")))) {
                good.refresh("catalog", true);
                var old = good.repository("catalog").findById("1").orElseThrow();
                assertThatThrownBy(() -> limited.refresh("catalog", true))
                        .hasMessageContaining("budget");
                assertThat(good.repository("catalog").findById("1").orElseThrow()).isEqualTo(old);
                fixture.execute("DELETE FROM " + fixture.root);
                good.refresh("catalog", true);
                assertThat(good.repository("catalog").findById("1")).isEmpty();
            }
            fixture.assertPoolReset();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void sourceSqlFailureRestoresConnectionAndAllowsNextWarm(String provider) throws Exception {
        try (Fixture fixture = new Fixture(provider)) {
            var missing = fixture.source("missing", fixture.root + "_absent", row -> "1");
            var invalid =
                    new SnapshotPlan<>(
                            "catalog",
                            missing,
                            id -> id,
                            List.of(missing),
                            String.class,
                            rows -> id -> List.of(id));
            try (var bad = fixture.jobs(invalid, SnapshotSettings.defaults());
                    var good = fixture.jobs(fixture.plan(), SnapshotSettings.defaults())) {
                assertThatThrownBy(() -> bad.refresh("catalog", true))
                        .hasMessageContaining("source read failed");
                fixture.assertPoolReset();
                assertThat(good.refresh("catalog", true).submittedRows()).isEqualTo(1);
            }
        }
    }

    @Test
    void sqlServerRefusesDisabledSnapshotIsolationWithoutAlteringDatabase() throws Exception {
        try (var connection =
                        DriverManager.getConnection(
                                url("mssql")
                                        .replace(
                                                "databaseName=cachedb_snapshot",
                                                "databaseName=master"),
                                "sa",
                                password("mssql"));
                var statement = connection.createStatement()) {
            String database = "snapshot_off_" + UUID.randomUUID().toString().replace("-", "");
            statement.execute("CREATE DATABASE " + database);
            try (var read =
                    DriverManager.getConnection(
                            url("mssql")
                                    .replace(
                                            "databaseName=cachedb_snapshot",
                                            "databaseName=" + database),
                            "sa",
                            password("mssql"))) {
                assertThatThrownBy(() -> SnapshotTransaction.begin(read))
                        .hasMessageContaining("ALLOW_SNAPSHOT_ISOLATION ON");
                assertThat(read.getAutoCommit()).isTrue();
                assertThat(read.getTransactionIsolation())
                        .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            } finally {
                statement.execute("DROP DATABASE " + database);
            }
        }
    }

    private static String url(String provider) {
        return System.getProperty(
                "cachedb.snapshot." + provider + ".url",
                switch (provider) {
                    case "postgres" -> "jdbc:postgresql://127.0.0.1:15435/postgres";
                    case "mssql" ->
                            "jdbc:sqlserver://127.0.0.1:14331;databaseName=cachedb_snapshot;encrypt=false";
                    case "oracle" -> "jdbc:oracle:thin:@//127.0.0.1:15211/FREEPDB1";
                    default -> throw new IllegalArgumentException(provider);
                });
    }

    private static String password(String provider) {
        return System.getProperty(
                "cachedb.snapshot." + provider + ".password",
                switch (provider) {
                    case "postgres" -> "LocalFixture_7p92_test";
                    case "mssql" -> "CacheDbSnapshot123";
                    default -> "CacheDbOracle123";
                });
    }

    private final class Fixture implements AutoCloseable {
        final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        final String root = "snap_r_" + suffix;
        final String child = "snap_c_" + suffix;
        final String namespace = "snapshot-provider-" + suffix;
        final HikariDataSource source;
        final JedisPooled redis =
                new JedisPooled(
                        "redis://127.0.0.1:"
                                + Integer.getInteger("cachedb.snapshot.redis.port", 16383));

        Fixture(String provider) {
            var config = new HikariConfig();
            config.setJdbcUrl(url(provider));
            config.setUsername(
                    System.getProperty(
                            "cachedb.snapshot." + provider + ".user",
                            switch (provider) {
                                case "postgres" -> "postgres";
                                case "mssql" -> "sa";
                                default -> "cachedb";
                            }));
            config.setPassword(password(provider));
            config.setMaximumPoolSize(2);
            config.setMinimumIdle(1);
            config.setConnectionTimeout(5000);
            config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
            source = new HikariDataSource(config);
            execute("CREATE TABLE " + root + " (id INTEGER PRIMARY KEY, payload VARCHAR(100))");
            execute("CREATE TABLE " + child + " (id INTEGER PRIMARY KEY, payload VARCHAR(100))");
            execute("INSERT INTO " + root + " VALUES (1,'root')");
            execute("INSERT INTO " + child + " VALUES (1,'old')");
            if ("oracle".equals(provider)) {
                awaitOracleFixtureVisible();
            }
        }

        // Readiness belongs to disposable DDL setup, never to production refresh retry policy.
        // Poll the actual snapshot visibility instead of assuming a fixed sleep is sufficient.
        private void awaitOracleFixtureVisible() {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while (true) {
                try (var connection = source.getConnection();
                        var transaction = SnapshotTransaction.begin(connection);
                        var statement = connection.createStatement()) {
                    statement.setQueryTimeout(5);
                    for (String table : List.of(root, child)) {
                        try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                            if (!rows.next())
                                throw new IllegalStateException("Missing Oracle fixture count");
                        }
                    }
                    return;
                } catch (java.sql.SQLException failure) {
                    if (failure.getErrorCode() != 1466 || System.nanoTime() >= deadline)
                        throw new IllegalStateException(
                                "Oracle fixture snapshot is not ready", failure);
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "Interrupted during Oracle fixture setup", interrupted);
                    }
                }
            }
        }

        <T> SnapshotSource<T> source(
                String name,
                String table,
                java.util.function.Function<Map<String, Object>, T> decoder) {
            return new SnapshotSource<>(
                    name, "SELECT id AS \"id\", payload AS \"payload\" FROM " + table, decoder);
        }

        SnapshotPlan<String, String> plan() {
            var roots = source("roots", root, row -> row.get("id").toString());
            return new SnapshotPlan<>(
                    "catalog",
                    roots,
                    id -> id,
                    List.of(roots),
                    String.class,
                    rows -> id -> List.of("payload"));
        }

        SnapshotJobs jobs(SnapshotPlan<?, ?> plan, SnapshotSettings settings) {
            return new SnapshotJobs(
                    source,
                    redis,
                    new ObjectMapper(),
                    Clock.systemUTC(),
                    namespace,
                    List.of(plan),
                    Map.of(plan.name(), settings));
        }

        void execute(String sql) {
            try (var connection = source.getConnection();
                    var statement = connection.createStatement()) {
                statement.setQueryTimeout(10);
                statement.execute(sql);
            } catch (java.sql.SQLException failure) {
                throw new IllegalStateException(failure);
            }
        }

        void assertPoolReset() throws Exception {
            try (var first = source.getConnection();
                    var second = source.getConnection()) {
                for (var connection : List.of(first, second)) {
                    assertThat(connection.getAutoCommit()).isTrue();
                    assertThat(connection.isReadOnly()).isFalse();
                    assertThat(connection.getTransactionIsolation())
                            .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
                }
            }
        }

        @Override
        public void close() {
            try {
                execute("DROP TABLE " + child);
                execute("DROP TABLE " + root);
            } finally {
                source.close();
                String cursor = "0";
                do {
                    var page =
                            redis.scan(cursor, new ScanParams().match(namespace + ":*").count(100));
                    if (!page.getResult().isEmpty())
                        redis.unlink(page.getResult().toArray(String[]::new));
                    cursor = page.getCursor();
                } while (!cursor.equals("0"));
                redis.close();
            }
        }
    }
}
