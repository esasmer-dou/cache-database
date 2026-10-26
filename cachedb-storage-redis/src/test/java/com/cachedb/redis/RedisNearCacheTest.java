package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.NearCachePolicy;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;

class RedisNearCacheTest {
    @Test void evictionFollowsRecentAccessInsteadOfInsertionAndKeepsDecodedReference() {
        var cache = new RedisNearCacheCoordinator.Store(new NearCachePolicy(32, 10, 1_048_576));
        // All three identities hash to the same segment, whose capacity is two.
        String a = "Aa", b = "BB", c = "C#";
        Object decoded = new Object();
        cache.put(a, "first", decoded, -1, cache.epoch());
        cache.put(b, "second", -1, cache.epoch());
        assertEquals("first", cache.get(a));
        assertSame(decoded, cache.getDecoded(a, "first"));
        cache.put(c, "third", -1, cache.epoch());
        assertNull(cache.get(b));
        assertEquals("first", cache.get(a));
        assertEquals("third", cache.get(c));
        cache.invalidate(a);
        assertNull(cache.getDecoded(a, "first"));
    }
    @Test void strictTtlCapacityByteBudgetAndLateFillProtection() throws Exception {
        var cache = new RedisNearCacheCoordinator.Store(new NearCachePolicy(2, 1, 2048));
        long before = cache.epoch();
        cache.put("a", "one", -1, before);
        assertEquals("one", cache.get("a"));
        cache.invalidate("a");
        cache.put("a", "stale", -1, before);
        assertNull(cache.get("a"));
        cache.put("a", "fresh", 1, cache.epoch());
        Thread.sleep(5); assertNull(cache.get("a"));
        cache.put("a", "x".repeat(5000), -1, cache.epoch()); assertNull(cache.get("a"));
        cache.put("a", "fresh", -2, cache.epoch()); assertNull(cache.get("a"));
        assertThrows(IllegalArgumentException.class, () -> new NearCachePolicy(64_001, 1, 1024));
        assertThrows(IllegalArgumentException.class, () -> new NearCachePolicy(1, 61, 1024));
    }

    @Test @EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
    void separateRuntimeSubscriptionsEvictPeerCopiesAndClearOnClose() throws Exception {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri())) {
            var keys = new RedisKeyStrategy("near-" + UUID.randomUUID());
            try (var first = new RedisNearCacheCoordinator(JedisPooledOperations.of(redis), keys);
                    var second = new RedisNearCacheCoordinator(JedisPooledOperations.of(redis), keys)) {
                var policy = new NearCachePolicy(32, 10, 65536);
                var a = first.register("products", policy); var b = second.register("products", policy);
                long deadline = System.nanoTime() + 5_000_000_000L;
                while ((!first.connected() || !second.connected()) && System.nanoTime() < deadline) Thread.sleep(2);
                assertTrue(first.connected()); assertTrue(second.connected());
                a.put("1", "old", -1, a.epoch()); b.put("1", "old", -1, b.epoch());
                redis.publish(keys.nearInvalidationChannel(), RedisNearCacheCoordinator.message("products", "1"));
                while ((a.get("1") != null || b.get("1") != null) && System.nanoTime() < deadline) Thread.sleep(2);
                assertNull(a.get("1")); assertNull(b.get("1"));
                b.put("1", "fresh", -1, b.epoch()); second.close(); assertNull(b.get("1"));
            }
        }
    }
}
