package com.reactor.cachedb.core.testing;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class InMemoryTenantBatchTest {
    record Row(Long id,String tenant,String status) {}
    private static EntityMetadata<Row,Long> metadata() {
        return new EntityMetadata<>() {
            public String entityName() { return "TenantRow"; }
            public String tableName() { return "rows"; }
            public String redisNamespace() { return "rows"; }
            public String idColumn() { return "id"; }
            public String tenantFieldName() { return "tenant"; }
            public String extractTenantId(Row row) { return row.tenant(); }
            public Class<Row> entityType() { return Row.class; }
            public Function<Row,Long> idAccessor() { return Row::id; }
            public List<String> columns() { return List.of("id","tenant","status"); }
            public List<RelationDefinition> relations() { return List.of(); }
        };
    }
    private static EntityCodec<Row> codec(AtomicBoolean fail) {
        return new EntityCodec<>() {
            public String toRedisValue(Row row) {
                if (fail.get() && row.id()==2L) throw new IllegalArgumentException("Injected serialization failure");
                return row.id()+"|"+row.tenant()+"|"+row.status();
            }
            public Row fromRedisValue(String raw) { String[] values=raw.split("\\|",-1); return new Row(Long.valueOf(values[0]),values[1],values[2]); }
            public Map<String,Object> toColumns(Row row) { return Map.of("id",row.id(),"tenant",row.tenant(),"status",row.status()); }
        };
    }
    @Test void rollbackRestoresOwnerMarkersAsWellAsPayloadsAndVersions() {
        var fail = new AtomicBoolean(); var db = new InMemoryCacheDatabase(); var repository = db.repository(metadata(),codec(fail));
        try (var scope = CacheTenantContext.withTenant("a")) {
            var batch=db.writeBatch("rollback").put(new Row(1L,"a","OPEN")).put(new Row(2L,"a","OPEN"));
            fail.set(true);
            assertThrows(IllegalArgumentException.class,batch::commit);
            assertTrue(repository.findById(1L).isEmpty());
        }
        fail.set(false);
        try (var scope = CacheTenantContext.withTenant("b")) {
            assertEquals(1,repository.saveWithReceipt(new Row(1L,"b","OPEN")).version());
        }
    }
    @Test void successCrossTenantDenialAndScopeChangesAreAtomic() {
        var db = new InMemoryCacheDatabase(); var repository = db.repository(metadata(),codec(new AtomicBoolean()));
        com.reactor.cachedb.core.api.CacheWriteBatch pending;
        try (var scope = CacheTenantContext.withTenant("a")) {
            pending=db.writeBatch("scope-bound").put(new Row(1L,"a","OPEN"));
            var receipt=db.writeBatch("accepted").put(new Row(2L,"a","OPEN")).commit();
            assertEquals(List.of(1L),receipt.versions());
            assertThrows(IllegalStateException.class,() -> db.writeBatch().put(new Row(3L,"b","OPEN")));
        }
        assertThrows(IllegalStateException.class,pending::commit);
        try (var scope = CacheTenantContext.withTenant("b")) {
            assertThrows(IllegalStateException.class,pending::commit);
            assertThrows(IllegalStateException.class,() -> db.writeBatch().delete(Row.class,2L));
            assertEquals(1,repository.saveWithReceipt(new Row(1L,"b","OPEN")).version());
        }
    }

    @Test void facetsRequireTenantScopeAndRejectForeignPredicatesWithTheAdvancedApiException() {
        var db = new InMemoryCacheDatabase();
        var repository = db.repository(metadata(), codec(new AtomicBoolean()));
        var count = com.reactor.cachedb.core.query.AggregateSpec.count(null);
        assertThrows(IllegalStateException.class, () -> repository.aggregateBy("status", count));
        try (var scope = CacheTenantContext.withTenant("a")) {
            repository.save(new Row(1L, "a", "OPEN"));
            assertEquals(1, repository.aggregateBy("status", count).get("OPEN").count());
            var foreign = com.reactor.cachedb.core.query.AggregateSpec.count(
                    com.reactor.cachedb.core.query.QueryFilter.eq("tenant", "b"));
            assertThrows(IllegalStateException.class, () -> repository.aggregateBy("status", foreign));
        }
    }
}
