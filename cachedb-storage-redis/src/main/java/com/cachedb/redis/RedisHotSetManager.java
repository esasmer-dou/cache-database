package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.cache.CachePolicy;
import redis.clients.jedis.JedisPooled;

import java.util.List;

public final class RedisHotSetManager {

    private static final String RECORD_ACCESS_SCRIPT = """
            redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])
            if ARGV[4] ~= '1' then
                return {}
            end
            local overflow = redis.call('ZCARD', KEYS[1]) - tonumber(ARGV[3])
            if overflow <= 0 then
                return {}
            end
            local evicted = redis.call('ZRANGE', KEYS[1], 0, overflow - 1)
            if #evicted > 0 then
                redis.call('ZREM', KEYS[1], unpack(evicted))
            end
            return evicted
            """;

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

        Object result = jedis.eval(
                RECORD_ACCESS_SCRIPT,
                List.of(keyStrategy.hotSetKey(namespace)),
                List.of(
                        String.valueOf(System.currentTimeMillis()),
                        String.valueOf(id),
                        String.valueOf(cachePolicy.hotEntityLimit()),
                        cachePolicy.lruEvictionEnabled() ? "1" : "0"
                )
        );
        if (!(result instanceof List<?> values) || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(String::valueOf).toList();
    }

    public <ID> void remove(String namespace, ID id) {
        jedis.zrem(keyStrategy.hotSetKey(namespace), String.valueOf(id));
    }
}
