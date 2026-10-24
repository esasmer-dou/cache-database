package com.reactor.cachedb.spring.boot;

/** Environment credentials keep disposable database passwords out of Surefire system properties. */
public final class TestFixtureSettings {
    public static String value(String property, String fallback) {
        String environment = System.getenv(property.toUpperCase(java.util.Locale.ROOT).replace('.', '_').replace('-', '_'));
        return System.getProperty(property, environment == null ? fallback : environment);
    }
    public static com.reactor.cachedb.redis.JedisClusterOperations cluster() {
        String host = System.getProperty("cachedb.redis.cluster.host");
        var config = redis.clients.jedis.DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(3000).socketTimeoutMillis(5000)
                .hostAndPortMapper(address -> host != null ? address : new redis.clients.jedis.HostAndPort(
                        "127.0.0.1",address.getPort()<10000 ? address.getPort()+10000 : address.getPort())).build();
        return new com.reactor.cachedb.redis.JedisClusterOperations(new redis.clients.jedis.JedisCluster(
                java.util.Set.of(new redis.clients.jedis.HostAndPort(host == null ? "127.0.0.1" : host,
                        Integer.getInteger("cachedb.redis.cluster.port",17001))),config));
    }
    private TestFixtureSettings() {}
}
