package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class JdbcWriteColumnGuardTest {
    @Test void rejectsPhysicalColumnsBeforeAnyTransactionOrSqlMutation() throws Exception {
        for (int reported : new int[]{0, 1000, 4096}) {
            int limit = reported == 0 ? 1000 : reported;
            var columns = columns(limit);
            // The version column is not in the payload; it still consumes a physical slot.
            var flusher = new Flusher();
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> flusher.run(connection(reported), operation(columns)));
            assertTrue(failure.getMessage().contains("exceeds database dictionary limit " + limit));
        }
    }

    @Test void rejectsOversizedSingleStatementRatherThanClampingToOneRow() {
        JdbcDatabaseDialect dialect = new JdbcDatabaseDialect() {
            public String name() { return "small-parameter-budget"; }
            public int maxParametersPerStatement() { return 3; }
            public String deleteSql(QueuedWriteOperation o) { throw new AssertionError(); }
            public String deleteMultiRowSql(QueuedWriteOperation o, int n) { throw new AssertionError(); }
            public String upsertSql(QueuedWriteOperation o, List<Map.Entry<String,String>> e) { throw new AssertionError(); }
            public String upsertMultiRowSql(QueuedWriteOperation o, List<Map.Entry<String,String>> e, int n) { throw new AssertionError(); }
            public String sqlCastType(String type) { throw new AssertionError(); }
        };
        assertThrows(IllegalArgumentException.class, () -> dialect.safeStatementRowLimit(100, 4));
        assertEquals(1, dialect.safeStatementRowLimit(100, 3));
        assertEquals(3, dialect.safeStatementRowLimit(100, 1));
    }

    private static Map<String,String> columns(int count) {
        var columns = new LinkedHashMap<String,String>();
        columns.put("id", "1");
        for (int i = 1; i < count; i++) columns.put("c" + i, "0");
        return columns;
    }
    private static QueuedWriteOperation operation(Map<String,String> columns) {
        return new QueuedWriteOperation(OperationType.UPSERT, "Wide", "wide", "wide", "test", "id",
                "entity_version", null, "1", columns, 1, Instant.EPOCH);
    }
    private Connection connection(int reported) {
        var metadata = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DatabaseMetaData.class},
                (p, m, a) -> {
                    if (m.getName().equals("getMaxColumnsInTable")) return reported;
                    throw new AssertionError("Unexpected metadata access: " + m.getName());
                });
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (p, m, a) -> {
                    if (m.getName().equals("getMetaData")) return metadata;
                    throw new AssertionError("Connection was mutated before validation: " + m.getName());
                });
    }
    private static final class Flusher extends AbstractJdbcWriteBehindFlusher {
        Flusher() {
            super((DataSource) Proxy.newProxyInstance(Flusher.class.getClassLoader(), new Class<?>[]{DataSource.class},
                    (p, m, a) -> { throw new AssertionError("Unexpected connection acquisition"); }), null,
                    JdbcWriteFence.Provider.ORACLE, 10);
        }
        public void flush(QueuedWriteOperation operation) { throw new UnsupportedOperationException(); }
        public com.reactor.cachedb.core.queue.WriteFailureDetails classify(Exception exception) {
            return com.reactor.cachedb.core.queue.WriteFailureDetails.unknown(exception);
        }
        void run(Connection connection, QueuedWriteOperation operation) throws Exception {
            inTransaction(connection, List.of(operation), null, null, rows -> { throw new AssertionError("SQL writer invoked"); });
        }
    }
}
