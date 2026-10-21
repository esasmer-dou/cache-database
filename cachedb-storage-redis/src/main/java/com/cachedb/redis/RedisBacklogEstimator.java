package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.WriteBehindConfig;

import redis.clients.jedis.JedisPooled;

import java.util.Map;

public final class RedisBacklogEstimator {

    private RedisBacklogEstimator() {}

    public static long estimateWriteBehindBacklog(
            JedisPooled jedis, RedisKeyStrategy keyStrategy, WriteBehindConfig writeBehindConfig) {
        return estimateWriteBehindBacklog(
                JedisPooledOperations.of(jedis), keyStrategy, writeBehindConfig);
    }

    public static long estimateWriteBehindBacklog(
            RedisClientOperations jedis,
            RedisKeyStrategy keyStrategy,
            WriteBehindConfig writeBehindConfig) {
        if (keyStrategy instanceof ClusterHashTagKeyStrategy cluster
                && cluster.namespaceSharded()) {
            long total = 0;
            for (String namespace : cluster.namespaces()) {
                long streamCount = 0;
                for (String stream : writeBehindConfig.activeStreamKeys()) {
                    streamCount +=
                            safeStreamLength(jedis, cluster.writeStreamKey(stream, namespace));
                }
                if (writeBehindConfig.durableCompactionEnabled()) {
                    Map<String, String> stats =
                            safeStats(jedis, cluster.compactionStatsKey(namespace));
                    if (!stats.isEmpty()) {
                        // Atomic envelopes share the streams but are not compaction-stat entries.
                        total += Math.max(parseLong(stats.get("pendingCount")), streamCount);
                        continue;
                    }
                }
                total += streamCount;
            }
            return total;
        }
        return estimateWriteBehindBacklog(
                jedis,
                keyStrategy,
                writeBehindConfig.activeStreamKeys(),
                writeBehindConfig.durableCompactionEnabled());
    }

    public static long estimateWriteBehindBacklog(
            JedisPooled jedis,
            RedisKeyStrategy keyStrategy,
            java.util.List<String> activeStreamKeys,
            boolean durableCompactionEnabled) {
        return estimateWriteBehindBacklog(
                JedisPooledOperations.of(jedis),
                keyStrategy,
                activeStreamKeys,
                durableCompactionEnabled);
    }

    public static long estimateWriteBehindBacklog(
            RedisClientOperations jedis,
            RedisKeyStrategy keyStrategy,
            java.util.List<String> activeStreamKeys,
            boolean durableCompactionEnabled) {
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
        return pendingCount(JedisPooledOperations.of(jedis), keyStrategy);
    }

    public static long pendingCount(RedisClientOperations jedis, RedisKeyStrategy keyStrategy) {
        if (keyStrategy instanceof ClusterHashTagKeyStrategy cluster
                && cluster.namespaceSharded()) {
            long total = 0;
            for (String namespace : cluster.namespaces())
                total +=
                        parseLong(
                                safeStats(jedis, cluster.compactionStatsKey(namespace))
                                        .get("pendingCount"));
            return total;
        }
        return parseLong(safeStats(jedis, keyStrategy).get("pendingCount"));
    }

    private static Map<String, String> safeStats(JedisPooled jedis, RedisKeyStrategy keyStrategy) {
        return safeStats(JedisPooledOperations.of(jedis), keyStrategy);
    }

    private static Map<String, String> safeStats(
            RedisClientOperations jedis, RedisKeyStrategy keyStrategy) {
        return safeStats(jedis, keyStrategy.compactionStatsKey());
    }

    private static Map<String, String> safeStats(RedisClientOperations jedis, String statsKey) {
        try {
            return jedis.commands().hgetAll(statsKey);
        } catch (RuntimeException exception) {
            return Map.of();
        }
    }

    private static long safeStreamLength(JedisPooled jedis, String streamKey) {
        return safeStreamLength(JedisPooledOperations.of(jedis), streamKey);
    }

    private static long safeStreamLength(RedisClientOperations jedis, String streamKey) {
        try {
            return jedis.commands().xlen(streamKey);
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
