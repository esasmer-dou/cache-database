package com.reactor.cachedb.spring.boot.snapshot;

import static org.assertj.core.api.Assertions.*;

import com.reactor.cachedb.core.config.ResourceLimits;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.queue.*;
import com.reactor.cachedb.core.registry.DefaultEntityRegistry;
import com.reactor.cachedb.core.repository.WindowRequest;
import com.reactor.cachedb.jdbc.*;
import com.reactor.cachedb.mssql.*;
import com.reactor.cachedb.oracle.*;
import com.reactor.cachedb.postgres.*;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@EnabledIfSystemProperty(named = "cachedb.snapshot.providers", matches = "true")
class FrameworkV013ProvidersTest {
    enum State {
        ACTIVE,
        CLOSED
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "mssql", "oracle", "h2"})
    void timeEnumUuidAndDisabledAdmissionUseRealJdbcValues(String provider) throws Exception {
        var suite = new SnapshotProvidersIntegrationTest();
        try (var f = suite.new Fixture(provider)) {
            String table = "typed_" + f.suffix;
            String timeType = "oracle".equals(provider) ? "TIMESTAMP" : "TIME";
            String instantType =
                    "mssql".equals(provider) ? "DATETIMEOFFSET" : "TIMESTAMP WITH TIME ZONE";
            String uuidType =
                    switch (provider) {
                        case "postgres" -> "UUID";
                        case "mssql" -> "UNIQUEIDENTIFIER";
                        default -> "VARCHAR(36)";
                    };
            f.execute(
                    "CREATE TABLE "
                            + table
                            + " (owner_id "
                            + uuidType
                            + ", target_id "
                            + uuidType
                            + ", happened_at "
                            + instantType
                            + ", local_clock "
                            + timeType
                            + ", state VARCHAR(20), optional_flag INTEGER)");
            try {
                UUID owner = UUID.randomUUID(), target = UUID.randomUUID();
                var instant = OffsetDateTime.parse("2026-01-02T12:00:00+03:00");
                try (var c = f.source.getConnection();
                        var s =
                                c.prepareStatement(
                                        "INSERT INTO " + table + " VALUES (?,?,?,?,?,?)")) {
                    s.setObject(1, "postgres".equals(provider) ? owner : owner.toString());
                    s.setObject(2, "postgres".equals(provider) ? target : target.toString());
                    s.setObject(3, instant);
                    if ("oracle".equals(provider))
                        s.setTimestamp(4, java.sql.Timestamp.valueOf("2026-01-02 12:00:00"));
                    else s.setTime(4, java.sql.Time.valueOf("12:00:00"));
                    s.setString(5, "ACTIVE");
                    s.setNull(6, java.sql.Types.INTEGER);
                    s.executeUpdate();
                }
                if ("oracle".equals(provider)) f.awaitOracleFixtureVisible(table);
                var roots =
                        SnapshotSource.entity(
                                        "typed",
                                        new SourceMapping<UUID>(
                                                table,
                                                List.of(
                                                        "owner_id",
                                                        "target_id",
                                                        "happened_at",
                                                        "local_clock",
                                                        "state",
                                                        "optional_flag"),
                                                row -> {
                                                    assertThat(
                                                                    SourceValues.read(
                                                                                    row,
                                                                                    "happened_at",
                                                                                    OffsetDateTime
                                                                                            .class)
                                                                            .toInstant())
                                                            .isEqualTo(instant.toInstant());
                                                    assertThat(
                                                                    SourceValues.read(
                                                                            row,
                                                                            "local_clock",
                                                                            LocalTime.class))
                                                            .isEqualTo(LocalTime.NOON);
                                                    assertThat(
                                                                    SourceValues.<State>readEnum(
                                                                            row,
                                                                            "state",
                                                                            State::valueOf))
                                                            .isEqualTo(State.ACTIVE);
                                                    assertThat(
                                                                    SourceValues.read(
                                                                            row,
                                                                            "optional_flag",
                                                                            Boolean.class))
                                                            .isNull();
                                                    return SourceValues.read(
                                                            row, "owner_id", UUID.class);
                                                }))
                                .where(
                                        SnapshotPredicate.eq("state", State.ACTIVE)
                                                .and(SnapshotPredicate.isNull("optional_flag")));
                var links = SnapshotRelation.uuids("links", table, "owner_id", "target_id");
                var plan =
                        new SnapshotPlan<>(
                                "typed",
                                roots,
                                UUID::toString,
                                SnapshotPlan.inputs(roots, links),
                                String.class,
                                rows -> {
                                    var forward = rows.lists(links);
                                    var reverse = rows.membership(links.reverse());
                                    return id -> {
                                        assertThat(reverse.containsAll(target, List.of(id)))
                                                .isTrue();
                                        return forward.get(id).stream()
                                                .map(UUID::toString)
                                                .toList();
                                    };
                                });
                try (var jobs = f.jobs(plan, SnapshotSettings.defaults())) {
                    jobs.refresh("typed", true);
                    assertThat(
                                    jobs.repository("typed")
                                            .findById(owner.toString())
                                            .orElseThrow()
                                            .payload())
                            .contains(target.toString());
                }
                var disabled =
                        com.reactor.cachedb.core.cache.CachePolicy.builder()
                                .hotEntityLimit(0)
                                .build();
                assertThat(
                                com.reactor.cachedb.core.guardrail.ReadShapeGuardrails
                                        .shouldCacheLoadedPage(
                                                1,
                                                disabled,
                                                com.reactor.cachedb.core.config
                                                        .ReadShapeGuardrailConfig.defaults()))
                        .isFalse();
                for (var admission : com.reactor.cachedb.core.cache.CacheAdmissionSource.values())
                    assertThat(disabled.shouldAdmit(Map.of(), admission)).isFalse();
            } finally {
                f.execute("DROP TABLE " + table);
            }
            f.assertPoolReset();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "mssql", "oracle", "h2"})
    void typedRelationsPredicatesAndScopedIndexedPublicationAgree(String provider)
            throws Exception {
        var suite = new SnapshotProvidersIntegrationTest();
        try (var f = suite.new Fixture(provider)) {
            f.execute("INSERT INTO " + f.root + " VALUES (2,'root')");
            f.execute("INSERT INTO " + f.child + " VALUES (2,'old')");
            var roots =
                    SnapshotSource.entity(
                                    "roots",
                                    new SourceMapping<Long>(
                                            f.root,
                                            List.of("id", "payload"),
                                            row -> SourceValues.read(row, "id", Long.class)))
                            .where(
                                    SnapshotPredicate.between("id", 1L, 3L)
                                            .and(SnapshotPredicate.ne("payload", "deleted"))
                                            .and(SnapshotPredicate.isNotNull("id"))
                                            .and(
                                                    SnapshotPredicate.inValues(
                                                            "id", List.of(1L, 2L))));
            var links = SnapshotRelation.longs("links", f.child, "id", "id");
            var scope =
                    SnapshotRootScope.builder()
                            .source(
                                    roots,
                                    ids ->
                                            SnapshotPredicate.inValues(
                                                    "id", ids.stream().map(Long::valueOf).toList()))
                            .source(
                                    links,
                                    ids ->
                                            SnapshotPredicate.inValues(
                                                    "id", ids.stream().map(Long::valueOf).toList()))
                            .build();
            var plan =
                    new SnapshotPlan<>(
                                    "catalog",
                                    roots,
                                    Object::toString,
                                    SnapshotPlan.inputs(roots, links),
                                    String.class,
                                    rows -> {
                                        var forward = rows.lists(links);
                                        var reverse = rows.membership(links.reverse());
                                        return id -> {
                                            assertThat(reverse.containsAll(id, List.of(id)))
                                                    .isTrue();
                                            return List.of(forward.get(id).toString());
                                        };
                                    })
                            .indexed(SnapshotIndexSpec.of(id -> "all", Long::doubleValue))
                            .scoped(scope)
                            .withContentReuse();
            try (var jobs = f.jobs(plan, SnapshotSettings.defaults())) {
                jobs.refresh("catalog", true);
                var repo = jobs.repository("catalog");
                assertThat(repo.findPage("all", WindowRequest.first(1)).items()).hasSize(1);
                var before = repo.findById("2").orElseThrow();
                f.execute("DELETE FROM " + f.root + " WHERE id=1");
                jobs.refreshRoots("catalog", List.of("1"));
                assertThat(repo.findById("1")).isEmpty();
                assertThat(repo.findById("2").orElseThrow()).isEqualTo(before);
                assertThat(repo.findPage("all", WindowRequest.first(10)).items()).hasSize(1);
            }
            // Read real JDBC values, not only hand-built maps.
            try (var connection = f.source.getConnection();
                    var statement = connection.createStatement();
                    var result =
                            statement.executeQuery(
                                    "SELECT CAST(7 AS DECIMAL(20,0)) AS n FROM " + f.root)) {
                assertThat(result.next()).isTrue();
                var values = Map.<String, Object>of("n", result.getObject(1));
                assertThat(SourceValues.read(values, "n", BigInteger.class))
                        .isEqualTo(BigInteger.valueOf(7));
                assertThat(SourceValues.read(values, "n", Float.class)).isEqualTo(7f);
                assertThat(SourceValues.read(values, "n", Short.class)).isEqualTo((short) 7);
                assertThat(SourceValues.read(values, "n", Byte.class)).isEqualTo((byte) 7);
            }
            f.assertPoolReset();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void pooledSequenceIsDisjointFromLegacyAndOtherPods(String provider) throws Exception {
        var suite = new SnapshotProvidersIntegrationTest();
        try (var f = suite.new Fixture(provider)) {
            String sequence = "seq_" + f.suffix;
            f.execute("CREATE SEQUENCE " + sequence + " START WITH 1 INCREMENT BY 1");
            try {
                var a = new JdbcPooledSequenceIdGenerator(f.source, sequence, 7);
                var b = new JdbcPooledSequenceIdGenerator(f.source, sequence, 11);
                var legacy = new JdbcPooledSequenceIdGenerator(f.source, sequence, 1);
                Set<Long> ids = ConcurrentHashMap.newKeySet();
                var executor = Executors.newFixedThreadPool(3);
                try {
                    var tasks =
                            List.of(a, b, legacy).stream()
                                    .map(
                                            g ->
                                                    (Callable<Void>)
                                                            () -> {
                                                                for (int i = 0; i < 70; i++)
                                                                    assertThat(ids.add(g.nextId()))
                                                                            .isTrue();
                                                                return null;
                                                            })
                                    .toList();
                    for (var future : executor.invokeAll(tasks)) future.get(30, TimeUnit.SECONDS);
                } finally {
                    executor.shutdownNow();
                }
                assertThat(ids).hasSize(210);
            } finally {
                f.execute("DROP SEQUENCE " + sequence);
            }
            f.assertPoolReset();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void atomicFlusherRollsBackEveryMemberAndAllowsWholeGroupRetry(String provider)
            throws Exception {
        var suite = new SnapshotProvidersIntegrationTest();
        try (var f = suite.new Fixture(provider)) {
            String table = "write_" + f.suffix;
            f.execute(
                    "CREATE TABLE "
                            + table
                            + " (id VARCHAR(20) PRIMARY KEY, payload VARCHAR(8) NOT NULL,"
                            + " entity_version "
                            + ("oracle".equals(provider) ? "NUMBER(19)" : "BIGINT")
                            + " NOT NULL)");
            try {
                var registry = new DefaultEntityRegistry(ResourceLimits.defaults());
                WriteBehindFlusher flusher =
                        switch (provider) {
                            case "postgres" -> new PostgresWriteBehindFlusher(f.source, registry);
                            case "mssql" -> new MssqlWriteBehindFlusher(f.source, registry);
                            default -> new OracleWriteBehindFlusher(f.source, registry);
                        };
                var first = write(table, 1, "first");
                var bad = write(table, 2, "too-long-payload");
                assertThatThrownBy(() -> flusher.flushAtomicBatch(List.of(first, bad)))
                        .isInstanceOf(java.sql.SQLException.class);
                assertThat(count(f, table)).isZero();
                flusher.flushAtomicBatch(List.of(first, write(table, 2, "second")));
                flusher.flushAtomicBatch(List.of(first, write(table, 2, "second")));
                assertThat(count(f, table)).isEqualTo(2);
            } finally {
                f.execute("DROP TABLE " + table);
            }
            f.assertPoolReset();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void twoShardsWithCompetingPodsKeepPerEntityOrderAndIndependentCheckpoints(String provider)
            throws Exception {
        var suite = new SnapshotProvidersIntegrationTest();
        try (var f = suite.new Fixture(provider)) {
            String table = "out_" + f.suffix, checkpoint = "cp_" + f.suffix;
            String time = "mssql".equals(provider) ? "DATETIME2" : "TIMESTAMP";
            String number = "oracle".equals(provider) ? "NUMBER(19)" : "BIGINT";
            f.execute(
                    "CREATE TABLE "
                            + table
                            + " (id "
                            + number
                            + " PRIMARY KEY, entity_name VARCHAR(100), entity_id VARCHAR(100),"
                            + " event_type VARCHAR(20), payload_json VARCHAR(100), entity_version "
                            + number
                            + ", occurred_at "
                            + time
                            + ", event_source VARCHAR(100), partition_key INTEGER NOT NULL)");
            f.execute(
                    "CREATE TABLE "
                            + checkpoint
                            + " (adapter_name VARCHAR(200) PRIMARY KEY, last_event_id "
                            + number
                            + " NOT NULL, updated_at "
                            + time
                            + " NOT NULL)");
            f.execute("CREATE INDEX ix_" + f.suffix + " ON " + table + " (partition_key,id)");
            try {
                var partition = new JdbcOutboxPartition(2, 0, "partition_key");
                try (var c = f.source.getConnection();
                        var insert =
                                c.prepareStatement(
                                        "INSERT INTO " + table + " VALUES (?,?,?,?,?,?,?,?,?)")) {
                    for (int i = 1; i <= 80; i++) {
                        String id = Integer.toString(i % 8);
                        insert.setLong(1, i);
                        insert.setString(2, "Order");
                        insert.setString(3, id);
                        insert.setString(4, "UPSERT");
                        insert.setString(5, "{}");
                        insert.setLong(6, i);
                        insert.setTimestamp(7, java.sql.Timestamp.from(Instant.now()));
                        insert.setString(8, "test");
                        insert.setInt(9, partition.partitionFor("Order", id));
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                JdbcOutboxDialect dialect =
                        switch (provider) {
                            case "postgres" -> new PostgresOutboxDialect();
                            case "mssql" -> new MssqlOutboxDialect();
                            default -> new OracleOutboxDialect();
                        };
                var consumers = new ArrayList<JdbcOutboxExternalChangeFeedAdapter>();
                for (int i = 0; i < 4; i++)
                    consumers.add(
                            JdbcOutboxExternalChangeFeedAdapter.builder(f.source, dialect)
                                    .adapterName("feed")
                                    .outboxTable(table)
                                    .checkpointTable(checkpoint)
                                    .createCheckpointTable(false)
                                    .partition(2, i % 2, "partition_key")
                                    .batchSize(7)
                                    .build());
                Map<String, List<Long>> versions = new ConcurrentHashMap<>();
                var executor = Executors.newFixedThreadPool(4);
                try {
                    var tasks =
                            consumers.stream()
                                    .map(
                                            adapter ->
                                                    (Callable<Void>)
                                                            () -> {
                                                                for (int i = 0; i < 12; i++)
                                                                    adapter.pollOnce(
                                                                            event ->
                                                                                    versions.computeIfAbsent(
                                                                                                    event.id()
                                                                                                            .toString(),
                                                                                                    id ->
                                                                                                            Collections
                                                                                                                    .synchronizedList(
                                                                                                                            new ArrayList<>()))
                                                                                            .add(
                                                                                                    event
                                                                                                            .version()));
                                                                return null;
                                                            })
                                    .toList();
                    for (var future : executor.invokeAll(tasks)) future.get(30, TimeUnit.SECONDS);
                } finally {
                    executor.shutdownNow();
                    for (var consumer : consumers) consumer.close();
                }
                assertThat(versions.values().stream().mapToInt(List::size).sum()).isEqualTo(80);
                for (var values : versions.values())
                    assertThat(values).isSorted().doesNotHaveDuplicates();
                assertThat(count(f, checkpoint)).isEqualTo(2);
            } finally {
                f.execute("DROP TABLE " + table);
                f.execute("DROP TABLE " + checkpoint);
            }
            f.assertPoolReset();
        }
    }

    private QueuedWriteOperation write(String table, int id, String payload) {
        return new QueuedWriteOperation(
                        OperationType.UPSERT,
                        "Row",
                        table,
                        table,
                        "",
                        "id",
                        "entity_version",
                        null,
                        Integer.toString(id),
                        Map.of(
                                "id",
                                Integer.toString(id),
                                "payload",
                                payload,
                                "entity_version",
                                "1"),
                        1,
                        Instant.now())
                .inTransaction("group");
    }

    private int count(SnapshotProvidersIntegrationTest.Fixture f, String table) throws Exception {
        try (var c = f.source.getConnection();
                var s = c.createStatement();
                var rows = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        }
    }
}
