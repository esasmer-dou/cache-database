package com.reactor.cachedb.core.model;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.time.Instant;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class WriteOperationCoalescingTest {
    private EntityMetadata<String, Long> metadata(boolean versioned) {
        return new EntityMetadata<>() {
            public String entityName() { return "Row"; }
            public String tableName() { return "rows"; }
            public String redisNamespace() { return "rows"; }
            public String idColumn() { return "id"; }
            public String versionColumn() { return versioned ? "entity_version" : null; }
            public Class<String> entityType() { return String.class; }
            public Function<String, Long> idAccessor() { return Long::valueOf; }
            public List<String> columns() { return List.of("a", "b"); }
            public List<RelationDefinition> relations() { return List.of(); }
        };
    }
    private WriteOperation<String, Long> operation(EntityMetadata<String, Long> metadata, OperationType type, long version, Map<String, Object> values) {
        return new WriteOperation<>(type, metadata, 1L, values, "payload" + version, version, Instant.EPOCH);
    }
    @Test void unversionedPatchUnionUsesLatestValuesAndFullPayloadWithoutReorderingColumns() {
        var metadata = metadata(false);
        var a = operation(metadata, OperationType.PATCH, 1, Map.of("a", 1));
        var values = new LinkedHashMap<String, Object>(); values.put("a", 2); values.put("b", null);
        var result = a.coalescePatch(operation(metadata, OperationType.PATCH, 2, values)).orElseThrow();
        assertEquals(List.of("a", "b"), List.copyOf(result.columns().keySet()));
        assertEquals(2, result.columns().get("a")); assertNull(result.columns().get("b"));
        assertEquals("payload2", result.redisPayload()); assertEquals(2, result.version());
        assertEquals(OperationType.UPSERT, operation(metadata, OperationType.UPSERT, 1, Map.of("a", 1))
                .coalescePatch(operation(metadata, OperationType.PATCH, 2, values)).orElseThrow().type());
    }
    @Test void neverCoalescesVersionedOrDeletedPredecessors() {
        var versioned = metadata(true);
        assertTrue(operation(versioned, OperationType.PATCH, 1, Map.of("a", 1))
                .coalescePatch(operation(versioned, OperationType.PATCH, 2, Map.of("b", 2))).isEmpty());
        var plain = metadata(false);
        assertTrue(operation(plain, OperationType.DELETE, 1, Map.of())
                .coalescePatch(operation(plain, OperationType.PATCH, 2, Map.of("b", 2))).isEmpty());
        var dependent = new WriteOperation<>(OperationType.PATCH, plain, 1L, Map.<String, Object>of("b", 2),
                "payload2", 2, Instant.EPOCH, new WriteDependency("parents", "1", 1));
        assertTrue(operation(plain, OperationType.PATCH, 1, Map.of("a", 1)).coalescePatch(dependent).isEmpty());
    }
}
