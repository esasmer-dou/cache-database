package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.query.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class RedisWatchRegistryTest {
    record Row(Long id, String status) {}
    private static final EntityMetadata<Row, Long> METADATA = new EntityMetadata<>() {
        public String entityName() { return "WatchRow"; }
        public String tableName() { return "watch_rows"; }
        public String redisNamespace() { return "watch_rows"; }
        public String idColumn() { return "id"; }
        public Class<Row> entityType() { return Row.class; }
        public Function<Row, Long> idAccessor() { return Row::id; }
        public List<String> columns() { return List.of("id", "status"); }
        public List<RelationDefinition> relations() { return List.of(); }
    };
    private static final EntityCodec<Row> CODEC = new EntityCodec<>() {
        public String toRedisValue(Row row) { return row.status(); }
        public Row fromRedisValue(String body) { return new Row(1L, body); }
        public Map<String, Object> toColumns(Row row) { return Map.of("id", row.id(), "status", row.status()); }
    };

    @Test void listenerFailureDoesNotBreakWriteOrFollowingListenersAndCapacityIsBounded() throws Exception {
        var registry = new RedisWatchRegistry<>(METADATA, CODEC, new QueryEvaluator(), 2);
        var delivered = new CountDownLatch(1);
        try (var failing = registry.register(QuerySpec.allOf(), event -> { throw new IllegalStateException("Expected test callback failure"); });
                var healthy = registry.register(QuerySpec.allOf(), event -> delivered.countDown())) {
            assertThrows(IllegalStateException.class, () -> registry.register(QuerySpec.allOf(), event -> {}));
            assertDoesNotThrow(() -> registry.changed(1L, null, "OPEN"));
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
        }
        assertFalse(registry.active());
    }

    @Test void oversizedNotificationsAreDroppedBeforeDecodeAndCounted() throws Exception {
        var registry = new RedisWatchRegistry<>(METADATA, CODEC, new QueryEvaluator(), 1);
        var delivered = new CountDownLatch(1);
        try (var registration = registry.register(QuerySpec.allOf(), event -> delivered.countDown())) {
            registry.changed(1L, null, "x".repeat(256 * 1024));
            assertEquals(1, registry.dropped());
            assertFalse(delivered.await(30, TimeUnit.MILLISECONDS));
        }
    }
}
