package com.reactor.cachedb.oracle;

import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleDatabaseDialectTest {
    private final OracleDatabaseDialect dialect = new OracleDatabaseDialect();

    @Test
    void shouldGenerateVersionGuardedSingleRowMerge() {
        QueuedWriteOperation operation = operation(OperationType.UPSERT, 7);
        List<Map.Entry<String, String>> entries = List.copyOf(operation.columns().entrySet());

        String sql = dialect.upsertSql(operation, entries);

        assertTrue(sql.startsWith("MERGE INTO cachedb_it_entity target"));
        assertTrue(sql.contains("USING (SELECT ? id, ? name, ? entity_version FROM dual) source"));
        assertTrue(sql.contains("source.entity_version > target.entity_version"));
        assertTrue(sql.contains("WHEN NOT MATCHED THEN INSERT"));
        assertThrows(UnsupportedOperationException.class, () -> dialect.upsertMultiRowSql(operation, entries, 2));
    }

    @Test
    void shouldGenerateVersionGuardedDeleteAndOracleTypes() {
        String sql = dialect.deleteSql(operation(OperationType.DELETE, 8));

        assertTrue(sql.contains("entity_version IS NULL OR entity_version <= ?"));
        assertEquals("NUMBER(1)", dialect.sqlCastType("java.lang.Boolean"));
        assertEquals("NUMBER(38,0)", dialect.sqlCastType("java.math.BigInteger"));
        assertEquals("TIMESTAMP WITH TIME ZONE", dialect.sqlCastType("java.time.Instant"));
        assertEquals("VARCHAR2(4000 CHAR)", dialect.sqlCastType("java.lang.String"));
    }

    private static QueuedWriteOperation operation(OperationType type, long version) {
        LinkedHashMap<String, String> columns = new LinkedHashMap<>();
        columns.put("id", "1");
        columns.put("name", "entity-" + version);
        columns.put("entity_version", String.valueOf(version));
        return new QueuedWriteOperation(
                type, "DemoEntity", "cachedb_it_entity", "demo", "write",
                "id", "entity_version", "deleted", "1", columns, version,
                Instant.parse("2026-04-05T13:00:00Z")
        );
    }
}
