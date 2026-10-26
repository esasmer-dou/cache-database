package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.query.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis", matches="true")
class RedisQueryIndexDiffRedisTest {
    record Row(Long id, Long tenant, String name, Long score) {}
    private static final EntityMetadata<Row,Long> METADATA = new EntityMetadata<>() {
        public String entityName() { return "Row"; }
        public String tableName() { return "rows"; }
        public String redisNamespace() { return "rows"; }
        public String idColumn() { return "id"; }
        public Class<Row> entityType() { return Row.class; }
        public Function<Row,Long> idAccessor() { return Row::id; }
        public List<String> columns() { return List.of("id","tenant_id","name","score"); }
        public Map<String,String> columnTypes() { return Map.of("id","java.lang.Long","tenant_id","java.lang.Long","name","java.lang.String","score","java.lang.Long"); }
        public Map<String,List<String>> partitionedSortIndexes() { return Map.of("tenant_id",List.of("score")); }
        public List<RelationDefinition> relations() { return List.of(); }
    };
    private static final EntityCodec<Row> CODEC = new EntityCodec<>() {
        public String toRedisValue(Row row) { throw new UnsupportedOperationException(); }
        public Row fromRedisValue(String raw) { throw new UnsupportedOperationException(); }
        public Map<String,Object> toColumns(Row row) {
            Map<String,Object> values = new LinkedHashMap<>();
            values.put("id",row.id()); values.put("tenant_id",row.tenant());
            values.put("name",row.name()); values.put("score",row.score());
            return values;
        }
    };

    @Test void unchangedRowsUseOneReadPipelineAndNoIndexWrites() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri())) {
            var keys = new RedisKeyStrategy("diff-test-"+UUID.randomUUID());
            AtomicInteger pipelines = new AtomicInteger();
            RedisClientOperations counted = new RedisClientOperations() {
                public UnifiedJedis commands() { return redis; }
                public AbstractPipeline pipeline() { pipelines.incrementAndGet(); return redis.pipelined(); }
                public void close() {}
            };
            var config = QueryIndexConfig.builder().prefixIndexEnabled(true).textIndexEnabled(true).build();
            var manager = new RedisQueryIndexManager<>(counted,METADATA,CODEC,keys,null,config,
                    RelationConfig.defaults(),new QueryEvaluator(),null);
            Row row = new Row(1L,7L,"Istanbul stock",10L);
            manager.reindexBatch(List.of(row));
            assertEquals(2,pipelines.get());
            long writes = calls(redis,"hset") + calls(redis,"sadd") + calls(redis,"zadd") + calls(redis,"zrem");
            manager.reindexBatch(List.of(row,row));
            assertEquals(3,pipelines.get());
            assertEquals(writes,calls(redis,"hset") + calls(redis,"sadd") + calls(redis,"zadd") + calls(redis,"zrem"));
            long scoreWrites = calls(redis,"zadd");
            manager.reindex(new Row(1L,7L,"new stock",10L));
            assertEquals(scoreWrites,calls(redis,"zadd"));
            assertTrue(manager.resolveCandidateIds(QuerySpec.where(QueryFilter.eq("name","new stock"))).contains("1"));
            assertTrue(manager.resolveCandidateIds(QuerySpec.where(QueryFilter.eq("name","Istanbul stock"))).isEmpty());
            manager.reindex(new Row(1L,8L,null,20L));
            assertEquals(20d,redis.zscore(keys.indexSortKey("rows","score"),"1"));
            assertNull(redis.zscore(keys.indexPartitionSortKey("rows","tenant_id",RedisIndexKeySpace.encodeKeyPart("7"),"score"),"1"));
            assertEquals(20d,redis.zscore(keys.indexPartitionSortKey("rows","tenant_id",RedisIndexKeySpace.encodeKeyPart("8"),"score"),"1"));
            assertTrue(manager.resolveCandidateIds(QuerySpec.where(QueryFilter.eq("name",null))).contains("1"));
            assertFalse(redis.exists(keys.indexDegradedKey("rows")));
        }
    }
    @Test void lazyAndProbesOnlySmallCandidatesAndInUsesOneBatch() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri())) {
            var keys = new RedisKeyStrategy("lazy-index-" + UUID.randomUUID());
            var probes = new AtomicInteger(); var batches = new AtomicInteger();
            RedisClientOperations client = new RedisClientOperations() {
                public UnifiedJedis commands() { return redis; }
                public Set<String> probeMembers(String key, Collection<String> candidates) {
                    probes.incrementAndGet(); return RedisClientOperations.super.probeMembers(key, candidates);
                }
                public Map<String, Set<String>> smembersBatch(Collection<String> names, int max) {
                    batches.incrementAndGet(); return RedisClientOperations.super.smembersBatch(names, max);
                }
            };
            var manager = new RedisQueryIndexManager<>(client, METADATA, CODEC, keys, null,
                    QueryIndexConfig.defaults(), RelationConfig.defaults(), new QueryEvaluator(), null);
            manager.reindexBatch(List.of(new Row(1L, 7L, "one", 1L), new Row(2L, 7L, "two", 2L), new Row(3L, 7L, "three", 3L)));
            long members = calls(redis, "smembers");
            assertTrue(manager.resolveCandidateIds(QuerySpec.allOf(QueryFilter.eq("id", 999L), QueryFilter.eq("tenant_id", 7L))).isEmpty());
            assertTrue(calls(redis, "smembers") - members <= 1, "Empty first candidate must not materialize later indexes");
            assertEquals(List.of("1"), manager.resolveCandidateIds(QuerySpec.allOf(QueryFilter.eq("id", 1L), QueryFilter.eq("tenant_id", 7L))));
            assertEquals(1, probes.get());
            var values = new ArrayList<Long>(); for (long i = 1; i <= 20; i++) values.add(i);
            assertEquals(Set.of("1", "2", "3"), Set.copyOf(manager.resolveCandidateIds(QuerySpec.where(QueryFilter.in("score", values)))));
            assertEquals(1, batches.get());
        }
    }

    @Test void largerSameSlotExactPredicatesIntersectOnServerWithBoundedReply() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri())) {
            var keys = new RedisKeyStrategy("sinter-index-" + UUID.randomUUID());
            var manager = new RedisQueryIndexManager<>(redis, METADATA, CODEC, keys, null,
                    QueryIndexConfig.defaults(), RelationConfig.defaults(), new QueryEvaluator(), null);
            var rows = new ArrayList<Row>(); for (long i = 1; i <= 200; i++) rows.add(new Row(i, 7L, "shared", i));
            manager.reindexBatch(rows);
            long calls = calls(redis, "sinter");
            assertEquals(200, manager.resolveCandidateIds(QuerySpec.allOf(QueryFilter.eq("tenant_id", 7L), QueryFilter.eq("name", "shared"))).size());
            assertEquals(calls + 1, calls(redis, "sinter"));
        }
    }

    @Test void orderedPayloadFetchSkipsDeletedAndExpiredRowsAndCrossSlotFallsBack() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri())) {
            String prefix = "ordered-" + UUID.randomUUID();
            redis.zadd(prefix + ":sorted", Map.of("1", 1d, "2", 2d, "3", 3d, "4", 4d));
            redis.set(prefix + ":entity:1", "one"); redis.set(prefix + ":entity:2", "two");
            redis.set(prefix + ":entity:3", "deleted"); redis.set(prefix + ":tomb:3", "1");
            var operations = JedisPooledOperations.of(redis);
            assertEquals(List.of("two", "one"), operations.zrangeWithPayloads(prefix + ":sorted", prefix + ":tomb:", prefix + ":entity:", true, 0, 2).orElseThrow());
            RedisClientOperations cluster = new RedisClientOperations() {
                public boolean clustered() { return true; }
                public UnifiedJedis commands() { throw new AssertionError("Cross-slot optimization must not call Redis"); }
            };
            assertTrue(cluster.fetchOrderedPayloads("a:sorted", "b:tomb:", "c:entity:", true, 0, 2).isEmpty());
            redis.sadd(prefix + ":too-large", "1", "2");
            assertThrows(redis.clients.jedis.exceptions.JedisDataException.class, () -> operations.smembersBatch(List.of(prefix + ":too-large"), 1));
        }
    }
    private static long calls(JedisPooled redis,String command) {
        Object response = redis.sendCommand(Protocol.Command.INFO,"commandstats");
        String info = response instanceof byte[] bytes ? new String(bytes,java.nio.charset.StandardCharsets.UTF_8) : String.valueOf(response);
        for (String line : info.split("\n")) {
            if (line.startsWith("cmdstat_"+command+":")) {
                int start = line.indexOf("calls=")+6;
                return Long.parseLong(line.substring(start,line.indexOf(',',start)));
            }
        }
        return 0;
    }
}
