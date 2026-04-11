package com.reactor.cachedb.starter;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.Connection;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

public record RedisConnectionConfig(
        String uri,
        int poolMaxTotal,
        int poolMaxIdle,
        int poolMinIdle,
        long poolMaxWaitMillis,
        boolean blockWhenExhausted,
        boolean testOnBorrow,
        boolean testWhileIdle,
        long timeBetweenEvictionRunsMillis,
        long minEvictableIdleTimeMillis,
        int numTestsPerEvictionRun,
        int connectionTimeoutMillis,
        int readTimeoutMillis,
        int blockingReadTimeoutMillis
) {
    public static final String DEFAULT_PREFIX = "cachedb.redis";

    public JedisPooled createClient() {
        URI redisUri = URI.create(uri);
        GenericObjectPoolConfig<Connection> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(poolMaxTotal);
        poolConfig.setMaxIdle(poolMaxIdle);
        poolConfig.setMinIdle(poolMinIdle);
        poolConfig.setBlockWhenExhausted(blockWhenExhausted);
        poolConfig.setTestOnBorrow(testOnBorrow);
        poolConfig.setTestWhileIdle(testWhileIdle);
        poolConfig.setMaxWait(Duration.ofMillis(poolMaxWaitMillis));
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofMillis(timeBetweenEvictionRunsMillis));
        poolConfig.setMinEvictableIdleDuration(Duration.ofMillis(minEvictableIdleTimeMillis));
        poolConfig.setNumTestsPerEvictionRun(numTestsPerEvictionRun);

        DefaultJedisClientConfig.Builder clientConfigBuilder = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(connectionTimeoutMillis)
                .socketTimeoutMillis(readTimeoutMillis)
                .blockingSocketTimeoutMillis(blockingReadTimeoutMillis);

        String userInfo = redisUri.getUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            String[] credentials = userInfo.split(":", 2);
            if (credentials.length == 2) {
                if (!credentials[0].isBlank()) {
                    clientConfigBuilder.user(credentials[0]);
                }
                clientConfigBuilder.password(credentials[1]);
            } else {
                clientConfigBuilder.password(credentials[0]);
            }
        }

        int database = parseDatabase(redisUri);
        if (database > 0) {
            clientConfigBuilder.database(database);
        }
        if ("rediss".equalsIgnoreCase(redisUri.getScheme())) {
            clientConfigBuilder.ssl(true);
        }

        String host = redisUri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Redis URI must include a host: " + uri);
        }
        int port = redisUri.getPort() > 0 ? redisUri.getPort() : 6379;
        return new JedisPooled(poolConfig, new HostAndPort(host, port), clientConfigBuilder.build());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static RedisConnectionConfig defaults() {
        return builder().build();
    }

    public static RedisConnectionConfig fromSystemProperties(String prefix, String defaultUri) {
        return fromProperties(System.getProperties(), prefix, defaultUri);
    }

    public static RedisConnectionConfig fromProperties(Properties properties, String prefix, String defaultUri) {
        String normalizedPrefix = normalizePrefix(prefix);
        return RedisConnectionConfig.builder()
                .uri(string(properties, normalizedPrefix + ".uri", string(properties, normalizedPrefix + "Uri", defaultUri)))
                .poolMaxTotal(integer(properties, normalizedPrefix + ".pool.maxTotal", 64))
                .poolMaxIdle(integer(properties, normalizedPrefix + ".pool.maxIdle", 16))
                .poolMinIdle(integer(properties, normalizedPrefix + ".pool.minIdle", 4))
                .poolMaxWaitMillis(longValue(properties, normalizedPrefix + ".pool.maxWaitMillis", 5_000L))
                .blockWhenExhausted(bool(properties, normalizedPrefix + ".pool.blockWhenExhausted", true))
                .testOnBorrow(bool(properties, normalizedPrefix + ".pool.testOnBorrow", false))
                .testWhileIdle(bool(properties, normalizedPrefix + ".pool.testWhileIdle", false))
                .timeBetweenEvictionRunsMillis(longValue(properties, normalizedPrefix + ".pool.timeBetweenEvictionRunsMillis", 30_000L))
                .minEvictableIdleTimeMillis(longValue(properties, normalizedPrefix + ".pool.minEvictableIdleTimeMillis", 60_000L))
                .numTestsPerEvictionRun(integer(properties, normalizedPrefix + ".pool.numTestsPerEvictionRun", 3))
                .connectionTimeoutMillis(integer(properties, normalizedPrefix + ".pool.connectionTimeoutMillis", 2_000))
                .readTimeoutMillis(integer(properties, normalizedPrefix + ".pool.readTimeoutMillis", 5_000))
                .blockingReadTimeoutMillis(integer(properties, normalizedPrefix + ".pool.blockingReadTimeoutMillis", 15_000))
                .build();
    }

    public static final class Builder {
        private String uri = "redis://default:welcome1@127.0.0.1:6379";
        private int poolMaxTotal = 64;
        private int poolMaxIdle = 16;
        private int poolMinIdle = 4;
        private long poolMaxWaitMillis = 5_000L;
        private boolean blockWhenExhausted = true;
        private boolean testOnBorrow = false;
        private boolean testWhileIdle = false;
        private long timeBetweenEvictionRunsMillis = 30_000L;
        private long minEvictableIdleTimeMillis = 60_000L;
        private int numTestsPerEvictionRun = 3;
        private int connectionTimeoutMillis = 2_000;
        private int readTimeoutMillis = 5_000;
        private int blockingReadTimeoutMillis = 15_000;

        public Builder uri(String uri) {
            this.uri = uri;
            return this;
        }

        public Builder poolMaxTotal(int poolMaxTotal) {
            this.poolMaxTotal = poolMaxTotal;
            return this;
        }

        public Builder poolMaxIdle(int poolMaxIdle) {
            this.poolMaxIdle = poolMaxIdle;
            return this;
        }

        public Builder poolMinIdle(int poolMinIdle) {
            this.poolMinIdle = poolMinIdle;
            return this;
        }

        public Builder poolMaxWaitMillis(long poolMaxWaitMillis) {
            this.poolMaxWaitMillis = poolMaxWaitMillis;
            return this;
        }

        public Builder blockWhenExhausted(boolean blockWhenExhausted) {
            this.blockWhenExhausted = blockWhenExhausted;
            return this;
        }

        public Builder testOnBorrow(boolean testOnBorrow) {
            this.testOnBorrow = testOnBorrow;
            return this;
        }

        public Builder testWhileIdle(boolean testWhileIdle) {
            this.testWhileIdle = testWhileIdle;
            return this;
        }

        public Builder timeBetweenEvictionRunsMillis(long timeBetweenEvictionRunsMillis) {
            this.timeBetweenEvictionRunsMillis = timeBetweenEvictionRunsMillis;
            return this;
        }

        public Builder minEvictableIdleTimeMillis(long minEvictableIdleTimeMillis) {
            this.minEvictableIdleTimeMillis = minEvictableIdleTimeMillis;
            return this;
        }

        public Builder numTestsPerEvictionRun(int numTestsPerEvictionRun) {
            this.numTestsPerEvictionRun = numTestsPerEvictionRun;
            return this;
        }

        public Builder connectionTimeoutMillis(int connectionTimeoutMillis) {
            this.connectionTimeoutMillis = connectionTimeoutMillis;
            return this;
        }

        public Builder readTimeoutMillis(int readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
            return this;
        }

        public Builder blockingReadTimeoutMillis(int blockingReadTimeoutMillis) {
            this.blockingReadTimeoutMillis = blockingReadTimeoutMillis;
            return this;
        }

        public RedisConnectionConfig build() {
            return new RedisConnectionConfig(
                    Objects.requireNonNull(uri, "uri"),
                    poolMaxTotal,
                    poolMaxIdle,
                    poolMinIdle,
                    poolMaxWaitMillis,
                    blockWhenExhausted,
                    testOnBorrow,
                    testWhileIdle,
                    timeBetweenEvictionRunsMillis,
                    minEvictableIdleTimeMillis,
                    numTestsPerEvictionRun,
                    connectionTimeoutMillis,
                    readTimeoutMillis,
                    blockingReadTimeoutMillis
            );
        }
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return DEFAULT_PREFIX;
        }
        return prefix.endsWith(".") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    private static String string(Properties properties, String key, String defaultValue) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static int integer(Properties properties, String key, int defaultValue) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
    }

    private static long longValue(Properties properties, String key, long defaultValue) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : Long.parseLong(value.trim());
    }

    private static boolean bool(Properties properties, String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "y", "on" -> true;
            case "false", "0", "no", "n", "off" -> false;
            default -> throw new IllegalArgumentException("Invalid boolean '" + value + "' for " + key);
        };
    }

    private static int parseDatabase(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            return 0;
        }
        return Integer.parseInt(path.substring(1));
    }
}
