package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.WriteBehindConfig;
import redis.clients.jedis.JedisPooled;

import java.util.Map;

public final class RedisBacklogEstimator {

    private RedisBacklogEstimator() {
    }

    public static long estimateWriteBehindBacklog(
            JedisPooled jedis,
            RedisKeyStrategy keyStrategy,
            WriteBehindConfig writeBehindConfig
    ) {
        return estimateWriteBehindBacklog(
                jedis,
                keyStrategy,
                writeBehindConfig.activeStreamKeys(),
                writeBehindConfig.durableCompactionEnabled()
        );
    }

    public static long estimateWriteBehindBacklog(
            JedisPooled jedis,
            RedisKeyStrategy keyStrategy,
            java.util.List<String> activeStreamKeys,
            boolean durableCompactionEnabled
    ) {
        if (durableCompactionEnabled) {
            long pendingCount = pendingCount(jedis, keyStrategy);
            if (pendingCount > 0L) {
                return pendingCount;
            }
            Map<String, String> stats = safeStats(jedis, keyStrategy);
            if (!stats.isEmpty()) {
                return 0L;
            }
        }

        long total = 0L;
        for (String streamKey : activeStreamKeys) {
            total += safeStreamLength(jedis, streamKey);
        }
        return total;
    }

    public static long pendingCount(JedisPooled jedis, RedisKeyStrategy keyStrategy) {
        return parseLong(safeStats(jedis, keyStrategy).get("pendingCount"));
    }

    private static Map<String, String> safeStats(JedisPooled jedis, RedisKeyStrategy keyStrategy) {
        try {
            return jedis.hgetAll(keyStrategy.compactionStatsKey());
        } catch (RuntimeException exception) {
            return Map.of();
        }
    }

    private static long safeStreamLength(JedisPooled jedis, String streamKey) {
        try {
            return jedis.xlen(streamKey);
        } catch (RuntimeException exception) {
            return 0L;
        }
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }
}
