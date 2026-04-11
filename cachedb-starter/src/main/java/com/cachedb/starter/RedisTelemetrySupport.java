package com.reactor.cachedb.starter;

import redis.clients.jedis.JedisPooled;

final class RedisTelemetrySupport {

    private RedisTelemetrySupport() {
    }

    static void expireIfNeeded(JedisPooled jedis, String key, long ttlSeconds) {
        if (ttlSeconds > 0 && key != null && !key.isBlank()) {
            jedis.expire(key, ttlSeconds);
        }
    }

    static long streamLengthSafely(JedisPooled jedis, String streamKey) {
        if (streamKey == null || streamKey.isBlank()) {
            return 0L;
        }
        try {
            return jedis.xlen(streamKey);
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    static long deleteKeySafely(JedisPooled jedis, String key) {
        if (key == null || key.isBlank()) {
            return 0L;
        }
        try {
            return jedis.del(key);
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    static long deleteKeysSafely(JedisPooled jedis, String[] keys) {
        if (keys == null || keys.length == 0) {
            return 0L;
        }
        try {
            return jedis.del(keys);
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    static int clearStream(JedisPooled jedis, String streamKey) {
        if (streamKey == null || streamKey.isBlank()) {
            return 0;
        }
        long cleared = streamLengthSafely(jedis, streamKey);
        deleteKeySafely(jedis, streamKey);
        return clampToInt(cleared);
    }

    static int clampToInt(long value) {
        if (value <= 0L) {
            return 0;
        }
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
