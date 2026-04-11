package com.reactor.cachedb.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cachedb")
public class CacheDbSpringProperties {

    private boolean enabled = true;
    private Profile profile = Profile.DEFAULT;
    private final RedisProperties redis = new RedisProperties();
    private final AdminUiProperties admin = new AdminUiProperties();
    private final RegistrationProperties registration = new RegistrationProperties();
    private final RuntimeProperties runtime = new RuntimeProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Profile getProfile() {
        return profile;
    }

    public void setProfile(Profile profile) {
        this.profile = profile == null ? Profile.DEFAULT : profile;
    }

    public RedisProperties getRedis() {
        return redis;
    }

    /**
     * Backward-compatible alias for legacy {@code cachedb.redis-uri}.
     */
    public String getRedisUri() {
        return redis.getUri();
    }

    /**
     * Backward-compatible alias for legacy {@code cachedb.redis-uri}.
     */
    public void setRedisUri(String redisUri) {
        redis.setUri(redisUri);
    }

    public AdminUiProperties getAdmin() {
        return admin;
    }

    public RegistrationProperties getRegistration() {
        return registration;
    }

    public RuntimeProperties getRuntime() {
        return runtime;
    }

    public enum Profile {
        DEFAULT,
        DEVELOPMENT,
        PRODUCTION,
        BENCHMARK,
        MEMORY_CONSTRAINED,
        MINIMAL_OVERHEAD
    }

    public static final class RedisProperties {
        private String uri = "redis://127.0.0.1:6379";
        private final PoolProperties pool = PoolProperties.foregroundDefaults();
        private final BackgroundRedisProperties background = new BackgroundRedisProperties();

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }

        public PoolProperties getPool() {
            return pool;
        }

        public BackgroundRedisProperties getBackground() {
            return background;
        }
    }

    public static class PoolProperties {
        private int maxTotal = 64;
        private int maxIdle = 16;
        private int minIdle = 4;
        private long maxWaitMillis = 5_000L;
        private boolean blockWhenExhausted = true;
        private boolean testOnBorrow = false;
        private boolean testWhileIdle = false;
        private long timeBetweenEvictionRunsMillis = 30_000L;
        private long minEvictableIdleTimeMillis = 60_000L;
        private int numTestsPerEvictionRun = 3;
        private int connectionTimeoutMillis = 2_000;
        private int readTimeoutMillis = 5_000;
        private int blockingReadTimeoutMillis = 15_000;

        public static PoolProperties foregroundDefaults() {
            return new PoolProperties();
        }

        public static PoolProperties backgroundDefaults() {
            PoolProperties properties = new PoolProperties();
            properties.setMaxTotal(24);
            properties.setMaxIdle(8);
            properties.setMinIdle(2);
            properties.setReadTimeoutMillis(10_000);
            properties.setBlockingReadTimeoutMillis(30_000);
            return properties;
        }

        public int getMaxTotal() {
            return maxTotal;
        }

        public void setMaxTotal(int maxTotal) {
            this.maxTotal = maxTotal;
        }

        public int getMaxIdle() {
            return maxIdle;
        }

        public void setMaxIdle(int maxIdle) {
            this.maxIdle = maxIdle;
        }

        public int getMinIdle() {
            return minIdle;
        }

        public void setMinIdle(int minIdle) {
            this.minIdle = minIdle;
        }

        public long getMaxWaitMillis() {
            return maxWaitMillis;
        }

        public void setMaxWaitMillis(long maxWaitMillis) {
            this.maxWaitMillis = maxWaitMillis;
        }

        public boolean isBlockWhenExhausted() {
            return blockWhenExhausted;
        }

        public void setBlockWhenExhausted(boolean blockWhenExhausted) {
            this.blockWhenExhausted = blockWhenExhausted;
        }

        public boolean isTestOnBorrow() {
            return testOnBorrow;
        }

        public void setTestOnBorrow(boolean testOnBorrow) {
            this.testOnBorrow = testOnBorrow;
        }

        public boolean isTestWhileIdle() {
            return testWhileIdle;
        }

        public void setTestWhileIdle(boolean testWhileIdle) {
            this.testWhileIdle = testWhileIdle;
        }

        public long getTimeBetweenEvictionRunsMillis() {
            return timeBetweenEvictionRunsMillis;
        }

        public void setTimeBetweenEvictionRunsMillis(long timeBetweenEvictionRunsMillis) {
            this.timeBetweenEvictionRunsMillis = timeBetweenEvictionRunsMillis;
        }

        public long getMinEvictableIdleTimeMillis() {
            return minEvictableIdleTimeMillis;
        }

        public void setMinEvictableIdleTimeMillis(long minEvictableIdleTimeMillis) {
            this.minEvictableIdleTimeMillis = minEvictableIdleTimeMillis;
        }

        public int getNumTestsPerEvictionRun() {
            return numTestsPerEvictionRun;
        }

        public void setNumTestsPerEvictionRun(int numTestsPerEvictionRun) {
            this.numTestsPerEvictionRun = numTestsPerEvictionRun;
        }

        public int getConnectionTimeoutMillis() {
            return connectionTimeoutMillis;
        }

        public void setConnectionTimeoutMillis(int connectionTimeoutMillis) {
            this.connectionTimeoutMillis = connectionTimeoutMillis;
        }

        public int getReadTimeoutMillis() {
            return readTimeoutMillis;
        }

        public void setReadTimeoutMillis(int readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
        }

        public int getBlockingReadTimeoutMillis() {
            return blockingReadTimeoutMillis;
        }

        public void setBlockingReadTimeoutMillis(int blockingReadTimeoutMillis) {
            this.blockingReadTimeoutMillis = blockingReadTimeoutMillis;
        }
    }

    public static final class BackgroundRedisProperties {
        private boolean enabled = true;
        private String uri;
        private final PoolProperties pool = PoolProperties.backgroundDefaults();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }

        public String resolveUri(String fallbackUri) {
            return uri == null || uri.isBlank() ? fallbackUri : uri;
        }

        public PoolProperties getPool() {
            return pool;
        }
    }

    public static final class AdminUiProperties {
        private boolean enabled = true;
        private String basePath = "/cachedb-admin";
        private boolean dashboardEnabled = true;
        private String title = "CacheDB Admin";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBasePath() {
            return basePath;
        }

        public void setBasePath(String basePath) {
            this.basePath = basePath;
        }

        public boolean isDashboardEnabled() {
            return dashboardEnabled;
        }

        public void setDashboardEnabled(boolean dashboardEnabled) {
            this.dashboardEnabled = dashboardEnabled;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }
    }

    public static final class RuntimeProperties {
        private String instanceId;
        private boolean appendInstanceIdToConsumerNames = true;
        private boolean leaderLeaseEnabled = true;
        private String leaderLeaseSegment = "coordination:leader";
        private long leaderLeaseTtlMillis = 15_000L;
        private long leaderLeaseRenewIntervalMillis = 5_000L;

        public String getInstanceId() {
            return instanceId;
        }

        public void setInstanceId(String instanceId) {
            this.instanceId = instanceId;
        }

        public boolean isAppendInstanceIdToConsumerNames() {
            return appendInstanceIdToConsumerNames;
        }

        public void setAppendInstanceIdToConsumerNames(boolean appendInstanceIdToConsumerNames) {
            this.appendInstanceIdToConsumerNames = appendInstanceIdToConsumerNames;
        }

        public boolean isLeaderLeaseEnabled() {
            return leaderLeaseEnabled;
        }

        public void setLeaderLeaseEnabled(boolean leaderLeaseEnabled) {
            this.leaderLeaseEnabled = leaderLeaseEnabled;
        }

        public String getLeaderLeaseSegment() {
            return leaderLeaseSegment;
        }

        public void setLeaderLeaseSegment(String leaderLeaseSegment) {
            this.leaderLeaseSegment = leaderLeaseSegment;
        }

        public long getLeaderLeaseTtlMillis() {
            return leaderLeaseTtlMillis;
        }

        public void setLeaderLeaseTtlMillis(long leaderLeaseTtlMillis) {
            this.leaderLeaseTtlMillis = leaderLeaseTtlMillis;
        }

        public long getLeaderLeaseRenewIntervalMillis() {
            return leaderLeaseRenewIntervalMillis;
        }

        public void setLeaderLeaseRenewIntervalMillis(long leaderLeaseRenewIntervalMillis) {
            this.leaderLeaseRenewIntervalMillis = leaderLeaseRenewIntervalMillis;
        }
    }

    public static final class RegistrationProperties {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
