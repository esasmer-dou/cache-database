package com.reactor.cachedb.redis;

import java.util.Objects;
import org.apache.commons.pool2.PooledObjectFactory;
import redis.clients.jedis.Connection;

public record RedisTrackingNode(String key, PooledObjectFactory<Connection> factory) {
    public RedisTrackingNode {
        Objects.requireNonNull(key); Objects.requireNonNull(factory);
        if (key.isEmpty() || key.length() > 512) throw new IllegalArgumentException("Invalid node identity");
    }
    /**
     * Retains credentials, TLS/socket factory, logical DB and endpoint mapping.
     * Creation/destruction run on the current virtual-thread I/O owner, never a timeout helper.
     */
    public RedisDedicatedConnection openDedicated(int ioTimeoutMillis) {
        return RedisDedicatedConnection.open(factory, ioTimeoutMillis);
    }
}
