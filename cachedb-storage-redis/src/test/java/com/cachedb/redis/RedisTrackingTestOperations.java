package com.reactor.cachedb.redis;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.pool2.PooledObjectFactory;
import redis.clients.jedis.Connection;
import redis.clients.jedis.UnifiedJedis;

/** Test-only provider: a dedicated factory is never passed to JedisPooled's eager bootstrap. */
final class RedisTrackingTestOperations implements RedisClientOperations, RedisTrackingConnectionProvider {
    private final RedisClientOperations borrowed;
    private final List<RedisTrackingNode> nodes;
    final AtomicInteger commandCalls = new AtomicInteger();

    RedisTrackingTestOperations(PooledObjectFactory<Connection> factory) { this(null, factory); }
    RedisTrackingTestOperations(RedisClientOperations borrowed, PooledObjectFactory<Connection> factory) {
        this.borrowed = borrowed;
        nodes = List.of(new RedisTrackingNode("standalone", Objects.requireNonNull(factory)));
    }
    @Override public List<RedisTrackingNode> trackingNodes(int maximumNodes) {
        if (maximumNodes < 1 || maximumNodes > 64) throw new IllegalArgumentException("Node limit must be 1..64");
        return nodes;
    }
    @Override public UnifiedJedis commands() {
        commandCalls.incrementAndGet();
        if (borrowed == null) throw new AssertionError("Scripted tracking fixture must not access ordinary commands");
        return borrowed.commands();
    }
    @Override public void close() { /* The test owns the independent borrowed client. */ }
}
