package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.queue.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcPatchH2Test {
    @Test void independentPlanningCompactsDistinctKeysButNeverCrossesSameKeyBarriers() {
        var operations = new ArrayList<QueuedWriteOperation>();
        for (int i = 0; i < 100; i++) operations.add(new QueuedWriteOperation(i % 2 == 0 ? OperationType.PATCH : OperationType.UPSERT,
                "Row", "rows", "rows", "", "id", "entity_version", null, "id" + i,
                Map.of("id", "id" + i, "value_text", "value"), 1, Instant.EPOCH));
        var runs = AbstractJdbcWriteBehindFlusher.planOrderPreservingRuns(operations);
        assertEquals(2, runs.size()); assertEquals(50, runs.get(0).size()); assertEquals(50, runs.get(1).size());
        var chain = List.of(operation(OperationType.UPSERT, 1, "a"), operation(OperationType.PATCH, 2, "b"),
                operation(OperationType.DELETE, 3, "c"), operation(OperationType.PATCH, 4, "d"));
        assertEquals(chain, AbstractJdbcWriteBehindFlusher.planOrderPreservingRuns(chain).stream().flatMap(List::stream).toList());
        assertEquals(4, AbstractJdbcWriteBehindFlusher.planOrderPreservingRuns(chain).size());
    }
    static final class Flusher extends AbstractJdbcWriteBehindFlusher {
        Flusher(JdbcDataSource source) { super(source, null, JdbcWriteFence.Provider.POSTGRES, 5); }
        public WriteFailureDetails classify(Exception failure) { return WriteFailureDetails.unknown(failure); }
        public void flush(QueuedWriteOperation operation) throws SQLException {
            try (var connection = dataSource.getConnection()) {
                // H2 validates shared parameterized UPDATE semantics, not PostgreSQL catalog/fence SQL.
                // Durable predecessor/deletion fences run against all three real provider fixtures.
                patch(connection, operation);
            }
        }
    }
    static QueuedWriteOperation operation(OperationType type, long version, String value) {
        var columns = new LinkedHashMap<String, String>();
        columns.put("id", "1"); columns.put("value_text", value); columns.put("entity_version", Long.toString(version));
        return new QueuedWriteOperation(type, "PatchRow", "patch_rows", "patch_rows", "", "id",
                "entity_version", null, "1", columns, version, Instant.EPOCH);
    }
    @Test void deltaSqlPreservesOtherColumnsNullsAndVersionsAndNeverInsertsMissingRows() throws Exception {
        var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE patch_rows (id VARCHAR(30) PRIMARY KEY, value_text VARCHAR(100), untouched VARCHAR(100), entity_version BIGINT)");
            sql.execute("INSERT INTO patch_rows VALUES ('1','initial','unchanged',1)");
            var flusher = new Flusher(source); flusher.flush(operation(OperationType.PATCH, 2, "__NULL__"));
            try (var row = sql.executeQuery("SELECT value_text, untouched, entity_version FROM patch_rows")) {
                assertTrue(row.next()); assertEquals("__NULL__", row.getString(1)); assertEquals("unchanged", row.getString(2)); assertEquals(2, row.getLong(3));
            }
            flusher.flush(operation(OperationType.PATCH, 3, null));
            try (var row = sql.executeQuery("SELECT value_text FROM patch_rows")) { assertTrue(row.next()); assertNull(row.getString(1)); }
            flusher.flush(operation(OperationType.PATCH, 2, "stale"));
            try (var row = sql.executeQuery("SELECT value_text,entity_version FROM patch_rows")) { assertTrue(row.next()); assertNull(row.getString(1)); assertEquals(3, row.getLong(2)); }
            sql.executeUpdate("DELETE FROM patch_rows WHERE id='1'");
            assertThrows(SQLException.class, () -> flusher.flush(operation(OperationType.PATCH, 4, "revived")));
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM patch_rows")) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
        }
    }
}
