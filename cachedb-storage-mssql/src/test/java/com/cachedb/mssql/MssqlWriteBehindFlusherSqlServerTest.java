package com.reactor.cachedb.mssql;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class MssqlWriteBehindFlusherSqlServerTest {

    private static final String JDBC_URL = System.getProperty(
            "cachedb.it.mssql.url",
            "jdbc:sqlserver://127.0.0.1:14333;databaseName=tempdb;encrypt=false;trustServerCertificate=true"
    );
    private static final String JDBC_USER = System.getProperty("cachedb.it.mssql.user", "sa");
    private static final String JDBC_PASSWORD = System.getProperty("cachedb.it.mssql.password", "YourStrong!Passw0rd");

    @BeforeEach
    void setUp() throws Exception {
        assumeReachable();
        try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_it_entity");
            statement.executeUpdate("""
                    CREATE TABLE cachedb_it_entity (
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
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_it_entity");
        }
    }

    @Test
    void mssqlFlusherShouldApplyVersionGuardedUpsertAndDelete() throws Exception {
        MssqlWriteBehindFlusher flusher = new MssqlWriteBehindFlusher(dataSource(), emptyRegistry());

        flusher.flush(upsert("1", "first", 10));
        flusher.flush(upsert("1", "stale", 9));
        flusher.flush(upsert("1", "newer", 11));
        flusher.flush(delete("1", 10));

        assertEquals("newer", scalarString("SELECT name FROM cachedb_it_entity WHERE id = 1"));
        assertEquals(11L, scalarLong("SELECT entity_version FROM cachedb_it_entity WHERE id = 1"));

        flusher.flush(delete("1", 12));

        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM cachedb_it_entity WHERE id = 1"));
    }

    private static QueuedWriteOperation upsert(String id, String name, long version) {
        LinkedHashMap<String, String> columns = new LinkedHashMap<>();
        columns.put("id", id);
        columns.put("name", name);
        columns.put("entity_version", String.valueOf(version));
        return operation(OperationType.UPSERT, id, columns, version);
    }

    private static QueuedWriteOperation delete(String id, long version) {
        LinkedHashMap<String, String> columns = new LinkedHashMap<>();
        columns.put("id", id);
        columns.put("name", "deleted");
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
                type,
                "DemoEntity",
                "cachedb_it_entity",
                "demo",
                "write",
                "id",
                "entity_version",
                "deleted",
                id,
                columns,
                version,
                Instant.parse("2026-04-05T13:00:00Z")
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
            public java.util.Collection<com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?>> projections(String entityName) {
                return List.of();
            }

            @Override
            public java.util.Collection<com.reactor.cachedb.core.registry.EntityBinding<?, ?>> all() {
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

    private String scalarString(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
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
