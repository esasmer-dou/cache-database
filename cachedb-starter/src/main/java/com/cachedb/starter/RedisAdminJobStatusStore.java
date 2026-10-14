package com.reactor.cachedb.starter;

import com.reactor.cachedb.redis.JedisPooledOperations;
import com.reactor.cachedb.redis.RedisClientOperations;

import redis.clients.jedis.JedisPooled;

import java.util.Optional;

final class RedisAdminJobStatusStore {

    private final RedisClientOperations jedis;
    private final String keyPrefix;
    private final int ttlSeconds;

    RedisAdminJobStatusStore(JedisPooled jedis, String keyPrefix, int ttlSeconds) {
        this(JedisPooledOperations.of(jedis), keyPrefix, ttlSeconds);
    }

    RedisAdminJobStatusStore(RedisClientOperations jedis, String keyPrefix, int ttlSeconds) {
        this.jedis = jedis;
        this.keyPrefix = keyPrefix + ":admin:job-status:";
        this.ttlSeconds = ttlSeconds;
    }

    void put(String jobId, String statusJson) {
        jedis.commands().setex(key(jobId), ttlSeconds, statusJson);
    }

    Optional<String> get(String jobId) {
        return Optional.ofNullable(jedis.commands().get(key(jobId)));
    }

    private String key(String jobId) {
        return keyPrefix + jobId;
    }
}
