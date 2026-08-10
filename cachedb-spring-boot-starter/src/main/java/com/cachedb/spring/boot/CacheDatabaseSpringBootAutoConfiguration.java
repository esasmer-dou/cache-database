package com.reactor.cachedb.spring.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactor.cachedb.core.config.AdminHttpConfig;
import com.reactor.cachedb.core.config.AdminMonitoringConfig;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.RuntimeCoordinationConfig;
import com.reactor.cachedb.core.queue.WriteBehindFlusherFactory;
import com.reactor.cachedb.core.cache.CachePolicyCatalog;
import com.reactor.cachedb.jdbc.JdbcStorageProvider;
import com.reactor.cachedb.jdbc.JdbcStorageProviders;
import io.micrometer.core.instrument.MeterRegistry;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.CacheDatabaseProfiles;
import com.reactor.cachedb.starter.GeneratedCacheBindingsDiscovery;
import com.reactor.cachedb.starter.RedisConnectionConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import redis.clients.jedis.JedisPooled;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@AutoConfiguration
@ConditionalOnClass({CacheDatabase.class, DataSource.class, JedisPooled.class})
@EnableConfigurationProperties(CacheDbSpringProperties.class)
@ConditionalOnProperty(prefix = "cachedb", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CacheDatabaseSpringBootAutoConfiguration {

    @Bean(name = "cacheDbJedisPooled", destroyMethod = "close")
    @org.springframework.context.annotation.Primary
    @ConditionalOnMissingBean(name = "cacheDbJedisPooled")
    public JedisPooled cacheDbJedisPooled(CacheDbSpringProperties properties) {
        return toConnectionConfig(properties.getRedis().getUri(), properties.getRedis().getPool()).createClient();
    }

    @Bean(name = "cacheDbBackgroundJedisPooled", destroyMethod = "close")
    @ConditionalOnBean(name = "cacheDbJedisPooled")
    @ConditionalOnMissingBean(name = "cacheDbBackgroundJedisPooled")
    @ConditionalOnProperty(prefix = "cachedb.redis.background", name = "enabled", havingValue = "true", matchIfMissing = true)
    public JedisPooled cacheDbBackgroundJedisPooled(CacheDbSpringProperties properties) {
        CacheDbSpringProperties.BackgroundRedisProperties background = properties.getRedis().getBackground();
        return toConnectionConfig(background.resolveUri(properties.getRedis().getUri()), background.getPool()).createClient();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "cachedb.scheduled-warm", name = "enabled", havingValue = "true", matchIfMissing = true)
    public CacheScheduledWarmRegistry cacheScheduledWarmRegistry() {
        return new CacheScheduledWarmRegistry();
    }

    @Bean(name = "cacheDbScheduledWarmTaskScheduler")
    @ConditionalOnMissingBean(name = "cacheDbScheduledWarmTaskScheduler")
    @ConditionalOnProperty(prefix = "cachedb.scheduled-warm", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ThreadPoolTaskScheduler cacheDbScheduledWarmTaskScheduler(CacheDbSpringProperties properties) {
        CacheDbSpringProperties.ScheduledWarmProperties scheduledWarm = properties.getScheduledWarm();
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, scheduledWarm.getSchedulerPoolSize()));
        String threadNamePrefix = scheduledWarm.getThreadNamePrefix();
        scheduler.setThreadNamePrefix(
                threadNamePrefix == null || threadNamePrefix.isBlank()
                        ? "cachedb-scheduled-warm-"
                        : threadNamePrefix
        );
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationMillis(Math.max(0L, scheduledWarm.getShutdownAwaitMillis()));
        return scheduler;
    }

    @Bean
    @ConditionalOnMissingBean
    public CacheDatabaseConfig cacheDatabaseConfig(
            CacheDbSpringProperties properties,
            org.springframework.beans.factory.ObjectProvider<CacheDatabaseConfigCustomizer> customizers
    ) {
        CacheDatabaseConfig.Builder builder = baseConfigForProfile(properties.getProfile()).toBuilder()
                .runtimeCoordination(RuntimeCoordinationConfig.builder()
                        .instanceId(properties.getRuntime().getInstanceId())
                        .appendInstanceIdToConsumerNames(properties.getRuntime().isAppendInstanceIdToConsumerNames())
                        .leaderLeaseEnabled(properties.getRuntime().isLeaderLeaseEnabled())
                        .leaderLeaseSegment(properties.getRuntime().getLeaderLeaseSegment())
                        .leaderLeaseTtlMillis(properties.getRuntime().getLeaderLeaseTtlMillis())
                        .leaderLeaseRenewIntervalMillis(properties.getRuntime().getLeaderLeaseRenewIntervalMillis())
                        .build())
                .adminMonitoring(AdminMonitoringConfig.builder()
                        .enabled(properties.getAdmin().isEnabled())
                        .build())
                .adminHttp(AdminHttpConfig.builder()
                        .enabled(false)
                        .dashboardEnabled(properties.getAdmin().isDashboardEnabled())
                        .dashboardTitle(properties.getAdmin().getTitle())
                        .authEnabled(properties.getAdmin().isAuthEnabled())
                        .authToken(properties.getAdmin().getAuthToken())
                        .authHeaderName(properties.getAdmin().getAuthHeaderName())
                        .requestQueueCapacity(properties.getAdmin().getRequestQueueCapacity())
                        .backgroundWorkerThreads(properties.getAdmin().getBackgroundWorkerThreads())
                        .backgroundQueueCapacity(properties.getAdmin().getBackgroundQueueCapacity())
                        .maxRequestBodyBytes(properties.getAdmin().getMaxRequestBodyBytes())
                        .jobStatusTtlSeconds(properties.getAdmin().getJobStatusTtlSeconds())
                        .build());
        applySqlProvider(builder, properties.getSql());
        for (CacheDatabaseConfigCustomizer customizer : customizers.orderedStream().toList()) {
            customizer.customize(builder, properties);
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    public CacheDbProviderInfo cacheDbProviderInfo(CacheDbSpringProperties properties) {
        CacheDbSpringProperties.SqlProvider configured = properties.getSql().getProvider();
        if (configured == CacheDbSpringProperties.SqlProvider.CUSTOM) {
            return new CacheDbProviderInfo("custom", "application-supplied", availableProviderIds());
        }
        JdbcStorageProvider provider = resolveProvider(configured);
        return new CacheDbProviderInfo(provider.id(), provider.dialect().getClass().getName(), availableProviderIds());
    }

    @Bean
    @ConditionalOnBean(CacheDatabase.class)
    @ConditionalOnMissingBean
    public CacheDbStartupReporter cacheDbStartupReporter(
            CacheDatabase cacheDatabase,
            CacheDbProviderInfo providerInfo,
            CacheDbSpringProperties properties
    ) {
        return new CacheDbStartupReporter(cacheDatabase, providerInfo, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public CachePolicyCatalog cachePolicyCatalog(
            CacheDbSpringProperties properties,
            CacheDatabaseConfig config,
            ObjectProvider<CachePolicyCatalogCustomizer> customizers
    ) {
        CachePolicyCatalog.Builder builder = CachePolicyCatalog.builder();
        CachePolicyCatalogFactory.addConfiguredPolicies(
                builder,
                properties.getRegistration(),
                config.resourceLimits().defaultCachePolicy()
        );
        for (CachePolicyCatalogCustomizer customizer : customizers.orderedStream().toList()) {
            customizer.customize(builder, properties);
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public CacheDatabase cacheDatabase(
            @Qualifier("cacheDbJedisPooled") ObjectProvider<JedisPooled> namedForegroundJedisProvider,
            ObjectProvider<JedisPooled> jedisProvider,
            @Qualifier("cacheDbBackgroundJedisPooled") ObjectProvider<JedisPooled> backgroundJedisProvider,
            DataSource dataSource,
            CacheDatabaseConfig config,
            CachePolicyCatalog policyCatalog,
            CacheDbSpringProperties properties
    ) {
        JedisPooled jedisPooled = namedForegroundJedisProvider.getIfAvailable(jedisProvider::getIfAvailable);
        JedisPooled backgroundJedis = backgroundJedisProvider.getIfAvailable(() -> jedisPooled);
        CacheDatabase cacheDatabase = new CacheDatabase(jedisPooled, backgroundJedis, dataSource, config);
        if (properties.getRegistration().isEnabled()) {
            if (properties.getRegistration().getSource() == CacheDbSpringProperties.RegistrationSource.JDBC) {
                GeneratedCacheBindingsDiscovery.registerDiscoveredJdbcBacked(
                        cacheDatabase,
                        policyCatalog,
                        resolveRegistrationClassLoader(),
                        properties.getRegistration().isFailOnUnknownEntity()
                );
            } else {
                GeneratedCacheBindingsDiscovery.registerDiscovered(
                        cacheDatabase,
                        config.resourceLimits().defaultCachePolicy(),
                        resolveRegistrationClassLoader()
                );
            }
        }
        cacheDatabase.start();
        return cacheDatabase;
    }

    @Bean
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnBean(CacheDatabase.class)
    @ConditionalOnMissingBean(name = "cacheDatabaseHealthIndicator")
    public CacheDatabaseHealthIndicator cacheDatabaseHealthIndicator(
            CacheDatabase cacheDatabase,
            @Qualifier("cacheDbJedisPooled") JedisPooled jedis,
            DataSource dataSource
    ) {
        return new CacheDatabaseHealthIndicator(cacheDatabase, jedis, dataSource);
    }

    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean({CacheDatabase.class, MeterRegistry.class})
    @ConditionalOnMissingBean
    public CacheDatabaseMetrics cacheDatabaseMetrics(CacheDatabase cacheDatabase) {
        return new CacheDatabaseMetrics(cacheDatabase);
    }

    @Bean
    @ConditionalOnClass(Endpoint.class)
    @ConditionalOnBean(CacheDatabase.class)
    @ConditionalOnMissingBean
    public CacheDatabaseEndpoint cacheDatabaseEndpoint(
            CacheDatabase cacheDatabase,
            CacheDbProviderInfo providerInfo
    ) {
        return new CacheDatabaseEndpoint(cacheDatabase, providerInfo);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnBean(CacheDatabase.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "cachedb.scheduled-warm", name = "enabled", havingValue = "true", matchIfMissing = true)
    CacheScheduledWarmCoordinator cacheScheduledWarmCoordinator(
            CacheDatabase cacheDatabase,
            @Qualifier("cacheDbBackgroundJedisPooled") ObjectProvider<JedisPooled> backgroundJedisProvider,
            @Qualifier("cacheDbJedisPooled") ObjectProvider<JedisPooled> foregroundJedisProvider,
            CacheScheduledWarmRegistry registry,
            CacheDbSpringProperties properties
    ) {
        JedisPooled jedis = backgroundJedisProvider.getIfAvailable(foregroundJedisProvider::getIfAvailable);
        if (jedis == null) {
            throw new IllegalStateException("@CacheScheduledWarm requires a CacheDB Redis client");
        }
        CacheDbSpringProperties.ScheduledWarmProperties scheduledWarm = properties.getScheduledWarm();
        return new CacheScheduledWarmCoordinator(
                cacheDatabase,
                jedis,
                registry,
                scheduledWarm.getLockKeySegment(),
                scheduledWarm.getHeartbeatThreads(),
                scheduledWarm.getShutdownAwaitMillis()
        );
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnBean(CacheDatabase.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "cachedb.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
    public CacheDistributedJobExecutor cacheDistributedJobExecutor(
            CacheDatabase cacheDatabase,
            @Qualifier("cacheDbBackgroundJedisPooled") ObjectProvider<JedisPooled> backgroundJedisProvider,
            @Qualifier("cacheDbJedisPooled") ObjectProvider<JedisPooled> foregroundJedisProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            ObjectProvider<CacheDistributedJobHandler<?>> handlerProvider,
            CacheDbSpringProperties properties
    ) {
        JedisPooled jedis = backgroundJedisProvider.getIfAvailable(foregroundJedisProvider::getIfAvailable);
        if (jedis == null) {
            throw new IllegalStateException("CacheDB distributed jobs require a CacheDB Redis client");
        }
        return new CacheDistributedJobExecutor(
                jedis,
                objectMapperProvider.getIfAvailable(ObjectMapper::new),
                cacheDatabase.instanceId(),
                properties.getJobs(),
                handlerProvider.orderedStream().toList()
        );
    }

    /**
     * Compatibility entry point for callers that instantiated the auto-configuration directly.
     * Spring uses the handler-aware {@link Bean} method above.
     */
    @Deprecated(since = "0.5.0", forRemoval = false)
    public CacheDistributedJobExecutor cacheDistributedJobExecutor(
            CacheDatabase cacheDatabase,
            ObjectProvider<JedisPooled> backgroundJedisProvider,
            ObjectProvider<JedisPooled> foregroundJedisProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            CacheDbSpringProperties properties
    ) {
        JedisPooled jedis = backgroundJedisProvider.getIfAvailable(foregroundJedisProvider::getIfAvailable);
        if (jedis == null) {
            throw new IllegalStateException("CacheDB distributed jobs require a CacheDB Redis client");
        }
        return new CacheDistributedJobExecutor(
                jedis,
                objectMapperProvider.getIfAvailable(ObjectMapper::new),
                cacheDatabase.instanceId(),
                properties.getJobs(),
                List.of()
        );
    }

    @Bean
    @ConditionalOnBean(CacheScheduledWarmCoordinator.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "cachedb.scheduled-warm", name = "enabled", havingValue = "true", matchIfMissing = true)
    CacheScheduledWarmRegistrar cacheScheduledWarmRegistrar(
            ConfigurableListableBeanFactory beanFactory,
            @Qualifier("cacheDbScheduledWarmTaskScheduler") ThreadPoolTaskScheduler taskScheduler,
            CacheScheduledWarmCoordinator coordinator,
            ObjectProvider<CacheScheduledWarmTask> taskProvider
    ) {
        return new CacheScheduledWarmRegistrar(
                beanFactory,
                taskScheduler,
                coordinator,
                taskProvider.orderedStream().toList()
        );
    }

    /**
     * Preserves the pre-0.4.0 factory surface for direct auto-configuration callers.
     * Spring uses the policy-catalog-aware {@link Bean} method above.
     */
    @Deprecated(since = "0.4.0", forRemoval = false)
    public CacheDatabase cacheDatabase(
            ObjectProvider<JedisPooled> namedForegroundJedisProvider,
            ObjectProvider<JedisPooled> jedisProvider,
            ObjectProvider<JedisPooled> backgroundJedisProvider,
            DataSource dataSource,
            CacheDatabaseConfig config,
            CacheDbSpringProperties properties
    ) {
        CachePolicyCatalog.Builder policies = CachePolicyCatalog.builder();
        CachePolicyCatalogFactory.addConfiguredPolicies(
                policies,
                properties.getRegistration(),
                config.resourceLimits().defaultCachePolicy()
        );
        return cacheDatabase(
                namedForegroundJedisProvider,
                jedisProvider,
                backgroundJedisProvider,
                dataSource,
                config,
                policies.build(),
                properties
        );
    }

    private RedisConnectionConfig toConnectionConfig(String uri, CacheDbSpringProperties.PoolProperties poolProperties) {
        return RedisConnectionConfig.builder()
                .uri(uri)
                .poolMaxTotal(poolProperties.getMaxTotal())
                .poolMaxIdle(poolProperties.getMaxIdle())
                .poolMinIdle(poolProperties.getMinIdle())
                .poolMaxWaitMillis(poolProperties.getMaxWaitMillis())
                .blockWhenExhausted(poolProperties.isBlockWhenExhausted())
                .testOnBorrow(poolProperties.isTestOnBorrow())
                .testWhileIdle(poolProperties.isTestWhileIdle())
                .timeBetweenEvictionRunsMillis(poolProperties.getTimeBetweenEvictionRunsMillis())
                .minEvictableIdleTimeMillis(poolProperties.getMinEvictableIdleTimeMillis())
                .numTestsPerEvictionRun(poolProperties.getNumTestsPerEvictionRun())
                .connectionTimeoutMillis(poolProperties.getConnectionTimeoutMillis())
                .readTimeoutMillis(poolProperties.getReadTimeoutMillis())
                .blockingReadTimeoutMillis(poolProperties.getBlockingReadTimeoutMillis())
                .build();
    }

    private CacheDatabaseConfig baseConfigForProfile(CacheDbSpringProperties.Profile profile) {
        if (profile == null) {
            return CacheDatabaseConfig.defaults();
        }
        return switch (profile) {
            case DEVELOPMENT -> CacheDatabaseProfiles.development();
            case PRODUCTION -> CacheDatabaseProfiles.production();
            case BENCHMARK -> CacheDatabaseProfiles.benchmark();
            case MEMORY_CONSTRAINED -> CacheDatabaseProfiles.memoryConstrained();
            case MINIMAL_OVERHEAD -> CacheDatabaseProfiles.minimalOverhead();
            case DEFAULT -> CacheDatabaseConfig.defaults();
        };
    }

    private void applySqlProvider(CacheDatabaseConfig.Builder builder, CacheDbSpringProperties.SqlProperties sqlProperties) {
        if (sqlProperties == null || sqlProperties.getProvider() == null) {
            return;
        }
        switch (sqlProperties.getProvider()) {
            case AUTO -> {
                JdbcStorageProvider provider = resolveProvider(CacheDbSpringProperties.SqlProvider.AUTO);
                Map<String, String> options = provider.id().equals("mssql")
                        ? mssqlOptions(sqlProperties.getMssql())
                        : Map.of();
                builder.writeBehindFlusherFactory(provider.writeBehindFlusherFactory(options));
            }
            case POSTGRES -> builder.writeBehindFlusherFactory(providerFactory("postgres", Map.of()));
            case MSSQL -> builder.writeBehindFlusherFactory(providerFactory(
                    "mssql", mssqlOptions(sqlProperties.getMssql())
            ));
            case CUSTOM -> builder.writeBehindFlusherFactory((dataSource, entityRegistry, writeBehindConfig, collector) -> {
                throw new IllegalStateException(
                        "cachedb.sql.provider=custom requires a CacheDatabaseConfigCustomizer "
                                + "or CacheDatabaseConfig bean that supplies writeBehindFlusherFactory"
                );
            });
        }
    }

    private WriteBehindFlusherFactory providerFactory(String providerId, Map<String, String> options) {
        JdbcStorageProvider provider = JdbcStorageProviders.require(providerId, resolveRegistrationClassLoader());
        return provider.writeBehindFlusherFactory(options);
    }

    private JdbcStorageProvider resolveProvider(CacheDbSpringProperties.SqlProvider configured) {
        if (configured == null || configured == CacheDbSpringProperties.SqlProvider.AUTO) {
            return JdbcStorageProviders.requireSingle(resolveRegistrationClassLoader());
        }
        String id = configured == CacheDbSpringProperties.SqlProvider.MSSQL ? "mssql" : "postgres";
        return JdbcStorageProviders.require(id, resolveRegistrationClassLoader());
    }

    private Map<String, String> mssqlOptions(CacheDbSpringProperties.MssqlProperties properties) {
        LinkedHashMap<String, String> options = new LinkedHashMap<>();
        options.put("lockTimeoutMillis", String.valueOf(properties.getLockTimeoutMillis()));
        options.put("queryTimeoutSeconds", String.valueOf(properties.getQueryTimeoutSeconds()));
        options.put("transactionIsolation", String.valueOf(jdbcIsolation(properties.getTransactionIsolation())));
        options.put("restoreLockTimeoutAfterTransaction", String.valueOf(properties.isRestoreLockTimeoutAfterTransaction()));
        return Map.copyOf(options);
    }

    private int jdbcIsolation(CacheDbSpringProperties.TransactionIsolation isolation) {
        CacheDbSpringProperties.TransactionIsolation resolved = isolation == null
                ? CacheDbSpringProperties.TransactionIsolation.SERIALIZABLE
                : isolation;
        return switch (resolved) {
            case READ_COMMITTED -> Connection.TRANSACTION_READ_COMMITTED;
            case REPEATABLE_READ -> Connection.TRANSACTION_REPEATABLE_READ;
            case SERIALIZABLE -> Connection.TRANSACTION_SERIALIZABLE;
        };
    }

    private ClassLoader resolveRegistrationClassLoader() {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        return contextClassLoader != null ? contextClassLoader : CacheDatabaseSpringBootAutoConfiguration.class.getClassLoader();
    }

    private List<String> availableProviderIds() {
        return JdbcStorageProviders.discover(resolveRegistrationClassLoader()).stream()
                .map(JdbcStorageProvider::id)
                .toList();
    }
}
