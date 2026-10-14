package com.reactor.cachedb.starter;

import com.reactor.cachedb.redis.JedisPooledOperations;
import com.reactor.cachedb.redis.RedisClientOperations;

import redis.clients.jedis.JedisPooled;

final class RedisTelemetrySupport {

    private RedisTelemetrySupport() {}

    static void expireIfNeeded(JedisPooled jedis, String key, long ttlSeconds) {
        expireIfNeeded(JedisPooledOperations.of(jedis), key, ttlSeconds);
    }

    static void expireIfNeeded(RedisClientOperations jedis, String key, long ttlSeconds) {
        if (ttlSeconds > 0 && key != null && !key.isBlank()) {
            jedis.commands().expire(key, ttlSeconds);
        }
    }

    static long streamLengthSafely(JedisPooled jedis, String streamKey) {
        return streamLengthSafely(JedisPooledOperations.of(jedis), streamKey);
    }

    static long streamLengthSafely(RedisClientOperations jedis, String streamKey) {
        if (streamKey == null || streamKey.isBlank()) {
            return 0L;
        }
        try {
            return jedis.commands().xlen(streamKey);
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    static long deleteKeySafely(JedisPooled jedis, String key) {
        return deleteKeySafely(JedisPooledOperations.of(jedis), key);
    }

    static long deleteKeySafely(RedisClientOperations jedis, String key) {
        if (key == null || key.isBlank()) {
            return 0L;
        }
        try {
            return jedis.commands().del(key);
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    static long deleteKeysSafely(JedisPooled jedis, String[] keys) {
        return deleteKeysSafely(JedisPooledOperations.of(jedis), keys);
    }

    static long deleteKeysSafely(RedisClientOperations jedis, String[] keys) {
        if (keys == null || keys.length == 0) {
            return 0L;
        }
        try {
            return jedis.delete(keys);
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    static int clearStream(JedisPooled jedis, String streamKey) {
        return clearStream(JedisPooledOperations.of(jedis), streamKey);
    }

    static int clearStream(RedisClientOperations jedis, String streamKey) {
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
