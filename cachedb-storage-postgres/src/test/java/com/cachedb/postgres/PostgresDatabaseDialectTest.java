package com.reactor.cachedb.postgres;

import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresDatabaseDialectTest {

    private final PostgresDatabaseDialect dialect = new PostgresDatabaseDialect();

    @Test
    void shouldGenerateVersionGuardedUpsert() {
        QueuedWriteOperation operation = operation("1", 3);
        List<Map.Entry<String, String>> entries = List.copyOf(operation.columns().entrySet());

        String sql = dialect.upsertSql(operation, entries);

        assertTrue(sql.contains("ON CONFLICT (id) DO UPDATE"));
        assertTrue(sql.contains("EXCLUDED.entity_version > demo_table.entity_version"));
    }

    @Test
    void shouldLimitRowsByPostgresParameterLimit() {
        assertEquals(21_845, dialect.safeStatementRowLimit(100_000, 3));
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
