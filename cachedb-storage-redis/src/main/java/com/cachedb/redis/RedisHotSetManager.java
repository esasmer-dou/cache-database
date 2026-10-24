package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.cache.CachePolicy;
import java.util.List;
import redis.clients.jedis.JedisPooled;

public final class RedisHotSetManager {
    private static final int MAX_BATCH_IDS = 10000;
    private static final String RECORD_ACCESS_BATCH_SCRIPT = """
            local added=0
            for i=4,#ARGV do if not redis.call('ZSCORE',KEYS[1],ARGV[i]) then added=added+1 end end
            local overflow=0
            if ARGV[3] == '1' then overflow=math.max(0,redis.call('ZCARD',KEYS[1])+added-tonumber(ARGV[2])) end
            if overflow > 10000 then return redis.error_reply('Hot-set eviction batch exceeds 10000 IDs') end
            for i=4,#ARGV do redis.call('ZADD',KEYS[1],ARGV[1],ARGV[i]) end
            if overflow == 0 then return {} end
            local evicted=redis.call('ZRANGE',KEYS[1],0,overflow-1)
            for i=1,#evicted,500 do redis.call('ZREM',KEYS[1],unpack(evicted,i,math.min(i+499,#evicted))) end
            return evicted
            """;

    private static final String RECORD_ACCESS_SCRIPT =
            """
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

    private final RedisClientOperations jedis;
    private final RedisKeyStrategy keyStrategy;

    public RedisHotSetManager(JedisPooled jedis, RedisKeyStrategy keyStrategy) {
        this(JedisPooledOperations.of(jedis), keyStrategy);
    }

    public RedisHotSetManager(RedisClientOperations jedis, RedisKeyStrategy keyStrategy) {
        this.jedis = jedis;
        this.keyStrategy = keyStrategy;
    }

    public <ID> List<String> recordAccess(String namespace, ID id, CachePolicy cachePolicy) {
        if (cachePolicy.hotEntityLimit() == CachePolicy.DISABLED) {
            return List.of();
        }

        Object result =
                jedis.eval(
                        RECORD_ACCESS_SCRIPT,
                        List.of(keyStrategy.hotSetKey(namespace)),
                        List.of(
                                String.valueOf(System.currentTimeMillis()),
                                String.valueOf(id),
                                String.valueOf(cachePolicy.hotEntityLimit()),
                                cachePolicy.lruEvictionEnabled() && cachePolicy.hotEntityLimit() > 0
                                        ? "1"
                                        : "0"));
        if (!(result instanceof List<?> values) || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(String::valueOf).toList();
    }

    public <ID> void remove(String namespace, ID id) {
        jedis.commands().zrem(keyStrategy.hotSetKey(namespace), String.valueOf(id));
    }

    public <ID> List<String> recordAccessBatch(String namespace, java.util.Collection<ID> ids, CachePolicy policy) {
        java.util.Objects.requireNonNull(ids, "ids");
        if (ids.size() > MAX_BATCH_IDS) throw new IllegalArgumentException("At most 10000 hot-set IDs");
        if (ids.isEmpty() || policy.hotEntityLimit() == CachePolicy.DISABLED) return List.of();
        java.util.Set<String> unique = new java.util.LinkedHashSet<>();
        for (ID id : ids) {
            String value = String.valueOf(java.util.Objects.requireNonNull(id, "id"));
            if (value.isBlank() || value.length() > 1024) throw new IllegalArgumentException("Invalid hot-set ID");
            unique.add(value);
        }
        var arguments = new java.util.ArrayList<String>(unique.size() + 3);
        arguments.add(Long.toString(System.currentTimeMillis()));
        arguments.add(Integer.toString(policy.hotEntityLimit()));
        arguments.add(policy.lruEvictionEnabled() && policy.hotEntityLimit() > 0 ? "1" : "0");
        arguments.addAll(unique);
        Object result = jedis.eval(RECORD_ACCESS_BATCH_SCRIPT, List.of(keyStrategy.hotSetKey(namespace)), arguments);
        return result instanceof List<?> values ? values.stream().map(String::valueOf).toList() : List.of();
    }
}
