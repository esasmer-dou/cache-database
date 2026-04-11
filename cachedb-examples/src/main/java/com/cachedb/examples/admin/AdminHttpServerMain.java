package com.reactor.cachedb.examples.admin;

import com.reactor.cachedb.core.config.AdminHttpConfig;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.starter.CacheDatabaseBootstrapFactory;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.CacheDatabaseAdminHttpServer;
import com.reactor.cachedb.starter.GeneratedCacheBindingsDiscovery;
import redis.clients.jedis.JedisPooled;

public final class AdminHttpServerMain {

    private AdminHttpServerMain() {
    }

    public static void main(String[] args) throws Exception {
        String redisUri = System.getProperty("cachedb.admin.redisUri", "redis://default:welcome1@127.0.0.1:6379");
        String jdbcUrl = System.getProperty("cachedb.admin.jdbcUrl", "jdbc:postgresql://127.0.0.1:5432/postgres");
        String jdbcUser = System.getProperty("cachedb.admin.jdbcUser", "postgres");
        String jdbcPassword = System.getProperty("cachedb.admin.jdbcPassword", "postgresql");

        JedisPooled jedis = CacheDatabaseBootstrapFactory.redisClient("cachedb.admin.redis", redisUri);
        CacheDatabaseConfig baseConfig = CacheDatabaseConfig.builder()
                .adminHttp(AdminHttpConfig.builder()
                        .enabled(true)
                        .host(System.getProperty("cachedb.admin.http.host", "127.0.0.1"))
                        .port(Integer.getInteger("cachedb.admin.http.port", 8080))
                        .workerThreads(Integer.getInteger("cachedb.admin.http.workerThreads", 2))
                        .dashboardTitle(System.getProperty("cachedb.admin.http.title", "CacheDB Admin"))
                        .build())
                .build();
        CacheDatabaseConfig config = CacheDatabaseBootstrapFactory.applyGlobalAndScopedOverrides(
                baseConfig,
                "cachedb.admin.config"
        );
        CacheDatabase cacheDatabase = new CacheDatabase(
                jedis,
                CacheDatabaseBootstrapFactory.postgresDataSource("cachedb.admin.postgres", jdbcUrl, jdbcUser, jdbcPassword),
                config
        );
        GeneratedCacheBindingsDiscovery.registerDiscovered(
                cacheDatabase,
                cacheDatabase.config().resourceLimits().defaultCachePolicy(),
                AdminHttpServerMain.class.getClassLoader()
        );
        cacheDatabase.start();

        CacheDatabaseAdminHttpServer httpServer = cacheDatabase.adminHttpServer();
        httpServer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            httpServer.close();
            cacheDatabase.close();
            jedis.close();
        }));

        System.out.println("CacheDB admin HTTP server listening on " + httpServer.baseUri());
        Thread.currentThread().join();
    }
}
