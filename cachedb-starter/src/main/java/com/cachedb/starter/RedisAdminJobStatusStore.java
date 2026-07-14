package com.reactor.cachedb.starter;

import redis.clients.jedis.JedisPooled;

import java.util.Optional;

final class RedisAdminJobStatusStore {

    private final JedisPooled jedis;
    private final String keyPrefix;
    private final int ttlSeconds;

    RedisAdminJobStatusStore(JedisPooled jedis, String keyPrefix, int ttlSeconds) {
        this.jedis = jedis;
        this.keyPrefix = keyPrefix + ":admin:job-status:";
        this.ttlSeconds = ttlSeconds;
    }

    void put(String jobId, String statusJson) {
        jedis.setex(key(jobId), ttlSeconds, statusJson);
    }

    Optional<String> get(String jobId) {
        return Optional.ofNullable(jedis.get(key(jobId)));
    }

    private String key(String jobId) {
        return keyPrefix + jobId;
    }
}
