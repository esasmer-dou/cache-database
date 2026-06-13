package com.reactor.cachedb.mssql;

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

class MssqlDatabaseDialectTest {

    private final MssqlDatabaseDialect dialect = new MssqlDatabaseDialect();

    @Test
    void shouldGenerateUpdateExistsInsertStatementsWithoutMerge() {
        QueuedWriteOperation operation = operation("1", 3);
        List<Map.Entry<String, String>> entries = List.copyOf(operation.columns().entrySet());

        assertTrue(dialect.updateSql(operation, entries).startsWith("UPDATE demo_table WITH (UPDLOCK, HOLDLOCK)"));
        assertTrue(dialect.existsSql(operation).contains("SELECT 1 FROM demo_table WITH (UPDLOCK, HOLDLOCK)"));
        assertTrue(dialect.insertSql(operation, entries).startsWith("INSERT INTO demo_table"));
        assertThrows(UnsupportedOperationException.class, () -> dialect.upsertMultiRowSql(operation, entries, 2));
    }

    @Test
    void shouldRespectSqlServerParameterLimit() {
        assertEquals(700, dialect.safeStatementRowLimit(1_000, 3));
        assertEquals(64, dialect.safeStatementRowLimit(64, 3));
    }

    private static QueuedWriteOperation operation(String id, long version) {
        LinkedHashMap<String, String> columns = new LinkedHashMap<>();
        columns.put("id", id);
        columns.put("name", "entity-" + version);
        columns.put("entity_version", String.valueOf(version));
        return new QueuedWriteOperation(
                OperationType.UPSERT,
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
}
