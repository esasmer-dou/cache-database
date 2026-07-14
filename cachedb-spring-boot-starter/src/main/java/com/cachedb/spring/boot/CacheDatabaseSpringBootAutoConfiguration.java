package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.core.config.AdminHttpConfig;
import com.reactor.cachedb.core.config.AdminMonitoringConfig;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.RuntimeCoordinationConfig;
import com.reactor.cachedb.core.queue.WriteBehindFlusherFactory;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.CacheDatabaseAdminHttpServer;
import com.reactor.cachedb.starter.CacheDatabaseProfiles;
import com.reactor.cachedb.starter.GeneratedCacheBindingsDiscovery;
import com.reactor.cachedb.starter.MigrationPlannerDemoSupport;
import com.reactor.cachedb.starter.RedisConnectionConfig;
import jakarta.servlet.Servlet;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import redis.clients.jedis.JedisPooled;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;

@AutoConfiguration
@ConditionalOnClass({CacheDatabase.class, DataSource.class, JedisPooled.class, Servlet.class})
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

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public CacheDatabase cacheDatabase(
            @Qualifier("cacheDbJedisPooled") ObjectProvider<JedisPooled> namedForegroundJedisProvider,
            ObjectProvider<JedisPooled> jedisProvider,
            @Qualifier("cacheDbBackgroundJedisPooled") ObjectProvider<JedisPooled> backgroundJedisProvider,
            DataSource dataSource,
            CacheDatabaseConfig config,
            CacheDbSpringProperties properties
    ) {
        JedisPooled jedisPooled = namedForegroundJedisProvider.getIfAvailable(jedisProvider::getIfAvailable);
        JedisPooled backgroundJedis = backgroundJedisProvider.getIfAvailable(() -> jedisPooled);
        CacheDatabase cacheDatabase = new CacheDatabase(jedisPooled, backgroundJedis, dataSource, config);
        if (properties.getRegistration().isEnabled()) {
            GeneratedCacheBindingsDiscovery.registerDiscovered(
                    cacheDatabase,
                    config.resourceLimits().defaultCachePolicy(),
                    resolveRegistrationClassLoader()
            );
        }
        cacheDatabase.start();
        return cacheDatabase;
    }

    @Bean
    @ConditionalOnBean(MigrationPlannerDemoSupport.class)
    public SmartInitializingSingleton cacheDatabaseMigrationPlannerDemoConfigurer(
            CacheDatabase cacheDatabase,
            ObjectProvider<MigrationPlannerDemoSupport> migrationPlannerDemoSupportProvider
    ) {
        return () -> {
            MigrationPlannerDemoSupport support = migrationPlannerDemoSupportProvider.getIfAvailable();
            if (support != null) {
                cacheDatabase.admin().configureMigrationPlannerDemo(support);
            }
        };
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "cachedb.admin", name = "http-enabled", havingValue = "true")
    public CacheDatabaseAdminHttpServer cacheDatabaseSpringBootAdminHandler(
            CacheDatabase cacheDatabase,
            CacheDbSpringProperties properties
    ) {
        CacheDbSpringProperties.AdminUiProperties adminProperties = properties.getAdmin();
        AdminHttpConfig adminHttpConfig = AdminHttpConfig.builder()
                .enabled(false)
                .host("127.0.0.1")
                .port(0)
                .backlog(0)
                .workerThreads(1)
                .dashboardEnabled(adminProperties.isDashboardEnabled())
                .dashboardTitle(adminProperties.getTitle())
                .authEnabled(adminProperties.isAuthEnabled())
                .authToken(adminProperties.getAuthToken())
                .authHeaderName(adminProperties.getAuthHeaderName())
                .requestQueueCapacity(adminProperties.getRequestQueueCapacity())
                .backgroundWorkerThreads(adminProperties.getBackgroundWorkerThreads())
                .backgroundQueueCapacity(adminProperties.getBackgroundQueueCapacity())
                .maxRequestBodyBytes(adminProperties.getMaxRequestBodyBytes())
                .jobStatusTtlSeconds(adminProperties.getJobStatusTtlSeconds())
                .build();
        return cacheDatabase.adminHttpServer(adminHttpConfig);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "cachedb.admin", name = "http-enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public CacheDatabaseAdminPageController cacheDatabaseAdminPageController(
            CacheDatabaseAdminHttpServer adminHandler,
            CacheDbSpringProperties properties
    ) {
        return new CacheDatabaseAdminPageController(adminHandler, properties);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "cachedb.admin", name = "http-enabled", havingValue = "true")
    public ServletRegistrationBean<CacheDatabaseAdminServlet> cacheDatabaseAdminServlet(
            CacheDatabaseAdminHttpServer adminHandler,
            CacheDbSpringProperties properties
    ) {
        String basePath = normalizeBasePath(properties.getAdmin().getBasePath());
        CacheDatabaseAdminServlet servlet = new CacheDatabaseAdminServlet(adminHandler, basePath);
        ServletRegistrationBean<CacheDatabaseAdminServlet> bean = new ServletRegistrationBean<>(
                servlet,
                basePath.isBlank() ? "/api/*" : basePath + "/api/*"
        );
        bean.setName("cacheDbAdminServlet");
        bean.setLoadOnStartup(1);
        return bean;
    }

    private String normalizeBasePath(String basePath) {
        if (basePath == null || basePath.isBlank()) {
            return "/cachedb-admin";
        }
        String normalized = basePath.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
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
            case POSTGRES -> {
                // PostgreSQL remains the starter default through CacheDatabase.
            }
            case MSSQL -> builder.writeBehindFlusherFactory(mssqlWriteBehindFlusherFactory(sqlProperties.getMssql()));
            case CUSTOM -> builder.writeBehindFlusherFactory((dataSource, entityRegistry, writeBehindConfig, collector) -> {
                throw new IllegalStateException(
                        "cachedb.sql.provider=custom requires a CacheDatabaseConfigCustomizer "
                                + "or CacheDatabaseConfig bean that supplies writeBehindFlusherFactory"
                );
            });
        }
    }

    private WriteBehindFlusherFactory mssqlWriteBehindFlusherFactory(CacheDbSpringProperties.MssqlProperties properties) {
        try {
            Class<?> optionsClass = Class.forName("com.reactor.cachedb.mssql.MssqlWriteBehindOptions");
            Object optionsBuilder = optionsClass.getMethod("builder").invoke(null);
            invokeBuilder(optionsBuilder, "lockTimeoutMillis", int.class, properties.getLockTimeoutMillis());
            invokeBuilder(optionsBuilder, "queryTimeoutSeconds", int.class, properties.getQueryTimeoutSeconds());
            invokeBuilder(optionsBuilder, "transactionIsolation", int.class, jdbcIsolation(properties.getTransactionIsolation()));
            invokeBuilder(
                    optionsBuilder,
                    "restoreLockTimeoutAfterTransaction",
                    boolean.class,
                    properties.isRestoreLockTimeoutAfterTransaction()
            );
            Object options = optionsBuilder.getClass().getMethod("build").invoke(optionsBuilder);

            Class<?> flusherClass = Class.forName("com.reactor.cachedb.mssql.MssqlWriteBehindFlusher");
            Object factory = flusherClass.getMethod("factory", optionsClass).invoke(null, options);
            return (WriteBehindFlusherFactory) factory;
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException(
                    "cachedb.sql.provider=mssql requires com.reactor.cachedb:cachedb-storage-mssql on the application classpath",
                    exception
            );
        } catch (InvocationTargetException exception) {
            Throwable target = exception.getTargetException();
            if (target instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Could not create MSSQL write-behind flusher factory", target);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not create MSSQL write-behind flusher factory", exception);
        }
    }

    private void invokeBuilder(Object builder, String methodName, Class<?> valueType, Object value)
            throws ReflectiveOperationException {
        builder.getClass().getMethod(methodName, valueType).invoke(builder, value);
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
}
