package com.reactor.cachedb.spring.boot.cache;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.NearCachePolicy;
import com.reactor.cachedb.core.config.SingleFlightConfig;
import com.reactor.cachedb.redis.JedisPooledOperations;
import com.reactor.cachedb.redis.RedisClientOperations;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;

/** External Redis only. Cleanup touches only bounded, explicitly recorded test keys. */
final class CacheDbCacheRedisFixture implements AutoCloseable {
    final String prefix = "spring-cache-test-" + UUID.randomUUID();
    final RedisClientOperations underlying;
    final Set<String> ownedKeys = ConcurrentHashMap.newKeySet();
    final Set<String> scripts = ConcurrentHashMap.newKeySet();
    final List<CacheDbCacheManager> managers = new ArrayList<>();
    final RedisClientOperations tracked;
    final java.util.concurrent.atomic.AtomicLong evalCalls = new java.util.concurrent.atomic.AtomicLong();
    final java.util.concurrent.atomic.AtomicLong commandsCalls = new java.util.concurrent.atomic.AtomicLong();

    static CacheDbCacheRedisFixture standalone() {
        return new CacheDbCacheRedisFixture(JedisPooledOperations.of(new JedisPooled(
                "redis://" + System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1") + ":"
                        + Integer.getInteger("cachedb.snapshot.redis.port", 16383))));
    }

    CacheDbCacheRedisFixture(RedisClientOperations underlying) {
        this.underlying = underlying;
        tracked = new RedisClientOperations() {
            public UnifiedJedis commands() { commandsCalls.incrementAndGet(); return underlying.commands(); }
            public boolean clustered() { return underlying.clustered(); }
            @Override public void close() { /* Borrowed by Spring contexts; the fixture owns the client. */ }
            @Override public Object eval(String script, List<String> keys, List<String> arguments) {
                if (ownedKeys.size() + keys.size() > 20000 || scripts.size() > 16)
                    throw new AssertionError("Test fixture exceeded its key/script budget");
                evalCalls.incrementAndGet();
                ownedKeys.addAll(keys);
                scripts.add(script);
                return underlying.eval(script, keys, arguments);
            }
        };
    }

    static CacheDbCacheConfiguration config(boolean tenant, boolean nullable, boolean near, Duration ttl, int bytes) {
        return new CacheDbCacheConfiguration(ttl,
                near ? new NearCachePolicy(128, 1, 1024 * 1024) : NearCachePolicy.disabled(), tenant, nullable, bytes);
    }

    CacheDbCacheManager manager(Map<String, CacheDbCacheConfiguration> configs) {
        return manager(configs, List.of());
    }
    CacheDbCacheManager manager(Map<String, CacheDbCacheConfiguration> configs, List<CacheDbCacheCodec<?>> codecs) {
        return manager(configs, codecs, tracked);
    }
    CacheDbCacheManager manager(Map<String, CacheDbCacheConfiguration> configs, List<CacheDbCacheCodec<?>> codecs,
            RedisClientOperations invalidationRedis) {
        if (managers.size() >= 16) throw new AssertionError("Test manager budget exceeded");
        var manager = new CacheDbCacheManager(tracked, invalidationRedis, prefix, configs, codecs, 256,
                new SingleFlightConfig(true, 256, 4096, 10000));
        managers.add(manager);
        return manager;
    }

    static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        assertTrue(condition.getAsBoolean(), "Condition did not become true within 10 seconds");
    }

    @Override public void close() {
        try {
            for (var manager : managers) manager.close();
            for (String key : ownedKeys) {
                assertTrue(key.startsWith(prefix + ":scache:"), "Cleanup must remain inside this fixture namespace");
                underlying.commands().unlink(key);
            }
        } finally { underlying.close(); }
    }
}
