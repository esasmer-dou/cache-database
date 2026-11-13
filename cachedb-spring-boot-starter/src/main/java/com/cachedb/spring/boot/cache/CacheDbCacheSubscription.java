package com.reactor.cachedb.spring.boot.cache;

import com.reactor.cachedb.redis.RedisClientOperations;
import com.reactor.cachedb.redis.RedisTrackingConnectionProvider;
import com.reactor.cachedb.redis.RedisTrackingNode;
import java.util.List;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.PooledObjectFactory;
import redis.clients.jedis.Connection;
import com.reactor.cachedb.redis.RedisDedicatedConnection;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;

/** One owned subscription socket; it is never returned to the application command pool. */
final class CacheDbCacheSubscription {
    private CacheDbCacheSubscription() {}

    static void listen(RedisClientOperations redis, JedisPubSub listener, String channel) {
        RedisTrackingNode node = node(redis);
        var factory = new ObservedFactory(node.factory());
        RedisDedicatedConnection owned = null;
        try {
            // Preserve the original factory's connect, AUTH/TLS and DB configuration.
            owned = new RedisTrackingNode(node.key(), factory).openDedicated(2000);
            listener.proceed(owned.connection(), channel);
        } finally {
            boolean failed = factory.cleanupFailed;
            if (owned != null) try { owned.close(); }
            catch (RuntimeException | Error failure) { failed = true; }
            if (failed || factory.cleanupFailed) throw new CleanupFailure();
        }
    }

    static final class CleanupFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        CleanupFailure() { super("Spring Cache owned subscription destruction was not confirmed"); }
    }

    private static final class ObservedFactory implements PooledObjectFactory<Connection> {
        final PooledObjectFactory<Connection> delegate;
        boolean cleanupFailed;
        ObservedFactory(PooledObjectFactory<Connection> delegate) { this.delegate = delegate; }
        public PooledObject<Connection> makeObject() throws Exception { return delegate.makeObject(); }
        public void destroyObject(PooledObject<Connection> value) throws Exception {
            try {
                delegate.destroyObject(value);
                if (value.getObject().isConnected()) {
                    cleanupFailed = true;
                    throw new IllegalStateException("Owned subscription socket remains connected");
                }
            } catch (Exception | Error failure) { cleanupFailed = true; throw failure; }
        }
        public boolean validateObject(PooledObject<Connection> value) { return delegate.validateObject(value); }
        public void activateObject(PooledObject<Connection> value) throws Exception { delegate.activateObject(value); }
        public void passivateObject(PooledObject<Connection> value) throws Exception { delegate.passivateObject(value); }
    }

    private static RedisTrackingNode node(RedisClientOperations redis) {
        if (redis instanceof RedisTrackingConnectionProvider provider) {
            List<RedisTrackingNode> nodes = provider.trackingNodes(64);
            if (nodes == null || nodes.isEmpty() || nodes.size() > 64)
                throw new IllegalStateException("Invalid bounded Spring Cache subscription node snapshot");
            return java.util.Objects.requireNonNull(nodes.get(0), "subscription node");
        }
        // Preserve existing RedisClientOperations wrappers around the two supported native clients.
        var commands = redis.commands();
        if (commands instanceof JedisPooled pooled)
            return new RedisTrackingNode("spring-cache", pooled.getPool().getFactory());
        if (commands instanceof JedisCluster cluster) {
            var nodes = cluster.getClusterNodes();
            if (nodes.isEmpty() || nodes.size() > 64)
                throw new IllegalStateException("Invalid bounded Spring Cache cluster node snapshot");
            return new RedisTrackingNode("spring-cache", nodes.values().iterator().next().getFactory());
        }
        throw new IllegalStateException("Spring Cache invalidation requires a dedicated connection factory");
    }
}
