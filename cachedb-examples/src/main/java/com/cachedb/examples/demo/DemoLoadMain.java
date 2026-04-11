package com.reactor.cachedb.examples.demo;

import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.AdminHttpConfig;
import com.reactor.cachedb.core.config.AdminMonitoringConfig;
import com.reactor.cachedb.core.config.DeadLetterRecoveryConfig;
import com.reactor.cachedb.core.config.KeyspaceConfig;
import com.reactor.cachedb.core.config.RedisFunctionsConfig;
import com.reactor.cachedb.core.config.SchemaBootstrapConfig;
import com.reactor.cachedb.core.config.SchemaBootstrapMode;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.starter.CacheDatabaseBootstrapFactory;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.CacheDatabaseAdminHttpServer;
import com.reactor.cachedb.starter.GeneratedCacheBindingsDiscovery;
import com.reactor.cachedb.starter.RedisConnectionConfig;
import redis.clients.jedis.JedisPooled;

import java.net.URI;
import java.util.Properties;
import java.util.UUID;

public final class DemoLoadMain {

    private DemoLoadMain() {
    }

    public static void main(String[] args) throws Exception {
        String redisUri = System.getProperty("cachedb.demo.redisUri", "redis://default:welcome1@127.0.0.1:56379");
        String jdbcUrl = System.getProperty("cachedb.demo.jdbcUrl", "jdbc:postgresql://127.0.0.1:55432/postgres");
        String jdbcUser = System.getProperty("cachedb.demo.jdbcUser", "postgres");
        String jdbcPassword = System.getProperty("cachedb.demo.jdbcPassword", "postgresql");
        String bindHost = System.getProperty("cachedb.demo.bindHost", "0.0.0.0");
        String publicHost = System.getProperty("cachedb.demo.publicHost", "127.0.0.1");
        int adminPort = Integer.getInteger("cachedb.demo.admin.port", 8080);
        int demoPort = Integer.getInteger("cachedb.demo.ui.port", 8090);
        String keyPrefix = System.getProperty("cachedb.demo.keyPrefix", "cachedb-demo");
        boolean dedicatedRedisDb = usesDedicatedRedisDatabase(redisUri);
        String functionPrefix = System.getProperty(
                "cachedb.demo.functionPrefix",
                "demo_" + UUID.randomUUID().toString().replace("-", "_")
        );
        DemoScenarioTuning tuning = DemoScenarioTuning.fromSystemProperties("cachedb.demo");
        String writeBehindStreamKey = keyPrefix + ":stream:write-behind";
        String compactionStreamKey = writeBehindStreamKey + ":compaction";
        String deadLetterStreamKey = writeBehindStreamKey + ":dlq";
        String reconciliationStreamKey = writeBehindStreamKey + ":reconciliation";
        String archiveStreamKey = writeBehindStreamKey + ":archive";
        String incidentStreamKey = keyPrefix + ":stream:admin:incidents";
        String monitoringHistoryStreamKey = keyPrefix + ":stream:admin:monitoring-history";
        String alertRouteHistoryStreamKey = keyPrefix + ":stream:admin:alert-route-history";
        String performanceHistoryStreamKey = keyPrefix + ":stream:admin:performance-history";
        String performanceSnapshotKey = keyPrefix + ":hash:admin:performance";
        String diagnosticsStreamKey = keyPrefix + ":stream:admin:diagnostics";
        Properties demoRedisDefaults = new Properties();
        demoRedisDefaults.putAll(System.getProperties());
        demoRedisDefaults.putIfAbsent("cachedb.demo.redis.uri", redisUri);
        demoRedisDefaults.putIfAbsent("cachedb.demo.redis.pool.maxTotal", "192");
        demoRedisDefaults.putIfAbsent("cachedb.demo.redis.pool.maxIdle", "48");
        demoRedisDefaults.putIfAbsent("cachedb.demo.redis.pool.minIdle", "12");
        demoRedisDefaults.putIfAbsent("cachedb.demo.redis.pool.maxWaitMillis", "10000");
        JedisPooled jedis = RedisConnectionConfig.fromProperties(demoRedisDefaults, "cachedb.demo.redis", redisUri).createClient();
        CacheDatabaseConfig baseConfig = CacheDatabaseConfig.builder()
                        .keyspace(KeyspaceConfig.builder()
                                .keyPrefix(keyPrefix)
                                .build())
                        .writeBehind(WriteBehindConfig.builder()
                                .streamKey(writeBehindStreamKey)
                                .consumerGroup(keyPrefix + "-write-behind")
                                .consumerNamePrefix(keyPrefix + "-worker")
                                .compactionStreamKey(compactionStreamKey)
                                .compactionConsumerGroup(keyPrefix + "-write-behind-compaction")
                                .compactionConsumerNamePrefix(keyPrefix + "-compaction-worker")
                                .deadLetterStreamKey(deadLetterStreamKey)
                                .build())
                        .deadLetterRecovery(DeadLetterRecoveryConfig.builder()
                                .consumerGroup(keyPrefix + "-write-behind-dlq")
                                .consumerNamePrefix(keyPrefix + "-dlq-worker")
                                .reconciliationStreamKey(reconciliationStreamKey)
                                .archiveStreamKey(archiveStreamKey)
                                .build())
                        .adminMonitoring(AdminMonitoringConfig.builder()
                                .enabled(true)
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
                                .enabled(true)
                                .host(bindHost)
                                .port(adminPort)
                                .workerThreads(Integer.getInteger("cachedb.demo.admin.workerThreads", 12))
                                .dashboardEnabled(true)
                                .dashboardTitle(System.getProperty("cachedb.demo.admin.title", "CacheDB Operations Console"))
                                .build())
                        .build();
        CacheDatabaseConfig config = CacheDatabaseBootstrapFactory.applyGlobalAndScopedOverrides(
                baseConfig,
                "cachedb.demo.config"
        );
        CacheDatabase cacheDatabase = new CacheDatabase(
                jedis,
                CacheDatabaseBootstrapFactory.postgresDataSource("cachedb.demo.postgres", jdbcUrl, jdbcUser, jdbcPassword),
                config
        );
        GeneratedCacheBindingsDiscovery.registerDiscovered(
                cacheDatabase,
                tuning.cachePolicy(),
                DemoLoadMain.class.getClassLoader()
        );
        cacheDatabase.start();
        cacheDatabase.admin().applySchemaMigrationPlan();

        CacheDatabaseAdminHttpServer adminHttpServer = cacheDatabase.adminHttpServer();
        adminHttpServer.start();

        DemoScenarioService service = new DemoScenarioService(
                cacheDatabase,
                tuning,
                () -> new DemoEnvironmentResetter(
                        jedis,
                        keyPrefix,
                        jdbcUrl,
                        jdbcUser,
                        jdbcPassword,
                        cacheDatabase.admin(),
                        dedicatedRedisDb
                ).reset(),
                () -> new DemoEnvironmentResetter(
                        jedis,
                        keyPrefix,
                        jdbcUrl,
                        jdbcUser,
                        jdbcPassword,
                        cacheDatabase.admin(),
                        dedicatedRedisDb
                ).clearDataOnly(),
                null
        );
        String adminDashboardUrl = "http://" + publicHost + ":" + adminPort + "/dashboard-v3?lang=tr";
        DemoScenarioHttpServer demoServer = new DemoScenarioHttpServer(
                service,
                bindHost,
                publicHost,
                demoPort,
                adminDashboardUrl,
                tuning
        );
        demoServer.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            demoServer.close();
            adminHttpServer.close();
            cacheDatabase.close();
            jedis.close();
        }));

        System.out.println("Demo load UI listening on http://" + publicHost + ":" + demoPort);
        System.out.println("Admin dashboard listening on " + adminDashboardUrl);
        Thread.currentThread().join();
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
}
