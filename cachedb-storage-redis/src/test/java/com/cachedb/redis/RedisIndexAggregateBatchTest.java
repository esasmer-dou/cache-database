package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
class RedisIndexAggregateBatchTest {
    @Test void oneCallSelectsLiveRawDecimalsAndCountsNullFieldsWithoutPayloads() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri())) {
            String ns = "aggregate-" + UUID.randomUUID();
            var keys = new RedisKeyStrategy(ns);
            var client = JedisPooledOperations.of(redis);
            var entities = List.of(keys.entityKey("row",1),keys.entityKey("row",2),keys.entityKey("row",3));
            var deleted = List.of(keys.tombstoneKey("row",1),keys.tombstoneKey("row",2),keys.tombstoneKey("row",3));
            var meta = List.of(keys.indexMetaKey("row",1),keys.indexMetaKey("row",2),keys.indexMetaKey("row",3));
            for (String entity : entities) redis.set(entity,"not-decoded");
            redis.hset(meta.get(0),Map.of("amount","999999999999999999.125"));
            redis.hset(meta.get(1),Map.of("amount","__NULL__"));
            redis.hset(meta.get(2),Map.of("amount","4.5")); redis.set(deleted.get(2),"1");
            assertEquals(2,client.aggregateIndexBatch(entities,deleted,meta,null,true).orElseThrow().count());
            assertEquals(1,client.aggregateIndexBatch(entities,deleted,meta,"amount",true).orElseThrow().count());
            var result = client.aggregateIndexBatch(entities,deleted,meta,"amount",false).orElseThrow();
            assertEquals(2,result.count()); assertEquals(List.of("999999999999999999.125"),result.values());
            redis.del(entities.get(0));
            assertEquals(1,client.aggregateIndexBatch(entities,deleted,meta,null,true).orElseThrow().count());
            assertThrows(RuntimeException.class,() -> client.boundedMget(entities,1));
            assertEquals(java.util.Arrays.asList(null,"not-decoded","not-decoded"),client.boundedMget(entities,1024));
            RedisClientOperations crossSlot = new RedisClientOperations() {
                public redis.clients.jedis.UnifiedJedis commands() { return redis; }
                public boolean clustered() { return true; }
            };
            assertTrue(crossSlot.aggregateIndexBatch(entities,deleted,meta,"amount",false).isEmpty());
        }
    }
}
