package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.api.CacheSession;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.AdminHttpConfig;
import com.reactor.cachedb.core.config.AdminMonitoringConfig;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.DeadLetterRecoveryConfig;
import com.reactor.cachedb.core.config.ProjectionRefreshConfig;
import com.reactor.cachedb.core.config.RuntimeCoordinationConfig;
import com.reactor.cachedb.core.config.SchemaBootstrapMode;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.page.EntityPageLoader;
import com.reactor.cachedb.core.page.NoOpEntityPageLoader;
import com.reactor.cachedb.core.query.QueryEvaluator;
import com.reactor.cachedb.core.queue.AdminReportJobSnapshot;
import com.reactor.cachedb.core.registry.DefaultEntityRegistry;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.registry.EntityRegistry;
import com.reactor.cachedb.core.queue.LatencyMetricSnapshot;
import com.reactor.cachedb.core.queue.StoragePerformanceSnapshot;
import com.reactor.cachedb.core.queue.StoragePerformanceCollector;
import com.reactor.cachedb.core.queue.DeadLetterManagement;
import com.reactor.cachedb.core.queue.ProjectionRefreshFailureEntry;
import com.reactor.cachedb.core.queue.ProjectionRefreshReplayResult;
import com.reactor.cachedb.core.queue.ProjectionRefreshSnapshot;
import com.reactor.cachedb.core.queue.RecoveryCleanupSnapshot;
import com.reactor.cachedb.core.queue.WriteBehindFlusher;
import com.reactor.cachedb.core.relation.NoOpRelationBatchLoader;
import com.reactor.cachedb.core.relation.RelationBatchLoader;
import com.reactor.cachedb.postgres.PostgresWriteBehindFlusher;
import com.reactor.cachedb.redis.RedisFunctionArgsMapper;
import com.reactor.cachedb.redis.RedisFunctionExecutor;
import com.reactor.cachedb.redis.RedisFunctionLibrarySource;
import com.reactor.cachedb.redis.RedisFunctionLoader;
import com.reactor.cachedb.redis.RedisDeadLetterManagement;
import com.reactor.cachedb.redis.RedisDeadLetterRecoveryWorker;
import com.reactor.cachedb.redis.RedisRecoveryCleanupWorker;
import com.reactor.cachedb.redis.RedisHotSetManager;
import com.reactor.cachedb.redis.RedisIndexMaintenance;
import com.reactor.cachedb.redis.RedisKeyStrategy;
import com.reactor.cachedb.redis.RedisLeaderLease;
import com.reactor.cachedb.redis.RedisProjectionRefreshQueue;
import com.reactor.cachedb.redis.RedisProjectionRefreshWorker;
import com.reactor.cachedb.redis.RedisProducerGuard;
import com.reactor.cachedb.redis.RedisWriteBehindQueue;
import com.reactor.cachedb.redis.RedisWriteBehindWorker;
import com.reactor.cachedb.redis.RedisWriteOperationMapper;
import com.reactor.cachedb.redis.ProjectionRefreshDispatcher;
import redis.clients.jedis.JedisPooled;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class CacheDatabase implements CacheSession, AutoCloseable {

    private final CacheDatabaseConfig config;
    private final DataSource dataSource;
    private final JedisPooled foregroundJedis;
    private final JedisPooled backgroundJedis;
    private final String instanceId;
    private final DefaultCacheSession session;
    private final EntityRegistry entityRegistry;
    private final RedisWriteBehindWorker writeBehindWorker;
    private final RedisDeadLetterRecoveryWorker deadLetterRecoveryWorker;
    private final RedisRecoveryCleanupWorker recoveryCleanupWorker;
    private final AdminReportJobWorker adminReportJobWorker;
    private final AdminIncidentDeliveryManager adminIncidentDeliveryManager;
    private final DeadLetterManagement deadLetterManagement;
    private final RedisFunctionLoader functionLoader;
    private final QueryEvaluator queryEvaluator;
    private final RedisProducerGuard producerGuard;
    private final RedisIndexMaintenance indexMaintenance;
    private final CacheDatabaseSchemaAdmin schemaAdmin;
    private final MonitoringHistoryBuffer monitoringHistoryBuffer;
    private final AlertRouteHistoryBuffer alertRouteHistoryBuffer;
    private final PerformanceHistoryBuffer performanceHistoryBuffer;
    private final StoragePerformanceCollector storagePerformanceCollector;
    private final RedisStoragePerformanceMirror storagePerformanceMirror;
    private final boolean adminMonitoringEnabled;
    private final ProjectionRefreshDispatcher projectionRefreshDispatcher;
    private final RedisProjectionRefreshQueue projectionRefreshQueue;
    private final RedisProjectionRefreshWorker projectionRefreshWorker;

    public static CacheDatabaseBootstrap bootstrap(DataSource dataSource) {
        return CacheDatabaseBootstrap.using(dataSource);
    }

    public static CacheDatabaseBootstrap bootstrap(JedisPooled jedis, DataSource dataSource) {
        return CacheDatabaseBootstrap.using(jedis, dataSource);
    }

    public static CacheDatabaseBootstrap bootstrap(
            JedisPooled foregroundJedis,
            JedisPooled backgroundJedis,
            DataSource dataSource
    ) {
        return CacheDatabaseBootstrap.using(foregroundJedis, backgroundJedis, dataSource);
    }

    public static CacheDatabaseBootstrap bootstrap(String redisUri, DataSource dataSource) {
        return CacheDatabaseBootstrap.connect(redisUri, dataSource);
    }

    public static CacheDatabaseBootstrap bootstrap(
            String foregroundRedisUri,
            String backgroundRedisUri,
            DataSource dataSource
    ) {
        return CacheDatabaseBootstrap.connect(foregroundRedisUri, backgroundRedisUri, dataSource);
    }

    public CacheDatabase(JedisPooled jedis, DataSource dataSource, CacheDatabaseConfig config) {
        this(jedis, jedis, dataSource, config);
    }

    public CacheDatabase(
            JedisPooled foregroundJedis,
            JedisPooled backgroundJedis,
            DataSource dataSource,
            CacheDatabaseConfig config
    ) {
        this.dataSource = dataSource;
        this.foregroundJedis = foregroundJedis;
        this.backgroundJedis = backgroundJedis;
        this.instanceId = RuntimeCoordinationSupport.resolveInstanceId(config.runtimeCoordination());
        RuntimeCoordinationConfig effectiveRuntimeCoordination = RuntimeCoordinationConfig.builder()
                .instanceId(instanceId)
                .appendInstanceIdToConsumerNames(config.runtimeCoordination().appendInstanceIdToConsumerNames())
                .leaderLeaseEnabled(config.runtimeCoordination().leaderLeaseEnabled())
                .leaderLeaseSegment(config.runtimeCoordination().leaderLeaseSegment())
                .leaderLeaseTtlMillis(config.runtimeCoordination().leaderLeaseTtlMillis())
                .leaderLeaseRenewIntervalMillis(config.runtimeCoordination().leaderLeaseRenewIntervalMillis())
                .build();
        this.entityRegistry = new DefaultEntityRegistry(config.resourceLimits());
        this.queryEvaluator = new QueryEvaluator();
        this.projectionRefreshDispatcher = new ProjectionRefreshDispatcher();
        this.adminMonitoringEnabled = config.adminMonitoring().enabled();
        WriteBehindConfig effectiveWriteBehindConfig = RuntimeCoordinationSupport.withInstanceIdentity(
                config.writeBehind(),
                config.runtimeCoordination(),
                instanceId
        );
        DeadLetterRecoveryConfig effectiveDeadLetterRecoveryConfig = RuntimeCoordinationSupport.withInstanceIdentity(
                config.deadLetterRecovery(),
                config.runtimeCoordination(),
                instanceId
        );
        ProjectionRefreshConfig effectiveProjectionRefreshConfig = RuntimeCoordinationSupport.withInstanceIdentity(
                config.projectionRefresh(),
                config.runtimeCoordination(),
                instanceId
        );
        AdminMonitoringConfig effectiveAdminMonitoringConfig = RuntimeCoordinationSupport.withInstanceIdentity(
                config.adminMonitoring(),
                config.runtimeCoordination(),
                instanceId
        );
        this.storagePerformanceCollector = adminMonitoringEnabled
                ? new StoragePerformanceCollector()
                : StoragePerformanceCollector.noop();
        this.storagePerformanceMirror = adminMonitoringEnabled
                ? new RedisStoragePerformanceMirror(storagePerformanceCollector, backgroundJedis, effectiveAdminMonitoringConfig)
                : RedisStoragePerformanceMirror.disabled(storagePerformanceCollector, effectiveAdminMonitoringConfig);

        RedisWriteOperationMapper mapper = new RedisWriteOperationMapper();
        RedisKeyStrategy keyStrategy = new RedisKeyStrategy(
                config.keyspace().keyPrefix(),
                config.keyspace().entitySegment(),
                config.keyspace().pageSegment(),
                config.keyspace().versionSegment(),
                config.keyspace().tombstoneSegment(),
                config.keyspace().hotSetSegment(),
                config.keyspace().indexSegment(),
                config.keyspace().compactionSegment()
        );
        RedisFunctionExecutor functionExecutor = new RedisFunctionExecutor(
                foregroundJedis,
                config.redisFunctions(),
                config.redisGuardrail(),
                new RedisFunctionArgsMapper()
        );
        RedisFunctionExecutor backgroundFunctionExecutor = new RedisFunctionExecutor(
                backgroundJedis,
                config.redisFunctions(),
                config.redisGuardrail(),
                new RedisFunctionArgsMapper()
        );
        RedisWriteBehindQueue queue = new RedisWriteBehindQueue(
                foregroundJedis,
                effectiveWriteBehindConfig.streamKey(),
                effectiveWriteBehindConfig.compactionStreamKey(),
                mapper,
                config.resourceLimits().maxColumnsPerOperation(),
                effectiveWriteBehindConfig,
                config.redisGuardrail(),
                keyStrategy
        );
        RedisHotSetManager hotSetManager = new RedisHotSetManager(foregroundJedis, keyStrategy);
        this.producerGuard = new RedisProducerGuard(
                backgroundJedis,
                config.redisGuardrail(),
                effectiveWriteBehindConfig,
                keyStrategy,
                config.adminReportJob().diagnosticsStreamKey(),
                config.adminReportJob().diagnosticsMaxLength()
        );
        this.indexMaintenance = new RedisIndexMaintenance(
                backgroundJedis,
                entityRegistry,
                keyStrategy,
                config.queryIndex(),
                config.relations(),
                queryEvaluator
        );
        this.projectionRefreshQueue = effectiveProjectionRefreshConfig.enabled()
                ? new RedisProjectionRefreshQueue(backgroundJedis, effectiveProjectionRefreshConfig)
                : null;
        this.projectionRefreshWorker = effectiveProjectionRefreshConfig.enabled()
                ? new RedisProjectionRefreshWorker(
                        backgroundJedis,
                        effectiveProjectionRefreshConfig,
                        projectionRefreshQueue,
                        keyStrategy,
                        entityRegistry,
                        config.queryIndex(),
                        config.relations(),
                        queryEvaluator,
                        producerGuard
                )
                : null;
        WriteBehindFlusher flusher = new PostgresWriteBehindFlusher(
                dataSource,
                entityRegistry,
                effectiveWriteBehindConfig,
                storagePerformanceCollector
        );
        this.functionLoader = new RedisFunctionLoader(
                backgroundJedis,
                config.redisFunctions(),
                new RedisFunctionLibrarySource()
        );

        CacheDatabaseConfig runtimeConfig = config.toBuilder()
                .writeBehind(effectiveWriteBehindConfig)
                .deadLetterRecovery(effectiveDeadLetterRecoveryConfig)
                .projectionRefresh(effectiveProjectionRefreshConfig)
                .adminMonitoring(effectiveAdminMonitoringConfig)
                .runtimeCoordination(effectiveRuntimeCoordination)
                .build();
        this.config = runtimeConfig;
        this.session = new DefaultCacheSession(
                foregroundJedis,
                backgroundJedis,
                runtimeConfig,
                entityRegistry,
                queue,
                keyStrategy,
                functionExecutor,
                producerGuard,
                hotSetManager,
                queryEvaluator,
                storagePerformanceCollector,
                projectionRefreshDispatcher,
                projectionRefreshQueue
        );
        this.writeBehindWorker = new RedisWriteBehindWorker(
                backgroundJedis,
                flusher,
                effectiveWriteBehindConfig,
                mapper,
                keyStrategy,
                backgroundFunctionExecutor
        );
        this.deadLetterRecoveryWorker = new RedisDeadLetterRecoveryWorker(
                backgroundJedis,
                flusher,
                effectiveDeadLetterRecoveryConfig,
                mapper,
                keyStrategy,
                effectiveWriteBehindConfig.deadLetterStreamKey()
        );
        RedisLeaderLease recoveryCleanupLeaderLease = RuntimeCoordinationSupport.leaderLease(
                backgroundJedis,
                config.keyspace().keyPrefix(),
                config.runtimeCoordination(),
                instanceId,
                "recovery-cleanup"
        );
        this.recoveryCleanupWorker = new RedisRecoveryCleanupWorker(
                backgroundJedis,
                effectiveDeadLetterRecoveryConfig,
                effectiveWriteBehindConfig.deadLetterStreamKey(),
                recoveryCleanupLeaderLease
        );
        this.deadLetterManagement = new RedisDeadLetterManagement(
                backgroundJedis,
                flusher,
                effectiveDeadLetterRecoveryConfig,
                mapper,
                keyStrategy,
                effectiveWriteBehindConfig.deadLetterStreamKey()
        );
        if (adminMonitoringEnabled) {
            RedisLeaderLease adminReportLeaderLease = RuntimeCoordinationSupport.leaderLease(
                    backgroundJedis,
                    config.keyspace().keyPrefix(),
                    config.runtimeCoordination(),
                    instanceId,
                    "admin-report-job"
            );
            RedisLeaderLease monitoringHistoryLeaderLease = RuntimeCoordinationSupport.leaderLease(
                    backgroundJedis,
                    config.keyspace().keyPrefix(),
                    config.runtimeCoordination(),
                    instanceId,
                    "monitoring-history"
            );
            RedisLeaderLease alertRouteHistoryLeaderLease = RuntimeCoordinationSupport.leaderLease(
                    backgroundJedis,
                    config.keyspace().keyPrefix(),
                    config.runtimeCoordination(),
                    instanceId,
                    "alert-route-history"
            );
            RedisLeaderLease performanceHistoryLeaderLease = RuntimeCoordinationSupport.leaderLease(
                    backgroundJedis,
                    config.keyspace().keyPrefix(),
                    config.runtimeCoordination(),
                    instanceId,
                    "performance-history"
            );
            this.adminIncidentDeliveryManager = new AdminIncidentDeliveryManager(effectiveAdminMonitoringConfig, backgroundJedis);
            this.adminReportJobWorker = new AdminReportJobWorker(
                    this::admin,
                    config.adminReportJob(),
                    adminReportLeaderLease,
                    "cachedb-admin-report-job-" + instanceId
            );
            this.monitoringHistoryBuffer = new MonitoringHistoryBuffer(
                    this::admin,
                    backgroundJedis,
                    effectiveAdminMonitoringConfig,
                    monitoringHistoryLeaderLease,
                    "cachedb-monitoring-history-" + instanceId
            );
            this.alertRouteHistoryBuffer = new AlertRouteHistoryBuffer(
                    this::admin,
                    backgroundJedis,
                    effectiveAdminMonitoringConfig,
                    alertRouteHistoryLeaderLease,
                    "cachedb-alert-route-history-" + instanceId
            );
            this.performanceHistoryBuffer = new PerformanceHistoryBuffer(
                    this::admin,
                    backgroundJedis,
                    effectiveAdminMonitoringConfig,
                    performanceHistoryLeaderLease,
                    "cachedb-performance-history-" + instanceId
            );
        } else {
            this.adminIncidentDeliveryManager = AdminIncidentDeliveryManager.disabled(effectiveAdminMonitoringConfig);
            this.adminReportJobWorker = AdminReportJobWorker.disabled(config.adminReportJob());
            this.monitoringHistoryBuffer = MonitoringHistoryBuffer.disabled(effectiveAdminMonitoringConfig);
            this.alertRouteHistoryBuffer = AlertRouteHistoryBuffer.disabled(effectiveAdminMonitoringConfig);
            this.performanceHistoryBuffer = PerformanceHistoryBuffer.disabled(effectiveAdminMonitoringConfig);
        }
        this.schemaAdmin = new CacheDatabaseSchemaAdmin(dataSource, entityRegistry, config.schemaBootstrap());
    }

    public void start() {
        if (config.schemaBootstrap().autoApplyOnStart()
                && config.schemaBootstrap().mode() != SchemaBootstrapMode.DISABLED) {
            schemaAdmin.applyConfiguredMode();
        }
        functionLoader.initialize();
        writeBehindWorker.start();
        deadLetterRecoveryWorker.start();
        recoveryCleanupWorker.start();
        if (adminMonitoringEnabled) {
            adminIncidentDeliveryManager.start();
            adminReportJobWorker.start();
            monitoringHistoryBuffer.start();
            alertRouteHistoryBuffer.start();
            performanceHistoryBuffer.start();
        }
        if (projectionRefreshWorker != null) {
            projectionRefreshWorker.start();
        }
    }

    public <T, ID> EntityBinding<T, ID> register(
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            CachePolicy cachePolicy
    ) {
        return entityRegistry.register(
                metadata,
                codec,
                cachePolicy,
                new NoOpRelationBatchLoader<>(),
                new NoOpEntityPageLoader<>()
        );
    }

    public <T, ID> EntityBinding<T, ID> register(
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            CachePolicy cachePolicy,
            RelationBatchLoader<T> relationBatchLoader
    ) {
        return entityRegistry.register(
                metadata,
                codec,
                cachePolicy,
                relationBatchLoader,
                new NoOpEntityPageLoader<>()
        );
    }

    public <T, ID> EntityBinding<T, ID> register(
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            CachePolicy cachePolicy,
            RelationBatchLoader<T> relationBatchLoader,
            EntityPageLoader<T> pageLoader
    ) {
        return entityRegistry.register(metadata, codec, cachePolicy, relationBatchLoader, pageLoader);
    }

    public <T, ID> EntityBinding<T, ID> register(
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec
    ) {
        return register(
                metadata,
                codec,
                config.resourceLimits().defaultCachePolicy(),
                new NoOpRelationBatchLoader<>(),
                new NoOpEntityPageLoader<>()
        );
    }

    public <T, ID> EntityBinding<T, ID> register(EntityBinding<T, ID> binding) {
        return register(
                binding.metadata(),
                binding.codec(),
                binding.cachePolicy(),
                binding.relationBatchLoader(),
                binding.pageLoader()
        );
    }

    @Override
    public <T, ID> EntityRepository<T, ID> repository(
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec
    ) {
        return session.repository(metadata, codec);
    }

    @Override
    public <T, ID> EntityRepository<T, ID> repository(
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            CachePolicy cachePolicy
    ) {
        return session.repository(metadata, codec, cachePolicy);
    }

    public EntityRegistry entityRegistry() {
        return entityRegistry;
    }

    public com.reactor.cachedb.core.queue.WriteBehindWorkerSnapshot workerSnapshot() {
        return writeBehindWorker.snapshot();
    }

    public com.reactor.cachedb.core.queue.DeadLetterRecoverySnapshot deadLetterRecoverySnapshot() {
        return deadLetterRecoveryWorker.snapshot();
    }

    public DeadLetterManagement deadLetterManagement() {
        return deadLetterManagement;
    }

    public RecoveryCleanupSnapshot recoveryCleanupSnapshot() {
        return recoveryCleanupWorker.snapshot();
    }

    public AdminReportJobSnapshot adminReportJobSnapshot() {
        return adminReportJobWorker.snapshot();
    }

    public com.reactor.cachedb.core.queue.IncidentDeliverySnapshot incidentDeliverySnapshot() {
        return adminIncidentDeliveryManager.snapshot();
    }

    public com.reactor.cachedb.core.queue.RedisGuardrailSnapshot redisGuardrailSnapshot() {
        return producerGuard.snapshot();
    }

    public com.reactor.cachedb.core.queue.RedisRuntimeProfileSnapshot redisRuntimeProfileSnapshot() {
        return producerGuard.runtimeProfileSnapshot();
    }

    public java.util.List<com.reactor.cachedb.core.queue.RedisRuntimeProfileEvent> runtimeProfileEvents() {
        return producerGuard.runtimeProfileEvents();
    }

    public Map<String, String> activeRuntimeProfileProperties() {
        return producerGuard.activeRuntimeProfileProperties();
    }

    public StoragePerformanceSnapshot storagePerformanceSnapshot() {
        return storagePerformanceMirror.snapshot();
    }

    public StoragePerformanceCollector storagePerformanceCollector() {
        return storagePerformanceCollector;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public StoragePerformanceSnapshot resetStoragePerformance() {
        return storagePerformanceMirror.reset();
    }

    public ProjectionRefreshSnapshot projectionRefreshSnapshot() {
        return projectionRefreshWorker == null ? ProjectionRefreshSnapshot.empty() : projectionRefreshWorker.snapshot();
    }

    public List<ProjectionRefreshFailureEntry> projectionRefreshFailures(int limit) {
        return projectionRefreshWorker == null ? List.of() : projectionRefreshWorker.failures(limit);
    }

    public ProjectionRefreshReplayResult replayProjectionRefreshFailure(String entryId) {
        return projectionRefreshWorker == null
                ? new ProjectionRefreshReplayResult(entryId == null ? "" : entryId, false, "", "Projection refresh worker is not enabled.")
                : projectionRefreshWorker.replayFailure(entryId);
    }

    public String manualRuntimeProfileOverride() {
        return producerGuard.manualRuntimeProfileOverride();
    }

    public void setManualRuntimeProfile(String profile) {
        producerGuard.setManualRuntimeProfile(profile);
    }

    public void clearManualRuntimeProfileOverride() {
        producerGuard.clearManualRuntimeProfileOverride();
    }

    public java.util.List<MonitoringHistoryPoint> monitoringHistory(int limit) {
        return monitoringHistoryBuffer.history(limit);
    }

    public java.util.List<AlertRouteHistoryPoint> alertRouteHistory(int limit) {
        return alertRouteHistoryBuffer.history(limit);
    }

    public java.util.List<PerformanceHistoryPoint> performanceHistory(int limit) {
        return performanceHistoryBuffer.history(limit);
    }

    public AdminTelemetryResetSnapshot resetAdminTelemetryBuffers() {
        StoragePerformanceSnapshot performanceReset;
        try {
            performanceReset = resetStoragePerformance();
        } catch (RuntimeException ignored) {
            performanceReset = emptyPerformanceSnapshot();
        }
        int monitoringHistoryCleared;
        try {
            monitoringHistoryCleared = monitoringHistoryBuffer.clear();
        } catch (RuntimeException ignored) {
            monitoringHistoryCleared = 0;
        }
        int alertRouteHistoryCleared;
        try {
            alertRouteHistoryCleared = alertRouteHistoryBuffer.clear();
        } catch (RuntimeException ignored) {
            alertRouteHistoryCleared = 0;
        }
        return new AdminTelemetryResetSnapshot(
                0L,
                0L,
                monitoringHistoryCleared,
                alertRouteHistoryCleared,
                totalOperations(performanceReset),
                java.time.Instant.now()
        );
    }

    public PerformanceTelemetryResetSnapshot resetPerformanceTelemetry() {
        StoragePerformanceSnapshot performanceReset;
        try {
            performanceReset = resetStoragePerformance();
        } catch (RuntimeException ignored) {
            performanceReset = emptyPerformanceSnapshot();
        }
        int performanceHistoryCleared;
        try {
            performanceHistoryCleared = performanceHistoryBuffer.clear();
        } catch (RuntimeException ignored) {
            performanceHistoryCleared = 0;
        }
        return new PerformanceTelemetryResetSnapshot(
                totalOperations(performanceReset),
                performanceHistoryCleared,
                java.time.Instant.now()
        );
    }

    private long totalOperations(StoragePerformanceSnapshot snapshot) {
        return snapshot.redisRead().operationCount()
                + snapshot.redisWrite().operationCount()
                + snapshot.postgresRead().operationCount()
                + snapshot.postgresWrite().operationCount();
    }

    private StoragePerformanceSnapshot emptyPerformanceSnapshot() {
        return new StoragePerformanceSnapshot(
                LatencyMetricSnapshot.empty(),
                LatencyMetricSnapshot.empty(),
                LatencyMetricSnapshot.empty(),
                LatencyMetricSnapshot.empty()
        );
    }

    public CacheDatabaseAdmin admin() {
        return new CacheDatabaseAdmin(
                deadLetterManagement,
                backgroundJedis,
                config.keyspace().keyPrefix(),
                config.writeBehind().activeStreamKeys(),
                config.writeBehind().deadLetterStreamKey(),
                config.deadLetterRecovery().reconciliationStreamKey(),
                config.deadLetterRecovery().archiveStreamKey(),
                config.adminReportJob().diagnosticsStreamKey(),
                config.adminReportJob().diagnosticsMaxLength(),
                config.adminMonitoring(),
                config.redisGuardrail(),
                this::workerSnapshot,
                this::deadLetterRecoverySnapshot,
                this::recoveryCleanupSnapshot,
                this::adminReportJobSnapshot,
                this::incidentDeliverySnapshot,
                this::redisGuardrailSnapshot,
                this::redisRuntimeProfileSnapshot,
                this::storagePerformanceSnapshot,
                this::projectionRefreshSnapshot,
                this::activeRuntimeProfileProperties,
                this::manualRuntimeProfileOverride,
                this::setManualRuntimeProfile,
                this::clearManualRuntimeProfileOverride,
                indexMaintenance,
                schemaAdmin,
                adminIncidentDeliveryManager::enqueue,
                dataSource,
                config,
                entityRegistry,
                session,
                new ProductionReportCatalog(ProductionReportCatalog.defaultRoots(Path.of("").toAbsolutePath())),
                this::monitoringHistory,
                this::alertRouteHistory,
                this::performanceHistory,
                this::projectionRefreshFailures,
                this::replayProjectionRefreshFailure,
                this::resetAdminTelemetryBuffers,
                this::resetPerformanceTelemetry
        );
    }

    public CacheDatabaseDebug debug() {
        return new CacheDatabaseDebug(entityRegistry, session);
    }

    public CacheDatabaseSchemaAdmin schema() {
        return schemaAdmin;
    }

    public CacheDatabaseConfig config() {
        return config;
    }

    public String instanceId() {
        return instanceId;
    }

    public CacheDatabaseAdminHttpServer adminHttpServer() {
        return adminHttpServer(config.adminHttp());
    }

    public CacheDatabaseAdminHttpServer adminHttpServer(AdminHttpConfig adminHttpConfig) {
        return new CacheDatabaseAdminHttpServer(this, adminHttpConfig);
    }

    @Override
    public void close() {
        writeBehindWorker.close();
        deadLetterRecoveryWorker.close();
        recoveryCleanupWorker.close();
        adminIncidentDeliveryManager.close();
        adminReportJobWorker.close();
        monitoringHistoryBuffer.close();
        alertRouteHistoryBuffer.close();
        performanceHistoryBuffer.close();
        if (projectionRefreshWorker != null) {
            projectionRefreshWorker.close();
        }
        projectionRefreshDispatcher.close();
    }
}
