package com.reactor.cachedb.oracle;

import com.reactor.cachedb.core.change.ExternalChangeEvent;
import com.reactor.cachedb.core.change.ExternalChangeType;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.model.RelationDefinition;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.registry.EntityRegistry;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.jdbc.JdbcEntitySourceLoader;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class OracleProviderIntegrationTest {
    private static final String JDBC_URL = System.getProperty(
            "cachedb.it.oracle.url",
            "jdbc:oracle:thin:@//127.0.0.1:15211/FREEPDB1"
    );
    private static final String JDBC_USER = System.getProperty("cachedb.it.oracle.user", "cachedb");
    private static final String JDBC_PASSWORD = System.getProperty("cachedb.it.oracle.password", "CacheDbOracle123");
    private HikariDataSource pooledDataSource;

    @BeforeEach
    void setUp() throws Exception {
        assumeReachable();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            drop(statement, "cachedb_it_outbox_checkpoint");
            drop(statement, "cachedb_it_outbox");
            drop(statement, "cachedb_it_entity");
            statement.executeUpdate("""
                    CREATE TABLE cachedb_it_entity (
                        id NUMBER(19) NOT NULL PRIMARY KEY,
                        name VARCHAR2(200 CHAR),
                        created_at NUMBER(19) NOT NULL,
                        entity_version NUMBER(19) NOT NULL,
                        deleted_flag NUMBER(1)
                    )
                    """);
        }
        pooledDataSource = createPooledDataSource();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (pooledDataSource != null) {
            pooledDataSource.close();
            pooledDataSource = null;
        }
        if (!reachable()) {
            return;
        }
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            drop(statement, "cachedb_it_outbox_checkpoint");
            drop(statement, "cachedb_it_outbox");
            drop(statement, "cachedb_it_entity");
        }
    }

    @Test
    void shouldApplyVersionGuardedUpsertDeleteAndEmptyStringPolicy() throws Exception {
        OracleWriteBehindFlusher flusher = new OracleWriteBehindFlusher(dataSource(), emptyRegistry());

        flusher.flush(upsert("1", "first", 10));
        flusher.flush(upsert("1", "stale", 9));
        flusher.flush(upsert("1", "newer", 11));
        flusher.flush(delete("1", 10));

        assertEquals("newer", scalarString("SELECT name FROM cachedb_it_entity WHERE id = 1"));
        assertEquals(11L, scalarLong("SELECT entity_version FROM cachedb_it_entity WHERE id = 1"));

        flusher.flush(delete("1", 12));
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM cachedb_it_entity WHERE id = 1"));

        SQLException emptyStringFailure = assertThrows(SQLException.class, () -> flusher.flush(upsert("2", "", 1)));
        assertEquals("CDB03", emptyStringFailure.getSQLState());

        OracleWriteBehindFlusher normalizingFlusher = new OracleWriteBehindFlusher(
                dataSource(),
                emptyRegistry(),
                com.reactor.cachedb.core.config.WriteBehindConfig.defaults(),
                OracleWriteBehindOptions.builder()
                        .emptyStringPolicy(OracleWriteBehindOptions.EmptyStringPolicy.NORMALIZE_TO_NULL)
                        .build()
        );
        normalizingFlusher.flush(upsert("2", "", 2));
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM cachedb_it_entity WHERE id = 2 AND name IS NULL"));
    }

    @Test
    void shouldSerializeConcurrentSameIdMergeRaces() throws Exception {
        int workers = Integer.getInteger("cachedb.it.oracle.raceWorkers", 6);
        int iterations = Integer.getInteger("cachedb.it.oracle.raceIterations", 20);
        OracleWriteBehindFlusher flusher = new OracleWriteBehindFlusher(dataSource(), emptyRegistry());
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int worker = 0; worker < workers; worker++) {
            int workerIndex = worker;
            executor.submit(() -> {
                try {
                    start.await();
                    for (int iteration = 1; iteration <= iterations; iteration++) {
                        long version = workerIndex * 1_000L + iteration;
                        flusher.flush(upsert("42", "worker-" + workerIndex + "-" + iteration, version));
                        flusher.flush(upsert("42", "stale", version - 1));
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            });
        }

        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(90, TimeUnit.SECONDS), "Oracle race test timed out");
        if (!failures.isEmpty()) {
            AssertionError error = new AssertionError("Oracle concurrent MERGE produced failures");
            failures.forEach(error::addSuppressed);
            throw error;
        }
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM cachedb_it_entity WHERE id = 42"));
        assertEquals((long) (workers - 1) * 1_000L + iterations,
                scalarLong("SELECT entity_version FROM cachedb_it_entity WHERE id = 42"));
    }

    @Test
    void shouldChunkOracleInListAndKeepReadBounded() throws Exception {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO cachedb_it_entity(id, name, created_at, entity_version) VALUES (?, ?, ?, ?)"
             )) {
            for (int id = 1; id <= 1_201; id++) {
                statement.setLong(1, id);
                statement.setString(2, "entity-" + id);
                statement.setLong(3, id);
                statement.setLong(4, id);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        List<Object> ids = new ArrayList<>(1_201);
        for (long id = 1; id <= 1_201; id++) {
            ids.add(id);
        }
        JdbcEntitySourceLoader<TestEntity, Long> loader = new JdbcEntitySourceLoader<>(
                dataSource(), new TestMetadata(), new TestCodec(), 1_500
        );

        List<TestEntity> rows = loader.load(QuerySpec.where(QueryFilter.in("id", ids)).limitTo(1_201));

        assertEquals(1_201, rows.size());
        assertEquals(1L, rows.get(0).id());
        assertEquals(1_201L, rows.get(rows.size() - 1).id());
    }

    @Test
    void shouldPollAndCheckpointOracleOutbox() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            createOutboxTables(statement);
            statement.executeUpdate("""
                    INSERT INTO cachedb_it_outbox
                    (entity_name, entity_id, event_type, payload_json, entity_version, event_source)
                    VALUES ('OrderEntity', '1001', 'UPSERT',
                    '{"customer_id":42,"status":"OPEN","amount":19.75}', 7, 'orders-outbox')
                    """);
            statement.executeUpdate("""
                    INSERT INTO cachedb_it_outbox
                    (entity_name, entity_id, event_type, payload_json, entity_version, event_source)
                    VALUES ('OrderEntity', '1001', 'DELETE',
                    '{"customer_id":42,"status":"DELETED"}', 8, 'orders-outbox')
                    """);
        }
        OracleOutboxExternalChangeFeedAdapter adapter = OracleOutboxExternalChangeFeedAdapter.builder(dataSource())
                .adapterName("oracle-integration-test")
                .outboxTable("cachedb_it_outbox")
                .checkpointTable("cachedb_it_outbox_checkpoint")
                .batchSize(10)
                .build();
        ArrayList<ExternalChangeEvent> events = new ArrayList<>();

        assertEquals(2, adapter.pollOnce(events::add));
        assertEquals(0, adapter.pollOnce(events::add));
        adapter.close();

        assertEquals(ExternalChangeType.UPSERT, events.get(0).type());
        assertEquals(42L, events.get(0).columns().get("customer_id"));
        assertEquals(ExternalChangeType.DELETE, events.get(1).type());
        assertEquals(2L, scalarLong("SELECT last_event_id FROM cachedb_it_outbox_checkpoint "
                + "WHERE adapter_name = 'oracle-integration-test'"));
    }

    @Test
    void shouldCreateCheckpointDdlOnlyWhenExplicitlyEnabled() throws Exception {
        OracleOutboxExternalChangeFeedAdapter adapter = OracleOutboxExternalChangeFeedAdapter.builder(dataSource())
                .adapterName("ddl-test")
                .checkpointTable("cachedb_it_outbox_checkpoint")
                .createCheckpointTable(true)
                .build();

        adapter.initialize();
        adapter.initialize();
        adapter.close();

        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM user_tables "
                + "WHERE table_name = 'CACHEDB_IT_OUTBOX_CHECKPOINT'"));
    }

    @Test
    void shouldSerializeMultiPodOutboxPollingWithoutDuplicateDelivery() throws Exception {
        int eventCount = Integer.getInteger("cachedb.it.oracle.outboxEvents", 100);
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            createOutboxTables(statement);
        }
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO cachedb_it_outbox
                (entity_name, entity_id, event_type, payload_json, entity_version, event_source)
                VALUES ('OrderEntity', ?, 'UPSERT', ?, ?, 'orders-outbox')
                """)) {
            for (int id = 1; id <= eventCount; id++) {
                statement.setString(1, String.valueOf(id));
                statement.setString(2, "{\"order_id\":" + id + "}");
                statement.setLong(3, id);
                statement.addBatch();
            }
            statement.executeBatch();
        }

        OracleOutboxExternalChangeFeedAdapter podA = outboxAdapter("shared-oracle-consumer");
        OracleOutboxExternalChangeFeedAdapter podB = outboxAdapter("shared-oracle-consumer");
        var deliveredIds = ConcurrentHashMap.<String>newKeySet();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        for (OracleOutboxExternalChangeFeedAdapter adapter : List.of(podA, podB)) {
            executor.submit(() -> {
                try {
                    start.await();
                    while (adapter.pollOnce(event -> deliveredIds.add(String.valueOf(event.id()))) > 0) {
                        // Continue until the shared checkpoint reaches the tail.
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            });
        }
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(60, TimeUnit.SECONDS), "Oracle multi-pod outbox test timed out");
        podA.close();
        podB.close();

        if (!failures.isEmpty()) {
            AssertionError error = new AssertionError("Oracle multi-pod outbox polling produced failures");
            failures.forEach(error::addSuppressed);
            throw error;
        }
        assertEquals(eventCount, deliveredIds.size());
        assertEquals(eventCount, scalarLong("SELECT last_event_id FROM cachedb_it_outbox_checkpoint "
                + "WHERE adapter_name = 'shared-oracle-consumer'"));
    }

    @Test
    void shouldMeetVersionGuardedBatchThroughputFloor() throws Exception {
        int rowCount = Integer.getInteger("cachedb.it.oracle.loadRows", 1_000);
        double minimumOpsPerSecond = Double.parseDouble(System.getProperty(
                "cachedb.it.oracle.minWriteOperationsPerSecond", "75"
        ));
        OracleWriteBehindFlusher flusher = new OracleWriteBehindFlusher(
                dataSource(),
                emptyRegistry(),
                com.reactor.cachedb.core.config.WriteBehindConfig.builder().maxFlushBatchSize(128).build()
        );

        long startedAt = System.nanoTime();
        flusher.flushBatch(operations(1, rowCount, OperationType.UPSERT, 1, "created-"));
        flusher.flushBatch(operations(1, rowCount, OperationType.UPSERT, 0, "stale-"));
        flusher.flushBatch(operations(1, rowCount / 2, OperationType.UPSERT, 2, "updated-"));
        flusher.flushBatch(deleteOperations(5, rowCount, 5, 3));
        long elapsedNanos = System.nanoTime() - startedAt;
        int operationCount = rowCount + rowCount + (rowCount / 2) + (rowCount / 5);
        double operationsPerSecond = operationCount / (elapsedNanos / 1_000_000_000.0d);

        assertEquals(rowCount - (rowCount / 5), scalarLong("SELECT COUNT(*) FROM cachedb_it_entity"));
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM cachedb_it_entity WHERE name LIKE 'stale-%'"));
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM cachedb_it_entity WHERE MOD(id, 5) = 0"));
        writeBenchmarkReport(rowCount, operationCount, elapsedNanos, operationsPerSecond, minimumOpsPerSecond);
        assertTrue(
                operationsPerSecond >= minimumOpsPerSecond,
                () -> "Oracle write-behind throughput regression: actual=" + operationsPerSecond
                        + " ops/s, required=" + minimumOpsPerSecond + " ops/s"
        );
    }

    private List<QueuedWriteOperation> operations(
            int firstId,
            int lastId,
            OperationType type,
            long version,
            String namePrefix
    ) {
        ArrayList<QueuedWriteOperation> operations = new ArrayList<>();
        for (int id = firstId; id <= lastId; id++) {
            operations.add(type == OperationType.DELETE
                    ? delete(String.valueOf(id), version)
                    : upsert(String.valueOf(id), namePrefix + id, version));
        }
        return operations;
    }

    private List<QueuedWriteOperation> deleteOperations(int firstId, int lastId, int step, long version) {
        ArrayList<QueuedWriteOperation> operations = new ArrayList<>();
        for (int id = firstId; id <= lastId; id += step) {
            operations.add(delete(String.valueOf(id), version));
        }
        return operations;
    }

    private void writeBenchmarkReport(
            int rowCount,
            int operationCount,
            long elapsedNanos,
            double operationsPerSecond,
            double minimumOpsPerSecond
    ) throws Exception {
        Path report = Path.of("target", "oracle-write-behind-benchmark.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report, String.format(Locale.ROOT, """
                {
                  "provider": "oracle",
                  "rowCount": %d,
                  "operationCount": %d,
                  "elapsedMillis": %.3f,
                  "operationsPerSecond": %.3f,
                  "minimumOperationsPerSecond": %.3f,
                  "status": "%s"
                }
                """,
                rowCount,
                operationCount,
                elapsedNanos / 1_000_000.0d,
                operationsPerSecond,
                minimumOpsPerSecond,
                operationsPerSecond >= minimumOpsPerSecond ? "PASS" : "FAIL"
        ));
    }

    private static QueuedWriteOperation upsert(String id, String name, long version) {
        LinkedHashMap<String, String> columns = new LinkedHashMap<>();
        columns.put("id", id);
        columns.put("name", name);
        columns.put("created_at", String.valueOf(version));
        columns.put("entity_version", String.valueOf(version));
        return operation(OperationType.UPSERT, id, columns, version);
    }

    private static QueuedWriteOperation delete(String id, long version) {
        LinkedHashMap<String, String> columns = new LinkedHashMap<>();
        columns.put("id", id);
        columns.put("name", "deleted");
        columns.put("created_at", String.valueOf(version));
        columns.put("entity_version", String.valueOf(version));
        return operation(OperationType.DELETE, id, columns, version);
    }

    private static QueuedWriteOperation operation(
            OperationType type,
            String id,
            LinkedHashMap<String, String> columns,
            long version
    ) {
        return new QueuedWriteOperation(
                type, "TestEntity", "cachedb_it_entity", "demo", "write",
                "id", "entity_version", "deleted_flag", id, columns, version,
                Instant.parse("2026-08-23T00:00:00Z")
        );
    }

    private DataSource dataSource() {
        return pooledDataSource;
    }

    private HikariDataSource createPooledDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(JDBC_URL);
        config.setUsername(JDBC_USER);
        config.setPassword(JDBC_PASSWORD);
        config.setPoolName("cachedb-oracle-provider-it");
        config.setMaximumPoolSize(Integer.getInteger("cachedb.it.oracle.poolSize", 8));
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10_000L);
        config.setValidationTimeout(5_000L);
        return new HikariDataSource(config);
    }

    private OracleOutboxExternalChangeFeedAdapter outboxAdapter(String adapterName) throws SQLException {
        return OracleOutboxExternalChangeFeedAdapter.builder(dataSource())
                .adapterName(adapterName)
                .outboxTable("cachedb_it_outbox")
                .checkpointTable("cachedb_it_outbox_checkpoint")
                .batchSize(17)
                .build();
    }

    private void createOutboxTables(Statement statement) throws SQLException {
        statement.executeUpdate("""
                CREATE TABLE cachedb_it_outbox (
                    id NUMBER(19) GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    entity_name VARCHAR2(200 CHAR) NOT NULL,
                    entity_id VARCHAR2(200 CHAR) NOT NULL,
                    event_type VARCHAR2(20 CHAR) NOT NULL,
                    payload_json CLOB,
                    entity_version NUMBER(19) NOT NULL,
                    occurred_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
                    event_source VARCHAR2(200 CHAR) NOT NULL
                )
                """);
        statement.executeUpdate("""
                CREATE TABLE cachedb_it_outbox_checkpoint (
                    adapter_name VARCHAR2(200 CHAR) NOT NULL PRIMARY KEY,
                    last_event_id NUMBER(19) NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
    }

    private long scalarLong(String sql) throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private String scalarString(String sql) throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static void drop(Statement statement, String table) throws SQLException {
        try {
            statement.executeUpdate("DROP TABLE " + table + " PURGE");
        } catch (SQLException exception) {
            if (Math.abs(exception.getErrorCode()) != 942) {
                throw exception;
            }
        }
    }

    private boolean reachable() {
        try (Connection ignored = open()) {
            return true;
        } catch (SQLException ignored) {
            return false;
        }
    }

    private void assumeReachable() {
        boolean available = reachable();
        if (!available && Boolean.getBoolean("cachedb.it.oracle.required")) {
            fail("No reachable Oracle test database found at " + JDBC_URL);
        }
        Assumptions.assumeTrue(available, "No reachable Oracle test database found");
    }

    private static EntityRegistry emptyRegistry() {
        return new EntityRegistry() {
            @Override
            public <T, ID> com.reactor.cachedb.core.registry.EntityBinding<T, ID> register(
                    EntityMetadata<T, ID> metadata,
                    EntityCodec<T> codec,
                    com.reactor.cachedb.core.cache.CachePolicy cachePolicy,
                    com.reactor.cachedb.core.relation.RelationBatchLoader<T> relationBatchLoader,
                    com.reactor.cachedb.core.page.EntityPageLoader<T> pageLoader
            ) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T, ID, P> com.reactor.cachedb.core.projection.EntityProjectionBinding<T, P, ID> registerProjection(
                    EntityMetadata<T, ID> metadata,
                    com.reactor.cachedb.core.projection.EntityProjection<T, P, ID> projection
            ) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<com.reactor.cachedb.core.registry.EntityBinding<?, ?>> find(String entityName) {
                return Optional.empty();
            }

            @Override
            public Optional<com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?>> findProjection(
                    String entityName,
                    String projectionName
            ) {
                return Optional.empty();
            }

            @Override
            public java.util.Collection<com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?>> projections(
                    String entityName
            ) {
                return List.of();
            }

            @Override
            public java.util.Collection<com.reactor.cachedb.core.registry.EntityBinding<?, ?>> all() {
                return List.of();
            }
        };
    }

    private record TestEntity(long id, String name, long createdAt) {
    }

    private static final class TestMetadata implements EntityMetadata<TestEntity, Long> {
        @Override public String entityName() { return "TestEntity"; }
        @Override public String tableName() { return "cachedb_it_entity"; }
        @Override public String redisNamespace() { return "cachedb-it-entity"; }
        @Override public String idColumn() { return "id"; }
        @Override public String versionColumn() { return "entity_version"; }
        @Override public String deletedColumn() { return "deleted_flag"; }
        @Override public Class<TestEntity> entityType() { return TestEntity.class; }
        @Override public Function<TestEntity, Long> idAccessor() { return TestEntity::id; }
        @Override public List<String> columns() { return List.of("id", "name", "created_at"); }
        @Override public List<RelationDefinition> relations() { return List.of(); }
    }

    private static final class TestCodec implements EntityCodec<TestEntity> {
        @Override public String toRedisValue(TestEntity entity) { return entity.id + "|" + entity.name + "|" + entity.createdAt; }
        @Override public TestEntity fromRedisValue(String encoded) { throw new UnsupportedOperationException(); }
        @Override public Map<String, Object> toColumns(TestEntity entity) { return Map.of(); }
        @Override
        public TestEntity fromColumns(Map<String, Object> columns) {
            return new TestEntity(
                    ((Number) value(columns, "id")).longValue(),
                    String.valueOf(value(columns, "name")),
                    ((Number) value(columns, "created_at")).longValue()
            );
        }

        private Object value(Map<String, Object> columns, String name) {
            return columns.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElseThrow();
        }
    }
}
