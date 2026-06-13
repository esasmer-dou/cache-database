package com.reactor.cachedb.mssql;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.registry.EntityRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class MssqlWriteBehindFlusherLoadSqlServerTest {

    private static final String JDBC_URL = System.getProperty(
            "cachedb.it.mssql.url",
            "jdbc:sqlserver://127.0.0.1:14333;databaseName=tempdb;encrypt=false;trustServerCertificate=true"
    );
    private static final String JDBC_USER = System.getProperty("cachedb.it.mssql.user", "sa");
    private static final String JDBC_PASSWORD = System.getProperty("cachedb.it.mssql.password", "YourStrong!Passw0rd");
    private static final int ROW_COUNT = Integer.getInteger("cachedb.it.mssql.loadRows", 1_000);

    @BeforeEach
    void setUp() throws Exception {
        assumeReachable();
        try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_it_load_entity");
            statement.executeUpdate("""
                    CREATE TABLE cachedb_it_load_entity (
                        id BIGINT NOT NULL PRIMARY KEY,
                        name NVARCHAR(200) NOT NULL,
                        entity_version BIGINT NOT NULL
                    )
                    """);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (!reachable()) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_it_load_entity");
        }
    }

    @Test
    void mssqlFlusherShouldHandleHighVolumeVersionGuardedLoad() throws Exception {
        MssqlWriteBehindFlusher flusher = new MssqlWriteBehindFlusher(
                dataSource(),
                emptyRegistry(),
                WriteBehindConfig.builder().maxFlushBatchSize(64).build()
        );

        flusher.flushBatch(operations(1, ROW_COUNT, OperationType.UPSERT, 1, "created-"));
        flusher.flushBatch(operations(1, ROW_COUNT, OperationType.UPSERT, 0, "stale-"));
        flusher.flushBatch(operations(1, ROW_COUNT / 2, OperationType.UPSERT, 2, "updated-"));
        flusher.flushBatch(deleteOperations(5, ROW_COUNT, 5, 3));

        assertEquals(ROW_COUNT - (ROW_COUNT / 5), scalarLong("SELECT COUNT(*) FROM cachedb_it_load_entity"));
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM cachedb_it_load_entity WHERE name LIKE 'stale-%'"));
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM cachedb_it_load_entity WHERE id % 5 = 0"));
    }

    private List<QueuedWriteOperation> operations(
            int firstId,
            int lastIdInclusive,
            OperationType operationType,
            long version,
            String namePrefix
    ) {
        ArrayList<QueuedWriteOperation> operations = new ArrayList<>();
        for (int id = firstId; id <= lastIdInclusive; id++) {
            operations.add(operation(operationType, id, version, namePrefix + id));
        }
        return operations;
    }

    private List<QueuedWriteOperation> deleteOperations(
            int firstId,
            int lastIdInclusive,
            int step,
            long version
    ) {
        ArrayList<QueuedWriteOperation> operations = new ArrayList<>();
        for (int id = firstId; id <= lastIdInclusive; id += step) {
            operations.add(operation(OperationType.DELETE, id, version, "deleted-" + id));
        }
        return operations;
    }

    private QueuedWriteOperation operation(OperationType type, int id, long version, String name) {
        LinkedHashMap<String, String> columns = new LinkedHashMap<>();
        columns.put("id", String.valueOf(id));
        columns.put("name", name);
        columns.put("entity_version", String.valueOf(version));
        return new QueuedWriteOperation(
                type,
                "LoadEntity",
                "cachedb_it_load_entity",
                "mssql",
                "load",
                "id",
                "entity_version",
                "deleted",
                String.valueOf(id),
                columns,
                version,
                Instant.parse("2026-06-13T08:00:00Z")
        );
    }

    private DataSource dataSource() {
        SQLServerDataSource dataSource = new SQLServerDataSource();
        dataSource.setURL(JDBC_URL);
        dataSource.setUser(JDBC_USER);
        dataSource.setPassword(JDBC_PASSWORD);
        return dataSource;
    }

    private static EntityRegistry emptyRegistry() {
        return new EntityRegistry() {
            @Override
            public <T, ID> com.reactor.cachedb.core.registry.EntityBinding<T, ID> register(
                    com.reactor.cachedb.core.model.EntityMetadata<T, ID> metadata,
                    com.reactor.cachedb.core.model.EntityCodec<T> codec,
                    com.reactor.cachedb.core.cache.CachePolicy cachePolicy,
                    com.reactor.cachedb.core.relation.RelationBatchLoader<T> relationBatchLoader,
                    com.reactor.cachedb.core.page.EntityPageLoader<T> pageLoader
            ) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T, ID, P> com.reactor.cachedb.core.projection.EntityProjectionBinding<T, P, ID> registerProjection(
                    com.reactor.cachedb.core.model.EntityMetadata<T, ID> metadata,
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
            public Collection<com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?>> projections(String entityName) {
                return List.of();
            }

            @Override
            public Collection<com.reactor.cachedb.core.registry.EntityBinding<?, ?>> all() {
                return List.of();
            }
        };
    }

    private long scalarLong(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private boolean reachable() {
        try (Connection ignored = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD)) {
            return true;
        } catch (SQLException ignored) {
            return false;
        }
    }

    private void assumeReachable() {
        boolean reachable = reachable();
        if (!reachable && Boolean.getBoolean("cachedb.it.mssql.required")) {
            fail("No reachable SQL Server test database found at " + JDBC_URL);
        }
        Assumptions.assumeTrue(reachable, "No reachable SQL Server test database found");
    }
}
