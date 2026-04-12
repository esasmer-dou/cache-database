package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.core.config.AdminHttpConfig;
import com.reactor.cachedb.core.config.AdminMonitoringConfig;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.RuntimeCoordinationConfig;
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
                        .build());
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
    @ConditionalOnProperty(prefix = "cachedb.admin", name = "enabled", havingValue = "true", matchIfMissing = true)
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
                .build();
        return cacheDatabase.adminHttpServer(adminHttpConfig);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "cachedb.admin", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public CacheDatabaseAdminPageController cacheDatabaseAdminPageController(
            CacheDatabaseAdminHttpServer adminHandler,
            CacheDbSpringProperties properties
    ) {
        return new CacheDatabaseAdminPageController(adminHandler, properties);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "cachedb.admin", name = "enabled", havingValue = "true", matchIfMissing = true)
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

    private ClassLoader resolveRegistrationClassLoader() {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        return contextClassLoader != null ? contextClassLoader : CacheDatabaseSpringBootAutoConfiguration.class.getClassLoader();
    }
}
