package com.reactor.cachedb.integration;

import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.config.AdminMonitoringConfig;
import com.reactor.cachedb.core.config.AdminReportJobConfig;
import com.reactor.cachedb.core.config.AdminHttpConfig;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.DeadLetterRecoveryConfig;
import com.reactor.cachedb.core.config.EntityFlushPolicy;
import com.reactor.cachedb.core.config.HardLimitEntityPolicy;
import com.reactor.cachedb.core.config.HardLimitQueryPolicy;
import com.reactor.cachedb.core.config.IncidentDeliveryDlqConfig;
import com.reactor.cachedb.core.config.IncidentWebhookConfig;
import com.reactor.cachedb.core.config.IncidentQueueConfig;
import com.reactor.cachedb.core.config.IncidentEmailConfig;
import com.reactor.cachedb.core.config.KeyspaceConfig;
import com.reactor.cachedb.core.config.PageCacheConfig;
import com.reactor.cachedb.core.config.QueryIndexConfig;
import com.reactor.cachedb.core.config.RedisFunctionsConfig;
import com.reactor.cachedb.core.config.RedisGuardrailConfig;
import com.reactor.cachedb.core.config.PersistenceSemantics;
import com.reactor.cachedb.core.config.RelationConfig;
import com.reactor.cachedb.core.config.ResourceLimits;
import com.reactor.cachedb.core.config.SchemaBootstrapConfig;
import com.reactor.cachedb.core.config.SchemaBootstrapMode;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.config.WriteRetryPolicyOverride;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.model.WriteOperation;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.queue.AdminExportFormat;
import com.reactor.cachedb.core.queue.DeadLetterQuery;
import com.reactor.cachedb.core.queue.DeadLetterManagement;
import com.reactor.cachedb.core.queue.AdminHealthStatus;
import com.reactor.cachedb.core.queue.ReconciliationQuery;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.queue.WriteFailureCategory;
import com.reactor.cachedb.core.query.QueryExplainPlan;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QueryGroup;
import com.reactor.cachedb.core.query.HardLimitQueryClass;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.examples.entity.OrderEntity;
import com.reactor.cachedb.examples.entity.OrderEntityCacheBinding;
import com.reactor.cachedb.examples.entity.UserEntity;
import com.reactor.cachedb.examples.entity.UserEntityCacheBinding;
import com.reactor.cachedb.examples.loader.UserOrdersRelationBatchLoader;
import com.reactor.cachedb.examples.loader.UserPageLoader;
import com.reactor.cachedb.redis.RedisDeadLetterManagement;
import com.reactor.cachedb.redis.RedisKeyStrategy;
import com.reactor.cachedb.redis.RedisDeadLetterRecoveryWorker;
import com.reactor.cachedb.redis.RedisWriteBehindQueue;
import com.reactor.cachedb.redis.RedisWriteBehindWorker;
import com.reactor.cachedb.redis.RedisWriteOperationMapper;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.CacheDatabaseAdminHttpServer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.params.XReadGroupParams;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class CacheDatabaseIntegrationTest {

    private static final String JDBC_USER = System.getProperty("cachedb.it.postgres.user", "postgres");
    private static final String JDBC_PASSWORD = System.getProperty("cachedb.it.postgres.password", "postgresql");
    private static final String REDIS_PASSWORD = System.getProperty("cachedb.it.redis.password", "welcome1");
    private static final String REDIS_URI = System.getProperty("cachedb.it.redis.uri", "redis://default:" + REDIS_PASSWORD + "@127.0.0.1:6379");

    private JedisPooled jedis;
    private CacheDatabase cacheDatabase;
    private String keyPrefix;
    private String streamKey;
    private String jdbcUrl;

    @BeforeEach
    void setUp() throws Exception {
        jedis = new JedisPooled(REDIS_URI);
        Assertions.assertEquals("PONG", jedis.ping());

        jdbcUrl = resolveJdbcUrl();
        recreateTables();

        keyPrefix = "cachedb-it-" + UUID.randomUUID();
        streamKey = keyPrefix + ":stream";
        String functionSuffix = keyPrefix.replace('-', '_');

        cacheDatabase = new CacheDatabase(jedis, dataSource(), CacheDatabaseConfig.builder()
                .writeBehind(WriteBehindConfig.builder()
                        .workerThreads(1)
                        .batchSize(10)
                        .blockTimeoutMillis(200)
                        .idleSleepMillis(50)
                        .streamKey(streamKey)
                        .consumerGroup(keyPrefix + "-group")
                        .consumerNamePrefix(keyPrefix + "-worker")
                        .build())
                .resourceLimits(ResourceLimits.builder()
                        .defaultCachePolicy(CachePolicy.builder()
                                .hotEntityLimit(2)
                                .pageSize(2)
                                .entityTtlSeconds(300)
                                .pageTtlSeconds(300)
                                .build())
                        .build())
                .keyspace(KeyspaceConfig.builder()
                        .keyPrefix(keyPrefix)
                        .entitySegment("entity")
                        .pageSegment("page")
                        .versionSegment("version")
                        .hotSetSegment("hotset")
                        .build())
                .redisFunctions(RedisFunctionsConfig.builder()
                        .enabled(true)
                        .autoLoadLibrary(true)
                        .replaceLibraryOnLoad(true)
                        .strictLoading(true)
                        .libraryName(functionSuffix)
                        .upsertFunctionName(functionSuffix + "_entity_upsert")
                        .deleteFunctionName(functionSuffix + "_entity_delete")
                        .compactionCompleteFunctionName(functionSuffix + "_compaction_complete")
                        .build())
                .relations(RelationConfig.builder()
                        .batchSize(10)
                        .maxFetchDepth(1)
                        .failOnMissingPreloader(false)
                        .build())
                .pageCache(PageCacheConfig.builder()
                        .readThroughEnabled(true)
                        .failOnMissingPageLoader(true)
                        .evictionBatchSize(10)
                        .build())
                .queryIndex(QueryIndexConfig.builder()
                        .prefixIndexEnabled(true)
                        .textIndexEnabled(true)
                        .build())
                .build());

        UserEntityCacheBinding.register(
                cacheDatabase,
                CachePolicy.builder().hotEntityLimit(2).pageSize(2).entityTtlSeconds(300).pageTtlSeconds(300).build()
        );
        cacheDatabase.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (cacheDatabase != null) {
            cacheDatabase.close();
        }
        if (jedis != null) {
            jedis.close();
        }
        dropTables();
    }

    @Test
    void saveShouldReachRedisAndEventuallyPostgres() throws Exception {
        UserEntity entity = new UserEntity();
        entity.id = 101L;
        entity.username = "alice";
        entity.status = "ACTIVE";

        UserEntityCacheBinding.attach(cacheDatabase, CachePolicy.defaults(), entity).save();

        String redisValue = jedis.get(keyPrefix + ":users:entity:101");
        Assertions.assertNotNull(redisValue);

        waitUntil(
                () -> countRows("select count(*) from cachedb_example_users where id = 101") == 1,
                Duration.ofSeconds(15),
                "worker=" + cacheDatabase.workerSnapshot() + ", stream=" + streamDiagnostics()
        );
        Assertions.assertEquals(1, countRows("select count(*) from cachedb_example_users where id = 101"));
    }

    @Test
    void pageReadThroughShouldCacheAndEvictHotSet() {
        EntityRepository<UserEntity, Long> repository = cacheDatabase.repository(
                UserEntityCacheBinding.METADATA,
                UserEntityCacheBinding.CODEC,
                CachePolicy.builder().hotEntityLimit(2).pageSize(2).entityTtlSeconds(300).pageTtlSeconds(300).build()
        );

        List<UserEntity> firstPage = repository.findPage(new PageWindow(0, 2));
        Assertions.assertEquals(2, firstPage.size());
        Assertions.assertNotNull(jedis.lrange(keyPrefix + ":users:page:0", 0, -1));

        repository.findById(1L);
        repository.findById(2L);
        repository.findPage(new PageWindow(1, 2));

        Long hotSetSize = jedis.zcard(keyPrefix + ":users:hotset");
        Assertions.assertTrue(hotSetSize <= 2);
    }

    @Test
    void deleteShouldEventuallyRemovePostgresRow() throws Exception {
        EntityRepository<UserEntity, Long> repository = cacheDatabase.repository(
                UserEntityCacheBinding.METADATA,
                UserEntityCacheBinding.CODEC,
                CachePolicy.defaults()
        );

        UserEntity entity = new UserEntity();
        entity.id = 202L;
        entity.username = "bob";
        entity.status = "ACTIVE";

        repository.save(entity);
        waitUntil(
                () -> countRows("select count(*) from cachedb_example_users where id = 202") == 1,
                Duration.ofSeconds(15),
                "worker=" + cacheDatabase.workerSnapshot() + ", stream=" + streamDiagnostics()
        );

        repository.deleteById(202L);
        waitUntil(
                () -> countRows("select count(*) from cachedb_example_users where id = 202") == 0,
                Duration.ofSeconds(15),
                "worker=" + cacheDatabase.workerSnapshot() + ", stream=" + streamDiagnostics()
        );
        Assertions.assertEquals(0, countRows("select count(*) from cachedb_example_users where id = 202"));
        Assertions.assertFalse(jedis.sismember(exactIndexKey("status", "ACTIVE"), "202"));
    }

    @Test
    void deleteShouldCreateTombstoneAndPreventStaleRedisResurrection() {
        EntityRepository<UserEntity, Long> repository = cacheDatabase.repository(
                UserEntityCacheBinding.METADATA,
                UserEntityCacheBinding.CODEC,
                CachePolicy.defaults()
        );

        UserEntity entity = user(260L, "tombstone-user", "ACTIVE");
        repository.save(entity);
        repository.deleteById(260L);

        String tombstoneKey = keyPrefix + ":users:tombstone:260";
        String entityKey = keyPrefix + ":users:entity:260";
        Assertions.assertNotNull(jedis.get(tombstoneKey));

        jedis.set(entityKey, UserEntityCacheBinding.CODEC.toRedisValue(entity));
        Assertions.assertTrue(repository.findById(260L).isEmpty());

        UserEntity revived = user(260L, "tombstone-recreated", "ACTIVE");
        repository.save(revived);
        Assertions.assertNull(jedis.get(tombstoneKey));
        Assertions.assertEquals("tombstone-recreated", repository.findById(260L).orElseThrow().username);
    }

    @Test
    void findAllShouldSkipTombstonedPayloadsWithoutRevivingDeletedEntity() {
        EntityRepository<UserEntity, Long> repository = cacheDatabase.repository(
                UserEntityCacheBinding.METADATA,
                UserEntityCacheBinding.CODEC,
                CachePolicy.defaults()
        );

        UserEntity active = user(261L, "active-user", "ACTIVE");
        UserEntity deleted = user(262L, "deleted-user", "ACTIVE");
        repository.save(active);
        repository.save(deleted);
        repository.deleteById(262L);

        String deletedEntityKey = keyPrefix + ":users:entity:262";
        jedis.set(deletedEntityKey, UserEntityCacheBinding.CODEC.toRedisValue(deleted));

        List<UserEntity> results = repository.findAll(List.of(261L, 262L));
        Assertions.assertEquals(List.of(261L), results.stream().map(entity -> entity.id).toList());
    }

    @Test
    void queryDslShouldFilterAndSortHotEntities() {
        EntityRepository<UserEntity, Long> repository = cacheDatabase.repository(
                UserEntityCacheBinding.METADATA,
                UserEntityCacheBinding.CODEC,
                CachePolicy.builder().hotEntityLimit(10).pageSize(5).entityTtlSeconds(300).pageTtlSeconds(300).build()
        );

        UserEntity first = new UserEntity();
        first.id = 301L;
        first.username = "zoe";
        first.status = "ACTIVE";
        repository.save(first);

        UserEntity second = new UserEntity();
        second.id = 302L;
        second.username = "anna";
        second.status = "ACTIVE";
        repository.save(second);

        UserEntity third = new UserEntity();
        third.id = 303L;
        third.username = "mark";
        third.status = "SUSPENDED";
        repository.save(third);

        List<UserEntity> result = repository.query(QuerySpec.builder()
                .filter(QueryFilter.eq("status", "ACTIVE"))
                .sort(QuerySort.asc("username"))
                .limit(10)
                .build());

        Assertions.assertEquals(2, result.size());
        Assertions.assertEquals("anna", result.get(0).username);
        Assertions.assertEquals("zoe", result.get(1).username);
        Assertions.assertEquals(
                java.util.Set.of("301", "302"),
                jedis.smembers(exactIndexKey("status", "ACTIVE"))
        );
    }

    @Test
    void queryDslShouldUsePrefixAndTextIndexes() {
        EntityRepository<UserEntity, Long> repository = cacheDatabase.repository(
                UserEntityCacheBinding.METADATA,
                UserEntityCacheBinding.CODEC,
                CachePolicy.builder().hotEntityLimit(10).pageSize(5).entityTtlSeconds(300).pageTtlSeconds(300).build()
        );

        UserEntity first = new UserEntity();
        first.id = 351L;
        first.username = "anna bell";
        first.status = "ACTIVE";
        repository.save(first);

        UserEntity second = new UserEntity();
        second.id = 352L;
        second.username = "zoe lane";
        second.status = "ACTIVE";
        repository.save(second);

        List<UserEntity> prefixResult = repository.query(QuerySpec.builder()
                .filter(QueryFilter.startsWith("username", "ann"))
                .limit(10)
                .build());
        Assertions.assertEquals(1, prefixResult.size());
        Assertions.assertEquals(351L, prefixResult.get(0).id);

        List<UserEntity> textResult = repository.query(QuerySpec.builder()
                .filter(QueryFilter.contains("username", "lane"))
                .limit(10)
                .build());
        Assertions.assertEquals(1, textResult.size());
        Assertions.assertEquals(352L, textResult.get(0).id);
        Assertions.assertTrue(jedis.sismember(prefixIndexKey("username", "ann"), "351"));
        Assertions.assertTrue(jedis.sismember(tokenIndexKey("username", "lane"), "352"));
    }

    @Test
    void queryDslShouldResolveRelationAwareFilters() {
        EntityRepository<UserEntity, Long> userRepository = cacheDatabase.repository(
                UserEntityCacheBinding.METADATA,
                UserEntityCacheBinding.CODEC,
                CachePolicy.builder().hotEntityLimit(10).pageSize(5).entityTtlSeconds(300).pageTtlSeconds(300).build()
        );
        EntityRepository<OrderEntity, Long> orderRepository = cacheDatabase.repository(
                OrderEntityCacheBinding.METADATA,
                OrderEntityCacheBinding.CODEC,
                CachePolicy.builder().hotEntityLimit(10).pageSize(5).entityTtlSeconds(300).pageTtlSeconds(300).build()
        );

        UserEntity alice = new UserEntity();
        alice.id = 401L;
        alice.username = "alice";
        alice.status = "ACTIVE";
        userRepository.save(alice);

        UserEntity bob = new UserEntity();
        bob.id = 402L;
        bob.username = "bob";
        bob.status = "ACTIVE";
        userRepository.save(bob);

        OrderEntity paid = new OrderEntity();
        paid.id = 601L;
        paid.userId = 401L;
        paid.totalAmount = 99.0;
        paid.status = "PAID";
        orderRepository.save(paid);

        OrderEntity open = new OrderEntity();
        open.id = 602L;
        open.userId = 402L;
        open.totalAmount = 55.0;
        open.status = "OPEN";
        orderRepository.save(open);

        List<UserEntity> result = userRepository.query(QuerySpec.builder()
                .filter(QueryFilter.eq("orders.status", "PAID"))
                .sort(QuerySort.asc("username"))
                .limit(10)
                .build());

        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals(401L, result.get(0).id);
    }

    @Test
    void queryExplainShouldDescribeIndexAndFetchStrategies() {
        EntityRepository<UserEntity, Long> userRepository = cacheDatabase.repository(
                UserEntityCacheBinding.METADATA,
                UserEntityCacheBinding.CODEC,
                CachePolicy.builder().hotEntityLimit(10).pageSize(5).entityTtlSeconds(300).pageTtlSeconds(300).build()
        );
        EntityRepository<OrderEntity, Long> orderRepository = cacheDatabase.repository(
                OrderEntityCacheBinding.METADATA,
                OrderEntityCacheBinding.CODEC,
                CachePolicy.builder().hotEntityLimit(10).pageSize(5).entityTtlSeconds(300).pageTtlSeconds(300).build()
        );

        UserEntity alice = new UserEntity();
        alice.id = 451L;
        alice.username = "alice";
        alice.status = "ACTIVE";
        userRepository.save(alice);

        OrderEntity paid = new OrderEntity();
        paid.id = 651L;
        paid.userId = 451L;
        paid.totalAmount = 42.0;
        paid.status = "PAID";
        orderRepository.save(paid);

        QueryExplainPlan plan = userRepository.explain(QuerySpec.builder()
                .filter(QueryFilter.eq("orders.status", "PAID"))
                .filter(QueryFilter.startsWith("username", "ali"))
                .sort(QuerySort.asc("username"))
                .fetchPlan(FetchPlan.of("orders"))
                .limit(10)
                .build());

        Assertions.assertEquals("UserEntity", plan.entityName());
        Assertions.assertEquals(1, plan.candidateCount());
        Assertions.assertTrue(plan.fullyIndexed());
        Assertions.assertTrue(plan.steps().stream().anyMatch(step -> "RELATION_INDEX_TRAVERSAL".equals(step.strategy())));
        Assertions.assertTrue(plan.steps().stream().anyMatch(step -> "FILTER".equals(step.stage()) && step.detail().contains("selectivity=")));
        Assertions.assertTrue(plan.steps().stream().anyMatch(step -> "FETCH".equals(step.stage()) && "BATCH_PRELOAD".equals(step.strategy())));
        Assertions.assertTrue(plan.relationStates().stream().anyMatch(state ->
                "orders".equals(state.relationName())
                        && "FILTER".equals(state.usage())
                        && "RESOLVED".equals(state.status())
                        && state.indexed()
                        && state.candidateCount() == 1
        ));
        Assertions.assertTrue(plan.relationStates().stream().anyMatch(state ->
                "orders".equals(state.relationName())
                        && "FETCH".equals(state.usage())
                        && "BATCH_PRELOAD".equals(state.status())
                        && "OrderEntity".equals(state.targetEntity())
        ));
        Assertions.assertTrue(plan.warnings().isEmpty());
        Assertions.assertTrue(plan.sortStrategy().contains("IN_MEMORY_SORT"));
    }

    @Test
    void queryExplainShouldFlagMissingFetchLoaderWhenOnlyDefaultBindingExists() {
        EntityMetadata<UserEntity, Long> lateMetadata = relationAwareUserMetadata(
                "LoaderMissingUserEntity",
                "loader-missing-users",
                "orders",
                "OrderEntity",
                "userId"
        );

        QueryExplainPlan plan = cacheDatabase.repository(
                        lateMetadata,
                        UserEntityCacheBinding.CODEC,
                        CachePolicy.defaults()
                )
                .explain(QuerySpec.builder()
                        .fetchPlan(FetchPlan.of("orders"))
                        .limit(10)
                        .build());

        Assertions.assertTrue(plan.relationStates().stream().anyMatch(state ->
                "orders".equals(state.relationName())
                        && "FETCH".equals(state.usage())
                        && "FETCH_LOADER_MISSING".equals(state.status())
        ));
        Assertions.assertTrue(plan.warnings().contains("FETCH:orders:FETCH_LOADER_MISSING"));
    }

    @Test
    void queryExplainShouldExposeWarningWhenFetchDepthExceedsConfiguredLimit() {
        EntityRepository<UserEntity, Long> repository = cacheDatabase.repository(
                UserEntityCacheBinding.METADATA,
                UserEntityCacheBinding.CODEC,
                CachePolicy.defaults()
        );

        QueryExplainPlan plan = repository.explain(QuerySpec.builder()
                .fetchPlan(FetchPlan.of("orders.user"))
                .limit(10)
                .build());

        Assertions.assertTrue(plan.relationStates().stream().anyMatch(state ->
                "orders.user".equals(state.relationName())
                        && "FETCH".equals(state.usage())
                        && "FETCH_DEPTH_EXCEEDED".equals(state.status())
        ));
        Assertions.assertTrue(plan.steps().stream().anyMatch(step ->
                "FETCH".equals(step.stage()) && "FETCH_DEPTH_EXCEEDED".equals(step.strategy())
        ));
        Assertions.assertTrue(plan.warnings().contains("FETCH:orders.user:FETCH_DEPTH_EXCEEDED"));
    }

    @Test
    void repositoryShouldRejectFetchPlansThatExceedConfiguredMaxDepth() {
        EntityRepository<UserEntity, Long> repository = cacheDatabase.repository(
                UserEntityCacheBinding.METADATA,
                UserEntityCacheBinding.CODEC,
                CachePolicy.defaults()
        );

        UserEntity entity = new UserEntity();
        entity.id = 9801L;
        entity.username = "depth-check";
        entity.status = "ACTIVE";
        repository.save(entity);

        IllegalArgumentException exception = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> repository.withFetchPlan(FetchPlan.of("orders.user")).findById(9801L)
        );
        Assertions.assertTrue(exception.getMessage().contains("maxFetchDepth=1"));
        Assertions.assertTrue(exception.getMessage().contains("depth 2"));
    }

    @Test
    void repositoryShouldUseLateRegisteredRelationLoaderAfterEarlyRepositoryCreation() {
        cacheDatabase.repository(
                OrderEntityCacheBinding.METADATA,
                OrderEntityCacheBinding.CODEC,
                CachePolicy.defaults()
        );

        EntityMetadata<UserEntity, Long> lateMetadata = relationAwareUserMetadata(
                "LateBoundUserEntity",
                "late-bound-users",
                "orders",
                "OrderEntity",
                "userId"
        );

        EntityRepository<UserEntity, Long> earlyRepository = cacheDatabase.repository(
                lateMetadata,
                UserEntityCacheBinding.CODEC,
                CachePolicy.defaults()
        );

        cacheDatabase.register(
                lateMetadata,
                UserEntityCacheBinding.CODEC,
                CachePolicy.defaults(),
                (entities, context) -> {
                    for (UserEntity entity : entities) {
                        OrderEntity order = new OrderEntity();
                        order.id = 9901L;
                        order.userId = entity.id;
                        order.totalAmount = 25.0;
                        order.status = "PAID";
                        entity.orders = List.of(order);
                    }
                },
                new UserPageLoader()
        );

        UserEntity entity = new UserEntity();
        entity.id = 991L;
        entity.username = "late-bound";
        entity.status = "ACTIVE";
        earlyRepository.save(entity);

        UserEntity loaded = earlyRepository.withFetchPlan(FetchPlan.of("orders")).findById(991L).orElseThrow();
        Assertions.assertNotNull(loaded.orders);
        Assertions.assertEquals(1, loaded.orders.size());
        Assertions.assertEquals(991L, loaded.orders.get(0).userId);

        QueryExplainPlan plan = earlyRepository.explain(QuerySpec.builder()
                .fetchPlan(FetchPlan.of("orders"))
                .limit(10)
                .build());
        Assertions.assertTrue(plan.relationStates().stream().anyMatch(state ->
                "orders".equals(state.relationName())
                        && "FETCH".equals(state.usage())
                        && "BATCH_PRELOAD".equals(state.status())
        ));
        Assertions.assertFalse(plan.warnings().contains("FETCH:orders:FETCH_LOADER_MISSING"));
    }

    @Test
    void queryExplainShouldExposeWarningsForUnknownAndUnresolvedRelations() {
        EntityRepository<UserEntity, Long> repository = cacheDatabase.repository(
                UserEntityCacheBinding.METADATA,
                UserEntityCacheBinding.CODEC,
                CachePolicy.defaults()
        );

        QueryExplainPlan unknownPlan = repository.explain(QuerySpec.builder()
                .filter(QueryFilter.eq("missing.status", "PAID"))
                .fetchPlan(FetchPlan.of("missing"))
                .limit(10)
                .build());

        Assertions.assertTrue(unknownPlan.relationStates().stream().anyMatch(state ->
                "missing".equals(state.relationName())
                        && "FILTER".equals(state.usage())
                        && "UNKNOWN_RELATION".equals(state.status())
        ));
        Assertions.assertTrue(unknownPlan.relationStates().stream().anyMatch(state ->
                "missing".equals(state.relationName())
                        && "FETCH".equals(state.usage())
                        && "UNKNOWN_RELATION".equals(state.status())
        ));
        Assertions.assertTrue(unknownPlan.warnings().contains("FILTER:missing:UNKNOWN_RELATION"));
        Assertions.assertTrue(unknownPlan.warnings().contains("FETCH:missing:UNKNOWN_RELATION"));

        EntityMetadata<UserEntity, Long> brokenRelationMetadata = new EntityMetadata<>() {
            @Override
            public String entityName() {
                return "BrokenUserEntity";
            }

            @Override
            public String tableName() {
                return UserEntityCacheBinding.METADATA.tableName();
            }

            @Override
            public String redisNamespace() {
                return "broken-users";
            }

            @Override
            public String idColumn() {
                return UserEntityCacheBinding.METADATA.idColumn();
            }

            @Override
            public Class<UserEntity> entityType() {
                return UserEntity.class;
            }

            @Override
            public java.util.function.Function<UserEntity, Long> idAccessor() {
                return entity -> entity.id;
            }

            @Override
            public List<String> columns() {
                return UserEntityCacheBinding.METADATA.columns();
            }

            @Override
            public Map<String, String> columnTypes() {
                return UserEntityCacheBinding.METADATA.columnTypes();
            }

            @Override
            public List<com.reactor.cachedb.core.model.RelationDefinition> relations() {
                return List.of(new com.reactor.cachedb.core.model.RelationDefinition(
                        "brokenOrders",
                        "OrderEntity",
                        "ownerRef",
                        com.reactor.cachedb.core.model.RelationKind.ONE_TO_MANY,
                        true
                ));
            }
        };

        cacheDatabase.register(
                brokenRelationMetadata,
                UserEntityCacheBinding.CODEC,
                CachePolicy.defaults(),
                new UserOrdersRelationBatchLoader(),
                new UserPageLoader()
        );

        QueryExplainPlan unresolvedPlan = cacheDatabase.repository(
                        brokenRelationMetadata,
                        UserEntityCacheBinding.CODEC,
                        CachePolicy.defaults()
                )
                .explain(QuerySpec.builder()
                        .filter(QueryFilter.eq("brokenOrders.status", "PAID"))
                        .fetchPlan(FetchPlan.of("brokenOrders"))
                        .limit(10)
                        .build());

        Assertions.assertTrue(unresolvedPlan.relationStates().stream().anyMatch(state ->
                "brokenOrders".equals(state.relationName())
                        && "FILTER".equals(state.usage())
                        && "TARGET_BINDING_MISSING".equals(state.status())
        ));
        Assertions.assertTrue(unresolvedPlan.relationStates().stream().anyMatch(state ->
                "brokenOrders".equals(state.relationName())
                        && "FETCH".equals(state.usage())
                        && "FETCH_PATH_UNVERIFIED".equals(state.status())
        ));
        Assertions.assertTrue(unresolvedPlan.warnings().contains("FILTER:brokenOrders:TARGET_BINDING_MISSING"));
        Assertions.assertTrue(unresolvedPlan.warnings().contains("FETCH:brokenOrders:FETCH_PATH_UNVERIFIED"));
    }

    @Test
    void queryExplainShouldExposeRangeHistogramStatistics() {
        EntityRepository<OrderEntity, Long> repository = cacheDatabase.repository(
                OrderEntityCacheBinding.METADATA,
                OrderEntityCacheBinding.CODEC,
                CachePolicy.builder().hotEntityLimit(20).pageSize(5).entityTtlSeconds(300).pageTtlSeconds(300).build()
        );

        OrderEntity first = new OrderEntity();
        first.id = 901L;
        first.userId = 1L;
        first.totalAmount = 10.0;
        first.status = "PAID";
        repository.save(first);

        OrderEntity second = new OrderEntity();
        second.id = 902L;
        second.userId = 1L;
        second.totalAmount = 25.0;
        second.status = "PAID";
        repository.save(second);

        OrderEntity third = new OrderEntity();
        third.id = 903L;
        third.userId = 1L;
        third.totalAmount = 50.0;
        third.status = "OPEN";
        repository.save(third);

        QueryExplainPlan plan = repository.explain(QuerySpec.builder()
                .filter(QueryFilter.gte("total_amount", 20.0))
                .sort(QuerySort.asc("total_amount"))
                .limit(10)
                .build());

        Assertions.assertEquals("SORTED_INDEX_SCAN", plan.sortStrategy());
        Assertions.assertTrue(plan.steps().stream()
                .anyMatch(step -> "FILTER".equals(step.stage()) && step.detail().contains("histogram buckets")));
        Assertions.assertTrue(plan.steps().stream()
                .anyMatch(step -> "FILTER".equals(step.stage()) && step.detail().contains("selectivity=")));
        Assertions.assertTrue(plan.estimatedCost() > 0);
        Assertions.assertFalse(jedis.keys(keyPrefix + ":orders:index:stats:*").isEmpty());
    }

    @Test
    void queryShouldUsePrimarySortedWindowForMultiSortWithoutLosingTieBreakOrder() {
        EntityRepository<OrderEntity, Long> repository = cacheDatabase.repository(
                OrderEntityCacheBinding.METADATA,
                OrderEntityCacheBinding.CODEC,
                CachePolicy.builder().hotEntityLimit(20).pageSize(5).entityTtlSeconds(300).pageTtlSeconds(300).build()
        );

        OrderEntity highest = new OrderEntity();
        highest.id = 950L;
        highest.userId = 77L;
        highest.totalAmount = 30.0;
        highest.status = "PAID";
        repository.save(highest);

        OrderEntity tiedLate = new OrderEntity();
        tiedLate.id = 980L;
        tiedLate.userId = 77L;
        tiedLate.totalAmount = 20.0;
        tiedLate.status = "PAID";
        repository.save(tiedLate);

        OrderEntity tiedEarly = new OrderEntity();
        tiedEarly.id = 960L;
        tiedEarly.userId = 77L;
        tiedEarly.totalAmount = 20.0;
        tiedEarly.status = "PAID";
        repository.save(tiedEarly);

        OrderEntity tiedMiddle = new OrderEntity();
        tiedMiddle.id = 970L;
        tiedMiddle.userId = 77L;
        tiedMiddle.totalAmount = 20.0;
        tiedMiddle.status = "PAID";
        repository.save(tiedMiddle);

        OrderEntity low = new OrderEntity();
        low.id = 990L;
        low.userId = 77L;
        low.totalAmount = 5.0;
        low.status = "PAID";
        repository.save(low);

        List<OrderEntity> result = repository.query(QuerySpec.builder()
                .filter(QueryFilter.eq("user_id", 77L))
                .sort(QuerySort.desc("total_amount"))
                .sort(QuerySort.asc("id"))
                .limit(2)
                .build());

        Assertions.assertEquals(2, result.size());
        Assertions.assertEquals(950L, result.get(0).id);
        Assertions.assertEquals(960L, result.get(1).id);
    }

    @Test
    void queryDslShouldSupportCompoundGroupsAndSortedIndexScan() {
        EntityRepository<UserEntity, Long> repository = cacheDatabase.repository(
                UserEntityCacheBinding.METADATA,
                UserEntityCacheBinding.CODEC,
                CachePolicy.builder().hotEntityLimit(20).pageSize(5).entityTtlSeconds(300).pageTtlSeconds(300).build()
        );

        UserEntity first = new UserEntity();
        first.id = 1001L;
        first.username = "anna";
        first.status = "ACTIVE";
        repository.save(first);

        UserEntity second = new UserEntity();
        second.id = 1002L;
        second.username = "mark";
        second.status = "SUSPENDED";
        repository.save(second);

        UserEntity third = new UserEntity();
        third.id = 1003L;
        third.username = "zoe";
        third.status = "ACTIVE";
        repository.save(third);

        QuerySpec querySpec = QuerySpec.builder()
                .anyOf(
                        QueryFilter.eq("status", "ACTIVE"),
                        QueryGroup.and(
                                QueryFilter.eq("status", "SUSPENDED"),
                                QueryFilter.startsWith("username", "mar")
                        )
                )
                .sort(QuerySort.desc("id"))
                .offset(1)
                .limit(2)
                .build();

        List<UserEntity> result = repository.query(querySpec);
        Assertions.assertEquals(2, result.size());
        Assertions.assertEquals(1002L, result.get(0).id);
        Assertions.assertEquals(1001L, result.get(1).id);

        QueryExplainPlan plan = repository.explain(querySpec);
        Assertions.assertEquals("SORTED_INDEX_SCAN", plan.sortStrategy());
        Assertions.assertTrue(plan.plannerStrategy().startsWith("COST_BASED"));
        Assertions.assertTrue(plan.estimatedCost() > 0);
        Assertions.assertTrue(plan.steps().stream().anyMatch(step -> "GROUP".equals(step.stage()) && step.strategy().contains("OR_GROUP")));
    }

    @Test
    void debugShouldExportExplainPlans() throws Exception {
        EntityRepository<UserEntity, Long> repository = cacheDatabase.repository(
                UserEntityCacheBinding.METADATA,
                UserEntityCacheBinding.CODEC,
                CachePolicy.builder().hotEntityLimit(20).pageSize(5).entityTtlSeconds(300).pageTtlSeconds(300).build()
        );

        UserEntity first = new UserEntity();
        first.id = 1201L;
        first.username = "anna";
        first.status = "ACTIVE";
        repository.save(first);

        Path explainPath = Files.createTempFile("cachedb-explain-", ".md");
        cacheDatabase.debug().writeExplain(
                explainPath,
                "UserEntity",
                QuerySpec.builder()
                        .filter(QueryFilter.eq("status", "ACTIVE"))
                        .sort(QuerySort.desc("id"))
                        .limit(5)
                        .build(),
                AdminExportFormat.MARKDOWN
        );

        String explain = Files.readString(explainPath);
        Assertions.assertTrue(explain.contains("Explain UserEntity"));
        Assertions.assertTrue(explain.contains("SORTED_INDEX_SCAN"));
        Assertions.assertTrue(explain.contains("Estimated cost"));
        Assertions.assertFalse(explain.contains("## Relation States"));

        EntityRepository<OrderEntity, Long> orderRepository = cacheDatabase.repository(
                OrderEntityCacheBinding.METADATA,
                OrderEntityCacheBinding.CODEC,
                CachePolicy.builder().hotEntityLimit(20).pageSize(5).entityTtlSeconds(300).pageTtlSeconds(300).build()
        );
        UserEntity second = new UserEntity();
        second.id = 1202L;
        second.username = "bella";
        second.status = "ACTIVE";
        repository.save(second);

        OrderEntity relatedOrder = new OrderEntity();
        relatedOrder.id = 1203L;
        relatedOrder.userId = 1202L;
        relatedOrder.totalAmount = 77.0;
        relatedOrder.status = "PAID";
        orderRepository.save(relatedOrder);

        Path relationExplainPath = Files.createTempFile("cachedb-explain-relation-", ".md");
        cacheDatabase.debug().writeExplain(
                relationExplainPath,
                "UserEntity",
                QuerySpec.builder()
                        .filter(QueryFilter.eq("orders.status", "PAID"))
                        .fetchPlan(FetchPlan.of("orders"))
                        .limit(5)
                        .build(),
                AdminExportFormat.MARKDOWN
        );

        String relationExplain = Files.readString(relationExplainPath);
        Assertions.assertTrue(relationExplain.contains("## Relation States"));
        Assertions.assertTrue(relationExplain.contains("BATCH_PRELOAD"));
        Assertions.assertTrue(relationExplain.contains("RESOLVED"));
    }

    @Test
    void adminShouldPersistIncidentsWhenThresholdsAreExceeded() {
        String localPrefix = "cachedb-it-" + UUID.randomUUID();
        String localDeadLetterStream = localPrefix + ":dlq";
        String localIncidentStream = localPrefix + ":incidents";

        try (CacheDatabase localDatabase = new CacheDatabase(
                jedis,
                dataSource(),
                CacheDatabaseConfig.builder()
                        .writeBehind(WriteBehindConfig.builder()
                                .enabled(false)
                                .streamKey(localPrefix + ":stream")
                                .consumerGroup(localPrefix + "-group")
                                .consumerNamePrefix(localPrefix + "-worker")
                                .deadLetterStreamKey(localDeadLetterStream)
                                .build())
                        .redisFunctions(RedisFunctionsConfig.builder().enabled(false).build())
                        .deadLetterRecovery(DeadLetterRecoveryConfig.builder()
                                .enabled(false)
                                .consumerGroup(localPrefix + "-dlq-group")
                                .consumerNamePrefix(localPrefix + "-dlq-worker")
                                .reconciliationStreamKey(localPrefix + ":reconciliation")
                                .archiveStreamKey(localPrefix + ":archive")
                                .build())
                        .adminMonitoring(AdminMonitoringConfig.builder()
                                .deadLetterWarnThreshold(1)
                                .deadLetterCriticalThreshold(10)
                                .incidentStreamKey(localIncidentStream)
                                .incidentMaxLength(100)
                                .incidentCooldownMillis(0)
                                .build())
                        .build()
        )) {
            jedis.xadd(localDeadLetterStream, XAddParams.xAddParams(), Map.of(
                    "type", "UPSERT",
                    "entity", "UserEntity",
                    "table", "cachedb_example_users",
                    "namespace", "users",
                    "idColumn", "id",
                    "id", "4401",
                    "version", "1"
            ));

            Assertions.assertFalse(localDatabase.admin().incidents().isEmpty());
            var persisted = localDatabase.admin().persistIncidents("integration-test");
            Assertions.assertFalse(persisted.isEmpty());
            Assertions.assertTrue(persisted.stream().anyMatch(record -> "DEAD_LETTER_BACKLOG".equals(record.code())));
            Assertions.assertTrue(localDatabase.admin().exportIncidents(AdminExportFormat.JSON, 10).content().contains("DEAD_LETTER_BACKLOG"));
        }
    }

    @Test
    void incidentWebhookShouldReceivePersistedIncidentPayloads() throws Exception {
        AtomicReference<String> payloadRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> smtpPayloadRef = new AtomicReference<>();
        CountDownLatch smtpLatch = new CountDownLatch(1);
        HttpServer webhookServer = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        webhookServer.createContext("/incident", exchange -> {
            payloadRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            latch.countDown();
        });
        webhookServer.start();
        ServerSocket smtpServerSocket = new ServerSocket(0);
        Thread smtpThread = new Thread(() -> runFakeSmtpServer(smtpServerSocket, smtpPayloadRef, smtpLatch), "cachedb-fake-smtp");
        smtpThread.setDaemon(true);
        smtpThread.start();

        String localPrefix = "cachedb-it-" + UUID.randomUUID();
        String localDeadLetterStream = localPrefix + ":dlq";
        String incidentQueueStream = localPrefix + ":incident-queue";
        String endpointUrl = "http://127.0.0.1:" + webhookServer.getAddress().getPort() + "/incident";

        try (CacheDatabase localDatabase = new CacheDatabase(
                jedis,
                dataSource(),
                CacheDatabaseConfig.builder()
                        .writeBehind(WriteBehindConfig.builder()
                                .enabled(false)
                                .streamKey(localPrefix + ":stream")
                                .consumerGroup(localPrefix + "-group")
                                .consumerNamePrefix(localPrefix + "-worker")
                                .deadLetterStreamKey(localDeadLetterStream)
                                .build())
                        .redisFunctions(RedisFunctionsConfig.builder().enabled(false).build())
                        .deadLetterRecovery(DeadLetterRecoveryConfig.builder()
                                .enabled(false)
                                .consumerGroup(localPrefix + "-dlq-group")
                                .consumerNamePrefix(localPrefix + "-dlq-worker")
                                .reconciliationStreamKey(localPrefix + ":reconciliation")
                                .archiveStreamKey(localPrefix + ":archive")
                                .build())
                        .adminMonitoring(AdminMonitoringConfig.builder()
                                .deadLetterWarnThreshold(1)
                                .deadLetterCriticalThreshold(10)
                                .incidentStreamKey(localPrefix + ":incidents")
                                .incidentCooldownMillis(0)
                                .incidentWebhook(IncidentWebhookConfig.builder()
                                        .enabled(true)
                                        .endpointUrl(endpointUrl)
                                        .workerThreads(1)
                                        .queueCapacity(8)
                                        .maxRetries(1)
                                        .retryBackoffMillis(50)
                                        .build())
                                .incidentQueue(IncidentQueueConfig.builder()
                                        .enabled(true)
                                        .streamKey(incidentQueueStream)
                                        .maxLength(100)
                                        .maxRetries(1)
                                        .retryBackoffMillis(50)
                                        .build())
                                .incidentEmail(IncidentEmailConfig.builder()
                                        .enabled(true)
                                        .smtpHost("127.0.0.1")
                                        .smtpPort(smtpServerSocket.getLocalPort())
                                        .connectTimeoutMillis(2_000)
                                        .readTimeoutMillis(2_000)
                                        .maxRetries(1)
                                        .retryBackoffMillis(50)
                                        .heloHost("localhost")
                                        .username("incident-user")
                                        .password("incident-pass")
                                        .authMechanism("PLAIN")
                                        .fromAddress("cachedb@test.local")
                                        .addToAddress("ops@test.local")
                                        .subjectPrefix("[IT]")
                                        .build())
                                .build())
                        .build()
        )) {
            localDatabase.start();
            jedis.xadd(localDeadLetterStream, XAddParams.xAddParams(), Map.of(
                    "type", "UPSERT",
                    "entity", "UserEntity",
                    "table", "cachedb_example_users",
                    "namespace", "users",
                    "idColumn", "id",
                    "id", "5501",
                    "version", "1"
            ));

            localDatabase.admin().persistIncidents("webhook-test");
            Assertions.assertTrue(latch.await(10, TimeUnit.SECONDS));
            Assertions.assertTrue(payloadRef.get().contains("\"eventType\":\"ADMIN_INCIDENT\""));
            Assertions.assertTrue(payloadRef.get().contains("\"code\":\"DEAD_LETTER_BACKLOG\""));
            waitUntil(() -> jedis.xlen(incidentQueueStream) > 0, Duration.ofSeconds(10));
            Assertions.assertTrue(smtpLatch.await(10, TimeUnit.SECONDS));
            Assertions.assertTrue(smtpPayloadRef.get().contains("Subject: [IT] DEAD_LETTER_BACKLOG"));
            var snapshot = localDatabase.admin().metrics().incidentDeliverySnapshot();
            Assertions.assertTrue(snapshot.enqueuedCount() > 0);
            Assertions.assertTrue(snapshot.channels().stream().anyMatch(channel -> "webhook".equals(channel.channelName()) && channel.deliveredCount() > 0));
            Assertions.assertTrue(snapshot.channels().stream().anyMatch(channel -> "queue".equals(channel.channelName()) && channel.deliveredCount() > 0));
            Assertions.assertTrue(snapshot.channels().stream().anyMatch(channel -> "smtp".equals(channel.channelName()) && channel.deliveredCount() > 0));
            Assertions.assertEquals(0L, snapshot.recovery().failedReplayCount());
        } finally {
            webhookServer.stop(0);
            smtpServerSocket.close();
        }
    }

    @Test
    void incidentDeliveryShouldReplayDeadLetteredChannels() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        CountDownLatch replayedLatch = new CountDownLatch(1);
        HttpServer webhookServer = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        webhookServer.createContext("/incident", exchange -> {
            int current = requestCount.incrementAndGet();
            if (current == 1) {
                exchange.sendResponseHeaders(500, -1);
            } else {
                exchange.sendResponseHeaders(202, -1);
                replayedLatch.countDown();
            }
            exchange.close();
        });
        webhookServer.start();

        String localPrefix = "cachedb-it-" + UUID.randomUUID();
        String localDeadLetterStream = localPrefix + ":dlq";
        String deliveryDlqStream = localPrefix + ":incident-delivery-dlq";
        String deliveryRecoveryStream = localPrefix + ":incident-delivery-recovery";
        String endpointUrl = "http://127.0.0.1:" + webhookServer.getAddress().getPort() + "/incident";

        try (CacheDatabase localDatabase = new CacheDatabase(
                jedis,
                dataSource(),
                CacheDatabaseConfig.builder()
                        .writeBehind(WriteBehindConfig.builder()
                                .enabled(false)
                                .streamKey(localPrefix + ":stream")
                                .consumerGroup(localPrefix + "-group")
                                .consumerNamePrefix(localPrefix + "-worker")
                                .deadLetterStreamKey(localDeadLetterStream)
                                .build())
                        .redisFunctions(RedisFunctionsConfig.builder().enabled(false).build())
                        .deadLetterRecovery(DeadLetterRecoveryConfig.builder()
                                .enabled(false)
                                .consumerGroup(localPrefix + "-dlq-group")
                                .consumerNamePrefix(localPrefix + "-dlq-worker")
                                .reconciliationStreamKey(localPrefix + ":reconciliation")
                                .archiveStreamKey(localPrefix + ":archive")
                                .build())
                        .adminMonitoring(AdminMonitoringConfig.builder()
                                .deadLetterWarnThreshold(1)
                                .incidentStreamKey(localPrefix + ":incidents")
                                .incidentCooldownMillis(0)
                                .incidentWebhook(IncidentWebhookConfig.builder()
                                        .enabled(true)
                                        .endpointUrl(endpointUrl)
                                        .workerThreads(1)
                                        .queueCapacity(8)
                                        .maxRetries(0)
                                        .retryBackoffMillis(25)
                                        .build())
                                .incidentQueue(IncidentQueueConfig.builder().enabled(false).build())
                                .incidentEmail(IncidentEmailConfig.builder().enabled(false).build())
                                .incidentDeliveryDlq(IncidentDeliveryDlqConfig.builder()
                                        .enabled(true)
                                        .streamKey(deliveryDlqStream)
                                        .recoveryStreamKey(deliveryRecoveryStream)
                                        .consumerGroup(localPrefix + "-incident-delivery-group")
                                        .consumerNamePrefix(localPrefix + "-incident-delivery")
                                        .workerThreads(1)
                                        .batchSize(10)
                                        .blockTimeoutMillis(100)
                                        .maxReplayAttempts(2)
                                        .build())
                                .build())
                        .build()
        )) {
            localDatabase.start();
            jedis.xadd(localDeadLetterStream, XAddParams.xAddParams(), Map.of(
                    "type", "UPSERT",
                    "entity", "UserEntity",
                    "table", "cachedb_example_users",
                    "namespace", "users",
                    "idColumn", "id",
                    "id", "9901",
                    "version", "1"
            ));

            localDatabase.admin().persistIncidents("delivery-replay-test");
            Assertions.assertTrue(replayedLatch.await(10, TimeUnit.SECONDS));
            waitUntil(() -> jedis.xlen(deliveryRecoveryStream) > 0, Duration.ofSeconds(10));
            var snapshot = localDatabase.admin().metrics().incidentDeliverySnapshot();
            Assertions.assertTrue(snapshot.recovery().replayedCount() > 0);
            Assertions.assertTrue(requestCount.get() >= 2);
            Assertions.assertEquals(0L, jedis.xlen(deliveryDlqStream));
        } finally {
            webhookServer.stop(0);
        }
    }

    @Test
    void incidentDeliveryRecoveryShouldClaimAbandonedPendingEntries() throws Exception {
        CountDownLatch replayedLatch = new CountDownLatch(1);
        HttpServer webhookServer = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        webhookServer.createContext("/incident", exchange -> {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            replayedLatch.countDown();
        });
        webhookServer.start();

        String localPrefix = "cachedb-it-" + UUID.randomUUID();
        String deliveryDlqStream = localPrefix + ":incident-delivery-dlq";
        String deliveryRecoveryStream = localPrefix + ":incident-delivery-recovery";
        String deliveryGroup = localPrefix + "-incident-delivery-group";
        String endpointUrl = "http://127.0.0.1:" + webhookServer.getAddress().getPort() + "/incident";

        jedis.xadd(deliveryDlqStream, XAddParams.xAddParams(), Map.of(
                "channel", "webhook",
                "attempts", "1",
                "entryId", "seed-entry",
                "code", "DELIVERY_TEST",
                "description", "pending replay",
                "severity", "WARNING",
                "source", "integration-test",
                "recordedAt", Instant.now().toString()
        ));
        jedis.xgroupCreate(deliveryDlqStream, deliveryGroup, new StreamEntryID("0-0"), true);
        jedis.xreadGroup(
                deliveryGroup,
                "abandoned-consumer",
                new XReadGroupParams().count(1),
                Map.of(deliveryDlqStream, StreamEntryID.UNRECEIVED_ENTRY)
        );
        Thread.sleep(150);

        try (CacheDatabase localDatabase = new CacheDatabase(
                jedis,
                dataSource(),
                CacheDatabaseConfig.builder()
                        .writeBehind(WriteBehindConfig.builder()
                                .enabled(false)
                                .streamKey(localPrefix + ":stream")
                                .consumerGroup(localPrefix + "-group")
                                .consumerNamePrefix(localPrefix + "-worker")
                                .deadLetterStreamKey(localPrefix + ":dlq")
                                .build())
                        .redisFunctions(RedisFunctionsConfig.builder().enabled(false).build())
                        .deadLetterRecovery(DeadLetterRecoveryConfig.builder()
                                .enabled(false)
                                .consumerGroup(localPrefix + "-dlq-group")
                                .consumerNamePrefix(localPrefix + "-dlq-worker")
                                .reconciliationStreamKey(localPrefix + ":reconciliation")
                                .archiveStreamKey(localPrefix + ":archive")
                                .build())
                        .adminMonitoring(AdminMonitoringConfig.builder()
                                .incidentWebhook(IncidentWebhookConfig.builder()
                                        .enabled(true)
                                        .endpointUrl(endpointUrl)
                                        .workerThreads(1)
                                        .queueCapacity(8)
                                        .maxRetries(0)
                                        .retryBackoffMillis(25)
                                        .build())
                                .incidentQueue(IncidentQueueConfig.builder().enabled(false).build())
                                .incidentEmail(IncidentEmailConfig.builder().enabled(false).build())
                                .incidentDeliveryDlq(IncidentDeliveryDlqConfig.builder()
                                        .enabled(true)
                                        .streamKey(deliveryDlqStream)
                                        .recoveryStreamKey(deliveryRecoveryStream)
                                        .consumerGroup(deliveryGroup)
                                        .consumerNamePrefix(localPrefix + "-incident-delivery")
                                        .workerThreads(1)
                                        .batchSize(10)
                                        .blockTimeoutMillis(100)
                                        .claimAbandonedEntries(true)
                                        .claimIdleMillis(50)
                                        .claimBatchSize(10)
                                        .idleSleepMillis(25)
                                        .maxReplayAttempts(2)
                                        .build())
                                .build())
                        .build()
        )) {
            localDatabase.start();
            Assertions.assertTrue(replayedLatch.await(10, TimeUnit.SECONDS));
            waitUntil(() -> jedis.xlen(deliveryRecoveryStream) > 0, Duration.ofSeconds(10));
            var snapshot = localDatabase.admin().metrics().incidentDeliverySnapshot();
            Assertions.assertTrue(snapshot.recovery().claimedCount() > 0);
            Assertions.assertTrue(snapshot.recovery().replayedCount() > 0);
        } finally {
            webhookServer.stop(0);
        }
    }

    @Test
    void queryExecutionShouldPersistLearnedPlannerStatistics() throws Exception {
        EntityRepository<UserEntity, Long> repository = cacheDatabase.repository(
                UserEntityCacheBinding.METADATA,
                UserEntityCacheBinding.CODEC,
                CachePolicy.builder().hotEntityLimit(20).pageSize(5).entityTtlSeconds(300).pageTtlSeconds(300).build()
        );

        repository.save(user(7101L, "alpha-user", "ACTIVE"));
        repository.save(user(7102L, "beta-user", "ACTIVE"));
        repository.save(user(7103L, "gamma-user", "INACTIVE"));

        QuerySpec querySpec = QuerySpec.builder()
                .group(QueryGroup.and(
                        QueryFilter.eq("status", "ACTIVE"),
                        QueryFilter.startsWith("username", "a")
                ))
                .limit(10)
                .build();

        List<UserEntity> results = repository.query(querySpec);
        Assertions.assertEquals(1, results.size());

        waitUntil(
                () -> !jedis.keys(keyPrefix + ":users:index:stats:learned:*").isEmpty(),
                Duration.ofSeconds(5),
                "Expected learned planner statistics to be persisted"
        );

        QueryExplainPlan explainPlan = repository.explain(querySpec);
        Assertions.assertTrue(explainPlan.estimatedCost() > 0);
        Assertions.assertTrue(cacheDatabase.admin().metrics().plannerStatisticsSnapshot().learnedStatisticsKeyCount() > 0);
    }

    @Test
    void reindexShouldReplaceOldExactPrefixTokenAndRangeEntriesOnUpdate() throws Exception {
        EntityRepository<UserEntity, Long> repository = cacheDatabase.repository(
                UserEntityCacheBinding.METADATA,
                UserEntityCacheBinding.CODEC,
                CachePolicy.builder().hotEntityLimit(20).pageSize(5).entityTtlSeconds(300).pageTtlSeconds(300).build()
        );

        repository.save(user(7150L, "alpha lane", "ACTIVE"));
        repository.save(user(7150L, "beta route", "INACTIVE"));

        List<UserEntity> oldPrefixMatches = repository.query(QuerySpec.builder()
                .filter(QueryFilter.startsWith("username", "alp"))
                .limit(10)
                .build());
        List<UserEntity> newPrefixMatches = repository.query(QuerySpec.builder()
                .filter(QueryFilter.startsWith("username", "bet"))
                .limit(10)
                .build());
        List<UserEntity> oldTokenMatches = repository.query(QuerySpec.builder()
                .filter(QueryFilter.contains("username", "lane"))
                .limit(10)
                .build());
        List<UserEntity> newTokenMatches = repository.query(QuerySpec.builder()
                .filter(QueryFilter.contains("username", "route"))
                .limit(10)
                .build());
        List<UserEntity> activeMatches = repository.query(QuerySpec.builder()
                .filter(QueryFilter.eq("status", "ACTIVE"))
                .limit(10)
                .build());
        List<UserEntity> inactiveMatches = repository.query(QuerySpec.builder()
                .filter(QueryFilter.eq("status", "INACTIVE"))
                .limit(10)
                .build());
        List<UserEntity> rangeMatches = repository.query(QuerySpec.builder()
                .filter(QueryFilter.gte("id", 7150L))
                .limit(10)
                .build());

        Assertions.assertTrue(oldPrefixMatches.isEmpty());
        Assertions.assertEquals(1, newPrefixMatches.size());
        Assertions.assertEquals(7150L, newPrefixMatches.get(0).id);
        Assertions.assertTrue(oldTokenMatches.isEmpty());
        Assertions.assertEquals(1, newTokenMatches.size());
        Assertions.assertEquals(7150L, newTokenMatches.get(0).id);
        Assertions.assertTrue(activeMatches.stream().noneMatch(entity -> entity.id == 7150L));
        Assertions.assertTrue(inactiveMatches.stream().anyMatch(entity -> entity.id == 7150L));
        Assertions.assertTrue(rangeMatches.stream().anyMatch(entity -> entity.id == 7150L));

        Assertions.assertFalse(jedis.sismember(exactIndexKey("status", "ACTIVE"), "7150"));
        Assertions.assertTrue(jedis.sismember(exactIndexKey("status", "INACTIVE"), "7150"));
        Assertions.assertFalse(jedis.sismember(prefixIndexKey("username", "alp"), "7150"));
        Assertions.assertTrue(jedis.sismember(prefixIndexKey("username", "bet"), "7150"));
        Assertions.assertFalse(jedis.sismember(tokenIndexKey("username", "lane"), "7150"));
        Assertions.assertTrue(jedis.sismember(tokenIndexKey("username", "route"), "7150"));
        Assertions.assertEquals(7150.0, jedis.zscore(keyPrefix + ":users:index:sort:id", "7150"));
    }

    @Test
    void adminHttpServerShouldExposeHealthMetricsDiagnosticsAndExplain() throws Exception {
        EntityRepository<UserEntity, Long> repository = cacheDatabase.repository(
                UserEntityCacheBinding.METADATA,
                UserEntityCacheBinding.CODEC,
                CachePolicy.builder().hotEntityLimit(10).pageSize(5).entityTtlSeconds(300).pageTtlSeconds(300).build()
        );

        UserEntity entity = new UserEntity();
        entity.id = 1301L;
        entity.username = "dashboard-user";
        entity.status = "ACTIVE";
        repository.save(entity);
        cacheDatabase.admin().persistDiagnostics("integration-test", "http server smoke");
        Path reportDirectory = Path.of("target", "cachedb-prodtest-reports");
        Files.createDirectories(reportDirectory);
        Files.writeString(
                reportDirectory.resolve("production-gate-report.md"),
                "# Production Gate Report\n\nOverall: PASS\n",
                StandardCharsets.UTF_8
        );
        cacheDatabase.admin().applySchemaMigrationPlan();

        try (CacheDatabaseAdminHttpServer httpServer = cacheDatabase.adminHttpServer(
                AdminHttpConfig.builder()
                        .enabled(true)
                        .host("127.0.0.1")
                        .port(0)
                        .workerThreads(1)
                        .dashboardEnabled(true)
                        .dashboardTitle("CacheDB Test Admin")
                        .build()
        )) {
            httpServer.start();
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> healthResponse = httpGet(client, httpServer.baseUri().resolve("/api/health"));
            Assertions.assertEquals(200, healthResponse.statusCode());
            Assertions.assertTrue(healthResponse.body().contains("\"status\""));

            HttpResponse<String> diagnosticsResponse = httpGet(client, httpServer.baseUri().resolve("/api/diagnostics?limit=5"));
            Assertions.assertEquals(200, diagnosticsResponse.statusCode());
            Assertions.assertTrue(diagnosticsResponse.body().contains("\"items\""));

            HttpResponse<String> profileChurnResponse = httpGet(client, httpServer.baseUri().resolve("/api/profile-churn?limit=5"));
            Assertions.assertEquals(200, profileChurnResponse.statusCode());
            Assertions.assertTrue(profileChurnResponse.body().contains("\"items\""));

            HttpResponse<String> metricsResponse = httpGet(client, httpServer.baseUri().resolve("/api/metrics"));
            Assertions.assertEquals(200, metricsResponse.statusCode());
            Assertions.assertTrue(metricsResponse.body().contains("\"plannerStatisticsSnapshot\""));

            HttpResponse<String> triageResponse = httpGet(client, httpServer.baseUri().resolve("/api/triage"));
            Assertions.assertEquals(200, triageResponse.statusCode());
            Assertions.assertTrue(triageResponse.body().contains("\"primaryBottleneck\""));

            HttpResponse<String> servicesResponse = httpGet(client, httpServer.baseUri().resolve("/api/services"));
            Assertions.assertEquals(200, servicesResponse.statusCode());
            Assertions.assertTrue(servicesResponse.body().contains("\"serviceName\":\"write-behind\""));

            HttpResponse<String> performanceResponse = httpGet(client, httpServer.baseUri().resolve("/api/performance"));
            Assertions.assertEquals(200, performanceResponse.statusCode());
            Assertions.assertTrue(performanceResponse.body().contains("\"redisRead\""));
            Assertions.assertTrue(performanceResponse.body().contains("\"postgresWrite\""));

            HttpResponse<String> alertRoutingResponse = httpGet(client, httpServer.baseUri().resolve("/api/alert-routing"));
            Assertions.assertEquals(200, alertRoutingResponse.statusCode());
            Assertions.assertTrue(alertRoutingResponse.body().contains("\"routeName\":\"webhook\""));
            Assertions.assertTrue(alertRoutingResponse.body().contains("\"retryPolicy\""));
            Assertions.assertTrue(alertRoutingResponse.body().contains("\"status\""));
            Assertions.assertTrue(alertRoutingResponse.body().contains("\"escalationLevel\""));
            Assertions.assertTrue(alertRoutingResponse.body().contains("\"deliveredCount\""));

            HttpResponse<String> runbooksResponse = httpGet(client, httpServer.baseUri().resolve("/api/runbooks"));
            Assertions.assertEquals(200, runbooksResponse.statusCode());
            Assertions.assertTrue(runbooksResponse.body().contains("\"code\":\"WRITE_BEHIND_BACKLOG\""));

            HttpResponse<String> historyResponse = httpGet(client, httpServer.baseUri().resolve("/api/history?limit=5"));
            Assertions.assertEquals(200, historyResponse.statusCode());
            Assertions.assertTrue(historyResponse.body().contains("\"items\""));
            Assertions.assertTrue(historyResponse.body().contains("\"writeBehindBacklog\""));

            HttpResponse<String> resetTelemetryResponse = httpPost(client, httpServer.baseUri().resolve("/api/telemetry/reset"));
            Assertions.assertEquals(200, resetTelemetryResponse.statusCode());
            Assertions.assertTrue(resetTelemetryResponse.body().contains("\"diagnosticsEntriesCleared\""));
            Assertions.assertTrue(resetTelemetryResponse.body().contains("\"monitoringHistorySamplesCleared\""));

            HttpResponse<String> diagnosticsAfterResetResponse = httpGet(client, httpServer.baseUri().resolve("/api/diagnostics?limit=5"));
            Assertions.assertEquals(200, diagnosticsAfterResetResponse.statusCode());
            Assertions.assertFalse(diagnosticsAfterResetResponse.body().contains("integration-test"));

            HttpResponse<String> incidentSeverityHistoryResponse = httpGet(client, httpServer.baseUri().resolve("/api/incident-severity/history?limit=8"));
            Assertions.assertEquals(200, incidentSeverityHistoryResponse.statusCode());
            Assertions.assertTrue(incidentSeverityHistoryResponse.body().contains("\"items\""));

            HttpResponse<String> failingSignalsResponse = httpGet(client, httpServer.baseUri().resolve("/api/failing-signals?limit=4"));
            Assertions.assertEquals(200, failingSignalsResponse.statusCode());
            Assertions.assertTrue(failingSignalsResponse.body().contains("\"items\""));

            HttpResponse<String> alertRouteHistoryResponse = httpGet(client, httpServer.baseUri().resolve("/api/alert-routing/history?limit=8"));
            Assertions.assertEquals(200, alertRouteHistoryResponse.statusCode());
            Assertions.assertTrue(alertRouteHistoryResponse.body().contains("\"items\""));
            Assertions.assertTrue(alertRouteHistoryResponse.body().contains("\"routeName\":\"webhook\""));

            HttpResponse<String> deploymentResponse = httpGet(client, httpServer.baseUri().resolve("/api/deployment"));
            Assertions.assertEquals(200, deploymentResponse.statusCode());
            Assertions.assertTrue(deploymentResponse.body().contains("\"writeBehindEnabled\":true"));

            HttpResponse<String> schemaStatusResponse = httpGet(client, httpServer.baseUri().resolve("/api/schema/status"));
            Assertions.assertEquals(200, schemaStatusResponse.statusCode());
            Assertions.assertTrue(schemaStatusResponse.body().contains("\"bootstrapMode\""));

            HttpResponse<String> registryResponse = httpGet(client, httpServer.baseUri().resolve("/api/registry"));
            Assertions.assertEquals(200, registryResponse.statusCode());
            Assertions.assertTrue(registryResponse.body().contains("\"registeredEntityCount\""));
            Assertions.assertTrue(registryResponse.body().contains("\"entityName\":\"UserEntity\""));

            HttpResponse<String> tuningResponse = httpGet(client, httpServer.baseUri().resolve("/api/tuning"));
            Assertions.assertEquals(200, tuningResponse.statusCode());
            Assertions.assertTrue(tuningResponse.body().contains("\"explicitOverrideCount\""));
            Assertions.assertTrue(tuningResponse.body().contains("\"property\":\"cachedb.config.writeBehind.enabled\""));

            HttpResponse<String> tuningMarkdownResponse = httpGet(client, httpServer.baseUri().resolve("/api/tuning/export?format=markdown"));
            Assertions.assertEquals(200, tuningMarkdownResponse.statusCode());
            Assertions.assertTrue(tuningMarkdownResponse.body().contains("# Current Effective Tuning"));
            Assertions.assertTrue(tuningMarkdownResponse.body().contains("| Group | Property | Value | Source | Description |"));

            HttpResponse<String> tuningFlagsResponse = httpGet(client, httpServer.baseUri().resolve("/api/tuning/flags"));
            Assertions.assertEquals(200, tuningFlagsResponse.statusCode());
            Assertions.assertTrue(tuningFlagsResponse.body().contains("-Dcachedb.config.writeBehind.enabled="));

            HttpResponse<String> runtimeProfileResponse = httpGet(client, httpServer.baseUri().resolve("/api/runtime-profile"));
            Assertions.assertEquals(200, runtimeProfileResponse.statusCode());
            Assertions.assertTrue(runtimeProfileResponse.body().contains("\"activeProfile\""));
            Assertions.assertTrue(runtimeProfileResponse.body().contains("\"properties\""));
            Assertions.assertTrue(runtimeProfileResponse.body().contains("\"property\":\"hotEntityLimitFactor\""));

            HttpResponse<String> runtimeProfileBalancedResponse = httpPost(client, httpServer.baseUri().resolve("/api/runtime-profile?profile=BALANCED"));
            Assertions.assertEquals(200, runtimeProfileBalancedResponse.statusCode());
            Assertions.assertTrue(runtimeProfileBalancedResponse.body().contains("\"activeProfile\":\"BALANCED\""));
            Assertions.assertTrue(runtimeProfileBalancedResponse.body().contains("\"mode\":\"MANUAL\""));

            HttpResponse<String> runtimeProfileAutoResponse = httpPost(client, httpServer.baseUri().resolve("/api/runtime-profile?profile=AUTO"));
            Assertions.assertEquals(200, runtimeProfileAutoResponse.statusCode());
            Assertions.assertTrue(runtimeProfileAutoResponse.body().contains("\"mode\":\"AUTO\""));

            HttpResponse<String> backgroundErrorsResponse = httpGet(client, httpServer.baseUri().resolve("/api/background-errors"));
            Assertions.assertEquals(200, backgroundErrorsResponse.statusCode());
            Assertions.assertTrue(backgroundErrorsResponse.body().contains("\"items\""));

            HttpResponse<String> certificationResponse = httpGet(client, httpServer.baseUri().resolve("/api/certification"));
            Assertions.assertEquals(200, certificationResponse.statusCode());
            Assertions.assertTrue(certificationResponse.body().contains("\"reportKey\":\"production-gate-report\""));
            Assertions.assertTrue(certificationResponse.body().contains("\"status\":\"PASS\""));

            HttpResponse<String> schemaHistoryResponse = httpGet(client, httpServer.baseUri().resolve("/api/schema/history?limit=5"));
            Assertions.assertEquals(200, schemaHistoryResponse.statusCode());
            Assertions.assertTrue(schemaHistoryResponse.body().contains("\"items\""));
            Assertions.assertTrue(schemaHistoryResponse.body().contains("\"operation\":\"APPLY\""));

            HttpResponse<String> schemaDdlResponse = httpGet(client, httpServer.baseUri().resolve("/api/schema/ddl"));
            Assertions.assertEquals(200, schemaDdlResponse.statusCode());
            Assertions.assertTrue(schemaDdlResponse.body().contains("\"entityName\":\"UserEntity\""));
            Assertions.assertTrue(schemaDdlResponse.body().contains("CREATE TABLE"));

            HttpResponse<String> profilesResponse = httpGet(client, httpServer.baseUri().resolve("/api/profiles"));
            Assertions.assertEquals(200, profilesResponse.statusCode());
            Assertions.assertTrue(profilesResponse.body().contains("\"name\":\"development\""));
            Assertions.assertTrue(profilesResponse.body().contains("\"name\":\"production\""));

            HttpResponse<String> explainResponse = httpGet(
                    client,
                    URI.create(httpServer.baseUri() + "/api/explain?entity=UserEntity&filter=status:eq:ACTIVE&sort=id:desc&limit=5&format=json")
            );
            Assertions.assertEquals(200, explainResponse.statusCode());
            Assertions.assertTrue(explainResponse.body().contains("\"plannerStrategy\""));
            Assertions.assertTrue(explainResponse.body().contains("\"relationStates\""));
            Assertions.assertTrue(explainResponse.body().contains("\"warnings\""));

            HttpResponse<String> explainNoteResponse = httpPost(
                    client,
                    URI.create(httpServer.baseUri() + "/api/explain/note?entity=UserEntity&filter=status:eq:ACTIVE&sort=id:desc&limit=5&title=dashboard-note")
            );
            Assertions.assertEquals(200, explainNoteResponse.statusCode());
            Assertions.assertTrue(explainNoteResponse.body().contains("\"source\":\"explain-note\""));
            Assertions.assertTrue(explainNoteResponse.body().contains("\"title\":\"dashboard-note\""));

            HttpResponse<String> diagnosticsWithExplainNote = httpGet(client, httpServer.baseUri().resolve("/api/diagnostics?limit=10"));
            Assertions.assertEquals(200, diagnosticsWithExplainNote.statusCode());
            Assertions.assertTrue(diagnosticsWithExplainNote.body().contains("\"source\":\"explain-note\""));
            Assertions.assertTrue(diagnosticsWithExplainNote.body().contains("dashboard-note"));

            HttpResponse<String> dashboardResponse = httpGet(client, httpServer.baseUri().resolve("/dashboard?lang=en"));
            Assertions.assertEquals(200, dashboardResponse.statusCode());
            Assertions.assertTrue(
                    dashboardResponse.body().contains("CacheDB Test Admin")
                            || dashboardResponse.body().contains("CacheDB Operations Console")
                            || dashboardResponse.body().contains("Operations Console")
            );
            Assertions.assertTrue(
                    dashboardResponse.body().contains("Learned Stats")
                            || dashboardResponse.body().contains("Primary Signals")
            );
            Assertions.assertTrue(dashboardResponse.body().contains("Live Refresh"));
            Assertions.assertTrue(dashboardResponse.body().contains("Live Trends"));
            Assertions.assertTrue(dashboardResponse.body().contains("Refresh Now"));
            Assertions.assertTrue(dashboardResponse.body().contains("Triage"));
            Assertions.assertTrue(dashboardResponse.body().contains("Explain Warnings"));
            Assertions.assertTrue(dashboardResponse.body().contains("Relation States"));
            Assertions.assertTrue(dashboardResponse.body().contains("Explain Steps"));
            Assertions.assertTrue(dashboardResponse.body().contains("Raw Explain JSON"));
            Assertions.assertTrue(dashboardResponse.body().contains("explainRelationRows"));
            Assertions.assertTrue(dashboardResponse.body().contains("renderExplainResult"));
            Assertions.assertTrue(dashboardResponse.body().contains("explainRelationUsageFilter"));
            Assertions.assertTrue(dashboardResponse.body().contains("explainRelationWarningsOnly"));
            Assertions.assertTrue(dashboardResponse.body().contains("renderExplainRelations"));
            Assertions.assertTrue(dashboardResponse.body().contains("explainEstimatedCost"));
            Assertions.assertTrue(dashboardResponse.body().contains("explainFullyIndexed"));
            Assertions.assertTrue(dashboardResponse.body().contains("explainStepCount"));
            Assertions.assertTrue(dashboardResponse.body().contains("explainStepRows"));
            Assertions.assertTrue(dashboardResponse.body().contains("renderExplainSteps"));
            Assertions.assertTrue(dashboardResponse.body().contains("Why Slow"));
            Assertions.assertTrue(dashboardResponse.body().contains("Why Degraded"));
            Assertions.assertTrue(dashboardResponse.body().contains("Top Suspicious Step"));
            Assertions.assertTrue(dashboardResponse.body().contains("summarizeExplainTriage"));
            Assertions.assertTrue(dashboardResponse.body().contains("explainWhySlow"));
            Assertions.assertTrue(dashboardResponse.body().contains("explainWhyDegraded"));
            Assertions.assertTrue(dashboardResponse.body().contains("explainSuspiciousStep"));
            Assertions.assertTrue(dashboardResponse.body().contains("Copy Explain as Markdown"));
            Assertions.assertTrue(dashboardResponse.body().contains("Download Explain JSON"));
            Assertions.assertTrue(dashboardResponse.body().contains("Save as Incident Note"));
            Assertions.assertTrue(dashboardResponse.body().contains("copyExplainMarkdownAction"));
            Assertions.assertTrue(dashboardResponse.body().contains("downloadExplainJsonAction"));
            Assertions.assertTrue(dashboardResponse.body().contains("saveExplainNoteAction"));
            Assertions.assertTrue(dashboardResponse.body().contains("explainActionStatus"));
            Assertions.assertTrue(dashboardResponse.body().contains("Service Status"));
            Assertions.assertTrue(dashboardResponse.body().contains("Background Worker Errors"));
            Assertions.assertTrue(dashboardResponse.body().contains("backgroundErrorRows"));
            Assertions.assertTrue(dashboardResponse.body().contains("renderBackgroundErrors"));
            Assertions.assertTrue(dashboardResponse.body().contains("Alert Route Trends"));
            Assertions.assertTrue(dashboardResponse.body().contains("Incident Severity Trends"));
            Assertions.assertTrue(dashboardResponse.body().contains("Top Failing Signals"));
            Assertions.assertTrue(dashboardResponse.body().contains("Alert Route History"));
            Assertions.assertTrue(dashboardResponse.body().contains("Runtime Profile Churn"));
            Assertions.assertTrue(dashboardResponse.body().contains("Deployment"));
            Assertions.assertTrue(dashboardResponse.body().contains("Schema Status"));
            Assertions.assertTrue(dashboardResponse.body().contains("Schema History"));
            Assertions.assertTrue(dashboardResponse.body().contains("Starter Profiles"));
            Assertions.assertTrue(dashboardResponse.body().contains("API Registry"));
            Assertions.assertTrue(dashboardResponse.body().contains("Current Effective Tuning"));
            Assertions.assertTrue(dashboardResponse.body().contains("Runtime Profile Control"));
            Assertions.assertTrue(dashboardResponse.body().contains("runtimeProfilePropertyRows"));
            Assertions.assertTrue(dashboardResponse.body().contains("runtimeProfileStatus"));
            Assertions.assertTrue(dashboardResponse.body().contains("/api/runtime-profile"));
            Assertions.assertTrue(dashboardResponse.body().contains("setRuntimeProfile("));
            Assertions.assertTrue(dashboardResponse.body().contains("Export JSON"));
            Assertions.assertTrue(dashboardResponse.body().contains("Export Markdown"));
            Assertions.assertTrue(dashboardResponse.body().contains("Copy Startup Flags"));
            Assertions.assertTrue(dashboardResponse.body().contains("Alert Routing"));
            Assertions.assertTrue(dashboardResponse.body().contains("Runbooks"));
            Assertions.assertTrue(dashboardResponse.body().contains("Schema DDL"));
            Assertions.assertTrue(dashboardResponse.body().contains("Alert Delivered"));
            Assertions.assertTrue(dashboardResponse.body().contains("Alert Failed"));
            Assertions.assertTrue(dashboardResponse.body().contains("Alert Dropped"));
            Assertions.assertTrue(dashboardResponse.body().contains("Critical Signals"));
            Assertions.assertTrue(dashboardResponse.body().contains("Warning Signals"));
            Assertions.assertTrue(dashboardResponse.body().contains("Latency & Performance"));
            Assertions.assertTrue(dashboardResponse.body().contains("/api/performance"));
            Assertions.assertTrue(dashboardResponse.body().contains("renderPerformanceSection"));
            Assertions.assertTrue(dashboardResponse.body().contains("/api/deployment"));
            Assertions.assertTrue(dashboardResponse.body().contains("/api/triage"));
            Assertions.assertTrue(dashboardResponse.body().contains("/api/background-errors"));
            Assertions.assertTrue(dashboardResponse.body().contains("/api/alert-routing"));
            Assertions.assertTrue(dashboardResponse.body().contains("/api/alert-routing/history"));
            Assertions.assertTrue(dashboardResponse.body().contains("/api/incident-severity/history"));
            Assertions.assertTrue(dashboardResponse.body().contains("/api/failing-signals"));
            Assertions.assertTrue(dashboardResponse.body().contains("/api/history"));
            Assertions.assertTrue(dashboardResponse.body().contains("/api/tuning"));
            Assertions.assertTrue(dashboardResponse.body().contains("/api/tuning/export?format=json"));
            Assertions.assertTrue(dashboardResponse.body().contains("/api/tuning/flags"));
            Assertions.assertTrue(dashboardResponse.body().contains("/api/telemetry/reset"));
            Assertions.assertTrue(dashboardResponse.body().contains("/api/schema/history"));
            Assertions.assertTrue(dashboardResponse.body().contains("/api/profiles"));
            Assertions.assertTrue(dashboardResponse.body().contains("refreshInterval"));
            Assertions.assertTrue(dashboardResponse.body().contains("Reset Telemetry History"));
            Assertions.assertTrue(dashboardResponse.body().contains("backlogTrendSvg"));
            Assertions.assertTrue(dashboardResponse.body().contains("alertDeliveryTrendSvg"));
            Assertions.assertTrue(dashboardResponse.body().contains("alertFailureTrendSvg"));
            Assertions.assertTrue(dashboardResponse.body().contains("incidentSeverityTrendSvg"));
            Assertions.assertTrue(dashboardResponse.body().contains("failingSignalCards"));
            Assertions.assertTrue(dashboardResponse.body().contains("alertRouteHistoryRows"));
            Assertions.assertTrue(dashboardResponse.body().contains("tuningRows"));
            Assertions.assertTrue(dashboardResponse.body().contains("tuningExport"));
            Assertions.assertTrue(dashboardResponse.body().contains("loadTuningExport"));
            Assertions.assertTrue(dashboardResponse.body().contains("renderHistory"));
            Assertions.assertTrue(dashboardResponse.body().contains("renderChurnSvg"));
        }
    }

    @Test
    void adminHttpServerShouldExposeRuntimeProfileChurnAfterSwitch() throws Exception {
        String localPrefix = "cachedb-it-" + UUID.randomUUID();
        String localStreamKey = localPrefix + ":stream";
        String functionSuffix = localPrefix.replace('-', '_');

        try (CacheDatabase localDatabase = new CacheDatabase(jedis, dataSource(), CacheDatabaseConfig.builder()
                .writeBehind(WriteBehindConfig.builder()
                        .enabled(false)
                        .streamKey(localStreamKey)
                        .consumerGroup(localPrefix + "-group")
                        .consumerNamePrefix(localPrefix + "-worker")
                        .build())
                .resourceLimits(ResourceLimits.builder()
                        .defaultCachePolicy(CachePolicy.builder()
                                .hotEntityLimit(10)
                                .pageSize(5)
                                .entityTtlSeconds(300)
                                .pageTtlSeconds(300)
                                .build())
                        .build())
                .keyspace(KeyspaceConfig.builder()
                        .keyPrefix(localPrefix)
                        .entitySegment("entity")
                        .pageSegment("page")
                        .versionSegment("version")
                        .hotSetSegment("hotset")
                        .compactionSegment("compaction")
                        .build())
                .redisFunctions(RedisFunctionsConfig.builder()
                        .enabled(true)
                        .autoLoadLibrary(true)
                        .replaceLibraryOnLoad(true)
                        .strictLoading(true)
                        .libraryName(functionSuffix)
                        .upsertFunctionName(functionSuffix + "_entity_upsert")
                        .deleteFunctionName(functionSuffix + "_entity_delete")
                        .compactionCompleteFunctionName(functionSuffix + "_compaction_complete")
                        .build())
                .redisGuardrail(RedisGuardrailConfig.builder()
                        .enabled(true)
                        .producerBackpressureEnabled(false)
                        .usedMemoryWarnBytes(1L)
                        .usedMemoryCriticalBytes(1L)
                        .compactionPendingWarnThreshold(Long.MAX_VALUE)
                        .compactionPendingCriticalThreshold(Long.MAX_VALUE)
                        .automaticRuntimeProfileSwitchingEnabled(true)
                        .warnSamplesToBalanced(1)
                        .criticalSamplesToAggressive(1)
                        .warnSamplesToDeescalateAggressive(10)
                        .normalSamplesToStandard(10)
                        .build())
                .build())) {
            UserEntityCacheBinding.register(
                    localDatabase,
                    CachePolicy.builder().hotEntityLimit(10).pageSize(5).entityTtlSeconds(300).pageTtlSeconds(300).build(),
                    new UserOrdersRelationBatchLoader(),
                    new UserPageLoader()
            );
            localDatabase.start();

            EntityRepository<UserEntity, Long> repository = localDatabase.repository(
                    UserEntityCacheBinding.METADATA,
                    UserEntityCacheBinding.CODEC,
                    CachePolicy.builder().hotEntityLimit(10).pageSize(5).entityTtlSeconds(300).pageTtlSeconds(300).build()
            );
            UserEntity entity = new UserEntity();
            entity.id = 1302L;
            entity.username = "profile-churn-user";
            entity.status = "ACTIVE";
            repository.save(entity);

            Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (localDatabase.runtimeProfileEvents().isEmpty()) {
                    Thread.sleep(50L);
                }
            });

            try (CacheDatabaseAdminHttpServer httpServer = localDatabase.adminHttpServer(
                    AdminHttpConfig.builder()
                            .enabled(true)
                            .host("127.0.0.1")
                            .port(0)
                            .workerThreads(1)
                            .dashboardEnabled(true)
                            .dashboardTitle("CacheDB Churn Admin")
                            .build()
            )) {
                httpServer.start();
                HttpClient client = HttpClient.newHttpClient();

                HttpResponse<String> churnResponse = httpGet(client, httpServer.baseUri().resolve("/api/profile-churn?limit=5"));
                Assertions.assertEquals(200, churnResponse.statusCode());
                Assertions.assertTrue(churnResponse.body().contains("\"fromProfile\":\"STANDARD\""));
                Assertions.assertTrue(churnResponse.body().contains("\"toProfile\":\"AGGRESSIVE\""));

                HttpResponse<String> dashboardResponse = httpGet(client, httpServer.baseUri().resolve("/dashboard?lang=en"));
                Assertions.assertEquals(200, dashboardResponse.statusCode());
                Assertions.assertTrue(dashboardResponse.body().contains("Runtime Profile Churn"));
                Assertions.assertTrue(dashboardResponse.body().contains("/api/profile-churn?limit=24"));
            }
        }
    }

    @Test
    void adminHttpServerShouldExposePrometheusMetrics() throws Exception {
        cacheDatabase.admin().persistDiagnostics("integration-test", "prometheus smoke");

        try (CacheDatabaseAdminHttpServer httpServer = cacheDatabase.adminHttpServer(
                AdminHttpConfig.builder()
                        .enabled(true)
                        .host("127.0.0.1")
                        .port(0)
                        .workerThreads(1)
                        .dashboardEnabled(false)
                        .build()
        )) {
            httpServer.start();
            HttpResponse<String> response = httpGet(HttpClient.newHttpClient(), httpServer.baseUri().resolve("/api/prometheus"));
            Assertions.assertEquals(200, response.statusCode());
            Assertions.assertTrue(response.body().contains("cachedb_write_behind_stream_length"));
            Assertions.assertTrue(response.body().contains("cachedb_redis_used_memory_bytes"));
            Assertions.assertTrue(response.body().contains("cachedb_runtime_profile_info"));

            HttpResponse<String> rulesResponse = httpGet(HttpClient.newHttpClient(), httpServer.baseUri().resolve("/api/prometheus/rules"));
            Assertions.assertEquals(200, rulesResponse.statusCode());
            Assertions.assertTrue(rulesResponse.body().contains("CacheDbWriteBehindBacklogWarning"));
        }
    }

    @Test
    void workerShouldClaimAbandonedPendingEntries() throws Exception {
        cacheDatabase.close();
        keyPrefix = "cachedb-it-" + UUID.randomUUID();
        streamKey = keyPrefix + ":stream";

        cacheDatabase = new CacheDatabase(jedis, dataSource(), CacheDatabaseConfig.builder()
                .writeBehind(WriteBehindConfig.builder()
                        .workerThreads(1)
                        .batchSize(10)
                        .blockTimeoutMillis(200)
                        .idleSleepMillis(50)
                        .streamKey(streamKey)
                        .consumerGroup(keyPrefix + "-group")
                        .consumerNamePrefix(keyPrefix + "-worker")
                        .recoverPendingEntries(true)
                        .claimIdleMillis(25)
                        .claimBatchSize(10)
                        .build())
                .resourceLimits(ResourceLimits.builder()
                        .defaultCachePolicy(CachePolicy.builder()
                                .hotEntityLimit(4)
                                .pageSize(2)
                                .entityTtlSeconds(300)
                                .pageTtlSeconds(300)
                                .build())
                        .build())
                .keyspace(KeyspaceConfig.builder()
                        .keyPrefix(keyPrefix)
                        .entitySegment("entity")
                        .pageSegment("page")
                        .versionSegment("version")
                        .hotSetSegment("hotset")
                        .indexSegment("index")
                        .build())
                .redisFunctions(RedisFunctionsConfig.builder()
                        .enabled(false)
                        .build())
                .relations(RelationConfig.builder()
                        .batchSize(10)
                        .maxFetchDepth(1)
                        .failOnMissingPreloader(false)
                        .build())
                .pageCache(PageCacheConfig.builder()
                        .readThroughEnabled(true)
                        .failOnMissingPageLoader(true)
                        .evictionBatchSize(10)
                        .build())
                .queryIndex(QueryIndexConfig.defaults())
                .build());

        UserEntityCacheBinding.register(
                cacheDatabase,
                CachePolicy.builder().hotEntityLimit(4).pageSize(2).entityTtlSeconds(300).pageTtlSeconds(300).build(),
                new UserOrdersRelationBatchLoader(),
                new UserPageLoader()
        );

        EntityRepository<UserEntity, Long> repository = cacheDatabase.repository(
                UserEntityCacheBinding.METADATA,
                UserEntityCacheBinding.CODEC,
                CachePolicy.builder().hotEntityLimit(4).pageSize(2).entityTtlSeconds(300).pageTtlSeconds(300).build()
        );

        UserEntity entity = new UserEntity();
        entity.id = 404L;
        entity.username = "orphan";
        entity.status = "ACTIVE";
        repository.save(entity);

        jedis.xgroupCreate(streamKey, keyPrefix + "-group", new StreamEntryID("0-0"), false);
        var pending = jedis.xreadGroup(
                keyPrefix + "-group",
                "abandoned-consumer",
                new XReadGroupParams().count(10),
                Map.of(streamKey, StreamEntryID.UNRECEIVED_ENTRY)
        );
        Assertions.assertNotNull(pending);
        Assertions.assertFalse(pending.isEmpty());

        Thread.sleep(50);
        cacheDatabase.start();

        waitUntil(
                () -> countRows("select count(*) from cachedb_example_users where id = 404") == 1,
                Duration.ofSeconds(15),
                "worker=" + cacheDatabase.workerSnapshot() + ", stream=" + streamDiagnostics()
        );
        Assertions.assertTrue(cacheDatabase.workerSnapshot().claimedCount() > 0);
    }

    @Test
    void workerShouldApplyEntityAndOperationSpecificRetryOverrides() throws Exception {
        String localPrefix = "cachedb-it-" + UUID.randomUUID();
        String localStreamKey = localPrefix + ":stream";
        RedisKeyStrategy keyStrategy = new RedisKeyStrategy(localPrefix, "entity", "page", "version", "hotset", "index");
        RedisWriteOperationMapper mapper = new RedisWriteOperationMapper();
        RedisWriteBehindQueue queue = new RedisWriteBehindQueue(jedis, localStreamKey, mapper, 32);

        final int[] attempts = {0};
        RedisWriteBehindWorker worker = new RedisWriteBehindWorker(
                jedis,
                operation -> {
                    attempts[0]++;
                    if (attempts[0] < 3) {
                        throw new SQLException("forced failure");
                    }
                },
                WriteBehindConfig.builder()
                        .workerThreads(1)
                        .batchSize(10)
                        .blockTimeoutMillis(100)
                        .idleSleepMillis(25)
                        .streamKey(localStreamKey)
                        .consumerGroup(localPrefix + "-group")
                        .consumerNamePrefix(localPrefix + "-worker")
                        .maxFlushRetries(0)
                        .retryBackoffMillis(1)
                        .retryOverrides(List.of(
                                WriteRetryPolicyOverride.builder()
                                        .entityName("UserEntity")
                                        .operationType(OperationType.UPSERT)
                                        .maxRetries(3)
                                        .backoffMillis(10)
                                        .build()
                        ))
                        .build(),
                mapper,
                keyStrategy
        );

        jedis.set(keyStrategy.versionKey("users", 901L), "1");
        worker.start();
        queue.enqueue(new WriteOperation<>(
                OperationType.UPSERT,
                UserEntityCacheBinding.METADATA,
                901L,
                Map.of(
                        "id", 901L,
                        "username", "retry-user",
                        "status", "ACTIVE",
                        "entity_version", 1L
                ),
                "retry-payload",
                1L,
                Instant.now()
        ));

        waitUntil(
                () -> worker.snapshot().flushedCount() == 1,
                Duration.ofSeconds(10),
                "worker=" + worker.snapshot()
        );
        Assertions.assertTrue(worker.snapshot().retriedCount() >= 2);
        Assertions.assertEquals(3, attempts[0]);
        worker.close();
    }

    @Test
    void compactionWorkerShouldBatchFetchPayloadsAndMergeDuplicateTokens() throws Exception {
        String localPrefix = "cachedb-it-" + UUID.randomUUID();
        String localCompactionStreamKey = localPrefix + ":compaction";
        RedisKeyStrategy keyStrategy = new RedisKeyStrategy(localPrefix, "entity", "page", "version", "hotset", "index");
        RedisWriteOperationMapper mapper = new RedisWriteOperationMapper();
        java.util.concurrent.atomic.AtomicReference<QueuedWriteOperation> flushedOperation = new java.util.concurrent.atomic.AtomicReference<>();
        AtomicInteger flushCount = new AtomicInteger();

        QueuedWriteOperation operation = new QueuedWriteOperation(
                OperationType.UPSERT,
                "UserEntity",
                "cachedb_example_users",
                "users",
                "order-line-write-burst",
                "id",
                "entity_version",
                null,
                "9901",
                Map.of(
                        "id", "9901",
                        "username", "compaction-user",
                        "status", "ACTIVE",
                        "entity_version", "2"
                ),
                2L,
                Instant.now()
        );

        jedis.hset(keyStrategy.compactionPayloadKey("users", "9901"), mapper.toBody(operation));
        jedis.set(keyStrategy.compactionPendingKey("users", "9901"), "2");
        jedis.xadd(localCompactionStreamKey, XAddParams.xAddParams(), Map.of(
                "namespace", "users",
                "id", "9901",
                "version", "1",
                "entity", "UserEntity"
        ));
        jedis.xadd(localCompactionStreamKey, XAddParams.xAddParams(), Map.of(
                "namespace", "users",
                "id", "9901",
                "version", "2",
                "entity", "UserEntity"
        ));

        RedisWriteBehindWorker worker = new RedisWriteBehindWorker(
                jedis,
                queuedWriteOperation -> {
                    flushedOperation.set(queuedWriteOperation);
                    flushCount.incrementAndGet();
                },
                WriteBehindConfig.builder()
                        .workerThreads(1)
                        .batchSize(10)
                        .blockTimeoutMillis(100)
                        .idleSleepMillis(25)
                        .dedicatedWriteConsumerGroupEnabled(true)
                        .streamKey(localPrefix + ":stream")
                        .consumerGroup(localPrefix + "-group")
                        .consumerNamePrefix(localPrefix + "-worker")
                        .compactionStreamKey(localCompactionStreamKey)
                        .compactionConsumerGroup(localPrefix + "-compaction-group")
                        .compactionConsumerNamePrefix(localPrefix + "-compaction-worker")
                        .recoverPendingEntries(false)
                        .build(),
                mapper,
                keyStrategy
        );

        worker.start();

        waitUntil(
                () -> flushCount.get() == 1,
                Duration.ofSeconds(10),
                "worker=" + worker.snapshot()
        );

        Assertions.assertEquals(1, flushCount.get());
        Assertions.assertNotNull(flushedOperation.get());
        Assertions.assertEquals(2L, flushedOperation.get().version());
        Assertions.assertEquals("order-line-write-burst", flushedOperation.get().observationTag());
        Assertions.assertNull(jedis.get(keyStrategy.compactionPendingKey("users", "9901")));
        Assertions.assertTrue(jedis.hgetAll(keyStrategy.compactionPayloadKey("users", "9901")).isEmpty());

        worker.close();
    }

    @Test
    void deadLetterRecoveryWorkerShouldReplayAndPublishReconciliation() throws Exception {
        String localPrefix = "cachedb-it-" + UUID.randomUUID();
        String localStreamKey = localPrefix + ":stream";
        String deadLetterStreamKey = localPrefix + ":dlq";
        String reconciliationStreamKey = localPrefix + ":reconciliation";
        RedisKeyStrategy keyStrategy = new RedisKeyStrategy(localPrefix, "entity", "page", "version", "hotset", "index");
        RedisWriteOperationMapper mapper = new RedisWriteOperationMapper();
        RedisWriteBehindQueue queue = new RedisWriteBehindQueue(jedis, localStreamKey, mapper, 32);

        RedisWriteBehindWorker mainWorker = new RedisWriteBehindWorker(
                jedis,
                operation -> {
                    throw new SQLException("force dead letter");
                },
                WriteBehindConfig.builder()
                        .workerThreads(1)
                        .batchSize(10)
                        .blockTimeoutMillis(100)
                        .idleSleepMillis(25)
                        .streamKey(localStreamKey)
                        .consumerGroup(localPrefix + "-group")
                        .consumerNamePrefix(localPrefix + "-worker")
                        .maxFlushRetries(0)
                        .deadLetterStreamKey(deadLetterStreamKey)
                        .build(),
                mapper,
                keyStrategy
        );

        final int[] recoveryAttempts = {0};
        RedisDeadLetterRecoveryWorker recoveryWorker = new RedisDeadLetterRecoveryWorker(
                jedis,
                operation -> {
                    recoveryAttempts[0]++;
                },
                DeadLetterRecoveryConfig.builder()
                        .workerThreads(1)
                        .blockTimeoutMillis(100)
                        .idleSleepMillis(25)
                        .consumerGroup(localPrefix + "-dlq-group")
                        .consumerNamePrefix(localPrefix + "-dlq-worker")
                        .reconciliationStreamKey(reconciliationStreamKey)
                        .build(),
                mapper,
                keyStrategy,
                deadLetterStreamKey
        );

        jedis.set(keyStrategy.versionKey("users", 902L), "1");
        mainWorker.start();
        recoveryWorker.start();
        queue.enqueue(new WriteOperation<>(
                OperationType.UPSERT,
                UserEntityCacheBinding.METADATA,
                902L,
                Map.of(
                        "id", 902L,
                        "username", "recoverable-user",
                        "status", "ACTIVE",
                        "entity_version", 1L
                ),
                "recoverable-payload",
                1L,
                Instant.now()
        ));

        waitUntil(
                () -> mainWorker.snapshot().deadLetterCount() == 1,
                Duration.ofSeconds(10),
                "main=" + mainWorker.snapshot()
        );
        waitUntil(
                () -> recoveryWorker.snapshot().replayedCount() == 1,
                Duration.ofSeconds(10),
                "recovery=" + recoveryWorker.snapshot()
        );

        Assertions.assertEquals(1, recoveryAttempts[0]);
        List<Map<String, Object>> reconciliation = jedis.xrange(reconciliationStreamKey, "-", "+", 10)
                .stream()
                .map(entry -> java.util.Map.<String, Object>copyOf(entry.getFields()))
                .toList();
        Assertions.assertFalse(reconciliation.isEmpty());
        Assertions.assertEquals("REPLAYED", reconciliation.get(0).get("status"));

        mainWorker.close();
        recoveryWorker.close();
    }

    @Test
    void deadLetterManagementShouldReplaySkipAndCloseEntries() throws Exception {
        String localPrefix = "cachedb-it-" + UUID.randomUUID();
        String localStreamKey = localPrefix + ":stream";
        String deadLetterStreamKey = localPrefix + ":dlq";
        String reconciliationStreamKey = localPrefix + ":reconciliation";
        RedisKeyStrategy keyStrategy = new RedisKeyStrategy(localPrefix, "entity", "page", "version", "hotset", "index");
        RedisWriteOperationMapper mapper = new RedisWriteOperationMapper();
        RedisWriteBehindQueue queue = new RedisWriteBehindQueue(jedis, localStreamKey, mapper, 32);

        RedisWriteBehindWorker mainWorker = new RedisWriteBehindWorker(
                jedis,
                operation -> {
                    throw new SQLException("force dead letter for management");
                },
                WriteBehindConfig.builder()
                        .workerThreads(1)
                        .batchSize(10)
                        .blockTimeoutMillis(100)
                        .idleSleepMillis(25)
                        .streamKey(localStreamKey)
                        .consumerGroup(localPrefix + "-group")
                        .consumerNamePrefix(localPrefix + "-worker")
                        .maxFlushRetries(0)
                        .deadLetterStreamKey(deadLetterStreamKey)
                        .build(),
                mapper,
                keyStrategy
        );

        final int[] replayAttempts = {0};
        DeadLetterManagement management = new RedisDeadLetterManagement(
                jedis,
                operation -> replayAttempts[0]++,
                DeadLetterRecoveryConfig.builder()
                        .enabled(false)
                        .consumerGroup(localPrefix + "-dlq-group")
                        .consumerNamePrefix(localPrefix + "-dlq-worker")
                        .reconciliationStreamKey(reconciliationStreamKey)
                        .archiveStreamKey(localPrefix + ":archive")
                        .build(),
                mapper,
                keyStrategy,
                deadLetterStreamKey
        );

        mainWorker.start();
        jedis.set(keyStrategy.versionKey("users", 903L), "1");
        jedis.set(keyStrategy.versionKey("users", 904L), "1");
        jedis.set(keyStrategy.versionKey("users", 905L), "1");

        queue.enqueue(writeOperationForId(903L, "replay-user"));
        queue.enqueue(writeOperationForId(904L, "skip-user"));
        queue.enqueue(writeOperationForId(905L, "close-user"));

        waitUntil(
                () -> mainWorker.snapshot().deadLetterCount() == 3,
                Duration.ofSeconds(10),
                "main=" + mainWorker.snapshot()
        );

        var firstPage = management.queryDeadLetters(DeadLetterQuery.builder()
                .limit(2)
                .entityName("UserEntity")
                .build());
        Assertions.assertEquals(2, firstPage.items().size());
        Assertions.assertTrue(firstPage.hasMore());

        var secondPage = management.queryDeadLetters(DeadLetterQuery.builder()
                .limit(2)
                .cursor(firstPage.nextCursor())
                .entityName("UserEntity")
                .build());
        Assertions.assertEquals(1, secondPage.items().size());

        List<com.reactor.cachedb.core.queue.DeadLetterEntry> entries = new java.util.ArrayList<>(firstPage.items());
        entries.addAll(secondPage.items());
        Assertions.assertEquals(3, entries.size());

        var replayEntry = entries.stream().filter(entry -> "903".equals(entry.operation().id())).findFirst().orElseThrow();
        var skipEntry = entries.stream().filter(entry -> "904".equals(entry.operation().id())).findFirst().orElseThrow();
        var closeEntry = entries.stream().filter(entry -> "905".equals(entry.operation().id())).findFirst().orElseThrow();

        Assertions.assertTrue(management.dryRunReplay(replayEntry.entryId()).replayEligible());

        try (CacheDatabase adminDatabase = new CacheDatabase(
                jedis,
                dataSource(),
                CacheDatabaseConfig.builder()
                        .writeBehind(WriteBehindConfig.builder()
                                .enabled(false)
                                .streamKey(localStreamKey)
                                .consumerGroup(localPrefix + "-group")
                                .consumerNamePrefix(localPrefix + "-worker")
                                .deadLetterStreamKey(deadLetterStreamKey)
                                .build())
                        .redisFunctions(RedisFunctionsConfig.builder().enabled(false).build())
                        .deadLetterRecovery(DeadLetterRecoveryConfig.builder()
                                .enabled(false)
                                .consumerGroup(localPrefix + "-dlq-group")
                                .consumerNamePrefix(localPrefix + "-dlq-worker")
                                .reconciliationStreamKey(reconciliationStreamKey)
                                .archiveStreamKey(localPrefix + ":archive")
                                .build())
                        .adminMonitoring(AdminMonitoringConfig.builder()
                                .deadLetterWarnThreshold(1)
                                .deadLetterCriticalThreshold(10)
                                .build())
                        .build()
        )) {
            Assertions.assertEquals(AdminHealthStatus.DEGRADED, adminDatabase.admin().health().status());
            Assertions.assertEquals(3L, adminDatabase.admin().metrics().deadLetterStreamLength());
        }

        Assertions.assertEquals("MANUAL_REPLAYED", management.bulkReplay(List.of(replayEntry.entryId()), "operator replay").results().get(0).status());
        Assertions.assertEquals("MANUAL_SKIPPED", management.bulkSkip(List.of(skipEntry.entryId()), "operator skip").results().get(0).status());
        Assertions.assertEquals("MANUAL_CLOSED", management.bulkClose(List.of(closeEntry.entryId()), "operator close").results().get(0).status());

        Assertions.assertEquals(1, replayAttempts[0]);
        Assertions.assertTrue(management.listDeadLetters(10).isEmpty());

        var reconciliationPage = management.queryReconciliation(ReconciliationQuery.builder()
                .limit(10)
                .status("MANUAL_REPLAYED")
                .build());
        Assertions.assertEquals(1, reconciliationPage.items().size());

        List<com.reactor.cachedb.core.queue.ReconciliationRecord> reconciliation = management.listReconciliation(10);
        Assertions.assertEquals(3, reconciliation.size());
        Assertions.assertTrue(reconciliation.stream().anyMatch(record -> "MANUAL_REPLAYED".equals(record.status())));
        Assertions.assertTrue(reconciliation.stream().anyMatch(record -> "MANUAL_SKIPPED".equals(record.status())));
        Assertions.assertTrue(reconciliation.stream().anyMatch(record -> "MANUAL_CLOSED".equals(record.status())));

        var archivePage = management.queryArchive(ReconciliationQuery.builder()
                .limit(10)
                .status("MANUAL_CLOSED")
                .build());
        Assertions.assertEquals(1, archivePage.items().size());
        Assertions.assertEquals("905", archivePage.items().get(0).entityId());

        mainWorker.close();
    }

    @Test
    void adminShouldExportDeadLetterAndReconciliationReports() throws Exception {
        String localPrefix = "cachedb-it-" + UUID.randomUUID();
        String localStreamKey = localPrefix + ":stream";
        String deadLetterStreamKey = localPrefix + ":dlq";
        String reconciliationStreamKey = localPrefix + ":reconciliation";
        String archiveStreamKey = localPrefix + ":archive";
        RedisKeyStrategy keyStrategy = new RedisKeyStrategy(localPrefix, "entity", "page", "version", "hotset", "index");
        RedisWriteOperationMapper mapper = new RedisWriteOperationMapper();
        RedisWriteBehindQueue queue = new RedisWriteBehindQueue(jedis, localStreamKey, mapper, 32);

        RedisWriteBehindWorker mainWorker = new RedisWriteBehindWorker(
                jedis,
                operation -> {
                    throw new SQLException("force dead letter for export");
                },
                WriteBehindConfig.builder()
                        .workerThreads(1)
                        .batchSize(10)
                        .blockTimeoutMillis(100)
                        .idleSleepMillis(25)
                        .streamKey(localStreamKey)
                        .consumerGroup(localPrefix + "-group")
                        .consumerNamePrefix(localPrefix + "-worker")
                        .maxFlushRetries(0)
                        .deadLetterStreamKey(deadLetterStreamKey)
                        .build(),
                mapper,
                keyStrategy
        );

        DeadLetterManagement management = new RedisDeadLetterManagement(
                jedis,
                operation -> {
                },
                DeadLetterRecoveryConfig.builder()
                        .enabled(false)
                        .consumerGroup(localPrefix + "-dlq-group")
                        .consumerNamePrefix(localPrefix + "-dlq-worker")
                        .reconciliationStreamKey(reconciliationStreamKey)
                        .archiveStreamKey(archiveStreamKey)
                        .build(),
                mapper,
                keyStrategy,
                deadLetterStreamKey
        );

        mainWorker.start();
        jedis.set(keyStrategy.versionKey("users", 911L), "1");
        jedis.set(keyStrategy.versionKey("users", 912L), "1");
        queue.enqueue(writeOperationForId(911L, "export-one"));
        queue.enqueue(writeOperationForId(912L, "export-two"));

        waitUntil(
                () -> mainWorker.snapshot().deadLetterCount() == 2,
                Duration.ofSeconds(10),
                "main=" + mainWorker.snapshot()
        );

        try (CacheDatabase adminDatabase = new CacheDatabase(
                jedis,
                dataSource(),
                CacheDatabaseConfig.builder()
                        .writeBehind(WriteBehindConfig.builder()
                                .enabled(false)
                                .streamKey(localStreamKey)
                                .consumerGroup(localPrefix + "-group")
                                .consumerNamePrefix(localPrefix + "-worker")
                                .deadLetterStreamKey(deadLetterStreamKey)
                                .build())
                        .redisFunctions(RedisFunctionsConfig.builder().enabled(false).build())
                        .deadLetterRecovery(DeadLetterRecoveryConfig.builder()
                                .enabled(false)
                                .consumerGroup(localPrefix + "-dlq-group")
                                .consumerNamePrefix(localPrefix + "-dlq-worker")
                                .reconciliationStreamKey(reconciliationStreamKey)
                                .archiveStreamKey(archiveStreamKey)
                                .build())
                        .build()
        )) {
            String csv = adminDatabase.admin().exportDeadLetters(
                    DeadLetterQuery.builder().limit(10).build(),
                    AdminExportFormat.CSV
            ).content();
            Assertions.assertTrue(csv.contains("failureCategory"));
            Assertions.assertTrue(csv.contains("911"));
        }

        List<com.reactor.cachedb.core.queue.DeadLetterEntry> entries = management.listDeadLetters(10);
        var replayEntry = entries.stream().filter(entry -> "911".equals(entry.operation().id())).findFirst().orElseThrow();
        var closeEntry = entries.stream().filter(entry -> "912".equals(entry.operation().id())).findFirst().orElseThrow();
        management.replay(replayEntry.entryId(), "report replay");
        management.close(closeEntry.entryId(), "report close");

        try (CacheDatabase adminDatabase = new CacheDatabase(
                jedis,
                dataSource(),
                CacheDatabaseConfig.builder()
                        .writeBehind(WriteBehindConfig.builder()
                                .enabled(false)
                                .streamKey(localStreamKey)
                                .consumerGroup(localPrefix + "-group")
                                .consumerNamePrefix(localPrefix + "-worker")
                                .deadLetterStreamKey(deadLetterStreamKey)
                                .build())
                        .redisFunctions(RedisFunctionsConfig.builder().enabled(false).build())
                        .deadLetterRecovery(DeadLetterRecoveryConfig.builder()
                                .enabled(false)
                                .consumerGroup(localPrefix + "-dlq-group")
                                .consumerNamePrefix(localPrefix + "-dlq-worker")
                                .reconciliationStreamKey(reconciliationStreamKey)
                                .archiveStreamKey(archiveStreamKey)
                                .build())
                        .build()
        )) {
            String reconciliationJson = adminDatabase.admin().exportReconciliation(
                    ReconciliationQuery.builder().limit(10).build(),
                    AdminExportFormat.JSON
            ).content();
            Assertions.assertTrue(reconciliationJson.contains("MANUAL_REPLAYED"));

            String archiveMarkdown = adminDatabase.admin().exportArchive(
                    ReconciliationQuery.builder().limit(10).build(),
                    AdminExportFormat.MARKDOWN
            ).content();
            Assertions.assertTrue(archiveMarkdown.contains("MANUAL_CLOSED"));
            Assertions.assertTrue(archiveMarkdown.contains("report close"));
        }

        mainWorker.close();
    }

    @Test
    void cleanupWorkerShouldPruneExpiredReconciliationAndArchiveEntries() throws Exception {
        String localPrefix = "cachedb-it-" + UUID.randomUUID();
        String deadLetterStreamKey = localPrefix + ":dlq";
        String reconciliationStreamKey = localPrefix + ":reconciliation";
        String archiveStreamKey = localPrefix + ":archive";

        try (CacheDatabase cleanupDatabase = new CacheDatabase(
                jedis,
                dataSource(),
                CacheDatabaseConfig.builder()
                        .writeBehind(WriteBehindConfig.builder()
                                .enabled(false)
                                .streamKey(localPrefix + ":stream")
                                .consumerGroup(localPrefix + "-group")
                                .consumerNamePrefix(localPrefix + "-worker")
                                .deadLetterStreamKey(deadLetterStreamKey)
                                .build())
                        .redisFunctions(RedisFunctionsConfig.builder().enabled(false).build())
                        .deadLetterRecovery(DeadLetterRecoveryConfig.builder()
                                .enabled(false)
                                .consumerGroup(localPrefix + "-dlq-group")
                                .consumerNamePrefix(localPrefix + "-dlq-worker")
                                .reconciliationStreamKey(reconciliationStreamKey)
                                .archiveStreamKey(archiveStreamKey)
                                .cleanupEnabled(true)
                                .cleanupIntervalMillis(50)
                                .cleanupBatchSize(50)
                                .reconciliationRetentionMillis(100)
                                .archiveRetentionMillis(100)
                                .deadLetterRetentionMillis(0)
                                .build())
                        .build()
        )) {
            cleanupDatabase.start();
            jedis.xadd(deadLetterStreamKey, XAddParams.xAddParams(), Map.of("status", "PENDING"));
            jedis.xadd(reconciliationStreamKey, XAddParams.xAddParams(), Map.of("status", "FAILED", "entity", "UserEntity", "operationType", "UPSERT", "id", "777", "version", "1", "reconciledAt", Instant.now().toString()));
            jedis.xadd(archiveStreamKey, XAddParams.xAddParams(), Map.of("status", "MANUAL_CLOSED", "entity", "UserEntity", "operationType", "UPSERT", "id", "778", "version", "1", "archivedAt", Instant.now().toString()));

            waitUntil(
                    () -> jedis.xlen(reconciliationStreamKey) == 0 && jedis.xlen(archiveStreamKey) == 0,
                    Duration.ofSeconds(10),
                    "reconciliationLength=" + jedis.xlen(reconciliationStreamKey) + ", archiveLength=" + jedis.xlen(archiveStreamKey)
            );
            Assertions.assertEquals(1, jedis.xlen(deadLetterStreamKey));
        }
    }

    @Test
    void adminReportJobShouldWriteFilesAndExposeSnapshotsInMetrics() throws Exception {
        String localPrefix = "cachedb-it-" + UUID.randomUUID();
        String localStreamKey = localPrefix + ":stream";
        String deadLetterStreamKey = localPrefix + ":dlq";
        String reconciliationStreamKey = localPrefix + ":reconciliation";
        String archiveStreamKey = localPrefix + ":archive";
        Path reportDirectory = Files.createTempDirectory("cachedb-admin-reports");

        try (CacheDatabase reportDatabase = new CacheDatabase(
                jedis,
                dataSource(),
                CacheDatabaseConfig.builder()
                        .writeBehind(WriteBehindConfig.builder()
                                .enabled(false)
                                .streamKey(localStreamKey)
                                .consumerGroup(localPrefix + "-group")
                                .consumerNamePrefix(localPrefix + "-worker")
                                .deadLetterStreamKey(deadLetterStreamKey)
                                .build())
                        .redisFunctions(RedisFunctionsConfig.builder().enabled(false).build())
                        .deadLetterRecovery(DeadLetterRecoveryConfig.builder()
                                .enabled(false)
                                .consumerGroup(localPrefix + "-dlq-group")
                                .consumerNamePrefix(localPrefix + "-dlq-worker")
                                .reconciliationStreamKey(reconciliationStreamKey)
                                .archiveStreamKey(archiveStreamKey)
                                .cleanupEnabled(true)
                                .cleanupIntervalMillis(50)
                                .cleanupBatchSize(50)
                                .reconciliationRetentionMillis(10_000)
                                .archiveRetentionMillis(10_000)
                                .build())
                        .adminReportJob(AdminReportJobConfig.builder()
                                .enabled(true)
                                .intervalMillis(100)
                                .outputDirectory(reportDirectory.toString())
                                .format(AdminExportFormat.CSV)
                                .queryLimit(50)
                                .writeDiagnostics(true)
                                .persistDiagnostics(true)
                                .includeTimestampInFileName(false)
                                .build())
                        .build()
        )) {
            jedis.xadd(deadLetterStreamKey, XAddParams.xAddParams(), Map.ofEntries(
                    Map.entry("type", "UPSERT"),
                    Map.entry("entity", "UserEntity"),
                    Map.entry("table", "cachedb_example_users"),
                    Map.entry("namespace", "users"),
                    Map.entry("idColumn", "id"),
                    Map.entry("versionColumn", "entity_version"),
                    Map.entry("deletedColumn", ""),
                    Map.entry("id", "1101"),
                    Map.entry("version", "1"),
                    Map.entry("createdAt", Instant.now().toString()),
                    Map.entry("deadLetterReason", "java.sql.SQLException"),
                    Map.entry("deadLetterMessage", "scheduled report"),
                    Map.entry("deadLetterFailureCategory", "CONNECTION"),
                    Map.entry("deadLetterSqlState", "08006"),
                    Map.entry("deadLetterRetryable", "true"),
                    Map.entry("deadLetterVendorCode", "0"),
                    Map.entry("deadLetterRootType", "java.sql.SQLException"),
                    Map.entry("deadLetterAttempts", "1")
            ));
            jedis.xadd(reconciliationStreamKey, XAddParams.xAddParams(), Map.ofEntries(
                    Map.entry("status", "FAILED"),
                    Map.entry("entity", "UserEntity"),
                    Map.entry("operationType", "UPSERT"),
                    Map.entry("id", "1101"),
                    Map.entry("version", "1"),
                    Map.entry("reconciledAt", Instant.now().toString()),
                    Map.entry("replayFailureCategory", "CONNECTION"),
                    Map.entry("replaySqlState", "08006"),
                    Map.entry("replayRetryable", "true"),
                    Map.entry("replayVendorCode", "0"),
                    Map.entry("replayRootType", "java.sql.SQLException"),
                    Map.entry("replayErrorMessage", "scheduled report")
            ));

            reportDatabase.start();

            waitUntil(
                    () -> Files.exists(reportDirectory.resolve("dead-letters.csv"))
                            && Files.exists(reportDirectory.resolve("reconciliation.csv"))
                            && Files.exists(reportDirectory.resolve("archive.csv"))
                            && Files.exists(reportDirectory.resolve("diagnostics.csv")),
                    Duration.ofSeconds(10),
                    "reports=" + Files.list(reportDirectory).map(path -> path.getFileName().toString()).toList()
            );

            Assertions.assertTrue(Files.readString(reportDirectory.resolve("dead-letters.csv")).contains("1101"));
            Assertions.assertTrue(Files.readString(reportDirectory.resolve("reconciliation.csv")).contains("FAILED"));
            Assertions.assertTrue(Files.readString(reportDirectory.resolve("diagnostics.csv")).contains("status"));

            var metrics = reportDatabase.admin().metrics();
            Assertions.assertTrue(metrics.recoveryCleanupSnapshot().runCount() > 0);
            Assertions.assertTrue(metrics.adminReportJobSnapshot().runCount() > 0);
            Assertions.assertTrue(metrics.adminReportJobSnapshot().writtenFileCount() >= 4);
            Assertions.assertEquals(reportDirectory.toAbsolutePath().normalize().toString(), metrics.adminReportJobSnapshot().lastOutputDirectory());
            Assertions.assertTrue(metrics.diagnosticsStreamLength() > 0);
            Assertions.assertFalse(reportDatabase.admin().diagnostics(10).isEmpty());
        }
    }

    @Test
    void adminReportJobShouldRotateOldReportFiles() throws Exception {
        String localPrefix = "cachedb-it-" + UUID.randomUUID();
        String reportKeyPrefix = localPrefix + ":reports";
        Path reportDirectory = Files.createTempDirectory("cachedb-admin-rotate");

        try (CacheDatabase reportDatabase = new CacheDatabase(
                jedis,
                dataSource(),
                CacheDatabaseConfig.builder()
                        .writeBehind(WriteBehindConfig.builder()
                                .enabled(false)
                                .streamKey(reportKeyPrefix + ":stream")
                                .consumerGroup(localPrefix + "-group")
                                .consumerNamePrefix(localPrefix + "-worker")
                                .deadLetterStreamKey(reportKeyPrefix + ":dlq")
                                .build())
                        .redisFunctions(RedisFunctionsConfig.builder().enabled(false).build())
                        .deadLetterRecovery(DeadLetterRecoveryConfig.builder()
                                .enabled(false)
                                .consumerGroup(localPrefix + "-dlq-group")
                                .consumerNamePrefix(localPrefix + "-dlq-worker")
                                .reconciliationStreamKey(reportKeyPrefix + ":reconciliation")
                                .archiveStreamKey(reportKeyPrefix + ":archive")
                                .build())
                        .adminReportJob(AdminReportJobConfig.builder()
                                .enabled(true)
                                .intervalMillis(100)
                                .outputDirectory(reportDirectory.toString())
                                .format(AdminExportFormat.JSON)
                                .queryLimit(10)
                                .writeReconciliation(false)
                                .writeArchive(false)
                                .writeDiagnostics(false)
                                .persistDiagnostics(false)
                                .maxRetainedFilesPerReport(2)
                                .fileRetentionMillis(60_000)
                                .includeTimestampInFileName(true)
                                .build())
                        .build()
        )) {
            reportDatabase.start();
            waitUntil(
                    () -> reportDatabase.adminReportJobSnapshot().runCount() >= 4,
                    Duration.ofSeconds(10),
                    "snapshot=" + reportDatabase.adminReportJobSnapshot()
            );

            List<Path> deadLetterReports;
            try (var stream = Files.list(reportDirectory)) {
                deadLetterReports = stream
                        .filter(path -> path.getFileName().toString().startsWith("dead-letters"))
                        .toList();
            }
            Assertions.assertTrue(deadLetterReports.size() <= 2);
        }
    }

    @Test
    void postgresFailureTaxonomyShouldClassifySchemaErrorsInDeadLetterAndReconciliation() throws Exception {
        String localPrefix = "cachedb-it-" + UUID.randomUUID();
        String localStreamKey = localPrefix + ":stream";
        String deadLetterStreamKey = localPrefix + ":dlq";
        String reconciliationStreamKey = localPrefix + ":reconciliation";
        String archiveStreamKey = localPrefix + ":archive";
        RedisKeyStrategy keyStrategy = new RedisKeyStrategy(localPrefix, "entity", "page", "version", "hotset", "index");
        RedisWriteOperationMapper mapper = new RedisWriteOperationMapper();
        RedisWriteBehindQueue queue = new RedisWriteBehindQueue(jedis, localStreamKey, mapper, 32);

        RedisWriteBehindWorker worker = new RedisWriteBehindWorker(
                jedis,
                new com.reactor.cachedb.postgres.PostgresWriteBehindFlusher(dataSource(), cacheDatabase.entityRegistry()),
                WriteBehindConfig.builder()
                        .workerThreads(1)
                        .batchSize(10)
                        .blockTimeoutMillis(100)
                        .idleSleepMillis(25)
                        .streamKey(localStreamKey)
                        .consumerGroup(localPrefix + "-group")
                        .consumerNamePrefix(localPrefix + "-worker")
                        .maxFlushRetries(0)
                        .deadLetterStreamKey(deadLetterStreamKey)
                        .build(),
                mapper,
                keyStrategy
        );

        DeadLetterManagement management = new RedisDeadLetterManagement(
                jedis,
                new com.reactor.cachedb.postgres.PostgresWriteBehindFlusher(dataSource(), cacheDatabase.entityRegistry()),
                DeadLetterRecoveryConfig.builder()
                        .enabled(false)
                        .consumerGroup(localPrefix + "-dlq-group")
                        .consumerNamePrefix(localPrefix + "-dlq-worker")
                        .reconciliationStreamKey(reconciliationStreamKey)
                        .archiveStreamKey(archiveStreamKey)
                        .build(),
                mapper,
                keyStrategy,
                deadLetterStreamKey
        );

        worker.start();
        jedis.set(keyStrategy.versionKey("users", 990L), "1");
        queue.enqueue(new WriteOperation<>(
                OperationType.UPSERT,
                brokenUserMetadata("cachedb_missing_users"),
                990L,
                Map.of(
                        "id", 990L,
                        "username", "broken-user",
                        "status", "ACTIVE",
                        "entity_version", 1L
                ),
                "payload-990",
                1L,
                Instant.now()
        ));

        waitUntil(
                () -> worker.snapshot().deadLetterCount() == 1,
                Duration.ofSeconds(10),
                "worker=" + worker.snapshot()
        );

        com.reactor.cachedb.core.queue.DeadLetterEntry entry = management.listDeadLetters(10).get(0);
        Assertions.assertEquals(WriteFailureCategory.SCHEMA, entry.failureDetails().category());
        Assertions.assertFalse(entry.failureDetails().sqlState().isBlank());

        var replayResult = management.replay(entry.entryId(), "taxonomy replay");
        Assertions.assertFalse(replayResult.applied());
        Assertions.assertEquals("MANUAL_REPLAY_FAILED", replayResult.status());

        com.reactor.cachedb.core.queue.ReconciliationRecord record = management.listReconciliation(10).get(0);
        Assertions.assertEquals("MANUAL_REPLAY_FAILED", record.status());
        Assertions.assertEquals(WriteFailureCategory.SCHEMA, record.replayFailureDetails().category());

        worker.close();
    }

    @Test
    void postgresFlusherShouldCompactLatestStatePolicies() throws Exception {
        String auditFunction = "cachedb_user_audit_fn_" + UUID.randomUUID().toString().replace("-", "");
        String auditTrigger = "cachedb_user_audit_trigger_" + UUID.randomUUID().toString().replace("-", "");
        createUserAudit(auditFunction, auditTrigger);
        try {
            var flusher = new com.reactor.cachedb.postgres.PostgresWriteBehindFlusher(
                    dataSource(),
                    cacheDatabase.entityRegistry(),
                    WriteBehindConfig.builder()
                            .postgresMultiRowFlushEnabled(false)
                            .postgresCopyBulkLoadEnabled(false)
                            .maxFlushBatchSize(16)
                            .entityFlushPolicies(List.of(
                                    new EntityFlushPolicy(
                                            "UserEntity",
                                            OperationType.UPSERT,
                                            true,
                                            false,
                                            false,
                                            16,
                                            0,
                                            0,
                                            PersistenceSemantics.LATEST_STATE
                                    )
                            ))
                            .build()
            );

            flusher.flushBatch(List.of(
                    queuedUserWrite(3001L, "latest-1", 1L),
                    queuedUserWrite(3001L, "latest-2", 2L),
                    queuedUserWrite(3001L, "latest-3", 3L),
                    queuedUserWrite(3001L, "latest-4", 4L)
            ));

            Assertions.assertEquals(1L, countRows("select count(*) from cachedb_example_users where id = 3001"));
            Assertions.assertEquals(1L, countRows("select count(*) from cachedb_example_user_audit where user_id = 3001"));
            Assertions.assertEquals("latest-4", stringValue("select username from cachedb_example_users where id = 3001"));
            Assertions.assertEquals(4L, countRows("select entity_version from cachedb_example_users where id = 3001"));
        } finally {
            dropUserAudit(auditFunction, auditTrigger);
        }
    }

    @Test
    void postgresFlusherShouldPreserveExactSequencePolicies() throws Exception {
        String auditFunction = "cachedb_user_audit_fn_" + UUID.randomUUID().toString().replace("-", "");
        String auditTrigger = "cachedb_user_audit_trigger_" + UUID.randomUUID().toString().replace("-", "");
        createUserAudit(auditFunction, auditTrigger);
        try {
            var flusher = new com.reactor.cachedb.postgres.PostgresWriteBehindFlusher(
                    dataSource(),
                    cacheDatabase.entityRegistry(),
                    WriteBehindConfig.builder()
                            .maxFlushBatchSize(1)
                            .postgresMultiRowFlushEnabled(false)
                            .postgresCopyBulkLoadEnabled(false)
                            .entityFlushPolicies(List.of(
                                    new EntityFlushPolicy(
                                            "UserEntity",
                                            OperationType.UPSERT,
                                            false,
                                            false,
                                            false,
                                            1,
                                            0,
                                            0,
                                            PersistenceSemantics.EXACT_SEQUENCE
                                    )
                            ))
                            .build()
            );

            flusher.flushBatch(List.of(
                    queuedUserWrite(3002L, "exact-1", 1L),
                    queuedUserWrite(3002L, "exact-2", 2L),
                    queuedUserWrite(3002L, "exact-3", 3L),
                    queuedUserWrite(3002L, "exact-4", 4L)
            ));

            Assertions.assertEquals(1L, countRows("select count(*) from cachedb_example_users where id = 3002"));
            Assertions.assertEquals(4L, countRows("select count(*) from cachedb_example_user_audit where user_id = 3002"));
            Assertions.assertEquals("exact-4", stringValue("select username from cachedb_example_users where id = 3002"));
            Assertions.assertEquals(4L, countRows("select entity_version from cachedb_example_users where id = 3002"));
        } finally {
            dropUserAudit(auditFunction, auditTrigger);
        }
    }

    @Test
    void queueShouldRejectWritesWhenCompactionHardLimitIsExceeded() {
        String localPrefix = "cachedb-it-" + UUID.randomUUID();
        String localStreamKey = localPrefix + ":stream";
        RedisKeyStrategy keyStrategy = new RedisKeyStrategy(localPrefix, "entity", "page", "version", "hotset", "index", "compaction");
        RedisWriteBehindQueue queue = new RedisWriteBehindQueue(
                jedis,
                localStreamKey,
                localPrefix + ":stream:compaction",
                new RedisWriteOperationMapper(),
                32,
                WriteBehindConfig.builder()
                        .streamKey(localStreamKey)
                        .compactionStreamKey(localPrefix + ":stream:compaction")
                        .dedicatedWriteConsumerGroupEnabled(true)
                        .durableCompactionEnabled(true)
                        .build(),
                RedisGuardrailConfig.builder()
                        .enabled(true)
                        .rejectWritesOnHardLimit(true)
                        .compactionPayloadHardLimit(1)
                        .compactionPendingHardLimit(1)
                        .build(),
                keyStrategy
        );

        queue.enqueue(writeOperationForId(9101L, "hard-limit-one"));
        IllegalStateException exception = Assertions.assertThrows(
                IllegalStateException.class,
                () -> queue.enqueue(writeOperationForId(9102L, "hard-limit-two"))
        );

        Assertions.assertTrue(exception.getMessage().contains("hard limit"));
        Assertions.assertEquals("1", jedis.hget(keyStrategy.compactionStatsKey(), "hardRejectedWriteCount"));
    }

    @Test
    void latestStatePolicyShouldCompactDeleteAfterUpdateRaceToFinalDelete() throws Exception {
        var flusher = new com.reactor.cachedb.postgres.PostgresWriteBehindFlusher(
                dataSource(),
                cacheDatabase.entityRegistry(),
                WriteBehindConfig.builder()
                        .postgresMultiRowFlushEnabled(false)
                        .postgresCopyBulkLoadEnabled(false)
                        .entityFlushPolicies(List.of(
                                new EntityFlushPolicy(
                                        "UserEntity",
                                        OperationType.UPSERT,
                                        true,
                                        false,
                                        false,
                                        16,
                                        0,
                                        0,
                                        PersistenceSemantics.LATEST_STATE
                                ),
                                new EntityFlushPolicy(
                                        "UserEntity",
                                        OperationType.DELETE,
                                        true,
                                        false,
                                        false,
                                        16,
                                        0,
                                        0,
                                        PersistenceSemantics.LATEST_STATE
                                )
                        ))
                        .build()
        );

        flusher.flushBatch(List.of(
                queuedUserWrite(3010L, "race-create", 1L),
                queuedUserDelete(3010L, 2L),
                queuedUserWrite(3010L, "stale-resurrection", 1L),
                queuedUserDelete(3010L, 2L)
        ));

        Assertions.assertEquals(0L, countRows("select count(*) from cachedb_example_users where id = 3010"));
    }

    @Test
    void hardLimitShouldShedPageCacheAndHotSetTracking() throws Exception {
        String localPrefix = "cachedb-it-" + UUID.randomUUID();
        String localStreamKey = localPrefix + ":stream";
        String functionSuffix = localPrefix.replace('-', '_');
        RedisKeyStrategy localKeyStrategy = new RedisKeyStrategy(localPrefix, "entity", "page", "version", "hotset", "index", "compaction");

        try (CacheDatabase localDatabase = new CacheDatabase(jedis, dataSource(), CacheDatabaseConfig.builder()
                .writeBehind(WriteBehindConfig.builder()
                        .enabled(false)
                        .streamKey(localStreamKey)
                        .consumerGroup(localPrefix + "-group")
                        .consumerNamePrefix(localPrefix + "-worker")
                        .build())
                .resourceLimits(ResourceLimits.builder()
                        .defaultCachePolicy(CachePolicy.builder()
                                .hotEntityLimit(10)
                                .pageSize(5)
                                .entityTtlSeconds(300)
                                .pageTtlSeconds(300)
                                .build())
                        .build())
                .keyspace(KeyspaceConfig.builder()
                        .keyPrefix(localPrefix)
                        .entitySegment("entity")
                        .pageSegment("page")
                        .versionSegment("version")
                        .hotSetSegment("hotset")
                        .indexSegment("index")
                        .compactionSegment("compaction")
                        .build())
                .redisFunctions(RedisFunctionsConfig.builder()
                        .enabled(true)
                        .autoLoadLibrary(true)
                        .replaceLibraryOnLoad(true)
                        .strictLoading(true)
                        .libraryName(functionSuffix)
                        .upsertFunctionName(functionSuffix + "_entity_upsert")
                        .deleteFunctionName(functionSuffix + "_entity_delete")
                        .compactionCompleteFunctionName(functionSuffix + "_compaction_complete")
                        .build())
                .relations(RelationConfig.builder()
                        .batchSize(10)
                        .maxFetchDepth(1)
                        .failOnMissingPreloader(false)
                        .build())
                .pageCache(PageCacheConfig.builder()
                        .readThroughEnabled(true)
                        .failOnMissingPageLoader(true)
                        .evictionBatchSize(10)
                        .build())
                .redisGuardrail(RedisGuardrailConfig.builder()
                        .enabled(true)
                        .producerBackpressureEnabled(false)
                        .compactionPendingHardLimit(1)
                        .rejectWritesOnHardLimit(false)
                        .shedPageCacheWritesOnHardLimit(true)
                        .shedReadThroughCacheOnHardLimit(true)
                        .shedHotSetTrackingOnHardLimit(true)
                        .sampleIntervalMillis(1)
                        .build())
                .build())) {
            UserEntityCacheBinding.register(
                    localDatabase,
                    CachePolicy.builder().hotEntityLimit(10).pageSize(5).entityTtlSeconds(300).pageTtlSeconds(300).build(),
                    new UserOrdersRelationBatchLoader(),
                    new UserPageLoader()
            );
            localDatabase.start();

            jedis.hset(localKeyStrategy.compactionStatsKey(), Map.of("pendingCount", "1", "payloadCount", "0"));
            List<UserEntity> page = localDatabase.repository(
                    UserEntityCacheBinding.METADATA,
                    UserEntityCacheBinding.CODEC,
                    CachePolicy.builder().hotEntityLimit(10).pageSize(5).entityTtlSeconds(300).pageTtlSeconds(300).build()
            ).findPage(new PageWindow(0, 2));

            Assertions.assertFalse(page.isEmpty());
            Assertions.assertFalse(jedis.exists(localKeyStrategy.pageKey("users", 0)));
            Assertions.assertEquals(0L, jedis.zcard(localKeyStrategy.hotSetKey("users")));
        }
    }

    @Test
    void hardLimitShouldShedQueryIndexesAndFallbackToFullScan() throws Exception {
        String localPrefix = "cachedb-it-" + UUID.randomUUID();
        String localStreamKey = localPrefix + ":stream";
        String functionSuffix = localPrefix.replace('-', '_');
        RedisKeyStrategy localKeyStrategy = new RedisKeyStrategy(localPrefix, "entity", "page", "version", "hotset", "index", "compaction");

        try (CacheDatabase localDatabase = new CacheDatabase(jedis, dataSource(), CacheDatabaseConfig.builder()
                .writeBehind(WriteBehindConfig.builder()
                        .enabled(false)
                        .streamKey(localStreamKey)
                        .consumerGroup(localPrefix + "-group")
                        .consumerNamePrefix(localPrefix + "-worker")
                        .build())
                .resourceLimits(ResourceLimits.builder()
                        .defaultCachePolicy(CachePolicy.builder()
                                .hotEntityLimit(10)
                                .pageSize(5)
                                .entityTtlSeconds(300)
                                .pageTtlSeconds(300)
                                .build())
                        .build())
                .keyspace(KeyspaceConfig.builder()
                        .keyPrefix(localPrefix)
                        .entitySegment("entity")
                        .pageSegment("page")
                        .versionSegment("version")
                        .tombstoneSegment("tombstone")
                        .hotSetSegment("hotset")
                        .indexSegment("index")
                        .compactionSegment("compaction")
                        .build())
                .redisFunctions(RedisFunctionsConfig.builder()
                        .enabled(true)
                        .autoLoadLibrary(true)
                        .replaceLibraryOnLoad(true)
                        .strictLoading(true)
                        .libraryName(functionSuffix)
                        .upsertFunctionName(functionSuffix + "_entity_upsert")
                        .deleteFunctionName(functionSuffix + "_entity_delete")
                        .compactionCompleteFunctionName(functionSuffix + "_compaction_complete")
                        .build())
                .queryIndex(QueryIndexConfig.builder()
                        .prefixIndexEnabled(true)
                        .textIndexEnabled(true)
                        .build())
                .redisGuardrail(RedisGuardrailConfig.builder()
                        .enabled(true)
                        .producerBackpressureEnabled(false)
                        .compactionPendingHardLimit(1)
                        .rejectWritesOnHardLimit(false)
                        .shedQueryIndexWritesOnHardLimit(true)
                        .shedQueryIndexReadsOnHardLimit(true)
                        .shedPlannerLearningOnHardLimit(true)
                        .sampleIntervalMillis(1)
                        .build())
                .build())) {
            UserEntityCacheBinding.register(
                    localDatabase,
                    CachePolicy.builder().hotEntityLimit(10).pageSize(5).entityTtlSeconds(300).pageTtlSeconds(300).build(),
                    new UserOrdersRelationBatchLoader(),
                    new UserPageLoader()
            );
            localDatabase.start();

            jedis.hset(localKeyStrategy.compactionStatsKey(), Map.of("pendingCount", "1", "payloadCount", "0"));
            EntityRepository<UserEntity, Long> repository = localDatabase.repository(
                    UserEntityCacheBinding.METADATA,
                    UserEntityCacheBinding.CODEC,
                    CachePolicy.defaults()
            );

            UserEntity entity = user(270L, "index-shed-user", "ACTIVE");
            repository.save(entity);

            Assertions.assertFalse(jedis.sismember(localKeyStrategy.indexExactKey("users", "status", encodeKeyPart("ACTIVE")), "270"));
            Assertions.assertTrue(jedis.exists(localKeyStrategy.indexDegradedKey("users")));

            List<UserEntity> result = repository.query(QuerySpec.builder()
                    .filter(QueryFilter.eq("status", "ACTIVE"))
                    .limit(10)
                    .build());
            Assertions.assertEquals(1, result.size());
            Assertions.assertEquals(270L, result.get(0).id);

            QueryExplainPlan explainPlan = repository.explain(QuerySpec.builder()
                    .filter(QueryFilter.eq("status", "ACTIVE"))
                    .limit(10)
                    .build());
            Assertions.assertEquals("DEGRADED_FULL_SCAN", explainPlan.plannerStrategy());
        }
    }

    @Test
    void adminShouldRebuildDegradedQueryIndexesAfterPressureFalls() throws Exception {
        String localPrefix = "cachedb-it-" + UUID.randomUUID();
        String localStreamKey = localPrefix + ":stream";
        String functionSuffix = localPrefix.replace('-', '_');
        RedisKeyStrategy localKeyStrategy = new RedisKeyStrategy(localPrefix, "entity", "page", "version", "hotset", "index", "compaction");

        try (CacheDatabase localDatabase = new CacheDatabase(jedis, dataSource(), CacheDatabaseConfig.builder()
                .writeBehind(WriteBehindConfig.builder()
                        .enabled(false)
                        .streamKey(localStreamKey)
                        .consumerGroup(localPrefix + "-group")
                        .consumerNamePrefix(localPrefix + "-worker")
                        .build())
                .resourceLimits(ResourceLimits.builder()
                        .defaultCachePolicy(CachePolicy.defaults())
                        .build())
                .keyspace(KeyspaceConfig.builder()
                        .keyPrefix(localPrefix)
                        .entitySegment("entity")
                        .pageSegment("page")
                        .versionSegment("version")
                        .tombstoneSegment("tombstone")
                        .hotSetSegment("hotset")
                        .indexSegment("index")
                        .compactionSegment("compaction")
                        .build())
                .redisFunctions(RedisFunctionsConfig.builder()
                        .enabled(true)
                        .autoLoadLibrary(true)
                        .replaceLibraryOnLoad(true)
                        .strictLoading(true)
                        .libraryName(functionSuffix)
                        .upsertFunctionName(functionSuffix + "_entity_upsert")
                        .deleteFunctionName(functionSuffix + "_entity_delete")
                        .compactionCompleteFunctionName(functionSuffix + "_compaction_complete")
                        .build())
                .redisGuardrail(RedisGuardrailConfig.builder()
                        .enabled(true)
                        .producerBackpressureEnabled(false)
                        .compactionPendingHardLimit(1)
                        .rejectWritesOnHardLimit(false)
                        .shedQueryIndexWritesOnHardLimit(true)
                        .shedQueryIndexReadsOnHardLimit(true)
                        .autoRecoverDegradedIndexesEnabled(false)
                        .sampleIntervalMillis(1)
                        .build())
                .build())) {
            UserEntityCacheBinding.register(localDatabase, CachePolicy.defaults(), new UserOrdersRelationBatchLoader(), new UserPageLoader());
            localDatabase.start();

            jedis.hset(localKeyStrategy.compactionStatsKey(), Map.of("pendingCount", "1", "payloadCount", "0"));
            EntityRepository<UserEntity, Long> repository = localDatabase.repository(
                    UserEntityCacheBinding.METADATA,
                    UserEntityCacheBinding.CODEC,
                    CachePolicy.defaults()
            );
            repository.save(user(280L, "rebuild-user", "ACTIVE"));

            Assertions.assertTrue(jedis.exists(localKeyStrategy.indexDegradedKey("users")));
            jedis.hset(localKeyStrategy.compactionStatsKey(), Map.of("pendingCount", "0", "payloadCount", "0"));

            var rebuild = localDatabase.admin().rebuildQueryIndexes("UserEntity", "integration-test");
            Assertions.assertTrue(rebuild.rebuiltEntityCount() >= 1);
            Assertions.assertFalse(jedis.exists(localKeyStrategy.indexDegradedKey("users")));
            Assertions.assertTrue(jedis.sismember(localKeyStrategy.indexExactKey("users", "status", encodeKeyPart("ACTIVE")), "280"));

            QueryExplainPlan plan = repository.explain(QuerySpec.builder()
                    .filter(QueryFilter.eq("status", "ACTIVE"))
                    .limit(10)
                    .build());
            Assertions.assertNotEquals("DEGRADED_FULL_SCAN", plan.plannerStrategy());
        }
    }

    @Test
    void hardLimitPoliciesShouldAllowExactQueriesWhileSheddingTextQueries() throws Exception {
        String localPrefix = "cachedb-it-" + UUID.randomUUID();
        String localStreamKey = localPrefix + ":stream";
        String functionSuffix = localPrefix.replace('-', '_');
        RedisKeyStrategy localKeyStrategy = new RedisKeyStrategy(localPrefix, "entity", "page", "version", "hotset", "index", "compaction");

        try (CacheDatabase localDatabase = new CacheDatabase(jedis, dataSource(), CacheDatabaseConfig.builder()
                .writeBehind(WriteBehindConfig.builder()
                        .enabled(false)
                        .streamKey(localStreamKey)
                        .consumerGroup(localPrefix + "-group")
                        .consumerNamePrefix(localPrefix + "-worker")
                        .build())
                .resourceLimits(ResourceLimits.builder()
                        .defaultCachePolicy(CachePolicy.defaults())
                        .build())
                .keyspace(KeyspaceConfig.builder()
                        .keyPrefix(localPrefix)
                        .entitySegment("entity")
                        .pageSegment("page")
                        .versionSegment("version")
                        .tombstoneSegment("tombstone")
                        .hotSetSegment("hotset")
                        .indexSegment("index")
                        .compactionSegment("compaction")
                        .build())
                .redisFunctions(RedisFunctionsConfig.builder()
                        .enabled(true)
                        .autoLoadLibrary(true)
                        .replaceLibraryOnLoad(true)
                        .strictLoading(true)
                        .libraryName(functionSuffix)
                        .upsertFunctionName(functionSuffix + "_entity_upsert")
                        .deleteFunctionName(functionSuffix + "_entity_delete")
                        .compactionCompleteFunctionName(functionSuffix + "_compaction_complete")
                        .build())
                .redisGuardrail(RedisGuardrailConfig.builder()
                        .enabled(true)
                        .producerBackpressureEnabled(false)
                        .compactionPendingHardLimit(1)
                        .rejectWritesOnHardLimit(false)
                        .shedQueryIndexWritesOnHardLimit(false)
                        .shedQueryIndexReadsOnHardLimit(false)
                        .entityPolicies(List.of(
                                new HardLimitEntityPolicy("users", false, false, false, false, false, false, true)
                        ))
                        .queryPolicies(List.of(
                                new HardLimitQueryPolicy("users", HardLimitQueryClass.TEXT, true, true)
                        ))
                        .sampleIntervalMillis(1)
                        .build())
                .build())) {
            UserEntityCacheBinding.register(localDatabase, CachePolicy.defaults(), new UserOrdersRelationBatchLoader(), new UserPageLoader());
            localDatabase.start();

            EntityRepository<UserEntity, Long> repository = localDatabase.repository(
                    UserEntityCacheBinding.METADATA,
                    UserEntityCacheBinding.CODEC,
                    CachePolicy.defaults()
            );
            repository.save(user(290L, "policy lane", "ACTIVE"));
            jedis.hset(localKeyStrategy.compactionStatsKey(), Map.of("pendingCount", "1", "payloadCount", "0"));

            QueryExplainPlan exactPlan = repository.explain(QuerySpec.builder()
                    .filter(QueryFilter.eq("status", "ACTIVE"))
                    .limit(10)
                    .build());
            Assertions.assertNotEquals("DEGRADED_FULL_SCAN", exactPlan.plannerStrategy());

            List<UserEntity> textResult = repository.query(QuerySpec.builder()
                    .filter(QueryFilter.contains("username", "lane"))
                    .limit(10)
                    .build());
            Assertions.assertEquals(1, textResult.size());

            QueryExplainPlan textPlan = repository.explain(QuerySpec.builder()
                    .filter(QueryFilter.contains("username", "lane"))
                    .limit(10)
                    .build());
            Assertions.assertEquals("DEGRADED_FULL_SCAN", textPlan.plannerStrategy());
        }
    }

    @Test
    void schemaBootstrapShouldCreateMissingTablesForRegisteredEntities() throws Exception {
        String localPrefix = "cachedb-it-" + UUID.randomUUID();
        String tableName = "cachedb_schema_bootstrap_users";
        dropTableIfExists(tableName);

        EntityMetadata<UserEntity, Long> schemaMetadata = new EntityMetadata<>() {
            @Override
            public String entityName() {
                return "SchemaUserEntity";
            }

            @Override
            public String tableName() {
                return tableName;
            }

            @Override
            public String redisNamespace() {
                return "schema-users";
            }

            @Override
            public String idColumn() {
                return "id";
            }

            @Override
            public Class<UserEntity> entityType() {
                return UserEntity.class;
            }

            @Override
            public java.util.function.Function<UserEntity, Long> idAccessor() {
                return entity -> entity.id;
            }

            @Override
            public List<String> columns() {
                return List.of("id", "username", "status");
            }

            @Override
            public Map<String, String> columnTypes() {
                return Map.of(
                        "id", "java.lang.Long",
                        "username", "java.lang.String",
                        "status", "java.lang.String"
                );
            }

            @Override
            public List<com.reactor.cachedb.core.model.RelationDefinition> relations() {
                return List.of();
            }
        };

        try (CacheDatabase localDatabase = new CacheDatabase(jedis, dataSource(), CacheDatabaseConfig.builder()
                .writeBehind(WriteBehindConfig.builder()
                        .enabled(false)
                        .streamKey(localPrefix + ":stream")
                        .consumerGroup(localPrefix + "-group")
                        .consumerNamePrefix(localPrefix + "-worker")
                        .build())
                .keyspace(KeyspaceConfig.builder()
                        .keyPrefix(localPrefix)
                        .entitySegment("entity")
                        .pageSegment("page")
                        .versionSegment("version")
                        .tombstoneSegment("tombstone")
                        .hotSetSegment("hotset")
                        .indexSegment("index")
                        .compactionSegment("compaction")
                        .build())
                .schemaBootstrap(SchemaBootstrapConfig.builder()
                        .mode(SchemaBootstrapMode.CREATE_IF_MISSING)
                        .autoApplyOnStart(false)
                        .build())
                .build())) {
            localDatabase.register(schemaMetadata, UserEntityCacheBinding.CODEC, CachePolicy.defaults());

            var result = localDatabase.schema().createIfMissing();
            Assertions.assertTrue(result.success());
            Assertions.assertTrue(result.createdTables().contains(tableName));
            Assertions.assertEquals(1, countRows("select count(*) from information_schema.columns where table_name = '" + tableName + "' and column_name = 'entity_version'"));

            var validation = localDatabase.schema().validate();
            Assertions.assertTrue(validation.success());
            Assertions.assertTrue(validation.validatedTables().contains(tableName));

            try (CacheDatabaseAdminHttpServer httpServer = localDatabase.adminHttpServer(
                    AdminHttpConfig.builder()
                            .enabled(true)
                            .host("127.0.0.1")
                            .port(0)
                            .workerThreads(1)
                            .dashboardEnabled(false)
                            .build()
            )) {
                httpServer.start();
                HttpClient client = HttpClient.newHttpClient();
                HttpResponse<String> planResponse = httpGet(client, httpServer.baseUri().resolve("/api/schema/plan"));
                Assertions.assertEquals(200, planResponse.statusCode());
                Assertions.assertTrue(planResponse.body().contains("\"stepCount\":0"));
            }
        } finally {
            dropTableIfExists(tableName);
        }
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(jdbcUrl);
        dataSource.setUser(JDBC_USER);
        dataSource.setPassword(JDBC_PASSWORD);
        return dataSource;
    }

    private void recreateTables() throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_example_orders");
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_example_users");
            statement.executeUpdate("""
                    CREATE TABLE cachedb_example_users (
                        id BIGINT PRIMARY KEY,
                        username TEXT,
                        status TEXT,
                        entity_version BIGINT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE cachedb_example_orders (
                        id BIGINT PRIMARY KEY,
                        user_id BIGINT,
                        total_amount DOUBLE PRECISION,
                        status TEXT,
                        entity_version BIGINT
                    )
                    """);
        }
    }

    private void dropTables() throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_example_orders");
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_example_users");
        }
    }

    private void dropTableIfExists(String tableName) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS " + tableName);
        }
    }

    private long countRows(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private String stringValue(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private HttpResponse<String> httpGet(HttpClient client, URI uri) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpResponse<String> httpPost(HttpClient client, URI uri) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private void runFakeSmtpServer(ServerSocket serverSocket, AtomicReference<String> payloadRef, CountDownLatch latch) {
        try (ServerSocket ignored = serverSocket;
             Socket socket = serverSocket.accept();
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            writer.write("220 cachedb-test-smtp\r\n");
            writer.flush();
            StringBuilder payload = new StringBuilder();
            boolean readingData = false;
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                if (readingData) {
                    if (".".equals(line)) {
                        payloadRef.set(payload.toString());
                        writer.write("250 queued\r\n");
                        writer.flush();
                        readingData = false;
                        latch.countDown();
                    } else {
                        payload.append(line).append('\n');
                    }
                    continue;
                }

                if (line.startsWith("EHLO")) {
                    writer.write("250-cachedb-test-smtp\r\n");
                    writer.write("250-AUTH PLAIN LOGIN\r\n");
                    writer.write("250 ok\r\n");
                } else if (line.startsWith("HELO")) {
                    writer.write("250 hello\r\n");
                } else if (line.startsWith("AUTH PLAIN")) {
                    String payloadBase64 = line.substring("AUTH PLAIN".length()).trim();
                    String decoded = new String(Base64.getDecoder().decode(payloadBase64), StandardCharsets.UTF_8);
                    if ("\0incident-user\0incident-pass".equals(decoded)) {
                        writer.write("235 authenticated\r\n");
                    } else {
                        writer.write("535 authentication failed\r\n");
                    }
                } else if ("AUTH LOGIN".equals(line)) {
                    writer.write("334 VXNlcm5hbWU6\r\n");
                } else if ("aW5jaWRlbnQtdXNlcg==".equals(line)) {
                    writer.write("334 UGFzc3dvcmQ6\r\n");
                } else if ("aW5jaWRlbnQtcGFzcw==".equals(line)) {
                    writer.write("235 authenticated\r\n");
                } else if (line.startsWith("MAIL FROM")) {
                    writer.write("250 sender ok\r\n");
                } else if (line.startsWith("RCPT TO")) {
                    writer.write("250 recipient ok\r\n");
                } else if ("DATA".equals(line)) {
                    writer.write("354 end with .\r\n");
                    readingData = true;
                } else if ("QUIT".equals(line)) {
                    writer.write("221 bye\r\n");
                    writer.flush();
                    break;
                } else {
                    writer.write("250 ok\r\n");
                }
                writer.flush();
            }
        } catch (IOException exception) {
            // test helper; caller assertions cover delivery
        }
    }

    private UserEntity user(long id, String username, String status) {
        UserEntity user = new UserEntity();
        user.id = id;
        user.username = username;
        user.status = status;
        return user;
    }

    private void waitUntil(CheckedBooleanSupplier supplier, Duration timeout, String failureDetails) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (supplier.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        Assertions.fail("Condition was not met within timeout. " + failureDetails);
    }

    private void waitUntil(CheckedBooleanSupplier supplier, Duration timeout) throws Exception {
        waitUntil(supplier, timeout, "");
    }

    private String streamDiagnostics() {
        try {
            long length = jedis.xlen(streamKey);
            var groups = jedis.xinfoGroups(streamKey);
            var consumers = jedis.xinfoConsumers(streamKey, keyPrefix + "-group");
            return "length=" + length + ", groups=" + groups + ", consumers=" + consumers;
        } catch (RuntimeException exception) {
            return "diagnostics-error=" + exception.getMessage();
        }
    }

    private String exactIndexKey(String column, String value) {
        return keyPrefix + ":users:index:eq:" + column + ":" + encodeKeyPart(value);
    }

    private String prefixIndexKey(String column, String prefix) {
        return keyPrefix + ":users:index:prefix:" + column + ":" + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(prefix.getBytes(StandardCharsets.UTF_8));
    }

    private String tokenIndexKey(String column, String token) {
        return keyPrefix + ":users:index:token:" + column + ":" + encodeKeyPart(token);
    }

    private String encodeKeyPart(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private WriteOperation<UserEntity, Long> writeOperationForId(long id, String username) {
        return new WriteOperation<>(
                OperationType.UPSERT,
                UserEntityCacheBinding.METADATA,
                id,
                Map.of(
                        "id", id,
                        "username", username,
                        "status", "ACTIVE",
                        "entity_version", 1L
                ),
                "payload-" + id,
                1L,
                Instant.now()
        );
    }

    private QueuedWriteOperation queuedUserWrite(long id, String username, long version) {
        return new QueuedWriteOperation(
                OperationType.UPSERT,
                "UserEntity",
                "cachedb_example_users",
                "users",
                null,
                "id",
                "entity_version",
                "",
                String.valueOf(id),
                Map.of(
                        "id", String.valueOf(id),
                        "username", username,
                        "status", "ACTIVE",
                        "entity_version", String.valueOf(version)
                ),
                version,
                Instant.now()
        );
    }

    private QueuedWriteOperation queuedUserDelete(long id, long version) {
        return new QueuedWriteOperation(
                OperationType.DELETE,
                "UserEntity",
                "cachedb_example_users",
                "users",
                null,
                "id",
                "entity_version",
                "",
                String.valueOf(id),
                Map.of(
                        "id", String.valueOf(id),
                        "entity_version", String.valueOf(version)
                ),
                version,
                Instant.now()
        );
    }

    private void createUserAudit(String functionName, String triggerName) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_example_user_audit");
            statement.executeUpdate("CREATE TABLE cachedb_example_user_audit (user_id BIGINT, operation TEXT)");
            statement.executeUpdate("""
                    CREATE OR REPLACE FUNCTION %s() RETURNS trigger AS $$
                    BEGIN
                        INSERT INTO cachedb_example_user_audit(user_id, operation) VALUES (NEW.id, TG_OP);
                        RETURN NEW;
                    END;
                    $$ LANGUAGE plpgsql
                    """.formatted(functionName));
            statement.executeUpdate("""
                    CREATE TRIGGER %s
                    AFTER INSERT OR UPDATE ON cachedb_example_users
                    FOR EACH ROW EXECUTE FUNCTION %s()
                    """.formatted(triggerName, functionName));
        }
    }

    private void dropUserAudit(String functionName, String triggerName) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TRIGGER IF EXISTS " + triggerName + " ON cachedb_example_users");
            statement.executeUpdate("DROP FUNCTION IF EXISTS " + functionName + "()");
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_example_user_audit");
        }
    }

    private EntityMetadata<UserEntity, Long> brokenUserMetadata(String tableName) {
        return new EntityMetadata<>() {
            @Override
            public String entityName() {
                return UserEntityCacheBinding.METADATA.entityName();
            }

            @Override
            public String tableName() {
                return tableName;
            }

            @Override
            public String redisNamespace() {
                return UserEntityCacheBinding.METADATA.redisNamespace();
            }

            @Override
            public String idColumn() {
                return UserEntityCacheBinding.METADATA.idColumn();
            }

            @Override
            public String versionColumn() {
                return UserEntityCacheBinding.METADATA.versionColumn();
            }

            @Override
            public String deletedColumn() {
                return UserEntityCacheBinding.METADATA.deletedColumn();
            }

            @Override
            public String activeMarkerValue() {
                return UserEntityCacheBinding.METADATA.activeMarkerValue();
            }

            @Override
            public String deletedMarkerValue() {
                return UserEntityCacheBinding.METADATA.deletedMarkerValue();
            }

            @Override
            public Class<UserEntity> entityType() {
                return UserEntityCacheBinding.METADATA.entityType();
            }

            @Override
            public java.util.function.Function<UserEntity, Long> idAccessor() {
                return UserEntityCacheBinding.METADATA.idAccessor();
            }

            @Override
            public List<String> columns() {
                return UserEntityCacheBinding.METADATA.columns();
            }

            @Override
            public Map<String, String> columnTypes() {
                return UserEntityCacheBinding.METADATA.columnTypes();
            }

            @Override
            public List<com.reactor.cachedb.core.model.RelationDefinition> relations() {
                return UserEntityCacheBinding.METADATA.relations();
            }
        };
    }

    private EntityMetadata<UserEntity, Long> relationAwareUserMetadata(
            String entityName,
            String redisNamespace,
            String relationName,
            String targetEntity,
            String mappedBy
    ) {
        return new EntityMetadata<>() {
            @Override
            public String entityName() {
                return entityName;
            }

            @Override
            public String tableName() {
                return UserEntityCacheBinding.METADATA.tableName();
            }

            @Override
            public String redisNamespace() {
                return redisNamespace;
            }

            @Override
            public String idColumn() {
                return UserEntityCacheBinding.METADATA.idColumn();
            }

            @Override
            public Class<UserEntity> entityType() {
                return UserEntity.class;
            }

            @Override
            public java.util.function.Function<UserEntity, Long> idAccessor() {
                return entity -> entity.id;
            }

            @Override
            public List<String> columns() {
                return UserEntityCacheBinding.METADATA.columns();
            }

            @Override
            public Map<String, String> columnTypes() {
                return UserEntityCacheBinding.METADATA.columnTypes();
            }

            @Override
            public List<com.reactor.cachedb.core.model.RelationDefinition> relations() {
                return List.of(new com.reactor.cachedb.core.model.RelationDefinition(
                        relationName,
                        targetEntity,
                        mappedBy,
                        com.reactor.cachedb.core.model.RelationKind.ONE_TO_MANY,
                        true
                ));
            }
        };
    }

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }

    private String resolveJdbcUrl() throws SQLException {
        String forced = System.getProperty("cachedb.it.postgres.url");
        if (forced != null && !forced.isBlank()) {
            return forced;
        }

        List<String> candidates = new java.util.ArrayList<>();
        candidates.add("jdbc:postgresql://127.0.0.1:5432/postgres");
        candidates.add("jdbc:postgresql://localhost:5432/postgres");

        String dockerIp = inspectContainerIp("postgresql-test");
        if (dockerIp != null && !dockerIp.isBlank()) {
            candidates.add("jdbc:postgresql://" + dockerIp + ":5432/postgres");
        }

        SQLException lastFailure = null;
        for (String candidate : candidates) {
            try (Connection ignored = DriverManager.getConnection(candidate, JDBC_USER, JDBC_PASSWORD)) {
                return candidate;
            } catch (SQLException exception) {
                lastFailure = exception;
            }
        }

        throw lastFailure == null ? new SQLException("No PostgreSQL endpoint candidate could be resolved") : lastFailure;
    }

    private String inspectContainerIp(String containerName) {
        try {
            Process process = new ProcessBuilder(
                    "docker",
                    "inspect",
                    containerName,
                    "--format",
                    "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}"
            ).start();
            process.waitFor();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            return output.isBlank() ? null : output;
        } catch (Exception exception) {
            return null;
        }
    }
}
