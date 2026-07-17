package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.core.cache.EntityHotPolicyCompositeOperator;
import com.reactor.cachedb.core.cache.EntityHotPolicyMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "cachedb")
public class CacheDbSpringProperties {

    private boolean enabled = true;
    private Profile profile = Profile.DEFAULT;
    private final RedisProperties redis = new RedisProperties();
    private final SqlProperties sql = new SqlProperties();
    private final AdminUiProperties admin = new AdminUiProperties();
    private final RegistrationProperties registration = new RegistrationProperties();
    private final RuntimeProperties runtime = new RuntimeProperties();
    private final ScheduledWarmProperties scheduledWarm = new ScheduledWarmProperties();

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

    public SqlProperties getSql() {
        return sql;
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

    public ScheduledWarmProperties getScheduledWarm() {
        return scheduledWarm;
    }

    public enum Profile {
        DEFAULT,
        DEVELOPMENT,
        PRODUCTION,
        BENCHMARK,
        MEMORY_CONSTRAINED,
        MINIMAL_OVERHEAD
    }

    public enum SqlProvider {
        POSTGRES,
        MSSQL,
        CUSTOM
    }

    public enum TransactionIsolation {
        READ_COMMITTED,
        REPEATABLE_READ,
        SERIALIZABLE
    }

    public enum RegistrationSource {
        JDBC,
        METADATA_ONLY
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

    public static final class SqlProperties {
        private SqlProvider provider = SqlProvider.POSTGRES;
        private final MssqlProperties mssql = new MssqlProperties();

        public SqlProvider getProvider() {
            return provider;
        }

        public void setProvider(SqlProvider provider) {
            this.provider = provider == null ? SqlProvider.POSTGRES : provider;
        }

        public MssqlProperties getMssql() {
            return mssql;
        }
    }

    public static final class MssqlProperties {
        private int lockTimeoutMillis = 5_000;
        private int queryTimeoutSeconds = 10;
        private TransactionIsolation transactionIsolation = TransactionIsolation.SERIALIZABLE;
        private boolean restoreLockTimeoutAfterTransaction = true;

        public int getLockTimeoutMillis() {
            return lockTimeoutMillis;
        }

        public void setLockTimeoutMillis(int lockTimeoutMillis) {
            this.lockTimeoutMillis = lockTimeoutMillis;
        }

        public int getQueryTimeoutSeconds() {
            return queryTimeoutSeconds;
        }

        public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
            this.queryTimeoutSeconds = queryTimeoutSeconds;
        }

        public TransactionIsolation getTransactionIsolation() {
            return transactionIsolation;
        }

        public void setTransactionIsolation(TransactionIsolation transactionIsolation) {
            this.transactionIsolation = transactionIsolation == null
                    ? TransactionIsolation.SERIALIZABLE
                    : transactionIsolation;
        }

        public boolean isRestoreLockTimeoutAfterTransaction() {
            return restoreLockTimeoutAfterTransaction;
        }

        public void setRestoreLockTimeoutAfterTransaction(boolean restoreLockTimeoutAfterTransaction) {
            this.restoreLockTimeoutAfterTransaction = restoreLockTimeoutAfterTransaction;
        }
    }

    public static final class AdminUiProperties {
        private boolean enabled = true;
        private boolean httpEnabled;
        private String basePath = "/cachedb-admin";
        private boolean dashboardEnabled = true;
        private String title = "CacheDB Admin";
        private boolean authEnabled;
        private String authToken = "";
        private String authHeaderName = "Authorization";
        private int requestQueueCapacity = 128;
        private int backgroundWorkerThreads = 2;
        private int backgroundQueueCapacity = 32;
        private int maxRequestBodyBytes = 1_048_576;
        private int jobStatusTtlSeconds = 86_400;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isHttpEnabled() {
            return httpEnabled;
        }

        public void setHttpEnabled(boolean httpEnabled) {
            this.httpEnabled = httpEnabled;
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

        public boolean isAuthEnabled() {
            return authEnabled;
        }

        public void setAuthEnabled(boolean authEnabled) {
            this.authEnabled = authEnabled;
        }

        public String getAuthToken() {
            return authToken;
        }

        public void setAuthToken(String authToken) {
            this.authToken = authToken;
        }

        public String getAuthHeaderName() {
            return authHeaderName;
        }

        public void setAuthHeaderName(String authHeaderName) {
            this.authHeaderName = authHeaderName;
        }

        public int getRequestQueueCapacity() {
            return requestQueueCapacity;
        }

        public void setRequestQueueCapacity(int requestQueueCapacity) {
            this.requestQueueCapacity = requestQueueCapacity;
        }

        public int getBackgroundWorkerThreads() {
            return backgroundWorkerThreads;
        }

        public void setBackgroundWorkerThreads(int backgroundWorkerThreads) {
            this.backgroundWorkerThreads = backgroundWorkerThreads;
        }

        public int getBackgroundQueueCapacity() {
            return backgroundQueueCapacity;
        }

        public void setBackgroundQueueCapacity(int backgroundQueueCapacity) {
            this.backgroundQueueCapacity = backgroundQueueCapacity;
        }

        public int getMaxRequestBodyBytes() {
            return maxRequestBodyBytes;
        }

        public void setMaxRequestBodyBytes(int maxRequestBodyBytes) {
            this.maxRequestBodyBytes = maxRequestBodyBytes;
        }

        public int getJobStatusTtlSeconds() {
            return jobStatusTtlSeconds;
        }

        public void setJobStatusTtlSeconds(int jobStatusTtlSeconds) {
            this.jobStatusTtlSeconds = jobStatusTtlSeconds;
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

    public static final class ScheduledWarmProperties {
        private boolean enabled = true;
        private int schedulerPoolSize = 2;
        private int heartbeatThreads = 1;
        private String threadNamePrefix = "cachedb-scheduled-warm-";
        private String lockKeySegment = "coordination:scheduled-warm";
        private long shutdownAwaitMillis = 10_000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getSchedulerPoolSize() {
            return schedulerPoolSize;
        }

        public void setSchedulerPoolSize(int schedulerPoolSize) {
            this.schedulerPoolSize = schedulerPoolSize;
        }

        public int getHeartbeatThreads() {
            return heartbeatThreads;
        }

        public void setHeartbeatThreads(int heartbeatThreads) {
            this.heartbeatThreads = heartbeatThreads;
        }

        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        public void setThreadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
        }

        public String getLockKeySegment() {
            return lockKeySegment;
        }

        public void setLockKeySegment(String lockKeySegment) {
            this.lockKeySegment = lockKeySegment;
        }

        public long getShutdownAwaitMillis() {
            return shutdownAwaitMillis;
        }

        public void setShutdownAwaitMillis(long shutdownAwaitMillis) {
            this.shutdownAwaitMillis = shutdownAwaitMillis;
        }
    }

    public static final class RegistrationProperties {
        private boolean enabled = true;
        private RegistrationSource source = RegistrationSource.METADATA_ONLY;
        private boolean failOnUnknownEntity = true;
        private final Map<String, EntityPolicyProperties> entities = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public RegistrationSource getSource() {
            return source;
        }

        public void setSource(RegistrationSource source) {
            this.source = source == null ? RegistrationSource.METADATA_ONLY : source;
        }

        public boolean isFailOnUnknownEntity() {
            return failOnUnknownEntity;
        }

        public void setFailOnUnknownEntity(boolean failOnUnknownEntity) {
            this.failOnUnknownEntity = failOnUnknownEntity;
        }

        public Map<String, EntityPolicyProperties> getEntities() {
            return entities;
        }
    }

    public static final class EntityPolicyProperties {
        private Integer hotEntityLimit;
        private Integer pageSize;
        private Boolean lruEvictionEnabled;
        private Long entityTtlSeconds;
        private Long pageTtlSeconds;
        private HotPolicyProperties hotPolicy;

        public Integer getHotEntityLimit() {
            return hotEntityLimit;
        }

        public void setHotEntityLimit(Integer hotEntityLimit) {
            this.hotEntityLimit = hotEntityLimit;
        }

        public Integer getPageSize() {
            return pageSize;
        }

        public void setPageSize(Integer pageSize) {
            this.pageSize = pageSize;
        }

        public Boolean getLruEvictionEnabled() {
            return lruEvictionEnabled;
        }

        public void setLruEvictionEnabled(Boolean lruEvictionEnabled) {
            this.lruEvictionEnabled = lruEvictionEnabled;
        }

        public Long getEntityTtlSeconds() {
            return entityTtlSeconds;
        }

        public void setEntityTtlSeconds(Long entityTtlSeconds) {
            this.entityTtlSeconds = entityTtlSeconds;
        }

        public Long getPageTtlSeconds() {
            return pageTtlSeconds;
        }

        public void setPageTtlSeconds(Long pageTtlSeconds) {
            this.pageTtlSeconds = pageTtlSeconds;
        }

        public HotPolicyProperties getHotPolicy() {
            return hotPolicy;
        }

        public void setHotPolicy(HotPolicyProperties hotPolicy) {
            this.hotPolicy = hotPolicy;
        }
    }

    public static final class HotPolicyProperties {
        private EntityHotPolicyMode mode;
        private String timeColumn;
        private Long hotForSeconds;
        private String stateColumn;
        private List<String> stateValues = new ArrayList<>();
        private Boolean admitOnWrite;
        private Boolean admitOnRead;
        private Boolean admitOnWarm;
        private Boolean evictWhenRejected;
        private EntityHotPolicyCompositeOperator compositeOperator;
        private List<HotPolicyProperties> children = new ArrayList<>();

        public EntityHotPolicyMode getMode() {
            return mode;
        }

        public void setMode(EntityHotPolicyMode mode) {
            this.mode = mode;
        }

        public String getTimeColumn() {
            return timeColumn;
        }

        public void setTimeColumn(String timeColumn) {
            this.timeColumn = timeColumn;
        }

        public Long getHotForSeconds() {
            return hotForSeconds;
        }

        public void setHotForSeconds(Long hotForSeconds) {
            this.hotForSeconds = hotForSeconds;
        }

        public String getStateColumn() {
            return stateColumn;
        }

        public void setStateColumn(String stateColumn) {
            this.stateColumn = stateColumn;
        }

        public List<String> getStateValues() {
            return stateValues;
        }

        public void setStateValues(List<String> stateValues) {
            this.stateValues = stateValues == null ? new ArrayList<>() : new ArrayList<>(stateValues);
        }

        public Boolean getAdmitOnWrite() {
            return admitOnWrite;
        }

        public void setAdmitOnWrite(Boolean admitOnWrite) {
            this.admitOnWrite = admitOnWrite;
        }

        public Boolean getAdmitOnRead() {
            return admitOnRead;
        }

        public void setAdmitOnRead(Boolean admitOnRead) {
            this.admitOnRead = admitOnRead;
        }

        public Boolean getAdmitOnWarm() {
            return admitOnWarm;
        }

        public void setAdmitOnWarm(Boolean admitOnWarm) {
            this.admitOnWarm = admitOnWarm;
        }

        public Boolean getEvictWhenRejected() {
            return evictWhenRejected;
        }

        public void setEvictWhenRejected(Boolean evictWhenRejected) {
            this.evictWhenRejected = evictWhenRejected;
        }

        public EntityHotPolicyCompositeOperator getCompositeOperator() {
            return compositeOperator;
        }

        public void setCompositeOperator(EntityHotPolicyCompositeOperator compositeOperator) {
            this.compositeOperator = compositeOperator;
        }

        public List<HotPolicyProperties> getChildren() {
            return children;
        }

        public void setChildren(List<HotPolicyProperties> children) {
            this.children = children == null ? new ArrayList<>() : new ArrayList<>(children);
        }
    }
}
