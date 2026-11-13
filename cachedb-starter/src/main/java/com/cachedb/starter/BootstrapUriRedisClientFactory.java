package com.reactor.cachedb.starter;

import java.net.URI;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.util.JedisURIHelper;

/** Owned URI clients only; borrowed clients retain their supplied settings and ownership. */
final class BootstrapUriRedisClientFactory {
    private BootstrapUriRedisClientFactory() { }

    static JedisPooled create(String value) {
        URI uri = URI.create(value);
        var client = clientConfig(uri);
        // Jedis 5.2 ConnectionPool uses plain GenericObjectPoolConfig, NOT ConnectionPoolConfig.
        var pool = new GenericObjectPoolConfig<Connection>();
        pool.setFairness(true);
        return new JedisPooled(JedisURIHelper.getHostAndPort(uri), client, pool);
    }

    /** Match UnifiedJedis(URI); do not substitute CacheDB pool/timeouts or normalize URI semantics. */
    static DefaultJedisClientConfig clientConfig(URI uri) {
        return DefaultJedisClientConfig.builder()
                .user(JedisURIHelper.getUser(uri)).password(JedisURIHelper.getPassword(uri))
                .database(JedisURIHelper.getDBIndex(uri)).protocol(JedisURIHelper.getRedisProtocol(uri))
                .ssl(JedisURIHelper.isRedisSSLScheme(uri)).build();
    }
}
