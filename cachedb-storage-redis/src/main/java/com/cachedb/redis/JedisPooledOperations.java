package com.reactor.cachedb.redis;

import redis.clients.jedis.JedisPooled;

import java.util.Objects;

public final class JedisPooledOperations implements RedisClientOperations {
    private final JedisPooled client;

    public JedisPooledOperations(JedisPooled client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public static RedisClientOperations of(JedisPooled client) {
        return client == null ? null : new JedisPooledOperations(client);
    }

    public JedisPooled commands() {
        return client;
    }

    @Override public java.util.Optional<java.util.List<String>> zrangeWithPayloads(String indexKey,
            String tombstonePrefix, String entityPrefix, boolean reverse, int offset, int limit) {
        return fetchOrderedPayloads(indexKey, tombstonePrefix, entityPrefix, reverse, offset, limit);
    }

    public JedisPooled pooledClient() {
        return client;
    }

    @Override public java.util.Optional<java.util.List<OrderedPayload>> orderedPayloadWindow(String indexKey,
            String tombstonePrefix, String entityPrefix, boolean reverse, int offset, int limit) {
        return fetchOrderedPayloadWindow(indexKey, tombstonePrefix, entityPrefix, reverse, offset, limit);
    }
}
