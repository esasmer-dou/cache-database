package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.cache.CachePolicy;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis", matches="true")
class RedisHotSetBatchTest {
    @Test void recordsOneBoundedBatchAndKeepsLruCapacity() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri())) {
            var keys = new RedisKeyStrategy("hot-test-"+UUID.randomUUID());
            var hot = new RedisHotSetManager(redis, keys);
            var policy = CachePolicy.builder().hotEntityLimit(2).lruEvictionEnabled(true).build();
            String key = keys.hotSetKey("orders");
            redis.zadd(key,1,"old");
            assertEquals(List.of("old"), hot.recordAccessBatch("orders",List.of("a","b","a"),policy));
            assertEquals(Set.of("a","b"), new HashSet<>(redis.zrange(key,0,-1)));
            assertTrue(hot.recordAccessBatch("orders",List.of("a","b"),policy).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> hot.recordAccessBatch("orders",Collections.nCopies(10001,"a"),policy));
            assertEquals(2,redis.zcard(key));
            redis.unlink(key);
        }
    }
}
