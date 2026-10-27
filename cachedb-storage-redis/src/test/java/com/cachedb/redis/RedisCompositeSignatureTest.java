package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.query.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;

class RedisCompositeSignatureTest {
    record Row(Long id, String tenant, String status) {}
    static EntityMetadata<Row, Long> metadata(Map<String, List<String>> indexes) {
        return new EntityMetadata<>() {
            public String entityName() { return "SignatureRow"; }
            public String tableName() { return "rows"; }
            public String redisNamespace() { return "rows"; }
            public String idColumn() { return "id"; }
            public Class<Row> entityType() { return Row.class; }
            public Function<Row,Long> idAccessor() { return Row::id; }
            public List<String> columns() { return List.of("id","tenant","status"); }
            public Map<String,String> columnTypes() { return Map.of("id","java.lang.Long","tenant","java.lang.String","status","java.lang.String"); }
            public List<RelationDefinition> relations() { return List.of(); }
            public Map<String,List<String>> compositeExactIndexes() { return indexes; }
        };
    }
    private static final EntityCodec<Row> CODEC = new EntityCodec<>() {
        public String toRedisValue(Row row) { return row.id() + "|" + row.tenant() + "|" + row.status(); }
        public Row fromRedisValue(String value) { var fields = value.split("\\|"); return new Row(Long.valueOf(fields[0]),fields[1],fields[2]); }
        public Map<String,Object> toColumns(Row row) { return Map.of("id",row.id(),"tenant",row.tenant(),"status",row.status()); }
    };

    @Test void signaturesAreDeterministicAndIncludeColumnOrderAndEmptyDeclarations() {
        var first = new LinkedHashMap<String,List<String>>();
        first.put("z",List.of("tenant","status")); first.put("a",List.of("status","tenant"));
        var second = new LinkedHashMap<String,List<String>>();
        second.put("a",first.get("a")); second.put("z",first.get("z"));
        assertEquals("v1|a=status,tenant;z=tenant,status",RedisCompositeIndexes.signature(metadata(first)));
        assertEquals(RedisCompositeIndexes.signature(metadata(first)),RedisCompositeIndexes.signature(metadata(second)));
        assertEquals("v1|",RedisCompositeIndexes.signature(metadata(Map.of())));
        assertThrows(IllegalArgumentException.class,() -> RedisCompositeIndexes.signature(metadata(Map.of("large",List.of("x".repeat(65536),"status")))));
    }

    @Test @EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
    void existingNamespacesUseCompleteFallbackUntilRebuildAndDetectChangedDeclarations() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri())) {
            var keys = new RedisKeyStrategy("signature-" + UUID.randomUUID());
            var readSets = new ArrayList<String>();
            RedisClientOperations counted = new RedisClientOperations() {
                public UnifiedJedis commands() { return redis; }
                public Map<String,Set<String>> smembersBatch(Collection<String> names, int maximum) {
                    readSets.addAll(names); return RedisClientOperations.super.smembersBatch(names,maximum);
                }
            };
            var legacy = manager(counted, keys, Map.of());
            for (long id = 1; id <= 4; id++) {
                var row = new Row(id,"shop-a","OPEN");
                redis.set(keys.entityKey("rows",id),CODEC.toRedisValue(row)); legacy.reindex(row);
            }
            var indexes = Map.of("tenant_status",List.of("tenant","status"));
            var current = manager(counted,keys,indexes);
            var query = QuerySpec.allOf(QueryFilter.eq("tenant","shop-a"),QueryFilter.eq("status","OPEN"));
            String composite = RedisCompositeIndexes.key(keys,"rows","tenant_status",indexes.get("tenant_status"),Map.of("tenant","shop-a","status","OPEN"));
            assertEquals(RedisQueryIndexManager.CompositeIndexState.PENDING_REBUILD,current.compositeIndexState());
            assertFalse(redis.exists(composite)); readSets.clear();
            assertEquals(Set.of("1","2","3","4"),new HashSet<>(current.resolveCandidateIds(query)));
            assertFalse(readSets.contains(composite));
            current.reindex(new Row(1L,"shop-a","OPEN"));
            assertEquals(1,redis.scard(composite));
            assertEquals(4,current.resolveCandidateIds(query).size());
            current.rebuildFromEntityStore(false,"signature-test");
            assertEquals(RedisQueryIndexManager.CompositeIndexState.VERIFIED,current.compositeIndexState());
            assertEquals(RedisCompositeIndexes.signature(metadata(indexes)),redis.get(keys.compositeIndexSignatureKey("rows")));
            readSets.clear(); assertEquals(4,current.resolveCandidateIds(query).size());
            assertEquals(List.of(composite),readSets);
            var changed = manager(counted,keys,Map.of("tenant_status",List.of("status","tenant")));
            assertEquals(RedisQueryIndexManager.CompositeIndexState.PENDING_REBUILD,changed.compositeIndexState());
            assertEquals(4,changed.resolveCandidateIds(query).size());
            changed.rebuildFromEntityStore(false,"changed-columns");
            assertFalse(redis.exists(composite));
            assertEquals(RedisQueryIndexManager.CompositeIndexState.VERIFIED,changed.compositeIndexState());
        }
    }

    @Test @EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
    void emptyNamespaceInitializesSignatureAtomically() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri())) {
            var keys = new RedisKeyStrategy("signature-empty-" + UUID.randomUUID());
            var indexes = Map.of("tenant_status",List.of("tenant","status"));
            var manager = manager(JedisPooledOperations.of(redis),keys,indexes);
            assertEquals(RedisQueryIndexManager.CompositeIndexState.VERIFIED,manager.compositeIndexState());
            assertEquals(RedisCompositeIndexes.signature(metadata(indexes)),redis.get(keys.compositeIndexSignatureKey("rows")));
        }
    }

    private static RedisQueryIndexManager<Row, Long> manager(RedisClientOperations redis, RedisKeyStrategy keys, Map<String,List<String>> indexes) {
        return new RedisQueryIndexManager<>(redis,metadata(indexes),CODEC,keys,null,QueryIndexConfig.defaults(),RelationConfig.defaults(),new QueryEvaluator(),null);
    }
}
