package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.cache.CachePolicy;
import redis.clients.jedis.JedisPooled;

import java.time.Instant;
import java.util.List;

public final class RedisHotSetManager {

    private final JedisPooled jedis;
    private final RedisKeyStrategy keyStrategy;

    public RedisHotSetManager(JedisPooled jedis, RedisKeyStrategy keyStrategy) {
        this.jedis = jedis;
        this.keyStrategy = keyStrategy;
    }

    public <ID> List<String> recordAccess(String namespace, ID id, CachePolicy cachePolicy) {
        if (cachePolicy.hotEntityLimit() <= 0) {
            return List.of();
        }

        String hotSetKey = keyStrategy.hotSetKey(namespace);
        jedis.zadd(hotSetKey, Instant.now().toEpochMilli(), String.valueOf(id));

        if (!cachePolicy.lruEvictionEnabled()) {
            return List.of();
        }

        long currentSize = jedis.zcard(hotSetKey);
        long overflow = currentSize - cachePolicy.hotEntityLimit();
        if (overflow <= 0) {
            return List.of();
        }

        List<String> overflowMembers = jedis.zrange(hotSetKey, 0, overflow - 1);
        if (overflowMembers == null || overflowMembers.isEmpty()) {
            return List.of();
        }

        for (String overflowId : overflowMembers) {
            jedis.zrem(hotSetKey, overflowId);
        }
        return List.copyOf(overflowMembers);
    }

    public <ID> void remove(String namespace, ID id) {
        jedis.zrem(keyStrategy.hotSetKey(namespace), String.valueOf(id));
    }
}
