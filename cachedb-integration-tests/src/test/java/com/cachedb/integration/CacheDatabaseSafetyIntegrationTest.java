package com.reactor.cachedb.integration;

import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.AdminHttpConfig;
import com.reactor.cachedb.core.config.KeyspaceConfig;
import com.reactor.cachedb.core.config.QueryIndexConfig;
import com.reactor.cachedb.core.config.RedisFunctionsConfig;
import com.reactor.cachedb.core.config.ResourceLimits;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.route.RouteCacheContext;
import com.reactor.cachedb.core.route.RouteCacheContract;
import com.reactor.cachedb.core.route.TenantCacheQuota;
import com.reactor.cachedb.examples.entity.UserEntity;
import com.reactor.cachedb.examples.entity.UserEntityCacheBinding;
import com.reactor.cachedb.redis.RedisEntityRepository;
import com.reactor.cachedb.redis.RedisKeyStrategy;
import com.reactor.cachedb.redis.RedisFunctionLibrarySource;
import com.reactor.cachedb.redis.RedisFunctionLoader;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.CacheDatabaseAdminHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import redis.clients.jedis.JedisPooled;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CacheDatabaseSafetyIntegrationTest {

    private static final String JDBC_URL = System.getProperty(
            "cachedb.it.postgres.url",
            "jdbc:postgresql://127.0.0.1:5432/postgres"
    );
    private static final String JDBC_USER = System.getProperty("cachedb.it.postgres.user", "postgres");
    private static final String JDBC_PASSWORD = System.getProperty("cachedb.it.postgres.password", "postgresql");
    private static final String REDIS_URI = System.getProperty(
            "cachedb.it.redis.uri",
            "redis://default:welcome1@127.0.0.1:6379"
    );

    private JedisPooled jedis;
    private CacheDatabase cacheDatabase;
    private String keyPrefix;

    @BeforeEach
    void setUp() throws Exception {
        recreateTable();
        jedis = new JedisPooled(REDIS_URI);
        keyPrefix = "cachedb-safety-" + UUID.randomUUID();
        CachePolicy policy = CachePolicy.builder()
                .hotEntityLimit(100)
                .pageSize(10)
                .entityTtlSeconds(300)
                .pageTtlSeconds(300)
                .build();
        cacheDatabase = new CacheDatabase(jedis, dataSource(), CacheDatabaseConfig.builder()
                .writeBehind(WriteBehindConfig.builder()
                        .streamKey(keyPrefix + ":stream")
                        .consumerGroup(keyPrefix + ":group")
                        .consumerNamePrefix(keyPrefix + ":worker")
                        .workerThreads(1)
                        .blockTimeoutMillis(50)
                        .idleSleepMillis(10)
                        .build())
                .redisFunctions(RedisFunctionsConfig.builder().enabled(false).build())
                .resourceLimits(ResourceLimits.builder().defaultCachePolicy(policy).build())
                .queryIndex(QueryIndexConfig.builder().maxMaterializedCandidateIds(2).build())
                .keyspace(KeyspaceConfig.builder().keyPrefix(keyPrefix).build())
                .build());
        UserEntityCacheBinding.register(cacheDatabase, policy);
        cacheDatabase.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (cacheDatabase != null) {
            cacheDatabase.close();
        }
        if (jedis != null) {
            for (String key : jedis.keys(keyPrefix + "*")) {
                jedis.del(key);
            }
            jedis.close();
        }
        try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_example_users");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleHydrationMustNotOverwriteOrResurrectNewerState() {
        RedisEntityRepository<UserEntity, Long> repository = (RedisEntityRepository<UserEntity, Long>)
                cacheDatabase.repository(UserEntityCacheBinding.METADATA, UserEntityCacheBinding.CODEC);

        repository.hydrateExternalUpsert(user(1L, "current", "ACTIVE"), 10L);
        repository.hydrateExternalUpsert(user(1L, "stale", "ACTIVE"), 9L);

        assertEquals("current", repository.findById(1L).orElseThrow().username);

        repository.hydrateExternalDelete(1L, 11L);
        repository.hydrateExternalUpsert(user(1L, "resurrected", "ACTIVE"), 10L);

        assertFalse(repository.findById(1L).isPresent());
        RedisKeyStrategy keys = new RedisKeyStrategy(keyPrefix, "entity", "page", "version", "hotset", "index");
        assertEquals(-1L, jedis.ttl(keys.versionKey(UserEntityCacheBinding.METADATA.redisNamespace(), 1L)));
    }

    @Test
    void repositoryGraphMustBeReusedWithinSession() {
        EntityRepository<UserEntity, Long> first = cacheDatabase.repository(
                UserEntityCacheBinding.METADATA,
                UserEntityCacheBinding.CODEC
        );
        EntityRepository<UserEntity, Long> second = cacheDatabase.repository(
                UserEntityCacheBinding.METADATA,
                UserEntityCacheBinding.CODEC
        );

        assertSame(first, second);
    }

    @Test
    void broadIndexedCandidateSetMustFailBeforeMaterialization() {
        EntityRepository<UserEntity, Long> repository = cacheDatabase.repository(
                UserEntityCacheBinding.METADATA,
                UserEntityCacheBinding.CODEC
        );
        repository.save(user(11L, "one", "ACTIVE"));
        repository.save(user(12L, "two", "ACTIVE"));
        repository.save(user(13L, "three", "ACTIVE"));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> repository.query(
                QuerySpec.builder().filter(QueryFilter.eq("status", "ACTIVE")).limit(1).build()
        ));

        assertTrue(exception.getMessage().contains("maxMaterializedCandidateIds=2"));
    }

    @Test
    void concurrentTenantAdmissionMustNotExceedRouteQuota() throws Exception {
        EntityRepository<UserEntity, Long> repository = cacheDatabase.repository(
                UserEntityCacheBinding.METADATA,
                UserEntityCacheBinding.CODEC
        );
        RouteCacheContract contract = RouteCacheContract.builder()
                .routeName("ConcurrentTenantQuota")
                .entityName("UserEntity")
                .pageSize(2)
                .hotWindow(2)
                .tenantQuota(new TenantCacheQuota("status", 2, 0L, true))
                .build();
        int writers = 8;
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(writers);
        var futures = new ArrayList<java.util.concurrent.Future<?>>();
        try {
            for (int index = 0; index < writers; index++) {
                long id = 100L + index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    RouteCacheContext.runWithContract(contract, () -> repository.save(user(id, "tenant-" + id, "ACTIVE")));
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (var future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdown();
        }
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));

        RedisKeyStrategy keys = new RedisKeyStrategy(keyPrefix, "entity", "page", "version", "hotset", "index");
        assertTrue(jedis.zcard(keys.tenantHotSetKey(
                UserEntityCacheBinding.METADATA.redisNamespace(),
                "status",
                "ACTIVE"
        )) <= 2L);
    }

    @Test
    void adminDispatchMustRejectOversizedBodiesBeforeParsing() throws Exception {
        try (CacheDatabaseAdminHttpServer server = cacheDatabase.adminHttpServer(AdminHttpConfig.builder()
                .maxRequestBodyBytes(16)
                .build())) {
            CacheDatabaseAdminHttpServer.AdminHttpResponse response = server.dispatch(
                    "POST",
                    URI.create("/api/migration-planner/plan"),
                    "x".repeat(17).getBytes(StandardCharsets.UTF_8)
            );

            assertEquals(413, response.statusCode());
        }
    }

    @Test
    void functionDeploymentMustAllowReleaseUpgradeAndRejectDriftOrDowngrade() {
        String libraryName = "safety_" + UUID.randomUUID().toString().replace("-", "_");
        String functionName = libraryName + "_ping";
        String source = "#!lua name=__LIBRARY_NAME__\n"
                + "redis.register_function('__UPSERT_FUNCTION__', function(keys, args) return 1 end)";
        try {
            initializeFunctionLibrary(libraryName, functionName, source, "1.0.0-beta.1");
            initializeFunctionLibrary(libraryName, functionName, source, "1.0.0");

            IllegalStateException drift = assertThrows(IllegalStateException.class, () -> initializeFunctionLibrary(
                    libraryName,
                    functionName,
                    source + "\n-- changed",
                    "1.0.0"
            ));
            assertTrue(drift.getMessage().contains("content drift"));

            IllegalStateException downgrade = assertThrows(IllegalStateException.class, () -> initializeFunctionLibrary(
                    libraryName,
                    functionName,
                    source,
                    "1.0.0-beta.2"
            ));
            assertTrue(downgrade.getMessage().contains("Refusing to downgrade"));
        } finally {
            jedis.functionDelete(libraryName);
            jedis.del("cachedb:function-library:" + libraryName + ":marker");
            jedis.del("cachedb:function-library:" + libraryName + ":marker:lease");
        }
    }

    private void initializeFunctionLibrary(String libraryName, String functionName, String source, String version) {
        RedisFunctionsConfig config = RedisFunctionsConfig.builder()
                .libraryName(libraryName)
                .upsertFunctionName(functionName)
                .sourceOverride(source)
                .libraryVersion(version)
                .build();
        new RedisFunctionLoader(jedis, config, new RedisFunctionLibrarySource()).initialize();
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(JDBC_URL);
        dataSource.setUser(JDBC_USER);
        dataSource.setPassword(JDBC_PASSWORD);
        return dataSource;
    }

    private void recreateTable() throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_example_users");
            statement.executeUpdate("""
                    CREATE TABLE cachedb_example_users (
                        id BIGINT PRIMARY KEY,
                        username TEXT,
                        status TEXT,
                        entity_version BIGINT
                    )
                    """);
        }
    }

    private UserEntity user(long id, String username, String status) {
        UserEntity user = new UserEntity();
        user.id = id;
        user.username = username;
        user.status = status;
        return user;
    }
}
