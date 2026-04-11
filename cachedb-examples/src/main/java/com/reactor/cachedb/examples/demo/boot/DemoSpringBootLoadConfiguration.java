package com.reactor.cachedb.examples.demo.boot;

import com.reactor.cachedb.core.config.AdminHttpConfig;
import com.reactor.cachedb.core.config.AdminMonitoringConfig;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.DeadLetterRecoveryConfig;
import com.reactor.cachedb.core.config.KeyspaceConfig;
import com.reactor.cachedb.core.config.RedisFunctionsConfig;
import com.reactor.cachedb.core.config.SchemaBootstrapConfig;
import com.reactor.cachedb.core.config.SchemaBootstrapMode;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.examples.demo.DemoBulkSeedBootstrapper;
import com.reactor.cachedb.examples.demo.DemoEnvironmentResetter;
import com.reactor.cachedb.examples.demo.DemoScenarioService;
import com.reactor.cachedb.examples.demo.DemoScenarioTuning;
import com.reactor.cachedb.spring.boot.CacheDbSpringProperties;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.GeneratedCacheBindingsDiscovery;
import com.reactor.cachedb.starter.RedisConnectionConfig;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.postgresql.ds.PGSimpleDataSource;
import redis.clients.jedis.JedisPooled;

import javax.sql.DataSource;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

@Configuration
public class DemoSpringBootLoadConfiguration {

    @Bean
    public DataSource demoSpringBootDataSource(org.springframework.core.env.Environment environment) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(environment.getProperty(
                "spring.datasource.url",
                "jdbc:postgresql://127.0.0.1:55432/postgres"
        ));
        dataSource.setUser(environment.getProperty("spring.datasource.username", "postgres"));
        dataSource.setPassword(environment.getProperty("spring.datasource.password", "postgresql"));
        return dataSource;
    }

    @Bean
    public DemoScenarioTuning demoScenarioTuning(org.springframework.core.env.Environment environment) {
        Properties properties = new Properties();
        properties.putAll(System.getProperties());
        BindResult<Map<String, Object>> bindResult = Binder.get(environment)
                .bind("cachedb.demo", Bindable.mapOf(String.class, Object.class));
        flattenInto("cachedb.demo", bindResult.orElse(Map.of()), properties);
        return DemoScenarioTuning.fromProperties(properties, "cachedb.demo");
    }

    @Bean
    public CacheDatabaseConfig cacheDatabaseConfig(
            org.springframework.core.env.Environment environment,
            CacheDbSpringProperties springProperties
    ) {
        String keyPrefix = environment.getProperty("cachedb.demo.keyPrefix", "cachedb-demo");
        String functionPrefix = environment.getProperty(
                "cachedb.demo.functionPrefix",
                "demo_" + UUID.randomUUID().toString().replace("-", "_")
        );
        String writeBehindStreamKey = keyPrefix + ":stream:write-behind";
        String incidentStreamKey = keyPrefix + ":stream:admin:incidents";
        String monitoringHistoryStreamKey = keyPrefix + ":stream:admin:monitoring-history";
        String alertRouteHistoryStreamKey = keyPrefix + ":stream:admin:alert-route-history";
        String performanceHistoryStreamKey = keyPrefix + ":stream:admin:performance-history";
        String performanceSnapshotKey = keyPrefix + ":hash:admin:performance";
        String diagnosticsStreamKey = keyPrefix + ":stream:admin:diagnostics";

        return CacheDatabaseConfig.builder()
                .keyspace(KeyspaceConfig.builder()
                        .keyPrefix(keyPrefix)
                        .build())
                .writeBehind(WriteBehindConfig.builder()
                        .streamKey(writeBehindStreamKey)
                        .consumerGroup(keyPrefix + "-write-behind")
                        .consumerNamePrefix(keyPrefix + "-worker")
                        .compactionStreamKey(writeBehindStreamKey + ":compaction")
                        .compactionConsumerGroup(keyPrefix + "-write-behind-compaction")
                        .compactionConsumerNamePrefix(keyPrefix + "-compaction-worker")
                        .deadLetterStreamKey(writeBehindStreamKey + ":dlq")
                        .build())
                .deadLetterRecovery(DeadLetterRecoveryConfig.builder()
                        .consumerGroup(keyPrefix + "-write-behind-dlq")
                        .consumerNamePrefix(keyPrefix + "-dlq-worker")
                        .reconciliationStreamKey(writeBehindStreamKey + ":reconciliation")
                        .archiveStreamKey(writeBehindStreamKey + ":archive")
                        .build())
                .adminMonitoring(AdminMonitoringConfig.builder()
                        .enabled(springProperties.getAdmin().isEnabled())
                        .monitoringHistoryStreamKey(monitoringHistoryStreamKey)
                        .alertRouteHistoryStreamKey(alertRouteHistoryStreamKey)
                        .performanceHistoryStreamKey(performanceHistoryStreamKey)
                        .performanceSnapshotKey(performanceSnapshotKey)
                        .incidentStreamKey(incidentStreamKey)
                        .build())
                .adminReportJob(com.reactor.cachedb.core.config.AdminReportJobConfig.builder()
                        .persistDiagnostics(true)
                        .diagnosticsStreamKey(diagnosticsStreamKey)
                        .build())
                .schemaBootstrap(SchemaBootstrapConfig.builder()
                        .mode(SchemaBootstrapMode.CREATE_IF_MISSING)
                        .autoApplyOnStart(true)
                        .build())
                .redisFunctions(RedisFunctionsConfig.builder()
                        .enabled(true)
                        .autoLoadLibrary(true)
                        .replaceLibraryOnLoad(true)
                        .strictLoading(true)
                        .libraryName(functionPrefix)
                        .upsertFunctionName(functionPrefix + "_entity_upsert")
                        .deleteFunctionName(functionPrefix + "_entity_delete")
                        .compactionCompleteFunctionName(functionPrefix + "_compaction_complete")
                        .build())
                .adminHttp(AdminHttpConfig.builder()
                        .enabled(false)
                        .dashboardEnabled(springProperties.getAdmin().isDashboardEnabled())
                        .dashboardTitle(springProperties.getAdmin().getTitle())
                        .build())
                .build();
    }

    @Bean(name = "demoSpringBootForegroundJedisPooled", destroyMethod = "close")
    @Primary
    public JedisPooled demoSpringBootJedisPooled(
            org.springframework.core.env.Environment environment,
            CacheDbSpringProperties springProperties
    ) {
        return buildRedisClient(environment, springProperties, "cachedb.demo.redis", 320, 96, 24);
    }

    @Bean(name = "demoSpringBootBackgroundJedisPooled", destroyMethod = "close")
    public JedisPooled demoSpringBootBackgroundJedisPooled(
            org.springframework.core.env.Environment environment,
            CacheDbSpringProperties springProperties
    ) {
        return buildRedisClient(environment, springProperties, "cachedb.demo.redis.background", 96, 32, 8);
    }

    @Bean(destroyMethod = "close")
    public CacheDatabase cacheDatabase(
            @Qualifier("demoSpringBootForegroundJedisPooled") JedisPooled jedisPooled,
            @Qualifier("demoSpringBootBackgroundJedisPooled") JedisPooled backgroundJedisPooled,
            DataSource dataSource,
            CacheDatabaseConfig config,
            DemoScenarioTuning tuning
    ) {
        CacheDatabase cacheDatabase = new CacheDatabase(jedisPooled, backgroundJedisPooled, dataSource, config);
        GeneratedCacheBindingsDiscovery.registerDiscovered(
                cacheDatabase,
                tuning.cachePolicy(),
                resolveRegistrationClassLoader()
        );
        cacheDatabase.start();
        cacheDatabase.admin().applySchemaMigrationPlan();
        return cacheDatabase;
    }

    @Bean(destroyMethod = "close")
    public DemoScenarioService demoScenarioService(
            CacheDatabase cacheDatabase,
            DemoScenarioTuning tuning,
            @Qualifier("demoSpringBootForegroundJedisPooled") JedisPooled jedisPooled,
            DataSource dataSource,
            org.springframework.core.env.Environment environment
    ) {
        String keyPrefix = environment.getProperty("cachedb.demo.keyPrefix", "cachedb-demo");
        String redisUri = environment.getProperty(
                "cachedb.demo.redis.uri",
                environment.getProperty("cachedb.demo.redisUri", "redis://default:welcome1@127.0.0.1:56379/15")
        );
        boolean dedicatedRedisDb = usesDedicatedRedisDatabase(redisUri);
        Runnable resetter = () -> new DemoEnvironmentResetter(
                jedisPooled,
                keyPrefix,
                dataSource,
                cacheDatabase.admin(),
                dedicatedRedisDb
        ).reset();
        Runnable clearDataResetter = () -> new DemoEnvironmentResetter(
                jedisPooled,
                keyPrefix,
                dataSource,
                cacheDatabase.admin(),
                dedicatedRedisDb
        ).clearDataOnly();
        return new DemoScenarioService(
                cacheDatabase,
                tuning,
                resetter,
                clearDataResetter,
                new DemoBulkSeedBootstrapper(jedisPooled, dataSource, cacheDatabase.admin(), keyPrefix, dedicatedRedisDb)
        );
    }

    private static boolean usesDedicatedRedisDatabase(String redisUri) {
        if (redisUri == null || redisUri.isBlank()) {
            return false;
        }
        String path = URI.create(redisUri).getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            return false;
        }
        try {
            return Integer.parseInt(path.replace("/", "")) > 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static JedisPooled buildRedisClient(
            org.springframework.core.env.Environment environment,
            CacheDbSpringProperties springProperties,
            String prefix,
            int defaultMaxTotal,
            int defaultMaxIdle,
            int defaultMinIdle
    ) {
        String redisUri = environment.getProperty(
                prefix + ".uri",
                environment.getProperty("cachedb.demo.redis.uri",
                        environment.getProperty("cachedb.demo.redisUri", springProperties.getRedisUri()))
        );
        RedisConnectionConfig connectionConfig = RedisConnectionConfig.builder()
                .uri(redisUri)
                .poolMaxTotal(environment.getProperty(prefix + ".pool.maxTotal", Integer.class, defaultMaxTotal))
                .poolMaxIdle(environment.getProperty(prefix + ".pool.maxIdle", Integer.class, defaultMaxIdle))
                .poolMinIdle(environment.getProperty(prefix + ".pool.minIdle", Integer.class, defaultMinIdle))
                .poolMaxWaitMillis(environment.getProperty(prefix + ".pool.maxWaitMillis", Long.class, 30_000L))
                .blockWhenExhausted(environment.getProperty(prefix + ".pool.blockWhenExhausted", Boolean.class, true))
                .testOnBorrow(environment.getProperty(prefix + ".pool.testOnBorrow", Boolean.class, false))
                .testWhileIdle(environment.getProperty(prefix + ".pool.testWhileIdle", Boolean.class, false))
                .timeBetweenEvictionRunsMillis(environment.getProperty(
                        prefix + ".pool.timeBetweenEvictionRunsMillis",
                        Long.class,
                        30_000L
                ))
                .minEvictableIdleTimeMillis(environment.getProperty(
                        prefix + ".pool.minEvictableIdleTimeMillis",
                        Long.class,
                        60_000L
                ))
                .numTestsPerEvictionRun(environment.getProperty(
                        prefix + ".pool.numTestsPerEvictionRun",
                        Integer.class,
                        3
                ))
                .connectionTimeoutMillis(environment.getProperty(
                        prefix + ".pool.connectionTimeoutMillis",
                        Integer.class,
                        5_000
                ))
                .readTimeoutMillis(environment.getProperty(
                        prefix + ".pool.readTimeoutMillis",
                        Integer.class,
                        prefix.contains(".background") ? 10_000 : 8_000
                ))
                .blockingReadTimeoutMillis(environment.getProperty(
                        prefix + ".pool.blockingReadTimeoutMillis",
                        Integer.class,
                        prefix.contains(".background") ? 30_000 : 20_000
                ))
                .build();
        return connectionConfig.createClient();
    }

    @SuppressWarnings("unchecked")
    private static void flattenInto(String prefix, Map<String, Object> source, Properties properties) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                LinkedHashMap<String, Object> nestedMap = new LinkedHashMap<>();
                for (Map.Entry<?, ?> nestedEntry : nested.entrySet()) {
                    nestedMap.put(String.valueOf(nestedEntry.getKey()), nestedEntry.getValue());
                }
                flattenInto(key, nestedMap, properties);
            } else if (value != null) {
                properties.setProperty(key, String.valueOf(value));
            }
        }
    }

    private static ClassLoader resolveRegistrationClassLoader() {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        return contextClassLoader != null ? contextClassLoader : DemoSpringBootLoadConfiguration.class.getClassLoader();
    }
}
