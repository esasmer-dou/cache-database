package com.reactor.cachedb.mssql;

import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.queue.StoragePerformanceCollector;
import com.reactor.cachedb.core.registry.EntityRegistry;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MssqlWriteBehindFlusherTest {

    @Test
    void shouldUpdateThenInsertWhenRowDoesNotExist() throws Exception {
        RecordingDataSource dataSource = new RecordingDataSource(0, false);
        MssqlWriteBehindFlusher flusher = new MssqlWriteBehindFlusher(dataSource, emptyRegistry());

        flusher.flush(operation(OperationType.UPSERT, "1", 5));

        assertEquals(List.of("UPDATE", "SELECT", "INSERT"), dataSource.statementKinds());
        assertTrue(dataSource.committed);
        assertFalse(dataSource.rolledBack);
        assertTrue(dataSource.serializableIsolationRequested);
    }

    @Test
    void shouldSkipInsertWhenVersionGuardUpdateMissesExistingRow() throws Exception {
        RecordingDataSource dataSource = new RecordingDataSource(0, true);
        MssqlWriteBehindFlusher flusher = new MssqlWriteBehindFlusher(dataSource, emptyRegistry());

        flusher.flush(operation(OperationType.UPSERT, "2", 3));

        assertEquals(List.of("UPDATE", "SELECT"), dataSource.statementKinds());
        assertTrue(dataSource.committed);
        assertFalse(dataSource.rolledBack);
    }

    @Test
    void shouldDeleteWithVersionGuard() throws Exception {
        RecordingDataSource dataSource = new RecordingDataSource(1, false);
        MssqlWriteBehindFlusher flusher = new MssqlWriteBehindFlusher(dataSource, emptyRegistry());

        flusher.flush(operation(OperationType.DELETE, "3", 8));

        assertEquals(List.of("DELETE"), dataSource.statementKinds());
        assertTrue(dataSource.sqlCalls.get(0).contains("entity_version <= ?"));
    }

    @Test
    void shouldChunkLargeBatchesByConfiguredTransactionSize() throws Exception {
        RecordingDataSource dataSource = new RecordingDataSource(1, false);
        WriteBehindConfig config = WriteBehindConfig.builder()
                .maxFlushBatchSize(2)
                .build();
        MssqlWriteBehindFlusher flusher = new MssqlWriteBehindFlusher(dataSource, emptyRegistry(), config);

        flusher.flushBatch(List.of(
                operation(OperationType.UPSERT, "1", 1),
                operation(OperationType.UPSERT, "2", 2),
                operation(OperationType.UPSERT, "3", 3),
                operation(OperationType.UPSERT, "4", 4),
                operation(OperationType.UPSERT, "5", 5)
        ));

        assertEquals(3, dataSource.statementKinds().size());
        assertEquals(3, dataSource.commitCount);
    }

    @Test
    void shouldBatchConsecutiveDeletesWithoutReorderingOperations() throws Exception {
        RecordingDataSource dataSource = new RecordingDataSource(1, false);
        MssqlWriteBehindFlusher flusher = new MssqlWriteBehindFlusher(dataSource, emptyRegistry());

        flusher.flushBatch(List.of(
                operation(OperationType.DELETE, "1", 1),
                operation(OperationType.DELETE, "2", 2),
                operation(OperationType.DELETE, "3", 3)
        ));

        assertEquals(List.of("DELETE"), dataSource.statementKinds());
        assertEquals(1, dataSource.commitCount);
    }

    @Test
    void shouldApplySharedPoolMssqlTimeoutOptionsAndRestoreLockTimeout() throws Exception {
        RecordingDataSource dataSource = new RecordingDataSource(1, false);
        MssqlWriteBehindOptions options = MssqlWriteBehindOptions.builder()
                .lockTimeoutMillis(250)
                .queryTimeoutSeconds(3)
                .restoreLockTimeoutAfterTransaction(true)
                .build();
        MssqlWriteBehindFlusher flusher = new MssqlWriteBehindFlusher(
                dataSource,
                emptyRegistry(),
                WriteBehindConfig.defaults(),
                options
        );

        flusher.flush(operation(OperationType.UPSERT, "6", 12));

        assertEquals(List.of("SELECT @@LOCK_TIMEOUT", "SET LOCK_TIMEOUT 250", "SET LOCK_TIMEOUT -1"), dataSource.sessionSqlCalls);
        assertFalse(dataSource.queryTimeouts.isEmpty());
        assertTrue(dataSource.queryTimeouts.stream().allMatch(timeout -> timeout == 3));
    }

    @Test
    void shouldUseDedicatedWorkerPoolPresetWithoutLockTimeoutRestoreRead() throws Exception {
        RecordingDataSource dataSource = new RecordingDataSource(1, false);
        MssqlWriteBehindOptions options = MssqlWriteBehindOptions.dedicatedWorkerPoolDefaults()
                .toBuilder()
                .lockTimeoutMillis(300)
                .queryTimeoutSeconds(4)
                .build();
        MssqlWriteBehindFlusher flusher = new MssqlWriteBehindFlusher(
                dataSource,
                emptyRegistry(),
                WriteBehindConfig.defaults(),
                options
        );

        flusher.flush(operation(OperationType.UPSERT, "7", 13));

        assertEquals(List.of("SET LOCK_TIMEOUT 300"), dataSource.sessionSqlCalls);
        assertFalse(dataSource.queryTimeouts.isEmpty());
        assertTrue(dataSource.queryTimeouts.stream().allMatch(timeout -> timeout == 4));
    }

    @Test
    void shouldRejectUnsupportedMssqlOptions() {
        assertThrows(IllegalArgumentException.class, () -> MssqlWriteBehindOptions.builder()
                .lockTimeoutMillis(-2)
                .build());
        assertThrows(IllegalArgumentException.class, () -> MssqlWriteBehindOptions.builder()
                .queryTimeoutSeconds(-1)
                .build());
        assertThrows(IllegalArgumentException.class, () -> MssqlWriteBehindOptions.builder()
                .transactionIsolation(Connection.TRANSACTION_NONE)
                .build());
    }

    @Test
    void shouldTagStoragePerformanceBreakdownWithMssqlProvider() throws Exception {
        RecordingDataSource dataSource = new RecordingDataSource(1, false);
        StoragePerformanceCollector collector = new StoragePerformanceCollector();
        MssqlWriteBehindFlusher flusher = new MssqlWriteBehindFlusher(
                dataSource,
                emptyRegistry(),
                WriteBehindConfig.defaults(),
                collector
        );

        flusher.flush(operation(OperationType.UPSERT, "5", 11));

        assertEquals(1L, collector.snapshot().postgresWrite().operationCount());
        assertTrue(collector.snapshot().postgresWriteBreakdown().containsKey("mssql:write"));
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

    private static QueuedWriteOperation operation(OperationType type, String id, long version) {
        LinkedHashMap<String, String> columns = new LinkedHashMap<>();
        columns.put("id", id);
        columns.put("name", "entity-" + version);
        columns.put("entity_version", String.valueOf(version));
        return new QueuedWriteOperation(
                type,
                "DemoEntity",
                "demo_table",
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

    private static final class RecordingDataSource implements DataSource {
        private final int updateCount;
        private final boolean exists;
        private final List<String> sqlCalls = new ArrayList<>();
        private final List<String> sessionSqlCalls = new ArrayList<>();
        private final List<Integer> queryTimeouts = new ArrayList<>();
        private boolean committed;
        private boolean rolledBack;
        private int commitCount;
        private int requestedIsolation;
        private boolean serializableIsolationRequested;
        private int currentLockTimeout = MssqlWriteBehindOptions.LOCK_TIMEOUT_WAIT_FOREVER;

        private RecordingDataSource(int updateCount, boolean exists) {
            this.updateCount = updateCount;
            this.exists = exists;
        }

        private List<String> statementKinds() {
            return sqlCalls.stream()
                    .map(sql -> {
                        if (sql.startsWith("UPDATE")) {
                            return "UPDATE";
                        }
                        if (sql.startsWith("SELECT")) {
                            return "SELECT";
                        }
                        if (sql.startsWith("INSERT")) {
                            return "INSERT";
                        }
                        if (sql.startsWith("DELETE")) {
                            return "DELETE";
                        }
                        return sql;
                    })
                    .toList();
        }

        @Override
        public Connection getConnection() {
            InvocationHandler handler = new ConnectionHandler(this);
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    handler
            );
        }

        @Override
        public Connection getConnection(String username, String password) {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("unwrap not supported");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    private static final class ConnectionHandler implements InvocationHandler {
        private final RecordingDataSource dataSource;

        private ConnectionHandler(RecordingDataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getAutoCommit" -> true;
                case "setAutoCommit", "close" -> null;
                case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                case "setTransactionIsolation" -> {
                    dataSource.requestedIsolation = (Integer) args[0];
                    if (dataSource.requestedIsolation == Connection.TRANSACTION_SERIALIZABLE) {
                        dataSource.serializableIsolationRequested = true;
                    }
                    yield null;
                }
                case "commit" -> {
                    dataSource.committed = true;
                    dataSource.commitCount++;
                    yield null;
                }
                case "rollback" -> {
                    dataSource.rolledBack = true;
                    yield null;
                }
                case "prepareStatement" -> preparedStatement(dataSource, (String) args[0]);
                case "createStatement" -> statement(dataSource);
                default -> defaultValue(method.getReturnType());
            };
        }
    }

    private static PreparedStatement preparedStatement(RecordingDataSource dataSource, String sql) {
        dataSource.sqlCalls.add(sql);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "executeUpdate" -> sql.startsWith("UPDATE") ? dataSource.updateCount : 1;
            case "executeQuery" -> resultSet(dataSource.exists);
            case "setQueryTimeout" -> {
                dataSource.queryTimeouts.add((Integer) args[0]);
                yield null;
            }
            case "setObject", "setLong", "close" -> null;
            default -> defaultValue(method.getReturnType());
        };
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                handler
        );
    }

    private static Statement statement(RecordingDataSource dataSource) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "executeQuery" -> {
                String sql = (String) args[0];
                dataSource.sessionSqlCalls.add(sql);
                yield intResultSet(dataSource.currentLockTimeout);
            }
            case "execute" -> {
                String sql = (String) args[0];
                dataSource.sessionSqlCalls.add(sql);
                if (sql.startsWith("SET LOCK_TIMEOUT ")) {
                    dataSource.currentLockTimeout = Integer.parseInt(sql.substring("SET LOCK_TIMEOUT ".length()));
                }
                yield false;
            }
            case "setQueryTimeout" -> {
                dataSource.queryTimeouts.add((Integer) args[0]);
                yield null;
            }
            case "close" -> null;
            default -> defaultValue(method.getReturnType());
        };
        return (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                handler
        );
    }

    private static ResultSet resultSet(boolean exists) {
        InvocationHandler handler = new InvocationHandler() {
            private boolean first = true;

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("next".equals(method.getName())) {
                    boolean result = first && exists;
                    first = false;
                    return result;
                }
                if ("close".equals(method.getName())) {
                    return null;
                }
                return defaultValue(method.getReturnType());
            }
        };
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                handler
        );
    }

    private static ResultSet intResultSet(int value) {
        InvocationHandler handler = new InvocationHandler() {
            private boolean first = true;

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                return switch (method.getName()) {
                    case "next" -> {
                        boolean result = first;
                        first = false;
                        yield result;
                    }
                    case "getInt" -> value;
                    case "close" -> null;
                    default -> defaultValue(method.getReturnType());
                };
            }
        };
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                handler
        );
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == Void.TYPE) {
            return null;
        }
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Integer.TYPE) {
            return 0;
        }
        if (returnType == Long.TYPE) {
            return 0L;
        }
        if (returnType == Double.TYPE) {
            return 0D;
        }
        if (returnType == Float.TYPE) {
            return 0F;
        }
        return null;
    }
}
