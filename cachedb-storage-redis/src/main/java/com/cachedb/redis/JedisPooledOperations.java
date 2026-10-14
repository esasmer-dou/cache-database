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

    public JedisPooled pooledClient() {
        return client;
    }
}
