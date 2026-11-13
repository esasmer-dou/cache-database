package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.query.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;
import redis.clients.jedis.executors.CommandExecutor;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis", matches="true")
class RedisQueryIndexDiffRedisTest {
    @Test void compositeSetResolvesWithoutIntersectionAndMovesOnMutation() {
        EntityMetadata<Row,Long> metadata = new EntityMetadata<>() {
            public String entityName() { return METADATA.entityName(); }
            public String tableName() { return METADATA.tableName(); }
            public String redisNamespace() { return METADATA.redisNamespace(); }
            public String idColumn() { return "id"; }
            public Class<Row> entityType() { return Row.class; }
            public Function<Row,Long> idAccessor() { return Row::id; }
            public List<String> columns() { return METADATA.columns(); }
            public Map<String,String> columnTypes() { return METADATA.columnTypes(); }
            public Map<String,List<String>> compositeExactIndexes() { return Map.of("tenant_name",List.of("tenant_id","name")); }
            public List<RelationDefinition> relations() { return List.of(); }
        };
        try (var redis = new JedisPooled(RedisTestEndpoint.uri())) {
            var keys = new RedisKeyStrategy("composite-" + UUID.randomUUID());
            var scoped = new RecordingOperations(redis);
            var manager = new RedisQueryIndexManager<>(scoped,metadata,CODEC,keys,null,QueryIndexConfig.defaults(),RelationConfig.defaults(),new QueryEvaluator(),null);
            manager.reindex(new Row(1L,7L,"a|b:c Ş",10L));
            scoped.reset();
            assertEquals(List.of("1"),manager.resolveCandidateIds(QuerySpec.allOf(QueryFilter.eq("tenant_id",7L),QueryFilter.eq("name","a|b:c Ş"))));
            assertEquals(0,scoped.intersections);
            assertEquals(0,scoped.calls("SINTER"));
            assertEquals(0,scoped.scriptsCalling("SINTER"));
            manager.reindex(new Row(1L,8L,"new",10L));
            assertTrue(manager.resolveCandidateIds(QuerySpec.allOf(QueryFilter.eq("tenant_id",7L),QueryFilter.eq("name","a|b:c Ş"))).isEmpty());
            assertEquals(List.of("1"),manager.resolveCandidateIds(QuerySpec.allOf(QueryFilter.eq("tenant_id",8L),QueryFilter.eq("name","new"))));
            manager.removeById(1L);
            assertTrue(manager.resolveCandidateIds(QuerySpec.allOf(QueryFilter.eq("tenant_id",8L),QueryFilter.eq("name","new"))).isEmpty());
            assertNotEquals(RedisCompositeIndexes.key(keys,"rows","pair",List.of("a","b"),Map.of("a","x|y","b","z")),
                    RedisCompositeIndexes.key(keys,"rows","pair",List.of("a","b"),Map.of("a","x","b","y|z")));
            assertNull(RedisCompositeIndexes.key(keys,"rows","pair",List.of("a","b"),Map.of("a","x")));
        }
    }
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
            var counted = new RecordingOperations(redis);
            var config = QueryIndexConfig.builder().prefixIndexEnabled(true).textIndexEnabled(true).build();
            var manager = new RedisQueryIndexManager<>(counted,METADATA,CODEC,keys,null,config,
                    RelationConfig.defaults(),new QueryEvaluator(),null);
            Row row = new Row(1L,7L,"Istanbul stock",10L);
            manager.reindexBatch(List.of(row));
            assertEquals(2,counted.pipelines.size());
            assertEquals(2,counted.syncs);
            String meta=keys.indexMetaKey("rows",1L);
            String exact=keys.indexExactKey("rows","name",RedisIndexKeySpace.encodeKeyPart("Istanbul stock"));
            String partition=keys.indexPartitionSortKey("rows","tenant_id",RedisIndexKeySpace.encodeKeyPart("7"),"score");
            var beforeMeta=redis.hgetAll(meta);
            assertFalse(beforeMeta.isEmpty());
            assertEquals(Set.of("1"),redis.smembers(keys.indexAllKey("rows")));
            assertEquals(Set.of("1"),redis.smembers(exact));
            assertEquals(10d,redis.zscore(keys.indexSortKey("rows","score"),"1"));
            assertEquals(10d,redis.zscore(partition,"1"));
            counted.reset();
            manager.reindexBatch(List.of(row,row));
            assertEquals(1,counted.pipelines.size());
            assertEquals(1,counted.syncs);
            assertTrue(counted.direct.isEmpty(),"Unchanged rows must not issue direct commands");
            assertEquals(List.of(List.of("HGETALL",meta)),counted.pipelines.getFirst(),
                    "Deduplicate IDs and submit exactly one metadata read, with zero index writes");
            assertEquals(beforeMeta,redis.hgetAll(meta));
            assertEquals(Set.of("1"),redis.smembers(keys.indexAllKey("rows")));
            assertEquals(Set.of("1"),redis.smembers(exact));
            assertEquals(10d,redis.zscore(keys.indexSortKey("rows","score"),"1"));
            assertEquals(10d,redis.zscore(partition,"1"));
            counted.reset();
            manager.reindex(new Row(1L,7L,"new stock",10L));
            assertEquals(0,counted.calls("ZADD"),"A text-only change must not rewrite sorted scores");
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
            var client = new RecordingOperations(redis);
            var manager = new RedisQueryIndexManager<>(client, METADATA, CODEC, keys, null,
                    QueryIndexConfig.defaults(), RelationConfig.defaults(), new QueryEvaluator(), null);
            manager.reindexBatch(List.of(new Row(1L, 7L, "one", 1L), new Row(2L, 7L, "two", 2L), new Row(3L, 7L, "three", 3L)));
            client.reset();
            assertTrue(manager.resolveCandidateIds(QuerySpec.allOf(QueryFilter.eq("id", 999L), QueryFilter.eq("tenant_id", 7L))).isEmpty());
            assertTrue(client.membershipKeys.stream().mapToInt(List::size).sum() <= 1,
                    "Empty first candidate must not materialize later indexes");
            assertTrue(client.setReadKeys().size() <= 1,
                    "Direct and Lua command plans must not fetch later memberships either");
            client.reset();
            assertEquals(List.of("1"), manager.resolveCandidateIds(QuerySpec.allOf(QueryFilter.eq("id", 1L), QueryFilter.eq("tenant_id", 7L))));
            assertEquals(1,client.probes);
            client.reset();
            var values = new ArrayList<Long>(); for (long i = 1; i <= 20; i++) values.add(i);
            assertEquals(Set.of("1", "2", "3"), Set.copyOf(manager.resolveCandidateIds(QuerySpec.where(QueryFilter.in("score", values)))));
            assertEquals(1,client.membershipKeys.size());
            assertEquals(1,client.scriptsCalling("SMEMBERS"));
            assertEquals(0,client.calls("SMEMBERS"),"IN must not degrade into direct per-value set reads");
            assertTrue(client.pipelines.isEmpty(),"Same-slot IN must use the single bounded server request");
        }
    }

    @Test void largerSameSlotExactPredicatesIntersectOnServerWithBoundedReply() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri())) {
            var keys = new RedisKeyStrategy("sinter-index-" + UUID.randomUUID());
            var client = new RecordingOperations(redis);
            var manager = new RedisQueryIndexManager<>(client, METADATA, CODEC, keys, null,
                    QueryIndexConfig.defaults(), RelationConfig.defaults(), new QueryEvaluator(), null);
            var rows = new ArrayList<Row>(); for (long i = 1; i <= 200; i++) rows.add(new Row(i, 7L, "shared", i));
            manager.reindexBatch(rows);
            client.reset();
            assertEquals(200, manager.resolveCandidateIds(QuerySpec.allOf(QueryFilter.eq("tenant_id", 7L), QueryFilter.eq("name", "shared"))).size());
            assertEquals(1,client.intersections);
            assertEquals(1,client.scriptsCalling("SINTER"),"Submit exactly one real bounded server-intersection plan");
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
    /** Observe only this manager's commands; every response still comes from its real Redis transport. */
    private static final class RecordingOperations implements RedisClientOperations {
        final JedisPooled redis;
        final UnifiedJedis commands;
        final List<List<String>> direct=new ArrayList<>();
        final List<List<List<String>>> pipelines=new ArrayList<>();
        final List<List<String>> membershipKeys=new ArrayList<>();
        int syncs,probes,intersections;

        RecordingOperations(JedisPooled redis){
            this.redis=redis;
            commands=new UnifiedJedis(new CommandExecutor(){
                public <T> T executeCommand(CommandObject<T> command){
                    direct.add(arguments(command));
                    return redis.executeCommand(command);
                }
                public void close(){} // The enclosing test owns the real client.
            });
        }
        public UnifiedJedis commands(){return commands;}
        public AbstractPipeline pipeline(){
            Pipeline real=redis.pipelined();
            var plan=new ArrayList<List<String>>();
            pipelines.add(plan);
            return new AbstractPipeline(new CommandObjects()){
                protected <T> Response<T> appendCommand(CommandObject<T> command){
                    plan.add(arguments(command));
                    return real.appendCommand(command);
                }
                public void sync(){syncs++;real.sync();}
                public void close(){real.close();}
            };
        }
        public Map<String,Set<String>> smembersBatch(Collection<String> names,int maximum){
            membershipKeys.add(List.copyOf(new LinkedHashSet<>(names)));
            return RedisClientOperations.super.smembersBatch(names,maximum);
        }
        public Set<String> probeMembers(String key,Collection<String> candidates){
            probes++;
            return RedisClientOperations.super.probeMembers(key,candidates);
        }
        public Optional<Set<String>> intersectSets(List<String> keys,int maximum){
            intersections++;
            return RedisClientOperations.super.intersectSets(keys,maximum);
        }
        List<List<String>> allCommands(){
            var result=new ArrayList<>(direct);
            for(var plan:pipelines)result.addAll(plan);
            return result;
        }
        long calls(String command){
            return allCommands().stream().filter(args->command.equals(args.getFirst())).count();
        }
        long scriptsCalling(String command){
            // This counts submitted Lua plans, not server-internal commands or fabricated round trips.
            return allCommands().stream().filter(args->args.size()>1&&"EVAL".equals(args.getFirst())
                    &&args.get(1).contains("redis.call('"+command+"'")).count();
        }
        List<String> setReadKeys(){
            var keys=new ArrayList<String>();
            for(var args:allCommands()){
                if("SMEMBERS".equals(args.getFirst()))keys.add(args.get(1));
                else if(args.size()>2&&"EVAL".equals(args.getFirst())&&args.get(1).contains("redis.call('SMEMBERS'")){
                    int count=Integer.parseInt(args.get(2));
                    keys.addAll(args.subList(3,3+count));
                }
            }
            return keys;
        }
        void reset(){direct.clear();pipelines.clear();membershipKeys.clear();syncs=0;probes=0;intersections=0;}
        private static List<String> arguments(CommandObject<?> command){
            var values=new ArrayList<String>();
            for(var argument:command.getArguments())values.add(new String(argument.getRaw(),StandardCharsets.UTF_8));
            return List.copyOf(values);
        }
        public void close(){} // Borrowed transport; never close the enclosing test's client.
    }
}
