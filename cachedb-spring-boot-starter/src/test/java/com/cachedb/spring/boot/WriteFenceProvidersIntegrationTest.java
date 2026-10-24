package com.reactor.cachedb.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reactor.cachedb.core.config.ResourceLimits;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.queue.WriteBehindFlusher;
import com.reactor.cachedb.core.registry.DefaultEntityRegistry;
import com.reactor.cachedb.jdbc.JdbcWriteFence;
import com.reactor.cachedb.mssql.MssqlWriteBehindFlusher;
import com.reactor.cachedb.oracle.OracleWriteBehindFlusher;
import com.reactor.cachedb.postgres.PostgresWriteBehindFlusher;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real SQL contracts. Requires explicitly enabled, disposable provider fixtures. */
@EnabledIfSystemProperty(named = "cachedb.snapshot.providers", matches = "true")
class WriteFenceProvidersIntegrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void coalescingIsExplicitAndTombstonesWinEqualVersions(String provider) throws Exception {
        try (Fixture fixture = new Fixture(provider)) {
            var fence = new JdbcWriteFence(fixture.source, fixture.registry,
                    JdbcWriteFence.Provider.valueOf(provider.toUpperCase(java.util.Locale.ROOT)),10);
            fence.initialize();
            var newest = fixture.operation("coalesced", OperationType.UPSERT,2);
            var stale = fixture.operation("coalesced", OperationType.UPSERT,1);
            var tombstone = fixture.operation("coalesced", OperationType.DELETE,2);
            try (var connection = fixture.source.getConnection()) {
                connection.setAutoCommit(false);
                assertThat(fence.filter(connection,List.of(newest,stale,tombstone),true)).containsExactly(tombstone);
                connection.rollback();
                assertThat(fence.filter(connection,List.of(newest,stale,tombstone))).containsExactly(newest,tombstone);
                connection.rollback();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void laterDeleteWaitsForTheOlderBusinessTransactionToCommit(String provider) throws Exception {
        verifyFenceOwnership(provider,false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void coalescedFenceHoldsTheSameBusinessTransactionLock(String provider) throws Exception {
        verifyFenceOwnership(provider,true);
    }

    private void verifyFenceOwnership(String provider,boolean coalesce) throws Exception {
        try (Fixture fixture = new Fixture(provider)) {
            var fence =
                    new JdbcWriteFence(
                            fixture.source,
                            fixture.registry,
                            JdbcWriteFence.Provider.valueOf(
                                    provider.toUpperCase(java.util.Locale.ROOT)),
                            10);
            fence.initialize();
            var pool = Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.Future<?> deletion;
                try (var connection = fixture.source.getConnection()) {
                    connection.setAutoCommit(false);
                    assertThat(
                                    fence.filter(
                                            connection,
                                            List.of(
                                                    fixture.operation(
                                                            "root", OperationType.UPSERT, 2)),coalesce))
                            .hasSize(1);
                    var started = new CountDownLatch(1);
                    deletion =
                            pool.submit(
                                    () -> {
                                        started.countDown();
                                        fixture.flusher()
                                                .flush(
                                                        fixture.operation(
                                                                "root", OperationType.DELETE, 3));
                                        return null;
                                    });
                    assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> deletion.get(200, TimeUnit.MILLISECONDS))
                            .isInstanceOf(java.util.concurrent.TimeoutException.class);
                    try (var statement = connection.createStatement()) {
                        statement.executeUpdate(
                                "INSERT INTO "
                                        + fixture.table
                                        + "(id,value_text,entity_version) VALUES ('root','v2',2)");
                    }
                    connection.commit();
                }
                deletion.get(30, TimeUnit.SECONDS);
                assertThat(fixture.rows()).isZero();
            } finally {
                pool.shutdownNow();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void physicalDeleteSurvivesOldUpsertAndFlusherRestart(String provider) throws Exception {
        try (Fixture fixture = new Fixture(provider)) {
            var first = fixture.flusher();
            first.flush(fixture.operation("root", OperationType.UPSERT, 1));
            first.flush(fixture.operation("root", OperationType.DELETE, 3));
            assertThat(fixture.rows()).isZero();
            var restarted = fixture.flusher();
            restarted.flush(fixture.operation("root", OperationType.UPSERT, 2));
            restarted.flush(fixture.operation("root", OperationType.UPSERT, 3));
            assertThat(fixture.rows()).isZero();
            restarted.flush(fixture.operation("root", OperationType.UPSERT, 4));
            assertThat(fixture.version("root")).isEqualTo(4);
            restarted.flush(fixture.operation("root", OperationType.DELETE, 3));
            assertThat(fixture.version("root")).isEqualTo(4);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void deletingAnAbsentRowAlsoFencesReplayAndDeleteWinsEqualVersion(String provider)
            throws Exception {
        try (Fixture fixture = new Fixture(provider)) {
            var flusher = fixture.flusher();
            flusher.flush(fixture.operation("absent", OperationType.DELETE, 3));
            flusher.flush(fixture.operation("absent", OperationType.UPSERT, 1));
            flusher.flush(fixture.operation("present", OperationType.UPSERT, 7));
            flusher.flush(fixture.operation("present", OperationType.DELETE, 7));
            flusher.flush(fixture.operation("present", OperationType.UPSERT, 7));
            assertThat(fixture.rows()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void businessFailureRollsBackTheFenceAsWellAsTheRows(String provider) throws Exception {
        try (Fixture fixture = new Fixture(provider)) {
            var flusher = fixture.flusher();
            flusher.flush(fixture.operation("root", OperationType.UPSERT, 1));
            var bad = fixture.operation("bad", OperationType.UPSERT, 10, "reject");
            assertThatThrownBy(
                            () ->
                                    flusher.flushAtomicBatch(
                                            List.of(
                                                    fixture.operation(
                                                            "root", OperationType.DELETE, 9),
                                                    bad)))
                    .isInstanceOf(SQLException.class);
            assertThat(fixture.version("root")).isEqualTo(1);
            flusher.flush(fixture.operation("root", OperationType.UPSERT, 2));
            flusher.flush(fixture.operation("bad", OperationType.UPSERT, 1));
            assertThat(fixture.version("root")).isEqualTo(2);
            assertThat(fixture.version("bad")).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void atomicOrderAndReplayRemainIdempotent(String provider) throws Exception {
        try (Fixture fixture = new Fixture(provider)) {
            var flusher = fixture.flusher();
            var writes =
                    List.of(
                            fixture.operation("root", OperationType.UPSERT, 1),
                            fixture.operation("root", OperationType.DELETE, 2),
                            fixture.operation("root", OperationType.UPSERT, 3));
            flusher.flushAtomicBatch(writes);
            assertThat(fixture.version("root")).isEqualTo(3);
            flusher.flushAtomicBatch(writes);
            assertThat(fixture.version("root")).isEqualTo(3);
            flusher.flushAtomicBatch(
                    List.of(
                            fixture.operation("root", OperationType.DELETE, 4),
                            fixture.operation("root", OperationType.UPSERT, 2)));
            assertThat(fixture.rows()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void batchBoundaryAndDuplicateIdentitiesKeepTheLatestDurableState(String provider)
            throws Exception {
        try (Fixture fixture = new Fixture(provider)) {
            var flusher = fixture.flusher();
            List<QueuedWriteOperation> operations = new ArrayList<>();
            for (int i = 0; i < 601; i++)
                operations.add(fixture.operation("row-" + i, OperationType.UPSERT, 1));
            flusher.flushBatch(operations);
            assertThat(fixture.rows()).isEqualTo(601);
            operations.clear();
            for (int i = 0; i < 601; i++) {
                operations.add(fixture.operation("row-" + i, OperationType.DELETE, 3));
                operations.add(fixture.operation("row-" + i, OperationType.UPSERT, 2));
            }
            flusher.flushBatch(operations);
            assertThat(fixture.rows()).isZero();
            flusher.flushBatch(operations);
            assertThat(fixture.rows()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void twoIndependentFlushersCannotResurrectDeletesUnderConcurrentDelivery(String provider)
            throws Exception {
        try (Fixture fixture = new Fixture(provider)) {
            var old = fixture.flusher();
            var newer = fixture.flusher();
            var pool = Executors.newFixedThreadPool(2);
            try {
                for (int i = 0; i < 20; i++) {
                    String id = "race-" + i;
                    var start = new CountDownLatch(1);
                    var upsert =
                            pool.submit(
                                    () -> {
                                        start.await();
                                        old.flush(fixture.operation(id, OperationType.UPSERT, 2));
                                        return null;
                                    });
                    var delete =
                            pool.submit(
                                    () -> {
                                        start.await();
                                        newer.flush(fixture.operation(id, OperationType.DELETE, 3));
                                        return null;
                                    });
                    start.countDown();
                    upsert.get(30, TimeUnit.SECONDS);
                    delete.get(30, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(fixture.rows()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void qualifiedTableAliasSharesFenceAndUnicodeIdsRemainDistinct(String provider)
            throws Exception {
        try (Fixture fixture = new Fixture(provider)) {
            var flusher = fixture.flusher();
            String id = "\u00fc\u0130\u001f:root";
            flusher.flush(fixture.operation(id, OperationType.DELETE, 3));
            var original = fixture.operation(id, OperationType.UPSERT, 2);
            String schema;
            try (var connection = fixture.source.getConnection()) {
                schema = connection.getSchema();
            }
            var qualified =
                    new QueuedWriteOperation(
                            original.type(),
                            original.entityName(),
                            schema + "." + fixture.table,
                            original.redisNamespace(),
                            "",
                            original.idColumn(),
                            original.versionColumn(),
                            null,
                            original.id(),
                            original.columns(),
                            original.version(),
                            original.createdAt());
            flusher.flush(qualified);
            assertThat(fixture.rows()).isZero();
            flusher.flush(fixture.operation(id + "2", OperationType.UPSERT, 1));
            assertThat(fixture.rows()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void corruptIdentityFailsClosedWithoutChangingBusinessData(String provider) throws Exception {
        try (Fixture fixture = new Fixture(provider)) {
            var flusher = fixture.flusher();
            flusher.flush(fixture.operation("root", OperationType.UPSERT, 1));
            try (var connection = fixture.source.getConnection();
                    var statement =
                            connection.prepareStatement(
                                    "UPDATE "
                                            + JdbcWriteFence.TABLE
                                            + " SET identity_value=? WHERE identity_value LIKE"
                                            + " ?")) {
                statement.setString(1, "corrupt-" + fixture.metadataTableName());
                statement.setString(2, "%" + fixture.metadataTableName() + "%");
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
            assertThatThrownBy(
                            () -> flusher.flush(fixture.operation("root", OperationType.DELETE, 2)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("identity collision");
            assertThat(fixture.version("root")).isEqualTo(1);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final String provider;
        final String table = "wf_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        final HikariDataSource source;
        final DefaultEntityRegistry registry = new DefaultEntityRegistry(ResourceLimits.defaults());

        Fixture(String provider) throws Exception {
            this.provider = provider;
            HikariConfig config = new HikariConfig();
            String url =
                    switch (provider) {
                        case "postgres" -> "jdbc:postgresql://127.0.0.1:15435/postgres";
                        case "mssql" ->
                                "jdbc:sqlserver://127.0.0.1:14331;databaseName=cachedb_snapshot;encrypt=false;trustServerCertificate=true";
                        default -> "jdbc:oracle:thin:@//127.0.0.1:15211/FREEPDB1";
                    };
            config.setJdbcUrl(com.reactor.cachedb.spring.boot.TestFixtureSettings.value("cachedb.snapshot." + provider + ".url", url));
            config.setUsername(
                    com.reactor.cachedb.spring.boot.TestFixtureSettings.value(
                            "cachedb.snapshot." + provider + ".user",
                            switch (provider) {
                                case "postgres" -> "postgres";
                                case "mssql" -> "sa";
                                default -> "cachedb";
                            }));
            config.setPassword(
                    com.reactor.cachedb.spring.boot.TestFixtureSettings.value(
                            "cachedb.snapshot." + provider + ".password",
                            switch (provider) {
                                case "postgres" -> "LocalFixture_7p92_test";
                                case "mssql" -> "CacheDbSnapshot123";
                                default -> "CacheDbOracle123";
                            }));
            config.setMaximumPoolSize(4);
            config.setMinimumIdle(0);
            config.setConnectionTimeout(10000);
            source = new HikariDataSource(config);
            String text = "mssql".equals(provider) ? "NVARCHAR(120)" : "VARCHAR(120)";
            try (var connection = source.getConnection();
                    var statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TABLE "
                                + table
                                + " (id "
                                + text
                                + " PRIMARY KEY, value_text "
                                + text
                                + " CHECK (value_text <> 'reject'), entity_version "
                                + ("oracle".equals(provider) ? "NUMBER(19)" : "BIGINT")
                                + ")");
            }
        }

        WriteBehindFlusher flusher() {
            return switch (provider) {
                case "postgres" -> new PostgresWriteBehindFlusher(source, registry);
                case "mssql" -> new MssqlWriteBehindFlusher(source, registry);
                default -> new OracleWriteBehindFlusher(source, registry);
            };
        }

        QueuedWriteOperation operation(String id, OperationType type, long version) {
            return operation(id, type, version, "v" + version);
        }

        QueuedWriteOperation operation(String id, OperationType type, long version, String value) {
            var columns = new LinkedHashMap<String, String>();
            columns.put("id", id);
            columns.put("value_text", value);
            columns.put("entity_version", Long.toString(version));
            return new QueuedWriteOperation(
                    type,
                    "WriteFenceFixture",
                    table,
                    table,
                    "",
                    "id",
                    "entity_version",
                    null,
                    id,
                    columns,
                    version,
                    Instant.EPOCH);
        }

        long rows() throws Exception {
            try (var connection = source.getConnection();
                    var statement = connection.createStatement();
                    var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                result.next();
                return result.getLong(1);
            }
        }

        long version(String id) throws Exception {
            try (var connection = source.getConnection();
                    var statement =
                            connection.prepareStatement(
                                    "SELECT entity_version FROM " + table + " WHERE id=?")) {
                statement.setString(1, id);
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    return result.getLong(1);
                }
            }
        }

        String metadataTableName() {
            return "oracle".equals(provider) ? table.toUpperCase(java.util.Locale.ROOT) : table;
        }

        @Override
        public void close() throws Exception {
            try (source;
                    var connection = source.getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("DROP TABLE " + table);
                try (var cleanup =
                        connection.prepareStatement(
                                "DELETE FROM "
                                        + JdbcWriteFence.TABLE
                                        + " WHERE identity_value LIKE ?")) {
                    cleanup.setString(1, "%" + metadataTableName() + "%");
                    cleanup.executeUpdate();
                }
            }
        }
    }
}
