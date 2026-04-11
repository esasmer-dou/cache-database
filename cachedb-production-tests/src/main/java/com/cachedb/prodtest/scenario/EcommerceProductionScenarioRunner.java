package com.reactor.cachedb.prodtest.scenario;

import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.EntityFlushPolicy;
import com.reactor.cachedb.core.config.HardLimitEntityPolicy;
import com.reactor.cachedb.core.config.HardLimitQueryPolicy;
import com.reactor.cachedb.core.config.KeyspaceConfig;
import com.reactor.cachedb.core.config.PersistenceSemantics;
import com.reactor.cachedb.core.config.RedisFunctionsConfig;
import com.reactor.cachedb.core.config.RedisGuardrailConfig;
import com.reactor.cachedb.core.config.ResourceLimits;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.query.HardLimitQueryClass;
import com.reactor.cachedb.core.queue.RedisRuntimeProfileEvent;
import com.reactor.cachedb.prodtest.entity.EcomCartEntity;
import com.reactor.cachedb.prodtest.entity.EcomCartEntityCacheBinding;
import com.reactor.cachedb.prodtest.entity.EcomCustomerEntity;
import com.reactor.cachedb.prodtest.entity.EcomCustomerEntityCacheBinding;
import com.reactor.cachedb.prodtest.entity.EcomInventoryEntity;
import com.reactor.cachedb.prodtest.entity.EcomInventoryEntityCacheBinding;
import com.reactor.cachedb.prodtest.entity.EcomOrderEntity;
import com.reactor.cachedb.prodtest.entity.EcomOrderEntityCacheBinding;
import com.reactor.cachedb.prodtest.entity.EcomProductEntity;
import com.reactor.cachedb.prodtest.entity.EcomProductEntityCacheBinding;
import com.reactor.cachedb.starter.CacheDatabase;
import org.postgresql.ds.PGSimpleDataSource;
import com.reactor.cachedb.redis.RedisKeyStrategy;
import com.reactor.cachedb.redis.RedisBacklogEstimator;
import redis.clients.jedis.JedisPooled;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

public final class EcommerceProductionScenarioRunner {

    private static final String JDBC_USER = System.getProperty("cachedb.prod.postgres.user", "postgres");
    private static final String JDBC_PASSWORD = System.getProperty("cachedb.prod.postgres.password", "postgresql");
    private static final String REDIS_PASSWORD = System.getProperty("cachedb.prod.redis.password", "welcome1");
    private static final String REDIS_URI = System.getProperty("cachedb.prod.redis.uri", "redis://default:" + REDIS_PASSWORD + "@127.0.0.1:6379");
    private static final String JDBC_URL = System.getProperty("cachedb.prod.postgres.url", "jdbc:postgresql://127.0.0.1:5432/postgres");
    private static final int REDIS_TIMEOUT_MILLIS = Integer.getInteger("cachedb.prod.redis.timeoutMillis", 60_000);
    private static final int POSTGRES_CONNECT_TIMEOUT_SECONDS = Integer.getInteger("cachedb.prod.postgres.connectTimeoutSeconds", 30);
    private static final int POSTGRES_SOCKET_TIMEOUT_SECONDS = Integer.getInteger("cachedb.prod.postgres.socketTimeoutSeconds", 300);
    private static final String SEED_MODE = System.getProperty("cachedb.prod.seed.mode", "bulk").trim().toLowerCase();
    private static final boolean UNLOGGED_SEED_TABLES = Boolean.parseBoolean(System.getProperty("cachedb.prod.seed.unloggedTables", "true"));
    private static final boolean BATCH_FLUSH_ENABLED = Boolean.parseBoolean(System.getProperty("cachedb.prod.writeBehind.batchFlushEnabled", "true"));
    private static final boolean TABLE_AWARE_BATCHING_ENABLED = Boolean.parseBoolean(System.getProperty("cachedb.prod.writeBehind.tableAwareBatchingEnabled", "true"));
    private static final int FLUSH_GROUP_PARALLELISM = Integer.getInteger("cachedb.prod.writeBehind.flushGroupParallelism", 2);
    private static final int FLUSH_PIPELINE_DEPTH = Integer.getInteger("cachedb.prod.writeBehind.flushPipelineDepth", 2);
    private static final boolean COALESCING_ENABLED = Boolean.parseBoolean(System.getProperty("cachedb.prod.writeBehind.coalescingEnabled", "true"));
    private static final int MAX_FLUSH_BATCH_SIZE = Integer.getInteger("cachedb.prod.writeBehind.maxFlushBatchSize", 250);
    private static final boolean BATCH_STALE_CHECK_ENABLED = Boolean.parseBoolean(System.getProperty("cachedb.prod.writeBehind.batchStaleCheckEnabled", "true"));
    private static final long ADAPTIVE_BACKLOG_HIGH_WATERMARK = Long.getLong("cachedb.prod.writeBehind.adaptiveHighWatermark", 250L);
    private static final long ADAPTIVE_BACKLOG_CRITICAL_WATERMARK = Long.getLong("cachedb.prod.writeBehind.adaptiveCriticalWatermark", 1_000L);
    private static final int ADAPTIVE_HIGH_FLUSH_BATCH_SIZE = Integer.getInteger("cachedb.prod.writeBehind.adaptiveHighBatchSize", 300);
    private static final int ADAPTIVE_CRITICAL_FLUSH_BATCH_SIZE = Integer.getInteger("cachedb.prod.writeBehind.adaptiveCriticalBatchSize", 500);
    private static final boolean POSTGRES_MULTI_ROW_FLUSH_ENABLED = Boolean.parseBoolean(System.getProperty("cachedb.prod.writeBehind.postgresMultiRowFlushEnabled", "true"));
    private static final int POSTGRES_MULTI_ROW_STATEMENT_ROW_LIMIT = Integer.getInteger("cachedb.prod.writeBehind.postgresMultiRowStatementRowLimit", 64);
    private static final boolean POSTGRES_COPY_BULK_LOAD_ENABLED = Boolean.parseBoolean(System.getProperty("cachedb.prod.writeBehind.postgresCopyBulkLoadEnabled", "true"));
    private static final int POSTGRES_COPY_THRESHOLD = Integer.getInteger("cachedb.prod.writeBehind.postgresCopyThreshold", 128);
    private static final boolean PRODUCER_BACKLOG_THROTTLING_ENABLED = Boolean.parseBoolean(System.getProperty("cachedb.prod.producer.throttlingEnabled", "true"));
    private static final long PRODUCER_THROTTLE_HIGH_WATERMARK = Long.getLong("cachedb.prod.producer.throttleHighWatermark", 250L);
    private static final long PRODUCER_THROTTLE_CRITICAL_WATERMARK = Long.getLong("cachedb.prod.producer.throttleCriticalWatermark", 750L);
    private static final long PRODUCER_THROTTLE_HIGH_SLEEP_MILLIS = Long.getLong("cachedb.prod.producer.throttleHighSleepMillis", 2L);
    private static final long PRODUCER_THROTTLE_CRITICAL_SLEEP_MILLIS = Long.getLong("cachedb.prod.producer.throttleCriticalSleepMillis", 5L);
    private static final int PRODUCER_BACKLOG_SAMPLE_EVERY_OPERATIONS = Integer.getInteger("cachedb.prod.producer.backlogSampleEveryOperations", 32);
    private static final long PRODUCER_BACKLOG_SAMPLE_INTERVAL_MILLIS = Long.getLong("cachedb.prod.producer.backlogSampleIntervalMillis", 50L);
    private static final long REDIS_USED_MEMORY_WARN_BYTES = Long.getLong("cachedb.prod.redis.usedMemoryWarnBytes", 0L);
    private static final long REDIS_USED_MEMORY_CRITICAL_BYTES = Long.getLong("cachedb.prod.redis.usedMemoryCriticalBytes", 0L);
    private static final long COMPACTION_PENDING_WARN_THRESHOLD = Long.getLong("cachedb.prod.redis.compactionPendingWarnThreshold", 1_000L);
    private static final long COMPACTION_PENDING_CRITICAL_THRESHOLD = Long.getLong("cachedb.prod.redis.compactionPendingCriticalThreshold", 5_000L);
    private static final int COMPACTION_PAYLOAD_TTL_SECONDS = Integer.getInteger("cachedb.prod.redis.compactionPayloadTtlSeconds", 3_600);
    private static final int COMPACTION_PENDING_TTL_SECONDS = Integer.getInteger("cachedb.prod.redis.compactionPendingTtlSeconds", 3_600);
    private static final int VERSION_KEY_TTL_SECONDS = Integer.getInteger("cachedb.prod.redis.versionKeyTtlSeconds", 86_400);
    private static final int TOMBSTONE_TTL_SECONDS = Integer.getInteger("cachedb.prod.redis.tombstoneTtlSeconds", 86_400);
    private static final boolean SHED_QUERY_INDEX_WRITES_ON_HARD_LIMIT = Boolean.parseBoolean(System.getProperty("cachedb.prod.redis.shedQueryIndexWritesOnHardLimit", "true"));
    private static final boolean SHED_QUERY_INDEX_READS_ON_HARD_LIMIT = Boolean.parseBoolean(System.getProperty("cachedb.prod.redis.shedQueryIndexReadsOnHardLimit", "true"));
    private static final boolean SHED_PLANNER_LEARNING_ON_HARD_LIMIT = Boolean.parseBoolean(System.getProperty("cachedb.prod.redis.shedPlannerLearningOnHardLimit", "true"));
    private static final boolean AUTOMATIC_RUNTIME_PROFILE_SWITCHING_ENABLED = Boolean.parseBoolean(System.getProperty("cachedb.prod.redis.automaticRuntimeProfileSwitchingEnabled", "true"));
    private static final int WARN_SAMPLES_TO_BALANCED = Integer.getInteger("cachedb.prod.redis.warnSamplesToBalanced", 3);
    private static final int CRITICAL_SAMPLES_TO_AGGRESSIVE = Integer.getInteger("cachedb.prod.redis.criticalSamplesToAggressive", 2);
    private static final int WARN_SAMPLES_TO_DEESCALATE_AGGRESSIVE = Integer.getInteger("cachedb.prod.redis.warnSamplesToDeescalateAggressive", 4);
    private static final int NORMAL_SAMPLES_TO_STANDARD = Integer.getInteger("cachedb.prod.redis.normalSamplesToStandard", 5);
    private static final boolean READ_WRITE_PATH_SEPARATION_ENABLED = Boolean.parseBoolean(System.getProperty("cachedb.prod.pathSeparationEnabled", "true"));
    private static final double READ_PATH_WORKER_SHARE_OVERRIDE = Double.parseDouble(System.getProperty("cachedb.prod.readPathWorkerShare", "-1"));
    private static final int COMPACTION_SHARD_COUNT_OVERRIDE = Integer.getInteger("cachedb.prod.writeBehind.compactionShardCount", -1);
    private static final boolean ENTITY_FLUSH_POLICIES_ENABLED = Boolean.parseBoolean(System.getProperty("cachedb.prod.writeBehind.entityFlushPoliciesEnabled", "false"));
    private static final boolean AUTO_DEGRADE_PROFILE_ENABLED = Boolean.parseBoolean(System.getProperty("cachedb.prod.guardrail.autoDegradeProfileEnabled", "true"));
    static final String FORCE_DEGRADE_PROFILE_PROPERTY = "cachedb.prod.guardrail.forceDegradeProfile";

    public ScenarioReport run(EcommerceScenarioProfile profile, double scaleFactor) throws Exception {
        return run(profile, scaleFactor, profile.name());
    }

    public ScenarioReport run(EcommerceScenarioProfile profile, double scaleFactor, String reportFilePrefix) throws Exception {
        String keyPrefix = "cachedb-prodtest-" + profile.name() + "-" + UUID.randomUUID();
        List<String> activeWriteBehindStreamKeys = activeWriteBehindStreamKeys(keyPrefix, profile);
        Path reportDirectory = Path.of("target", "cachedb-prodtest-reports");
        Files.createDirectories(reportDirectory);
        RedisKeyStrategy keyStrategy = new RedisKeyStrategy(keyPrefix, "entity", "page", "version", "hotset", "index", "compaction");
        DegradeProfile degradeProfile = resolveDegradeProfile();

        try (JedisPooled jedis = new JedisPooled(URI.create(REDIS_URI), REDIS_TIMEOUT_MILLIS);
             CacheDatabase cacheDatabase = new CacheDatabase(jedis, dataSource(), buildConfig(profile, keyPrefix, scaleFactor, degradeProfile))) {
            recreateTables();
            registerBindings(cacheDatabase, profile);
            if (usesBulkSeed()) {
                seedBulk(jedis, keyStrategy, profile);
                cacheDatabase.start();
            } else {
                cacheDatabase.start();
                seed(cacheDatabase, profile);
            }
            ScenarioCounters counters = executeWorkload(cacheDatabase, jedis, keyStrategy, activeWriteBehindStreamKeys, profile, scaleFactor);
            DrainResult drainResult = waitForDrain(cacheDatabase, profile);
            var metrics = cacheDatabase.admin().metrics();
            var guardrailSnapshot = metrics.redisGuardrailSnapshot();
            var runtimeProfileSnapshot = metrics.redisRuntimeProfileSnapshot();
            List<RedisRuntimeProfileEvent> runtimeProfileEvents = cacheDatabase.runtimeProfileEvents();
            List<String> runtimeProfileTimeline = runtimeProfileEvents.stream()
                    .map(event -> event.switchedAt() + ":" + event.fromProfile() + "->" + event.toProfile()
                            + "@" + event.pressureLevel()
                            + "[backlog=" + event.writeBehindBacklog()
                            + ",mem=" + event.usedMemoryBytes()
                            + ",pending=" + event.compactionPendingCount() + "]")
                    .toList();
            String finalHealthStatus = cacheDatabase.admin().health().status().name();

            ScenarioReport report = new ScenarioReport(
                    profile.name(),
                    profile.kind(),
                    scaleFactor,
                    counters.executedOperations.get(),
                    counters.readOperations.get(),
                    counters.writeOperations.get(),
                    counters.failedOperations.get(),
                    counters.elapsedMillis(),
                    counters.achievedTransactionsPerSecond(),
                    counters.averageLatencyMicros(),
                    counters.p95LatencyMicros(),
                    counters.p99LatencyMicros(),
                    counters.maxLatencyMicros.get(),
                    drainResult.drainMillis(),
                    drainResult.completed(),
                    metrics.writeBehindStreamLength(),
                    metrics.deadLetterStreamLength(),
                    metrics.plannerStatisticsSnapshot().learnedStatisticsKeyCount(),
                    metrics.incidentDeliverySnapshot().recovery().deadLetterCount(),
                    metrics.incidentDeliverySnapshot().recovery().claimedCount(),
                    guardrailSnapshot.usedMemoryBytes(),
                    guardrailSnapshot.usedMemoryPeakBytes(),
                    guardrailSnapshot.maxMemoryBytes(),
                    guardrailSnapshot.compactionPendingCount(),
                    guardrailSnapshot.compactionPayloadCount(),
                    guardrailSnapshot.hardRejectedWriteCount(),
                    guardrailSnapshot.producerHighPressureDelayCount(),
                    guardrailSnapshot.producerCriticalPressureDelayCount(),
                    guardrailSnapshot.pressureLevel(),
                    degradeProfile.name(),
                    runtimeProfileSnapshot.activeProfile(),
                    runtimeProfileSnapshot.switchCount(),
                    runtimeProfileTimeline,
                    finalHealthStatus,
                    buildNotes(profile, finalHealthStatus, degradeProfile, guardrailSnapshot.hardRejectedWriteCount())
            );

            writeMarkdown(reportDirectory.resolve(reportFilePrefix + ".md"), report);
            writeJson(reportDirectory.resolve(reportFilePrefix + ".json"), report);
            ScenarioProfileChurnReport profileChurnReport = toProfileChurnReport(report, runtimeProfileEvents);
            writeProfileChurnMarkdown(reportDirectory.resolve(reportFilePrefix + "-profile-churn.md"), profileChurnReport);
            writeProfileChurnJson(reportDirectory.resolve(reportFilePrefix + "-profile-churn.json"), profileChurnReport);
            return report;
        }
    }

    private CacheDatabaseConfig buildConfig(EcommerceScenarioProfile profile, String keyPrefix, double scaleFactor, DegradeProfile degradeProfile) {
        String functionPrefix = keyPrefix.replace('-', '_');
        int hotEntityLimit = Math.max(16, (int) Math.round(profile.hotEntityLimit() * Math.max(0.05d, scaleFactor) * degradeProfile.hotEntityLimitFactor()));
        int pageSize = Math.max(10, (int) Math.round(profile.pageSize() * Math.max(0.10d, scaleFactor) * degradeProfile.pageSizeFactor()));
        int entityTtlSeconds = Math.min(profile.entityTtlSeconds(), degradeProfile.entityTtlCapSeconds());
        int pageTtlSeconds = Math.min(profile.pageTtlSeconds(), degradeProfile.pageTtlCapSeconds());
        return CacheDatabaseConfig.builder()
                .writeBehind(WriteBehindConfig.builder()
                        .workerThreads(Math.max(1, profile.writeBehindWorkerThreads()))
                        .batchSize(Math.max(10, profile.writeBehindBatchSize()))
                        .dedicatedWriteConsumerGroupEnabled(true)
                        .durableCompactionEnabled(true)
                        .batchFlushEnabled(BATCH_FLUSH_ENABLED)
                        .tableAwareBatchingEnabled(TABLE_AWARE_BATCHING_ENABLED)
                        .flushGroupParallelism(Math.max(1, FLUSH_GROUP_PARALLELISM))
                        .flushPipelineDepth(Math.max(1, FLUSH_PIPELINE_DEPTH))
                        .coalescingEnabled(COALESCING_ENABLED)
                        .maxFlushBatchSize(Math.max(1, MAX_FLUSH_BATCH_SIZE))
                        .batchStaleCheckEnabled(BATCH_STALE_CHECK_ENABLED)
                        .adaptiveBacklogHighWatermark(ADAPTIVE_BACKLOG_HIGH_WATERMARK)
                        .adaptiveBacklogCriticalWatermark(ADAPTIVE_BACKLOG_CRITICAL_WATERMARK)
                        .adaptiveHighFlushBatchSize(Math.max(MAX_FLUSH_BATCH_SIZE, ADAPTIVE_HIGH_FLUSH_BATCH_SIZE))
                        .adaptiveCriticalFlushBatchSize(Math.max(ADAPTIVE_HIGH_FLUSH_BATCH_SIZE, ADAPTIVE_CRITICAL_FLUSH_BATCH_SIZE))
                        .postgresMultiRowFlushEnabled(POSTGRES_MULTI_ROW_FLUSH_ENABLED)
                        .postgresMultiRowStatementRowLimit(Math.max(1, POSTGRES_MULTI_ROW_STATEMENT_ROW_LIMIT))
                        .postgresCopyBulkLoadEnabled(POSTGRES_COPY_BULK_LOAD_ENABLED)
                        .postgresCopyThreshold(Math.max(2, POSTGRES_COPY_THRESHOLD))
                        .blockTimeoutMillis(200)
                        .idleSleepMillis(50)
                        .streamKey(keyPrefix + ":stream")
                        .consumerGroup(keyPrefix + "-group")
                        .consumerNamePrefix(keyPrefix + "-worker")
                        .compactionStreamKey(keyPrefix + ":stream:compaction")
                        .compactionConsumerGroup(keyPrefix + "-compaction-group")
                        .compactionConsumerNamePrefix(keyPrefix + "-compaction-worker")
                        .compactionShardCount(resolveCompactionShardCount(profile))
                        .entityFlushPolicies(ENTITY_FLUSH_POLICIES_ENABLED ? flushPolicies() : List.of())
                        .deadLetterStreamKey(keyPrefix + ":dlq")
                        .build())
                .resourceLimits(ResourceLimits.builder()
                        .defaultCachePolicy(CachePolicy.builder()
                                .hotEntityLimit(hotEntityLimit)
                                .pageSize(pageSize)
                                .entityTtlSeconds(entityTtlSeconds)
                                .pageTtlSeconds(pageTtlSeconds)
                                .build())
                        .maxColumnsPerOperation(64)
                        .build())
                .keyspace(KeyspaceConfig.builder()
                        .keyPrefix(keyPrefix)
                        .entitySegment("entity")
                        .pageSegment("page")
                        .versionSegment("version")
                        .tombstoneSegment("tombstone")
                        .hotSetSegment("hotset")
                        .compactionSegment("compaction")
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
                .redisGuardrail(RedisGuardrailConfig.builder()
                        .enabled(true)
                        .producerBackpressureEnabled(PRODUCER_BACKLOG_THROTTLING_ENABLED)
                        .usedMemoryWarnBytes(REDIS_USED_MEMORY_WARN_BYTES)
                        .usedMemoryCriticalBytes(REDIS_USED_MEMORY_CRITICAL_BYTES)
                        .writeBehindBacklogWarnThreshold(PRODUCER_THROTTLE_HIGH_WATERMARK)
                        .writeBehindBacklogCriticalThreshold(PRODUCER_THROTTLE_CRITICAL_WATERMARK)
                        .compactionPendingWarnThreshold(COMPACTION_PENDING_WARN_THRESHOLD)
                        .compactionPendingCriticalThreshold(COMPACTION_PENDING_CRITICAL_THRESHOLD)
                        .shedQueryIndexWritesOnHardLimit(SHED_QUERY_INDEX_WRITES_ON_HARD_LIMIT)
                        .shedQueryIndexReadsOnHardLimit(SHED_QUERY_INDEX_READS_ON_HARD_LIMIT)
                        .shedPlannerLearningOnHardLimit(SHED_PLANNER_LEARNING_ON_HARD_LIMIT)
                        .entityPolicies(hardLimitEntityPolicies())
                        .queryPolicies(hardLimitQueryPolicies())
                        .highSleepMillis(PRODUCER_THROTTLE_HIGH_SLEEP_MILLIS + degradeProfile.highSleepMillisBonus())
                        .criticalSleepMillis(PRODUCER_THROTTLE_CRITICAL_SLEEP_MILLIS + degradeProfile.criticalSleepMillisBonus())
                        .sampleIntervalMillis(PRODUCER_BACKLOG_SAMPLE_INTERVAL_MILLIS)
                        .automaticRuntimeProfileSwitchingEnabled(!isForcedDegradeProfileSelection() && AUTOMATIC_RUNTIME_PROFILE_SWITCHING_ENABLED)
                        .warnSamplesToBalanced(WARN_SAMPLES_TO_BALANCED)
                        .criticalSamplesToAggressive(CRITICAL_SAMPLES_TO_AGGRESSIVE)
                        .warnSamplesToDeescalateAggressive(WARN_SAMPLES_TO_DEESCALATE_AGGRESSIVE)
                        .normalSamplesToStandard(NORMAL_SAMPLES_TO_STANDARD)
                        .compactionPayloadTtlSeconds(COMPACTION_PAYLOAD_TTL_SECONDS)
                        .compactionPendingTtlSeconds(COMPACTION_PENDING_TTL_SECONDS)
                        .versionKeyTtlSeconds(VERSION_KEY_TTL_SECONDS)
                        .tombstoneTtlSeconds(TOMBSTONE_TTL_SECONDS)
                        .build())
                .build();
    }

    private void registerBindings(CacheDatabase cacheDatabase, EcommerceScenarioProfile profile) {
        CachePolicy policy = CachePolicy.builder()
                .hotEntityLimit(profile.hotEntityLimit())
                .pageSize(profile.pageSize())
                .entityTtlSeconds(profile.entityTtlSeconds())
                .pageTtlSeconds(profile.pageTtlSeconds())
                .build();
        cacheDatabase.register(EcomCustomerEntityCacheBinding.METADATA, EcomCustomerEntityCacheBinding.CODEC, policy);
        cacheDatabase.register(EcomProductEntityCacheBinding.METADATA, EcomProductEntityCacheBinding.CODEC, policy);
        cacheDatabase.register(EcomInventoryEntityCacheBinding.METADATA, EcomInventoryEntityCacheBinding.CODEC, policy);
        cacheDatabase.register(EcomCartEntityCacheBinding.METADATA, EcomCartEntityCacheBinding.CODEC, policy);
        cacheDatabase.register(EcomOrderEntityCacheBinding.METADATA, EcomOrderEntityCacheBinding.CODEC, policy);
    }

    private void seed(CacheDatabase cacheDatabase, EcommerceScenarioProfile profile) {
        EntityRepository<EcomCustomerEntity, Long> customerRepository = cacheDatabase.repository(
                EcomCustomerEntityCacheBinding.METADATA,
                EcomCustomerEntityCacheBinding.CODEC,
                CachePolicy.defaults()
        );
        EntityRepository<EcomProductEntity, Long> productRepository = cacheDatabase.repository(
                EcomProductEntityCacheBinding.METADATA,
                EcomProductEntityCacheBinding.CODEC,
                CachePolicy.defaults()
        );
        EntityRepository<EcomInventoryEntity, Long> inventoryRepository = cacheDatabase.repository(
                EcomInventoryEntityCacheBinding.METADATA,
                EcomInventoryEntityCacheBinding.CODEC,
                CachePolicy.defaults()
        );

        for (long id = 1; id <= profile.customerCount(); id++) {
            EcomCustomerEntity entity = new EcomCustomerEntity();
            entity.id = id;
            entity.status = "ACTIVE";
            entity.segment = id % 2 == 0 ? "SMS_PUSH" : "PUSH";
            entity.tier = id % 5 == 0 ? "VIP" : "STANDARD";
            entity.lastCampaignCode = "BOOTSTRAP";
            customerRepository.save(entity);
        }

        for (long id = 1; id <= profile.productCount(); id++) {
            EcomProductEntity product = new EcomProductEntity();
            product.id = id;
            product.sku = "SKU-" + id;
            product.category = "CAT-" + (id % 100);
            product.availabilityStatus = "LIVE";
            product.price = 25.0d + (id % 500);
            productRepository.save(product);

            EcomInventoryEntity inventory = new EcomInventoryEntity();
            inventory.id = id;
            inventory.sku = product.sku;
            inventory.warehouseCode = "WH-" + (id % 4);
            inventory.availableUnits = 10_000L;
            inventory.reservedUnits = 0L;
            inventoryRepository.save(inventory);
        }
    }

    private void seedBulk(JedisPooled jedis, RedisKeyStrategy keyStrategy, EcommerceScenarioProfile profile) throws SQLException {
        BulkBootstrapSeeder seeder = new BulkBootstrapSeeder();
        try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD)) {
            seeder.seedRange(
                    connection,
                    jedis,
                    keyStrategy,
                    EcomCustomerEntityCacheBinding.METADATA,
                    EcomCustomerEntityCacheBinding.CODEC,
                    1L,
                    profile.customerCount(),
                    this::newSeedCustomer
            );
            seeder.seedRange(
                    connection,
                    jedis,
                    keyStrategy,
                    EcomProductEntityCacheBinding.METADATA,
                    EcomProductEntityCacheBinding.CODEC,
                    1L,
                    profile.productCount(),
                    this::newSeedProduct
            );
            seeder.seedRange(
                    connection,
                    jedis,
                    keyStrategy,
                    EcomInventoryEntityCacheBinding.METADATA,
                    EcomInventoryEntityCacheBinding.CODEC,
                    1L,
                    profile.productCount(),
                    this::newSeedInventory
            );
        }
    }

    private ScenarioCounters executeWorkload(
            CacheDatabase cacheDatabase,
            JedisPooled jedis,
            RedisKeyStrategy keyStrategy,
            List<String> activeWriteBehindStreamKeys,
            EcommerceScenarioProfile profile,
            double scaleFactor
    )
            throws InterruptedException, ExecutionException {
        EntityRepository<EcomCustomerEntity, Long> customerRepository = cacheDatabase.repository(
                EcomCustomerEntityCacheBinding.METADATA,
                EcomCustomerEntityCacheBinding.CODEC,
                CachePolicy.defaults()
        );
        EntityRepository<EcomProductEntity, Long> productRepository = cacheDatabase.repository(
                EcomProductEntityCacheBinding.METADATA,
                EcomProductEntityCacheBinding.CODEC,
                CachePolicy.defaults()
        );
        EntityRepository<EcomInventoryEntity, Long> inventoryRepository = cacheDatabase.repository(
                EcomInventoryEntityCacheBinding.METADATA,
                EcomInventoryEntityCacheBinding.CODEC,
                CachePolicy.defaults()
        );
        EntityRepository<EcomCartEntity, Long> cartRepository = cacheDatabase.repository(
                EcomCartEntityCacheBinding.METADATA,
                EcomCartEntityCacheBinding.CODEC,
                CachePolicy.defaults()
        );
        EntityRepository<EcomOrderEntity, Long> orderRepository = cacheDatabase.repository(
                EcomOrderEntityCacheBinding.METADATA,
                EcomOrderEntityCacheBinding.CODEC,
                CachePolicy.defaults()
        );

        long effectiveTps = Math.max(100L, Math.round(profile.targetTransactionsPerSecond() * Math.max(0.001d, scaleFactor)));
        long endAtNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, profile.durationSeconds()));
        WorkerAllocation allocation = allocateWorkers(profile, effectiveTps);

        ScenarioCounters counters = new ScenarioCounters();
        AtomicLong cartIdSequence = new AtomicLong(1_000_000L);
        AtomicLong orderIdSequence = new AtomicLong(5_000_000L);
        ExecutorService executorService = Executors.newFixedThreadPool(allocation.totalWorkers());
        ArrayList<Future<Void>> futures = new ArrayList<>(allocation.totalWorkers());

        submitWorkers(
                futures,
                executorService,
                profile,
                WorkerMode.READ_ONLY,
                allocation.readWorkers(),
                allocation.readTps(),
                endAtNanos,
                customerRepository,
                productRepository,
                inventoryRepository,
                cartRepository,
                orderRepository,
                () -> streamLength(jedis, keyStrategy, activeWriteBehindStreamKeys),
                cartIdSequence,
                orderIdSequence,
                counters
        );
        submitWorkers(
                futures,
                executorService,
                profile,
                WorkerMode.WRITE_ONLY,
                allocation.writeWorkers(),
                allocation.writeTps(),
                endAtNanos,
                customerRepository,
                productRepository,
                inventoryRepository,
                cartRepository,
                orderRepository,
                () -> streamLength(jedis, keyStrategy, activeWriteBehindStreamKeys),
                cartIdSequence,
                orderIdSequence,
                counters
        );
        if (futures.isEmpty()) {
            submitWorkers(
                    futures,
                    executorService,
                    profile,
                    WorkerMode.MIXED,
                    Math.max(1, profile.workerThreads()),
                    effectiveTps,
                    endAtNanos,
                    customerRepository,
                    productRepository,
                    inventoryRepository,
                    cartRepository,
                    orderRepository,
                    () -> streamLength(jedis, keyStrategy, activeWriteBehindStreamKeys),
                    cartIdSequence,
                    orderIdSequence,
                    counters
            );
        }

        executorService.shutdown();
        for (Future<Void> future : futures) {
            future.get();
        }
        executorService.awaitTermination(10, TimeUnit.SECONDS);
        counters.finish();
        return counters;
    }

    private WorkerAllocation allocateWorkers(EcommerceScenarioProfile profile, long effectiveTps) {
        int totalWorkers = Math.max(1, profile.workerThreads());
        if (!READ_WRITE_PATH_SEPARATION_ENABLED) {
            return new WorkerAllocation(0, 0L, totalWorkers, effectiveTps);
        }

        int readWeight = profile.browsePercent() + profile.productLookupPercent();
        int writeWeight = Math.max(0, profile.totalWeight() - readWeight);
        if (readWeight == 0 || writeWeight == 0) {
            return new WorkerAllocation(0, 0L, totalWorkers, effectiveTps);
        }

        double readShare = READ_PATH_WORKER_SHARE_OVERRIDE >= 0.0d
                ? Math.min(0.95d, Math.max(0.05d, READ_PATH_WORKER_SHARE_OVERRIDE))
                : (double) readWeight / Math.max(1, profile.totalWeight());
        int readWorkers = Math.max(1, (int) Math.round(totalWorkers * readShare));
        int writeWorkers = Math.max(1, totalWorkers - readWorkers);
        if (readWorkers + writeWorkers > totalWorkers) {
            readWorkers = Math.max(1, totalWorkers - writeWorkers);
        }
        long readTps = Math.max(1L, Math.round(effectiveTps * readShare));
        long writeTps = Math.max(1L, effectiveTps - readTps);
        return new WorkerAllocation(readWorkers, readTps, writeWorkers, writeTps);
    }

    private int resolveCompactionShardCount(EcommerceScenarioProfile profile) {
        if (COMPACTION_SHARD_COUNT_OVERRIDE > 0) {
            return COMPACTION_SHARD_COUNT_OVERRIDE;
        }
        return Math.max(1, Math.min(8, profile.writeBehindWorkerThreads()));
    }

    private List<String> activeWriteBehindStreamKeys(String keyPrefix, EcommerceScenarioProfile profile) {
        String compactionBaseStreamKey = keyPrefix + ":stream:compaction";
        int shardCount = resolveCompactionShardCount(profile);
        if (shardCount <= 1) {
            return List.of(compactionBaseStreamKey);
        }
        ArrayList<String> streamKeys = new ArrayList<>(shardCount);
        for (int shard = 0; shard < shardCount; shard++) {
            streamKeys.add(compactionBaseStreamKey + ":" + shard);
        }
        return List.copyOf(streamKeys);
    }

    private long streamLength(JedisPooled jedis, RedisKeyStrategy keyStrategy, List<String> streamKeys) {
        return RedisBacklogEstimator.estimateWriteBehindBacklog(jedis, keyStrategy, streamKeys, true);
    }

    private List<EntityFlushPolicy> flushPolicies() {
        return List.of(
                new EntityFlushPolicy("EcomCustomerEntity", OperationType.UPSERT, true, false, true, 1024, 256, 0, PersistenceSemantics.LATEST_STATE),
                new EntityFlushPolicy("EcomCustomerEntity", OperationType.DELETE, true, false, true, 512, 128, 0, PersistenceSemantics.LATEST_STATE),
                new EntityFlushPolicy("EcomInventoryEntity", OperationType.UPSERT, true, true, true, 1024, 256, 192, PersistenceSemantics.LATEST_STATE),
                new EntityFlushPolicy("EcomInventoryEntity", OperationType.DELETE, true, true, true, 512, 128, 128, PersistenceSemantics.LATEST_STATE),
                new EntityFlushPolicy("EcomCartEntity", OperationType.UPSERT, true, false, true, 1024, 256, 0, PersistenceSemantics.LATEST_STATE),
                new EntityFlushPolicy("EcomCartEntity", OperationType.DELETE, true, false, true, 512, 128, 0, PersistenceSemantics.LATEST_STATE),
                new EntityFlushPolicy("EcomOrderEntity", OperationType.UPSERT, false, false, true, 512, 128, 0, PersistenceSemantics.EXACT_SEQUENCE),
                new EntityFlushPolicy("EcomOrderEntity", OperationType.DELETE, false, false, true, 256, 128, 0, PersistenceSemantics.EXACT_SEQUENCE)
        );
    }

    private void submitWorkers(
            List<Future<Void>> futures,
            ExecutorService executorService,
            EcommerceScenarioProfile profile,
            WorkerMode mode,
            int workerCount,
            long totalTps,
            long endAtNanos,
            EntityRepository<EcomCustomerEntity, Long> customerRepository,
            EntityRepository<EcomProductEntity, Long> productRepository,
            EntityRepository<EcomInventoryEntity, Long> inventoryRepository,
            EntityRepository<EcomCartEntity, Long> cartRepository,
            EntityRepository<EcomOrderEntity, Long> orderRepository,
            java.util.function.LongSupplier writeBehindBacklogSupplier,
            AtomicLong cartIdSequence,
            AtomicLong orderIdSequence,
            ScenarioCounters counters
    ) {
        if (workerCount <= 0 || totalTps <= 0) {
            return;
        }
        long perWorkerTps = Math.max(1L, totalTps / workerCount);
        long nanosPerOperation = Math.max(1L, 1_000_000_000L / perWorkerTps);
        for (int index = 0; index < workerCount; index++) {
            futures.add(executorService.submit(new Worker(
                    profile,
                    mode,
                    endAtNanos,
                    nanosPerOperation,
                    customerRepository,
                    productRepository,
                    inventoryRepository,
                    cartRepository,
                    orderRepository,
                    writeBehindBacklogSupplier,
                    cartIdSequence,
                    orderIdSequence,
                    counters
            )));
        }
    }

    private DrainResult waitForDrain(CacheDatabase cacheDatabase, EcommerceScenarioProfile profile) throws InterruptedException {
        long startedAt = System.currentTimeMillis();
        long deadline = System.currentTimeMillis() + Math.max(15_000L, profile.durationSeconds() * 1_000L);
        int stableSamples = 0;
        while (System.currentTimeMillis() < deadline) {
            var metrics = cacheDatabase.admin().metrics();
            boolean drained = metrics.redisGuardrailSnapshot().compactionPendingCount() == 0L
                    && metrics.deadLetterStreamLength() == 0L
                    && metrics.incidentDeliverySnapshot().recovery().deadLetterCount() == 0L;
            if (drained) {
                stableSamples++;
            } else {
                stableSamples = 0;
            }
            if (stableSamples >= 3) {
                return new DrainResult(System.currentTimeMillis() - startedAt, true);
            }
            Thread.sleep(100L);
        }
        return new DrainResult(System.currentTimeMillis() - startedAt, false);
    }

    private void recreateTables() throws SQLException {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement()) {
            String tableType = UNLOGGED_SEED_TABLES ? "UNLOGGED TABLE" : "TABLE";
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_prodtest_orders");
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_prodtest_carts");
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_prodtest_inventory");
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_prodtest_products");
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_prodtest_customers");
            statement.executeUpdate("""
                    CREATE %s cachedb_prodtest_customers (
                        id BIGINT PRIMARY KEY,
                        status TEXT,
                        segment TEXT,
                        tier TEXT,
                        last_campaign_code TEXT,
                        entity_version BIGINT
                    )
                    """.formatted(tableType));
            statement.executeUpdate("""
                    CREATE %s cachedb_prodtest_products (
                        id BIGINT PRIMARY KEY,
                        sku TEXT,
                        category TEXT,
                        availability_status TEXT,
                        price DOUBLE PRECISION,
                        entity_version BIGINT
                    )
                    """.formatted(tableType));
            statement.executeUpdate("""
                    CREATE %s cachedb_prodtest_inventory (
                        id BIGINT PRIMARY KEY,
                        sku TEXT,
                        warehouse_code TEXT,
                        available_units BIGINT,
                        reserved_units BIGINT,
                        entity_version BIGINT
                    )
                    """.formatted(tableType));
            statement.executeUpdate("""
                    CREATE %s cachedb_prodtest_carts (
                        id BIGINT PRIMARY KEY,
                        customer_id BIGINT,
                        sku TEXT,
                        quantity INTEGER,
                        state TEXT,
                        entity_version BIGINT
                    )
                    """.formatted(tableType));
            statement.executeUpdate("""
                    CREATE %s cachedb_prodtest_orders (
                        id BIGINT PRIMARY KEY,
                        customer_id BIGINT,
                        sku TEXT,
                        quantity INTEGER,
                        total_amount DOUBLE PRECISION,
                        status TEXT,
                        campaign_code TEXT,
                        entity_version BIGINT
                    )
                    """.formatted(tableType));
        }
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(JDBC_URL);
        dataSource.setUser(JDBC_USER);
        dataSource.setPassword(JDBC_PASSWORD);
        dataSource.setConnectTimeout(POSTGRES_CONNECT_TIMEOUT_SECONDS);
        dataSource.setSocketTimeout(POSTGRES_SOCKET_TIMEOUT_SECONDS);
        return dataSource;
    }

    private String buildNotes(
            EcommerceScenarioProfile profile,
            String finalHealthStatus,
            DegradeProfile degradeProfile,
            long hardRejectedWriteCount
    ) {
        return "targetTps=" + profile.targetTransactionsPerSecond()
                + ", workerThreads=" + profile.workerThreads()
                + ", writeBehindWorkers=" + profile.writeBehindWorkerThreads()
                + ", seedMode=" + SEED_MODE
                + ", batchFlushEnabled=" + BATCH_FLUSH_ENABLED
                + ", tableAwareBatchingEnabled=" + TABLE_AWARE_BATCHING_ENABLED
                + ", flushGroupParallelism=" + FLUSH_GROUP_PARALLELISM
                + ", flushPipelineDepth=" + FLUSH_PIPELINE_DEPTH
                + ", coalescingEnabled=" + COALESCING_ENABLED
                + ", maxFlushBatchSize=" + MAX_FLUSH_BATCH_SIZE
                + ", postgresMultiRowFlushEnabled=" + POSTGRES_MULTI_ROW_FLUSH_ENABLED
                + ", postgresCopyBulkLoadEnabled=" + POSTGRES_COPY_BULK_LOAD_ENABLED
                + ", postgresCopyThreshold=" + POSTGRES_COPY_THRESHOLD
                + ", producerThrottling=" + PRODUCER_BACKLOG_THROTTLING_ENABLED
            + ", pathSeparation=" + READ_WRITE_PATH_SEPARATION_ENABLED
            + ", runtimeProfileSwitching=" + (!isForcedDegradeProfileSelection() && AUTOMATIC_RUNTIME_PROFILE_SWITCHING_ENABLED)
            + ", semantics=customer|inventory|cart:LATEST_STATE;order:EXACT_SEQUENCE"
            + ", entityPolicies=customers|inventory|carts:auto-rebuild,index-shed;orders:preserve"
            + ", queryPolicies=text|relation|sort:hard-limit-shed"
            + ", queryIndexSheddingOnHardLimit=" + SHED_QUERY_INDEX_WRITES_ON_HARD_LIMIT + "/" + SHED_QUERY_INDEX_READS_ON_HARD_LIMIT
            + ", degradeProfile=" + degradeProfile.name()
            + ", hardRejectedWrites=" + hardRejectedWriteCount
            + ", health=" + finalHealthStatus;
    }

    private List<HardLimitEntityPolicy> hardLimitEntityPolicies() {
        return List.of(
                new HardLimitEntityPolicy("customers", true, true, true, true, true, true, true),
                new HardLimitEntityPolicy("inventory", true, true, true, true, true, true, true),
                new HardLimitEntityPolicy("carts", true, true, true, true, true, true, true),
                new HardLimitEntityPolicy("orders", true, true, true, false, false, false, false)
        );
    }

    private List<HardLimitQueryPolicy> hardLimitQueryPolicies() {
        return List.of(
                new HardLimitQueryPolicy("*", HardLimitQueryClass.TEXT, true, true),
                new HardLimitQueryPolicy("*", HardLimitQueryClass.RELATION, true, true),
                new HardLimitQueryPolicy("*", HardLimitQueryClass.SORT, true, true)
        );
    }

    private boolean usesBulkSeed() {
        return !"repository".equals(SEED_MODE);
    }

    private EcomCustomerEntity newSeedCustomer(long id) {
        EcomCustomerEntity entity = new EcomCustomerEntity();
        entity.id = id;
        entity.status = "ACTIVE";
        entity.segment = id % 2 == 0 ? "SMS_PUSH" : "PUSH";
        entity.tier = id % 5 == 0 ? "VIP" : "STANDARD";
        entity.lastCampaignCode = "BOOTSTRAP";
        return entity;
    }

    private EcomProductEntity newSeedProduct(long id) {
        EcomProductEntity product = new EcomProductEntity();
        product.id = id;
        product.sku = "SKU-" + id;
        product.category = "CAT-" + (id % 100);
        product.availabilityStatus = "LIVE";
        product.price = 25.0d + (id % 500);
        return product;
    }

    private EcomInventoryEntity newSeedInventory(long id) {
        EcomInventoryEntity inventory = new EcomInventoryEntity();
        inventory.id = id;
        inventory.sku = "SKU-" + id;
        inventory.warehouseCode = "WH-" + (id % 4);
        inventory.availableUnits = 10_000L;
        inventory.reservedUnits = 0L;
        return inventory;
    }

    private void writeMarkdown(Path path, ScenarioReport report) throws IOException {
        String content = "# " + report.scenarioName() + System.lineSeparator()
                + System.lineSeparator()
                + "- kind: `" + report.kind() + "`" + System.lineSeparator()
                + "- scaleFactor: `" + report.scaleFactor() + "`" + System.lineSeparator()
                + "- executedOperations: `" + report.executedOperations() + "`" + System.lineSeparator()
                + "- readOperations: `" + report.readOperations() + "`" + System.lineSeparator()
                + "- writeOperations: `" + report.writeOperations() + "`" + System.lineSeparator()
                + "- failedOperations: `" + report.failedOperations() + "`" + System.lineSeparator()
                + "- elapsedMillis: `" + report.elapsedMillis() + "`" + System.lineSeparator()
                + "- achievedTransactionsPerSecond: `" + report.achievedTransactionsPerSecond() + "`" + System.lineSeparator()
                + "- averageLatencyMicros: `" + report.averageLatencyMicros() + "`" + System.lineSeparator()
                + "- p95LatencyMicros: `" + report.p95LatencyMicros() + "`" + System.lineSeparator()
                + "- p99LatencyMicros: `" + report.p99LatencyMicros() + "`" + System.lineSeparator()
                + "- maxLatencyMicros: `" + report.maxLatencyMicros() + "`" + System.lineSeparator()
                + "- drainMillis: `" + report.drainMillis() + "`" + System.lineSeparator()
                + "- drainCompleted: `" + report.drainCompleted() + "`" + System.lineSeparator()
                + "- writeBehindStreamLength: `" + report.writeBehindStreamLength() + "`" + System.lineSeparator()
                + "- deadLetterStreamLength: `" + report.deadLetterStreamLength() + "`" + System.lineSeparator()
                + "- plannerLearnedStatsCount: `" + report.plannerLearnedStatsCount() + "`" + System.lineSeparator()
                + "- incidentDeliveryDeadLetterCount: `" + report.incidentDeliveryDeadLetterCount() + "`" + System.lineSeparator()
                + "- incidentDeliveryClaimedCount: `" + report.incidentDeliveryClaimedCount() + "`" + System.lineSeparator()
                + "- redisUsedMemoryBytes: `" + report.redisUsedMemoryBytes() + "`" + System.lineSeparator()
                + "- redisUsedMemoryPeakBytes: `" + report.redisUsedMemoryPeakBytes() + "`" + System.lineSeparator()
                + "- redisMaxMemoryBytes: `" + report.redisMaxMemoryBytes() + "`" + System.lineSeparator()
           + "- compactionPendingCount: `" + report.compactionPendingCount() + "`" + System.lineSeparator()
           + "- compactionPayloadCount: `" + report.compactionPayloadCount() + "`" + System.lineSeparator()
           + "- hardRejectedWriteCount: `" + report.hardRejectedWriteCount() + "`" + System.lineSeparator()
           + "- producerHighPressureDelayCount: `" + report.producerHighPressureDelayCount() + "`" + System.lineSeparator()
                + "- producerCriticalPressureDelayCount: `" + report.producerCriticalPressureDelayCount() + "`" + System.lineSeparator()
                + "- redisPressureLevel: `" + report.redisPressureLevel() + "`" + System.lineSeparator()
                + "- degradeProfile: `" + report.degradeProfile() + "`" + System.lineSeparator()
                + "- finalRuntimeProfile: `" + report.finalRuntimeProfile() + "`" + System.lineSeparator()
                + "- runtimeProfileSwitchCount: `" + report.runtimeProfileSwitchCount() + "`" + System.lineSeparator()
                + "- runtimeProfileTimeline: `" + String.join(" | ", report.runtimeProfileTimeline()) + "`" + System.lineSeparator()
                + "- finalHealthStatus: `" + report.finalHealthStatus() + "`" + System.lineSeparator()
                + "- notes: `" + report.notes() + "`" + System.lineSeparator();
        Files.writeString(path, content);
    }

    private void writeProfileChurnMarkdown(Path path, ScenarioProfileChurnReport report) throws IOException {
        StringBuilder content = new StringBuilder();
        content.append("# ").append(report.scenarioName()).append(" profile churn").append(System.lineSeparator())
                .append(System.lineSeparator())
                .append("- kind: `").append(report.kind()).append('`').append(System.lineSeparator())
                .append("- scaleFactor: `").append(report.scaleFactor()).append('`').append(System.lineSeparator())
                .append("- degradeProfile: `").append(report.degradeProfile()).append('`').append(System.lineSeparator())
                .append("- finalRuntimeProfile: `").append(report.finalRuntimeProfile()).append('`').append(System.lineSeparator())
                .append("- runtimeProfileSwitchCount: `").append(report.runtimeProfileSwitchCount()).append('`').append(System.lineSeparator())
                .append("- finalHealthStatus: `").append(report.finalHealthStatus()).append('`').append(System.lineSeparator())
                .append(System.lineSeparator());
        content.append("| Switched At | From | To | Pressure | Switch # | Used Memory | Backlog | Pending |")
                .append(System.lineSeparator());
        content.append("|---|---|---|---|---:|---:|---:|---:|").append(System.lineSeparator());
        if (report.entries().isEmpty()) {
            content.append("| - | - | - | - | 0 | 0 | 0 | 0 |").append(System.lineSeparator());
        } else {
            for (ScenarioProfileChurnEntry entry : report.entries()) {
                content.append("| ").append(entry.switchedAt())
                        .append(" | `").append(entry.fromProfile()).append('`')
                        .append(" | `").append(entry.toProfile()).append('`')
                        .append(" | `").append(entry.pressureLevel()).append('`')
                        .append(" | ").append(entry.switchCount())
                        .append(" | ").append(entry.usedMemoryBytes())
                        .append(" | ").append(entry.writeBehindBacklog())
                        .append(" | ").append(entry.compactionPendingCount())
                        .append(" |")
                        .append(System.lineSeparator());
            }
        }
        Files.writeString(path, content.toString());
    }

    private void writeJson(Path path, ScenarioReport report) throws IOException {
        String json = "{"
                + "\"scenarioName\":\"" + escapeJson(report.scenarioName()) + "\","
                + "\"kind\":\"" + report.kind().name() + "\","
                + "\"scaleFactor\":" + report.scaleFactor() + ","
                + "\"executedOperations\":" + report.executedOperations() + ","
                + "\"readOperations\":" + report.readOperations() + ","
                + "\"writeOperations\":" + report.writeOperations() + ","
                + "\"failedOperations\":" + report.failedOperations() + ","
                + "\"elapsedMillis\":" + report.elapsedMillis() + ","
                + "\"achievedTransactionsPerSecond\":" + report.achievedTransactionsPerSecond() + ","
                + "\"averageLatencyMicros\":" + report.averageLatencyMicros() + ","
                + "\"p95LatencyMicros\":" + report.p95LatencyMicros() + ","
                + "\"p99LatencyMicros\":" + report.p99LatencyMicros() + ","
                + "\"maxLatencyMicros\":" + report.maxLatencyMicros() + ","
                + "\"drainMillis\":" + report.drainMillis() + ","
                + "\"drainCompleted\":" + report.drainCompleted() + ","
                + "\"writeBehindStreamLength\":" + report.writeBehindStreamLength() + ","
                + "\"deadLetterStreamLength\":" + report.deadLetterStreamLength() + ","
                + "\"plannerLearnedStatsCount\":" + report.plannerLearnedStatsCount() + ","
                + "\"incidentDeliveryDeadLetterCount\":" + report.incidentDeliveryDeadLetterCount() + ","
                + "\"incidentDeliveryClaimedCount\":" + report.incidentDeliveryClaimedCount() + ","
                + "\"redisUsedMemoryBytes\":" + report.redisUsedMemoryBytes() + ","
                + "\"redisUsedMemoryPeakBytes\":" + report.redisUsedMemoryPeakBytes() + ","
           + "\"redisMaxMemoryBytes\":" + report.redisMaxMemoryBytes() + ","
           + "\"compactionPendingCount\":" + report.compactionPendingCount() + ","
           + "\"compactionPayloadCount\":" + report.compactionPayloadCount() + ","
           + "\"hardRejectedWriteCount\":" + report.hardRejectedWriteCount() + ","
           + "\"producerHighPressureDelayCount\":" + report.producerHighPressureDelayCount() + ","
                + "\"producerCriticalPressureDelayCount\":" + report.producerCriticalPressureDelayCount() + ","
                + "\"redisPressureLevel\":\"" + escapeJson(report.redisPressureLevel()) + "\","
                + "\"degradeProfile\":\"" + escapeJson(report.degradeProfile()) + "\","
                + "\"finalRuntimeProfile\":\"" + escapeJson(report.finalRuntimeProfile()) + "\","
                + "\"runtimeProfileSwitchCount\":" + report.runtimeProfileSwitchCount() + ","
                + "\"runtimeProfileTimeline\":[" + report.runtimeProfileTimeline().stream().map(value -> "\"" + escapeJson(value) + "\"").collect(java.util.stream.Collectors.joining(",")) + "],"
                + "\"finalHealthStatus\":\"" + escapeJson(report.finalHealthStatus()) + "\","
                + "\"notes\":\"" + escapeJson(report.notes()) + "\""
                + "}";
        Files.writeString(path, json);
    }

    private void writeProfileChurnJson(Path path, ScenarioProfileChurnReport report) throws IOException {
        String json = "{"
                + "\"scenarioName\":\"" + escapeJson(report.scenarioName()) + "\","
                + "\"kind\":\"" + report.kind().name() + "\","
                + "\"scaleFactor\":" + report.scaleFactor() + ","
                + "\"degradeProfile\":\"" + escapeJson(report.degradeProfile()) + "\","
                + "\"finalRuntimeProfile\":\"" + escapeJson(report.finalRuntimeProfile()) + "\","
                + "\"runtimeProfileSwitchCount\":" + report.runtimeProfileSwitchCount() + ","
                + "\"finalHealthStatus\":\"" + escapeJson(report.finalHealthStatus()) + "\","
                + "\"entries\":["
                + report.entries().stream().map(this::profileChurnEntryJson).collect(Collectors.joining(","))
                + "]"
                + "}";
        Files.writeString(path, json);
    }

    private ScenarioProfileChurnReport toProfileChurnReport(
            ScenarioReport report,
            List<RedisRuntimeProfileEvent> runtimeProfileEvents
    ) {
        return new ScenarioProfileChurnReport(
                report.scenarioName(),
                report.kind(),
                report.scaleFactor(),
                report.degradeProfile(),
                report.finalRuntimeProfile(),
                report.runtimeProfileSwitchCount(),
                report.finalHealthStatus(),
                runtimeProfileEvents.stream()
                        .map(event -> new ScenarioProfileChurnEntry(
                                event.switchedAt(),
                                event.fromProfile(),
                                event.toProfile(),
                                event.pressureLevel(),
                                event.switchCount(),
                                event.usedMemoryBytes(),
                                event.writeBehindBacklog(),
                                event.compactionPendingCount()
                        ))
                        .toList()
        );
    }

    private String profileChurnEntryJson(ScenarioProfileChurnEntry entry) {
        return "{"
                + "\"switchedAt\":\"" + entry.switchedAt() + "\","
                + "\"fromProfile\":\"" + escapeJson(entry.fromProfile()) + "\","
                + "\"toProfile\":\"" + escapeJson(entry.toProfile()) + "\","
                + "\"pressureLevel\":\"" + escapeJson(entry.pressureLevel()) + "\","
                + "\"switchCount\":" + entry.switchCount() + ","
                + "\"usedMemoryBytes\":" + entry.usedMemoryBytes() + ","
                + "\"writeBehindBacklog\":" + entry.writeBehindBacklog() + ","
                + "\"compactionPendingCount\":" + entry.compactionPendingCount()
                + "}";
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private boolean isForcedDegradeProfileSelection() {
        return !System.getProperty(FORCE_DEGRADE_PROFILE_PROPERTY, "").trim().isEmpty();
    }

    private DegradeProfile resolveDegradeProfile() {
        String forcedProfile = System.getProperty(FORCE_DEGRADE_PROFILE_PROPERTY, "").trim();
        if (!forcedProfile.isEmpty()) {
            return DegradeProfile.parse(forcedProfile);
        }
        if (!AUTO_DEGRADE_PROFILE_ENABLED) {
            return DegradeProfile.standard();
        }
        if ((REDIS_USED_MEMORY_CRITICAL_BYTES > 0 && REDIS_USED_MEMORY_CRITICAL_BYTES <= 1_073_741_824L)
                || COMPACTION_PENDING_CRITICAL_THRESHOLD <= 1_000L) {
            return DegradeProfile.aggressive();
        }
        if ((REDIS_USED_MEMORY_CRITICAL_BYTES > 0 && REDIS_USED_MEMORY_CRITICAL_BYTES <= 2_147_483_648L)
                || COMPACTION_PENDING_CRITICAL_THRESHOLD <= 2_500L) {
            return DegradeProfile.balanced();
        }
        return DegradeProfile.standard();
    }

    private static final class Worker implements Callable<Void> {

        private final EcommerceScenarioProfile profile;
        private final WorkerMode mode;
        private final long endAtNanos;
        private final long nanosPerOperation;
        private final EntityRepository<EcomCustomerEntity, Long> customerRepository;
        private final EntityRepository<EcomProductEntity, Long> productRepository;
        private final EntityRepository<EcomInventoryEntity, Long> inventoryRepository;
        private final EntityRepository<EcomCartEntity, Long> cartRepository;
        private final EntityRepository<EcomOrderEntity, Long> orderRepository;
        private final java.util.function.LongSupplier writeBehindBacklogSupplier;
        private final AtomicLong cartIdSequence;
        private final AtomicLong orderIdSequence;
        private final ScenarioCounters counters;
        private long operationSinceSample;
        private long cachedBacklog;
        private long lastBacklogSampleAtMillis;

        private Worker(
                EcommerceScenarioProfile profile,
                WorkerMode mode,
                long endAtNanos,
                long nanosPerOperation,
                EntityRepository<EcomCustomerEntity, Long> customerRepository,
                EntityRepository<EcomProductEntity, Long> productRepository,
                EntityRepository<EcomInventoryEntity, Long> inventoryRepository,
                EntityRepository<EcomCartEntity, Long> cartRepository,
                EntityRepository<EcomOrderEntity, Long> orderRepository,
                java.util.function.LongSupplier writeBehindBacklogSupplier,
                AtomicLong cartIdSequence,
                AtomicLong orderIdSequence,
                ScenarioCounters counters
        ) {
            this.profile = profile;
            this.mode = mode;
            this.endAtNanos = endAtNanos;
            this.nanosPerOperation = nanosPerOperation;
            this.customerRepository = customerRepository;
            this.productRepository = productRepository;
            this.inventoryRepository = inventoryRepository;
            this.cartRepository = cartRepository;
            this.orderRepository = orderRepository;
            this.writeBehindBacklogSupplier = writeBehindBacklogSupplier;
            this.cartIdSequence = cartIdSequence;
            this.orderIdSequence = orderIdSequence;
            this.counters = counters;
        }

        @Override
        public Void call() {
            Random random = ThreadLocalRandom.current();
            long nextSlot = System.nanoTime();
            while (System.nanoTime() < endAtNanos) {
                long startedAt = System.nanoTime();
                boolean writeOperation = false;
                try {
                    int roll = random.nextInt(Math.max(1, profile.totalWeight()));
                    writeOperation = executeOperation(random, roll);
                } catch (RuntimeException exception) {
                    counters.failedOperations.incrementAndGet();
                } finally {
                    long latencyMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startedAt);
                    counters.executedOperations.incrementAndGet();
                    counters.totalLatencyMicros.addAndGet(latencyMicros);
                    counters.maxLatencyMicros.accumulateAndGet(latencyMicros, Math::max);
                    counters.latencyHistogram.record(latencyMicros);
                }
                if (writeOperation) {
                    applyBacklogAwareThrottle();
                }
                nextSlot += nanosPerOperation;
                long sleepNanos = nextSlot - System.nanoTime();
                if (sleepNanos > 0) {
                    LockSupport.parkNanos(sleepNanos);
                }
            }
            return null;
        }

        private void applyBacklogAwareThrottle() {
            if (!PRODUCER_BACKLOG_THROTTLING_ENABLED) {
                return;
            }
            long backlog = sampleBacklog();
            if (backlog >= PRODUCER_THROTTLE_CRITICAL_WATERMARK) {
                sleepQuietly(PRODUCER_THROTTLE_CRITICAL_SLEEP_MILLIS);
                return;
            }
            if (backlog >= PRODUCER_THROTTLE_HIGH_WATERMARK) {
                sleepQuietly(PRODUCER_THROTTLE_HIGH_SLEEP_MILLIS);
            }
        }

        private long sampleBacklog() {
            operationSinceSample++;
            long now = System.currentTimeMillis();
            if (operationSinceSample >= Math.max(1, PRODUCER_BACKLOG_SAMPLE_EVERY_OPERATIONS)
                    || now - lastBacklogSampleAtMillis >= Math.max(1L, PRODUCER_BACKLOG_SAMPLE_INTERVAL_MILLIS)) {
                cachedBacklog = writeBehindBacklogSupplier.getAsLong();
                operationSinceSample = 0L;
                lastBacklogSampleAtMillis = now;
            }
            return cachedBacklog;
        }

        private void sleepQuietly(long millis) {
            if (millis <= 0L) {
                return;
            }
            try {
                Thread.sleep(millis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        private boolean executeOperation(Random random, int roll) {
            if (mode == WorkerMode.READ_ONLY) {
                return executeReadOperation(random);
            }
            if (mode == WorkerMode.WRITE_ONLY) {
                return executeWriteOperation(random);
            }
            int cursor = profile.browsePercent();
            if (roll < cursor) {
                productRepository.findPage(new PageWindow(random.nextInt(0, Math.max(1, profile.productCount() / Math.max(1, profile.pageSize()))), Math.max(10, profile.pageSize())));
                counters.readOperations.incrementAndGet();
                return false;
            }
            cursor += profile.productLookupPercent();
            if (roll < cursor) {
                productRepository.findById(randomProductId(random));
                counters.readOperations.incrementAndGet();
                return false;
            }
            cursor += profile.cartWritePercent();
            if (roll < cursor) {
                EcomCartEntity cart = new EcomCartEntity();
                cart.id = cartIdSequence.incrementAndGet();
                cart.customerId = randomCustomerId(random);
                cart.sku = "SKU-" + randomProductId(random);
                cart.quantity = random.nextInt(1, 4);
                cart.state = "ACTIVE";
                cartRepository.save(cart);
                counters.writeOperations.incrementAndGet();
                return true;
            }
            cursor += profile.inventoryReservePercent();
            if (roll < cursor) {
                long inventoryId = hotProductId(random);
                EcomInventoryEntity inventory = inventoryRepository.findById(inventoryId).orElseGet(() -> newInventory(inventoryId));
                inventory.availableUnits = Math.max(0L, (inventory.availableUnits == null ? 0L : inventory.availableUnits) - 1L);
                inventory.reservedUnits = (inventory.reservedUnits == null ? 0L : inventory.reservedUnits) + 1L;
                inventoryRepository.save(inventory);
                counters.writeOperations.incrementAndGet();
                return true;
            }
            cursor += profile.checkoutPercent();
            if (roll < cursor) {
                long productId = hotProductId(random);
                EcomOrderEntity order = new EcomOrderEntity();
                order.id = orderIdSequence.incrementAndGet();
                order.customerId = randomCustomerId(random);
                order.sku = "SKU-" + productId;
                order.quantity = random.nextInt(1, 3);
                order.totalAmount = (25.0d + (productId % 500)) * order.quantity;
                order.status = "PLACED";
                order.campaignCode = "CMP-" + (order.customerId % 10);
                orderRepository.save(order);
                counters.writeOperations.incrementAndGet();
                return true;
            }

            long customerId = randomCustomerId(random);
            EcomCustomerEntity customer = customerRepository.findById(customerId).orElseGet(() -> newCustomer(customerId));
            customer.lastCampaignCode = "CMP-" + random.nextInt(1, 20);
            customer.segment = customerId % 2 == 0 ? "SMS_PUSH" : "PUSH";
            customerRepository.save(customer);
            counters.writeOperations.incrementAndGet();
            return true;
        }

        private boolean executeReadOperation(Random random) {
            int readWeight = Math.max(1, profile.browsePercent() + profile.productLookupPercent());
            int readRoll = random.nextInt(readWeight);
            if (readRoll < profile.browsePercent()) {
                productRepository.findPage(new PageWindow(random.nextInt(0, Math.max(1, profile.productCount() / Math.max(1, profile.pageSize()))), Math.max(10, profile.pageSize())));
            } else {
                productRepository.findById(randomProductId(random));
            }
            counters.readOperations.incrementAndGet();
            return false;
        }

        private boolean executeWriteOperation(Random random) {
            int writeWeight = Math.max(1, profile.cartWritePercent()
                    + profile.inventoryReservePercent()
                    + profile.checkoutPercent()
                    + profile.customerTouchPercent());
            int writeRoll = random.nextInt(writeWeight);
            int cursor = profile.cartWritePercent();
            if (writeRoll < cursor) {
                EcomCartEntity cart = new EcomCartEntity();
                cart.id = cartIdSequence.incrementAndGet();
                cart.customerId = randomCustomerId(random);
                cart.sku = "SKU-" + randomProductId(random);
                cart.quantity = random.nextInt(1, 4);
                cart.state = "ACTIVE";
                cartRepository.save(cart);
                counters.writeOperations.incrementAndGet();
                return true;
            }
            cursor += profile.inventoryReservePercent();
            if (writeRoll < cursor) {
                long inventoryId = hotProductId(random);
                EcomInventoryEntity inventory = inventoryRepository.findById(inventoryId).orElseGet(() -> newInventory(inventoryId));
                inventory.availableUnits = Math.max(0L, (inventory.availableUnits == null ? 0L : inventory.availableUnits) - 1L);
                inventory.reservedUnits = (inventory.reservedUnits == null ? 0L : inventory.reservedUnits) + 1L;
                inventoryRepository.save(inventory);
                counters.writeOperations.incrementAndGet();
                return true;
            }
            cursor += profile.checkoutPercent();
            if (writeRoll < cursor) {
                long productId = hotProductId(random);
                EcomOrderEntity order = new EcomOrderEntity();
                order.id = orderIdSequence.incrementAndGet();
                order.customerId = randomCustomerId(random);
                order.sku = "SKU-" + productId;
                order.quantity = random.nextInt(1, 3);
                order.totalAmount = (25.0d + (productId % 500)) * order.quantity;
                order.status = "PLACED";
                order.campaignCode = "CMP-" + (order.customerId % 10);
                orderRepository.save(order);
                counters.writeOperations.incrementAndGet();
                return true;
            }

            long customerId = randomCustomerId(random);
            EcomCustomerEntity customer = customerRepository.findById(customerId).orElseGet(() -> newCustomer(customerId));
            customer.lastCampaignCode = "CMP-" + random.nextInt(1, 20);
            customer.segment = customerId % 2 == 0 ? "SMS_PUSH" : "PUSH";
            customerRepository.save(customer);
            counters.writeOperations.incrementAndGet();
            return true;
        }

        private long randomCustomerId(Random random) {
            return 1L + random.nextInt(Math.max(1, profile.customerCount()));
        }

        private long randomProductId(Random random) {
            return 1L + random.nextInt(Math.max(1, profile.productCount()));
        }

        private long hotProductId(Random random) {
            return 1L + random.nextInt(Math.max(1, Math.min(profile.productCount(), profile.hotProductSetSize())));
        }

        private EcomInventoryEntity newInventory(long id) {
            EcomInventoryEntity inventory = new EcomInventoryEntity();
            inventory.id = id;
            inventory.sku = "SKU-" + id;
            inventory.warehouseCode = "WH-0";
            inventory.availableUnits = 10_000L;
            inventory.reservedUnits = 0L;
            return inventory;
        }

        private EcomCustomerEntity newCustomer(long id) {
            EcomCustomerEntity customer = new EcomCustomerEntity();
            customer.id = id;
            customer.status = "ACTIVE";
            customer.segment = "PUSH";
            customer.tier = "STANDARD";
            customer.lastCampaignCode = "BOOT";
            return customer;
        }
    }

    private record DegradeProfile(
            String name,
            double hotEntityLimitFactor,
            double pageSizeFactor,
            int entityTtlCapSeconds,
            int pageTtlCapSeconds,
            long highSleepMillisBonus,
            long criticalSleepMillisBonus
    ) {
        private static DegradeProfile standard() {
            return new DegradeProfile("STANDARD", 1.0d, 1.0d, Integer.MAX_VALUE, Integer.MAX_VALUE, 0L, 0L);
        }

        private static DegradeProfile balanced() {
            return new DegradeProfile("BALANCED", 0.75d, 0.85d, 1_800, 600, 1L, 3L);
        }

        private static DegradeProfile aggressive() {
            return new DegradeProfile("AGGRESSIVE", 0.55d, 0.70d, 900, 300, 3L, 6L);
        }

        private static DegradeProfile parse(String value) {
            return switch (value.trim().toUpperCase()) {
                case "STANDARD" -> standard();
                case "BALANCED" -> balanced();
                case "AGGRESSIVE" -> aggressive();
                default -> throw new IllegalArgumentException("Unsupported degrade profile: " + value);
            };
        }
    }

    private static final class ScenarioCounters {
        private static final long[] LATENCY_BUCKET_UPPER_BOUNDS_MICROS = new long[]{
                50L, 100L, 250L, 500L, 1_000L, 2_500L, 5_000L, 10_000L,
                20_000L, 50_000L, 100_000L, 250_000L, 500_000L, 1_000_000L,
                2_000_000L, 5_000_000L
        };

        private final long startedAt = System.currentTimeMillis();
        private final AtomicLong executedOperations = new AtomicLong();
        private final AtomicLong readOperations = new AtomicLong();
        private final AtomicLong writeOperations = new AtomicLong();
        private final AtomicLong failedOperations = new AtomicLong();
        private final AtomicLong totalLatencyMicros = new AtomicLong();
        private final AtomicLong maxLatencyMicros = new AtomicLong();
        private final LatencyHistogram latencyHistogram = new LatencyHistogram(LATENCY_BUCKET_UPPER_BOUNDS_MICROS);
        private volatile long finishedAt;

        private void finish() {
            finishedAt = System.currentTimeMillis();
        }

        private long elapsedMillis() {
            return Math.max(1L, (finishedAt == 0L ? System.currentTimeMillis() : finishedAt) - startedAt);
        }

        private long averageLatencyMicros() {
            long executed = Math.max(1L, executedOperations.get());
            return totalLatencyMicros.get() / executed;
        }

        private double achievedTransactionsPerSecond() {
            return (executedOperations.get() * 1_000.0d) / elapsedMillis();
        }

        private long p95LatencyMicros() {
            return latencyHistogram.percentileUpperBound(0.95d);
        }

        private long p99LatencyMicros() {
            return latencyHistogram.percentileUpperBound(0.99d);
        }
    }

    private record DrainResult(long drainMillis, boolean completed) {
    }

    private record WorkerAllocation(int readWorkers, long readTps, int writeWorkers, long writeTps) {
        private int totalWorkers() {
            return readWorkers + writeWorkers;
        }
    }

    private enum WorkerMode {
        READ_ONLY,
        WRITE_ONLY,
        MIXED
    }

    private static final class LatencyHistogram {
        private final long[] upperBoundsMicros;
        private final AtomicLongArray bucketCounts;
        private final AtomicLong overflowCount = new AtomicLong();
        private final AtomicLong totalCount = new AtomicLong();

        private LatencyHistogram(long[] upperBoundsMicros) {
            this.upperBoundsMicros = upperBoundsMicros;
            this.bucketCounts = new AtomicLongArray(upperBoundsMicros.length);
        }

        private void record(long latencyMicros) {
            totalCount.incrementAndGet();
            for (int index = 0; index < upperBoundsMicros.length; index++) {
                if (latencyMicros <= upperBoundsMicros[index]) {
                    bucketCounts.incrementAndGet(index);
                    return;
                }
            }
            overflowCount.incrementAndGet();
        }

        private long percentileUpperBound(double percentile) {
            long target = Math.max(1L, (long) Math.ceil(totalCount.get() * percentile));
            long seen = 0L;
            for (int index = 0; index < upperBoundsMicros.length; index++) {
                seen += bucketCounts.get(index);
                if (seen >= target) {
                    return upperBoundsMicros[index];
                }
            }
            return overflowCount.get() > 0L
                    ? upperBoundsMicros[upperBoundsMicros.length - 1]
                    : 0L;
        }
    }
}
