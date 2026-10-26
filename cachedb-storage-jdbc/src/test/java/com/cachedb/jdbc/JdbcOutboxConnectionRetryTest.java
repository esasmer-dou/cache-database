package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcOutboxConnectionRetryTest {
    private static final JdbcOutboxDialect DIALECT = new JdbcOutboxDialect() {
        public String name() { return "h2"; }
        public String createCheckpointTableSql(String table) { throw new AssertionError("Fixture owns schema"); }
        public String readBatchSql(JdbcOutboxMapping mapping, int size) {
            return "SELECT id,entity_name,entity_id,event_type,payload_json,entity_version,occurred_at,event_source FROM cachedb_outbox WHERE id > ? ORDER BY id LIMIT " + size;
        }
        public void bindReadBatch(PreparedStatement statement, long checkpoint, int size) throws SQLException { statement.setLong(1, checkpoint); }
        public String writeCheckpointSql(String table) { return "UPDATE " + table + " SET last_event_id=?, updated_at=CURRENT_TIMESTAMP WHERE adapter_name=?"; }
        public void bindWriteCheckpoint(PreparedStatement statement, String name, long id) throws SQLException { statement.setLong(1, id); statement.setString(2, name); }
    };

    private JdbcDataSource source() throws SQLException {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE cachedb_outbox_adapter_checkpoint(adapter_name VARCHAR(200) PRIMARY KEY,last_event_id BIGINT,updated_at TIMESTAMP)");
            statement.execute("CREATE TABLE cachedb_outbox(id BIGINT,entity_name VARCHAR(30),entity_id VARCHAR(30),event_type VARCHAR(20),payload_json VARCHAR(100),entity_version BIGINT,occurred_at TIMESTAMP,event_source VARCHAR(30),partition_key INT)");
        }
        return source;
    }

    private JdbcOutboxExternalChangeFeedAdapter adapter(DataSource source) {
        return JdbcOutboxExternalChangeFeedAdapter.builder(source, DIALECT).adapterName("feed")
                .createCheckpointTable(false).partition(2, 0, "partition_key").pollIntervalMillis(50).build();
    }

    private DataSource flaky(JdbcDataSource source, AtomicInteger acquisitions, int failures, boolean failCommit) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
            if (!method.getName().equals("getConnection")) return invoke(method, source, args);
            if (acquisitions.incrementAndGet() <= failures) throw new SQLTransientConnectionException("Injected bounded pool saturation");
            Connection connection = source.getConnection();
            if (!failCommit) return connection;
            return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, operation, parameters) -> {
                if (operation.getName().equals("commit")) throw new SQLTransientConnectionException("Injected uncertain commit");
                return invoke(operation, connection, parameters);
            });
        });
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
    }

    @Test void retriesTransientAcquisitionBeforeAnyDeliveryWithinExistingBudget() throws Exception {
        var attempts = new AtomicInteger(); var callbacks = new AtomicInteger();
        try (var adapter = adapter(flaky(source(), attempts, 2, false))) {
            assertEquals(0, adapter.pollOnce(event -> callbacks.incrementAndGet()));
            assertEquals(3, attempts.get()); assertEquals(0, callbacks.get()); assertNull(adapter.lastFailure());
        }
    }

    @Test void permanentPoolSaturationStopsAfterFourAttempts() throws Exception {
        var attempts = new AtomicInteger();
        try (var adapter = adapter(flaky(source(), attempts, Integer.MAX_VALUE, false))) {
            assertThrows(IllegalStateException.class, () -> adapter.pollOnce(event -> fail("Must not deliver")));
            assertEquals(4, attempts.get()); assertNotNull(adapter.lastFailure());
        }
    }

    @Test void uncertainCommitAfterDeliveryIsNeverAutomaticallyReplayed() throws Exception {
        var source = source();
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO cachedb_outbox VALUES(1,'Order','1','UPSERT','{}',1,CURRENT_TIMESTAMP,'test',0)");
        }
        var attempts = new AtomicInteger(); var callbacks = new AtomicInteger();
        try (var adapter = adapter(flaky(source, attempts, 0, true))) {
            assertThrows(IllegalStateException.class, () -> adapter.pollOnce(event -> callbacks.incrementAndGet()));
            assertEquals(1, attempts.get()); assertEquals(1, callbacks.get());
        }
    }
}
