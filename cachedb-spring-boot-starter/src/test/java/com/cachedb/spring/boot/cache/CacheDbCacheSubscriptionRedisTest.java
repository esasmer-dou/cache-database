package com.reactor.cachedb.spring.boot.cache;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
@Timeout(45)
class CacheDbCacheSubscriptionRedisTest {
    @RepeatedTest(8)
    void reconnectAndCloseNeverReturnPubsubFramesToTheSingleCommandConnection() {
        try (var fixture = CacheDbCacheRedisFixture.standalone()) {
            var commands = (JedisPooled) fixture.underlying.commands();
            var pool = commands.getPool();
            pool.setMaxTotal(1);
            pool.setMaxIdle(1);
            pool.setBlockWhenExhausted(false);
            var manager = fixture.manager(Map.of("cache", CacheDbCacheRedisTest.slowNear(false)));
            CacheDbCacheRedisFixture.await(manager::invalidationConnected);
            assertEquals(0, pool.getNumActive(), "The subscriber must not reserve a shared command connection");
            var cache = manager.getCache("cache");
            cache.put("key", "before");
            assertEquals("before", cache.get("key", String.class));

            long disconnected = manager.invalidationCoordinator().disconnectCount();
            manager.invalidationCoordinator().redisFailure();
            CacheDbCacheRedisFixture.await(() -> manager.invalidationCoordinator().disconnectCount() > disconnected);
            CacheDbCacheRedisFixture.await(manager::invalidationConnected);
            assertEquals(0, pool.getNumActive());
            cache.put("key", "after");
            assertEquals("after", cache.get("key", String.class));
            for (int i = 0; i < 64; i++)
                commands.publish(manager.invalidationCoordinator().channel(), "invalid-fixture-event");
            manager.close();
            assertFalse(manager.invalidationConnected());
            assertEquals(0, pool.getNumActive());

            String marker = fixture.prefix + ":scache:command-probe";
            fixture.ownedKeys.add(marker);
            for (int i = 0; i < 32; i++) {
                assertEquals("OK", commands.set(marker, "value"));
                assertEquals("value", commands.get(marker));
                assertEquals(1L, commands.unlink(marker), "An integer reply must never be a pub/sub frame");
            }
        }
    }
}
