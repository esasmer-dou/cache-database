package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JdbcWriteBehindSupportTest {

    @Test
    void shouldConvertBigDecimalWithoutLosingPrecision() {
        Object converted = JdbcWriteBehindSupport.convertValue("1234567890.1234", "java.math.BigDecimal");

        assertEquals(new BigDecimal("1234567890.1234"), converted);
    }

    @Test
    void shouldPreservePerOperationColumnOrder() {
        QueuedWriteOperation first = operation("1", 1);
        QueuedWriteOperation second = operation("2", 2);
        List<Map.Entry<String, String>> entries = List.copyOf(first.columns().entrySet());

        assertEquals(List.of("1", "entity-1", "1"), JdbcWriteBehindSupport.orderedColumnValues(first, entries));
        assertEquals(List.of("2", "entity-2", "2"), JdbcWriteBehindSupport.orderedColumnValues(second, entries));
    }

    @Test
    void shouldPreserveNullColumnValuesForNullableWriteColumns() {
        QueuedWriteOperation operation = operationWithNullableDeletedColumn();
        List<Map.Entry<String, String>> entries = List.copyOf(operation.columns().entrySet());

        List<String> values = JdbcWriteBehindSupport.orderedColumnValues(operation, entries);

        assertEquals(4, values.size());
        assertEquals("1", values.get(0));
        assertEquals("entity-1", values.get(1));
        assertEquals("1", values.get(2));
        assertNull(values.get(3));
    }

    @Test
    void shouldSplitBatchesBeforeDuplicateEntityIdentity() {
        List<List<QueuedWriteOperation>> partitions = JdbcWriteBehindSupport.partitionDistinctIdentityBatches(List.of(
                operation("1", 1),
                operation("2", 2),
                operation("1", 3),
                operation("3", 4)
        ), 100);

        assertEquals(2, partitions.size());
        assertEquals(List.of("1", "2"), ids(partitions.get(0)));
        assertEquals(List.of("1", "3"), ids(partitions.get(1)));
    }

    private static List<String> ids(List<QueuedWriteOperation> operations) {
        return operations.stream().map(QueuedWriteOperation::id).toList();
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

    private static QueuedWriteOperation operationWithNullableDeletedColumn() {
        LinkedHashMap<String, String> columns = new LinkedHashMap<>();
        columns.put("id", "1");
        columns.put("name", "entity-1");
        columns.put("entity_version", "1");
        columns.put("deleted", null);
        return new QueuedWriteOperation(
                OperationType.UPSERT,
                "DemoEntity",
                "demo_table",
                "demo",
                "write",
                "id",
                "entity_version",
                "deleted",
                "1",
                columns,
                1,
                Instant.parse("2026-04-05T13:00:00Z")
        );
    }
}
