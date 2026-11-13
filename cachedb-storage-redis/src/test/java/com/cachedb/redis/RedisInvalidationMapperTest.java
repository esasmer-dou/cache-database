package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.*;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class RedisInvalidationMapperTest {
    record Row(Long id, String tenant) {}
    static final EntityMetadata<Row, Long> META = new EntityMetadata<>() {
        public String entityName() { return "MapperRow"; }
        public String tableName() { return "mapper_rows"; }
        public String redisNamespace() { return "mapper_rows"; }
        public String idColumn() { return "id"; }
        public Class<Row> entityType() { return Row.class; }
        public Function<Row, Long> idAccessor() { return Row::id; }
        public List<String> columns() { return List.of("id", "tenant"); }
        public String tenantFieldName() { return "tenant"; }
        public String extractTenantId(Row row) { return row.tenant(); }
        public List<RelationDefinition> relations() { return List.of(); }
    };
    static WriteOperation<Row, Long> operation(OperationType type) {
        return new WriteOperation<>(type, META, 7L, Map.of("id", 7L, "tenant", "acme"), "payload", 0, Instant.EPOCH);
    }
    static CachePolicy policy() {
        return CachePolicy.builder().nearCachePolicy(new NearCachePolicy(16, 10, 65536)).build();
    }
    @Test void dynamicUpsertDecisionPreservesTenantTrailerAndChecksSupplierOnlyOnce() {
        var enabled = new AtomicBoolean(true); var calls = new AtomicInteger();
        var mapper = new RedisFunctionArgsMapper().withNearInvalidationChannel("test:channel")
                .withNearInvalidationPublisher(() -> { calls.incrementAndGet(); return enabled.get(); });
        try (var scope = CacheTenantContext.withTenant("acme")) {
            for (boolean publish : new boolean[]{true, false, true}) {
                enabled.set(publish);
                var args = mapper.upsertArgs(operation(OperationType.UPSERT), policy(), RedisGuardrailConfig.defaults(), true);
                int trailer = 20 + 2 * Integer.parseInt(args.get(19));
                assertEquals(publish ? "test:channel" : "", args.get(trailer + 5));
                assertEquals(publish ? RedisNearCacheCoordinator.message("mapper_rows", 7L) : "", args.get(trailer + 6));
                assertEquals("cachedb:tenant:v1", args.get(trailer + 7));
                assertEquals("acme", args.get(trailer + 8));
            }
            assertEquals(3, calls.get());
        }
    }
    @Test void dynamicDeleteDecisionPreservesFixedTenantSlots() {
        var enabled = new AtomicBoolean(true);
        var mapper = new RedisFunctionArgsMapper().withNearInvalidationChannel("test:channel")
                .withNearInvalidationPublisher(enabled::get);
        try (var scope = CacheTenantContext.withTenant("acme")) {
            for (boolean publish : new boolean[]{true, false, true}) {
                enabled.set(publish);
                var args = mapper.deleteArgs(operation(OperationType.DELETE), RedisGuardrailConfig.defaults(),
                        WriteBehindConfig.defaults(), policy());
                assertEquals(publish ? "test:channel" : "", args.get(16));
                assertEquals(publish ? RedisNearCacheCoordinator.message("mapper_rows", 7L) : "", args.get(17));
                assertEquals("cachedb:tenant:v1", args.get(18)); assertEquals("acme", args.get(19));
            }
        }
    }
    @Test void noNearCacheDoesNotEvaluatePublicationOrGrowLegacyWireShape() {
        var mapper = new RedisFunctionArgsMapper().withNearInvalidationPublisher(() -> { throw new AssertionError("Not needed"); });
        // The administrative scope derives the UPSERT owner from columns; tenant slots remain present.
        try (var scope = CacheTenantContext.administrative()) {
            var args = mapper.upsertArgs(operation(OperationType.UPSERT), CachePolicy.defaults(), RedisGuardrailConfig.defaults(), true);
            int trailer = 20 + 2 * Integer.parseInt(args.get(19));
            assertEquals("", args.get(trailer + 5)); assertEquals("", args.get(trailer + 6));
            assertEquals("cachedb:tenant:v1", args.get(trailer + 7)); assertEquals("acme", args.get(trailer + 8));
        }
    }
    @Test void supplierFailureNeverSilentlySuppressesAPublish() {
        var mapper = new RedisFunctionArgsMapper().withNearInvalidationPublisher(() -> { throw new IllegalStateException("health unavailable"); });
        try (var scope = CacheTenantContext.withTenant("acme")) {
            assertThrows(IllegalStateException.class, () -> mapper.upsertArgs(operation(OperationType.UPSERT), policy(), RedisGuardrailConfig.defaults(), true));
            assertThrows(IllegalStateException.class, () -> mapper.deleteArgs(operation(OperationType.DELETE), RedisGuardrailConfig.defaults(), WriteBehindConfig.defaults(), policy()));
        }
    }
}
