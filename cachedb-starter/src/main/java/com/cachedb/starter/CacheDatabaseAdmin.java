package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.AdminMonitoringConfig;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.RedisGuardrailConfig;
import com.reactor.cachedb.core.api.CacheSession;
import com.reactor.cachedb.core.page.NoOpEntityPageLoader;
import com.reactor.cachedb.core.queue.AdminDiagnosticsRecord;
import com.reactor.cachedb.core.queue.AdminAlertRule;
import com.reactor.cachedb.core.queue.AdminHealthStatus;
import com.reactor.cachedb.core.queue.AdminIncident;
import com.reactor.cachedb.core.queue.AdminIncidentRecord;
import com.reactor.cachedb.core.queue.AdminIncidentSeverity;
import com.reactor.cachedb.core.queue.AdminExportFormat;
import com.reactor.cachedb.core.queue.AdminExportResult;
import com.reactor.cachedb.core.queue.IncidentDeliverySnapshot;
import com.reactor.cachedb.core.queue.PlannerStatisticsSnapshot;
import com.reactor.cachedb.core.queue.ProjectionRefreshFailureEntry;
import com.reactor.cachedb.core.queue.ProjectionRefreshReplayResult;
import com.reactor.cachedb.core.queue.ProjectionRefreshSnapshot;
import com.reactor.cachedb.core.queue.AdminReportJobSnapshot;
import com.reactor.cachedb.core.queue.BulkDeadLetterActionResult;
import com.reactor.cachedb.core.queue.DeadLetterActionResult;
import com.reactor.cachedb.core.queue.DeadLetterArchiveRecord;
import com.reactor.cachedb.core.queue.DeadLetterEntry;
import com.reactor.cachedb.core.queue.DeadLetterManagement;
import com.reactor.cachedb.core.queue.DeadLetterQuery;
import com.reactor.cachedb.core.queue.DeadLetterReplayPreview;
import com.reactor.cachedb.core.queue.DeadLetterRecoverySnapshot;
import com.reactor.cachedb.core.queue.ReconciliationQuery;
import com.reactor.cachedb.core.queue.ReconciliationHealth;
import com.reactor.cachedb.core.queue.ReconciliationMetrics;
import com.reactor.cachedb.core.queue.ReconciliationRecord;
import com.reactor.cachedb.core.queue.RedisGuardrailSnapshot;
import com.reactor.cachedb.core.queue.RedisRuntimeProfileSnapshot;
import com.reactor.cachedb.core.queue.RecoveryCleanupSnapshot;
import com.reactor.cachedb.core.queue.RuntimeProfileChurnRecord;
import com.reactor.cachedb.core.queue.SchemaMigrationPlan;
import com.reactor.cachedb.core.queue.SchemaMigrationStep;
import com.reactor.cachedb.core.queue.StoragePerformanceSnapshot;
import com.reactor.cachedb.core.queue.StreamPage;
import com.reactor.cachedb.core.queue.QueryIndexRebuildResult;
import com.reactor.cachedb.core.queue.WriteBehindWorkerSnapshot;
import com.reactor.cachedb.redis.RedisIndexMaintenance;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.resps.StreamEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.registry.EntityRegistry;
import com.reactor.cachedb.core.relation.NoOpRelationBatchLoader;

public final class CacheDatabaseAdmin {

    private final DeadLetterManagement deadLetterManagement;
    private final JedisPooled jedis;
    private final String keyPrefix;
    private final List<String> writeBehindStreamKeys;
    private final String deadLetterStreamKey;
    private final String reconciliationStreamKey;
    private final String archiveStreamKey;
    private final String diagnosticsStreamKey;
    private final long diagnosticsMaxLength;
    private final AdminMonitoringConfig monitoringConfig;
    private final RedisGuardrailConfig redisGuardrailConfig;
    private final Supplier<WriteBehindWorkerSnapshot> writeBehindSnapshotSupplier;
    private final Supplier<DeadLetterRecoverySnapshot> deadLetterRecoverySnapshotSupplier;
    private final Supplier<RecoveryCleanupSnapshot> recoveryCleanupSnapshotSupplier;
    private final Supplier<AdminReportJobSnapshot> adminReportJobSnapshotSupplier;
    private final Supplier<IncidentDeliverySnapshot> incidentDeliverySnapshotSupplier;
    private final Supplier<RedisGuardrailSnapshot> redisGuardrailSnapshotSupplier;
    private final Supplier<RedisRuntimeProfileSnapshot> redisRuntimeProfileSnapshotSupplier;
    private final Supplier<StoragePerformanceSnapshot> storagePerformanceSnapshotSupplier;
    private final Supplier<ProjectionRefreshSnapshot> projectionRefreshSnapshotSupplier;
    private final Supplier<Map<String, String>> runtimeProfilePropertySupplier;
    private final Supplier<String> manualRuntimeProfileOverrideSupplier;
    private final Consumer<String> runtimeProfileOverrideSetter;
    private final Runnable runtimeProfileOverrideClearer;
    private final RedisIndexMaintenance indexMaintenance;
    private final CacheDatabaseSchemaAdmin schemaAdmin;
    private final Consumer<AdminIncidentRecord> incidentNotifier;
    private final CacheDatabaseConfig cacheDatabaseConfig;
    private final EntityRegistry entityRegistry;
    private final ProductionReportCatalog productionReportCatalog;
    private final MigrationPlanner migrationPlanner;
    private final MigrationSchemaDiscovery migrationSchemaDiscovery;
    private final MigrationScaffoldGenerator migrationScaffoldGenerator;
    private final MigrationWarmRunner migrationWarmRunner;
    private final MigrationComparisonRunner migrationComparisonRunner;
    private volatile MigrationPlannerDemoSupport migrationPlannerDemoSupport;
    private final java.util.function.IntFunction<List<MonitoringHistoryPoint>> monitoringHistorySupplier;
    private final java.util.function.IntFunction<List<AlertRouteHistoryPoint>> alertRouteHistorySupplier;
    private final java.util.function.IntFunction<List<PerformanceHistoryPoint>> performanceHistorySupplier;
    private final java.util.function.IntFunction<List<ProjectionRefreshFailureEntry>> projectionRefreshFailureSupplier;
    private final Function<String, ProjectionRefreshReplayResult> projectionRefreshReplayFunction;
    private final java.util.function.Supplier<AdminTelemetryResetSnapshot> telemetryResetSupplier;
    private final java.util.function.Supplier<PerformanceTelemetryResetSnapshot> performanceResetSupplier;
    private final AtomicReference<List<AdminIncidentRecord>> lastIncidentHistory = new AtomicReference<>(List.of());
    private final AtomicReference<List<FailingSignalSnapshot>> lastFailingSignals = new AtomicReference<>(List.of());
    private final AtomicReference<ReconciliationMetrics> lastMetricsSnapshot = new AtomicReference<>();
    private final AtomicLong lastMetricsSampleAtEpochMillis = new AtomicLong();
    private final AtomicReference<List<AdminIncident>> lastIncidentsSnapshot = new AtomicReference<>();
    private final AtomicLong lastIncidentsSampleAtEpochMillis = new AtomicLong();
    private final AtomicReference<List<String>> lastDegradedQueryIndexesSnapshot = new AtomicReference<>();
    private final AtomicLong lastDegradedQueryIndexesSampleAtEpochMillis = new AtomicLong();
    private final AtomicReference<List<AdminDiagnosticsRecord>> lastDiagnosticsSnapshot = new AtomicReference<>(List.of());
    private final AtomicLong lastDiagnosticsSampleAtEpochMillis = new AtomicLong();
    private final AtomicReference<List<RuntimeProfileChurnRecord>> lastRuntimeProfileChurnSnapshot = new AtomicReference<>(List.of());
    private final AtomicLong lastRuntimeProfileChurnSampleAtEpochMillis = new AtomicLong();
    private final AtomicReference<SchemaStatusSnapshot> lastSchemaStatusSnapshot = new AtomicReference<>();
    private final AtomicLong lastSchemaStatusSampleAtEpochMillis = new AtomicLong();
    private final AtomicReference<List<MonitoringServiceSnapshot>> lastMonitoringServicesSnapshot = new AtomicReference<>();
    private final AtomicLong lastMonitoringServicesSampleAtEpochMillis = new AtomicLong();
    private final AtomicReference<List<BackgroundWorkerErrorSnapshot>> lastBackgroundWorkerErrorsSnapshot = new AtomicReference<>();
    private final AtomicLong lastBackgroundWorkerErrorsSampleAtEpochMillis = new AtomicLong();
    private final AtomicReference<MonitoringTriageSnapshot> lastTriageSnapshot = new AtomicReference<>();
    private final AtomicLong lastTriageSampleAtEpochMillis = new AtomicLong();
    private final AtomicReference<List<AlertRouteSnapshot>> lastAlertRoutesSnapshot = new AtomicReference<>();
    private final AtomicLong lastAlertRoutesSampleAtEpochMillis = new AtomicLong();

    public CacheDatabaseAdmin(
            DeadLetterManagement deadLetterManagement,
            JedisPooled jedis,
            String keyPrefix,
            List<String> writeBehindStreamKeys,
            String deadLetterStreamKey,
            String reconciliationStreamKey,
            String archiveStreamKey,
            String diagnosticsStreamKey,
            long diagnosticsMaxLength,
            AdminMonitoringConfig monitoringConfig,
            RedisGuardrailConfig redisGuardrailConfig,
            Supplier<WriteBehindWorkerSnapshot> writeBehindSnapshotSupplier,
            Supplier<DeadLetterRecoverySnapshot> deadLetterRecoverySnapshotSupplier,
            Supplier<RecoveryCleanupSnapshot> recoveryCleanupSnapshotSupplier,
            Supplier<AdminReportJobSnapshot> adminReportJobSnapshotSupplier,
            Supplier<IncidentDeliverySnapshot> incidentDeliverySnapshotSupplier,
            Supplier<RedisGuardrailSnapshot> redisGuardrailSnapshotSupplier,
            Supplier<RedisRuntimeProfileSnapshot> redisRuntimeProfileSnapshotSupplier,
            Supplier<StoragePerformanceSnapshot> storagePerformanceSnapshotSupplier,
            Supplier<ProjectionRefreshSnapshot> projectionRefreshSnapshotSupplier,
            Supplier<Map<String, String>> runtimeProfilePropertySupplier,
            Supplier<String> manualRuntimeProfileOverrideSupplier,
            Consumer<String> runtimeProfileOverrideSetter,
            Runnable runtimeProfileOverrideClearer,
            RedisIndexMaintenance indexMaintenance,
            CacheDatabaseSchemaAdmin schemaAdmin,
            Consumer<AdminIncidentRecord> incidentNotifier,
            DataSource dataSource,
            CacheDatabaseConfig cacheDatabaseConfig,
            EntityRegistry entityRegistry,
            CacheSession cacheSession,
            ProductionReportCatalog productionReportCatalog,
            java.util.function.IntFunction<List<MonitoringHistoryPoint>> monitoringHistorySupplier,
            java.util.function.IntFunction<List<AlertRouteHistoryPoint>> alertRouteHistorySupplier,
            java.util.function.IntFunction<List<PerformanceHistoryPoint>> performanceHistorySupplier,
            java.util.function.IntFunction<List<ProjectionRefreshFailureEntry>> projectionRefreshFailureSupplier,
            Function<String, ProjectionRefreshReplayResult> projectionRefreshReplayFunction,
            java.util.function.Supplier<AdminTelemetryResetSnapshot> telemetryResetSupplier,
            java.util.function.Supplier<PerformanceTelemetryResetSnapshot> performanceResetSupplier
    ) {
        this.deadLetterManagement = deadLetterManagement;
        this.jedis = jedis;
        this.keyPrefix = keyPrefix;
        this.writeBehindStreamKeys = List.copyOf(writeBehindStreamKeys);
        this.deadLetterStreamKey = deadLetterStreamKey;
        this.reconciliationStreamKey = reconciliationStreamKey;
        this.archiveStreamKey = archiveStreamKey;
        this.diagnosticsStreamKey = diagnosticsStreamKey;
        this.diagnosticsMaxLength = diagnosticsMaxLength;
        this.monitoringConfig = monitoringConfig;
        this.redisGuardrailConfig = redisGuardrailConfig;
        this.writeBehindSnapshotSupplier = writeBehindSnapshotSupplier;
        this.deadLetterRecoverySnapshotSupplier = deadLetterRecoverySnapshotSupplier;
        this.recoveryCleanupSnapshotSupplier = recoveryCleanupSnapshotSupplier;
        this.adminReportJobSnapshotSupplier = adminReportJobSnapshotSupplier;
        this.incidentDeliverySnapshotSupplier = incidentDeliverySnapshotSupplier;
        this.redisGuardrailSnapshotSupplier = redisGuardrailSnapshotSupplier;
        this.redisRuntimeProfileSnapshotSupplier = redisRuntimeProfileSnapshotSupplier;
        this.storagePerformanceSnapshotSupplier = storagePerformanceSnapshotSupplier;
        this.projectionRefreshSnapshotSupplier = projectionRefreshSnapshotSupplier;
        this.runtimeProfilePropertySupplier = runtimeProfilePropertySupplier;
        this.manualRuntimeProfileOverrideSupplier = manualRuntimeProfileOverrideSupplier;
        this.runtimeProfileOverrideSetter = runtimeProfileOverrideSetter;
        this.runtimeProfileOverrideClearer = runtimeProfileOverrideClearer;
        this.indexMaintenance = indexMaintenance;
        this.schemaAdmin = schemaAdmin;
        this.incidentNotifier = incidentNotifier;
        this.cacheDatabaseConfig = cacheDatabaseConfig;
        this.entityRegistry = entityRegistry;
        this.productionReportCatalog = productionReportCatalog;
        this.migrationPlanner = new MigrationPlanner();
        this.migrationSchemaDiscovery = new MigrationSchemaDiscovery(dataSource, entityRegistry);
        this.migrationWarmRunner = new MigrationWarmRunner(dataSource, MigrationWarmRunner.using(entityRegistry, cacheSession));
        this.migrationScaffoldGenerator = new MigrationScaffoldGenerator(this.migrationSchemaDiscovery);
        this.migrationComparisonRunner = new MigrationComparisonRunner(
                dataSource,
                this.migrationSchemaDiscovery,
                this.migrationWarmRunner,
                MigrationComparisonRunner.using(entityRegistry, cacheSession)
        );
        this.migrationPlannerDemoSupport = null;
        this.monitoringHistorySupplier = monitoringHistorySupplier;
        this.alertRouteHistorySupplier = alertRouteHistorySupplier;
        this.performanceHistorySupplier = performanceHistorySupplier;
        this.projectionRefreshFailureSupplier = projectionRefreshFailureSupplier;
        this.projectionRefreshReplayFunction = projectionRefreshReplayFunction;
        this.telemetryResetSupplier = telemetryResetSupplier;
        this.performanceResetSupplier = performanceResetSupplier;
    }

    public StreamPage<DeadLetterEntry> deadLetters(DeadLetterQuery query) {
        return deadLetterManagement.queryDeadLetters(query);
    }

    public StreamPage<ReconciliationRecord> reconciliation(ReconciliationQuery query) {
        return deadLetterManagement.queryReconciliation(query);
    }

    public StreamPage<DeadLetterArchiveRecord> archive(ReconciliationQuery query) {
        return deadLetterManagement.queryArchive(query);
    }

    public Optional<DeadLetterEntry> deadLetter(String entryId) {
        return deadLetterManagement.getDeadLetter(entryId);
    }

    public DeadLetterReplayPreview dryRunReplay(String entryId) {
        return deadLetterManagement.dryRunReplay(entryId);
    }

    public DeadLetterActionResult replay(String entryId, String note) {
        return deadLetterManagement.replay(entryId, note);
    }

    public DeadLetterActionResult skip(String entryId, String note) {
        return deadLetterManagement.skip(entryId, note);
    }

    public DeadLetterActionResult close(String entryId, String note) {
        return deadLetterManagement.close(entryId, note);
    }

    public BulkDeadLetterActionResult bulkReplay(List<String> entryIds, String note) {
        return deadLetterManagement.bulkReplay(entryIds, note);
    }

    public BulkDeadLetterActionResult bulkSkip(List<String> entryIds, String note) {
        return deadLetterManagement.bulkSkip(entryIds, note);
    }

    public BulkDeadLetterActionResult bulkClose(List<String> entryIds, String note) {
        return deadLetterManagement.bulkClose(entryIds, note);
    }

    public AdminExportResult exportDeadLetters(DeadLetterQuery query, AdminExportFormat format) {
        List<DeadLetterEntry> items = collectDeadLetters(query);
        return new AdminExportResult(
                "dead-letters." + format.fileExtension(),
                format,
                format.contentType(),
                renderDeadLetters(items, query, format)
        );
    }

    public AdminExportResult exportReconciliation(ReconciliationQuery query, AdminExportFormat format) {
        List<ReconciliationRecord> items = collectReconciliation(query);
        return new AdminExportResult(
                "reconciliation." + format.fileExtension(),
                format,
                format.contentType(),
                renderReconciliation(items, query, format)
        );
    }

    public AdminExportResult exportArchive(ReconciliationQuery query, AdminExportFormat format) {
        List<DeadLetterArchiveRecord> items = collectArchive(query);
        return new AdminExportResult(
                "archive." + format.fileExtension(),
                format,
                format.contentType(),
                renderArchive(items, query, format)
        );
    }

    public ReconciliationMetrics metrics() {
        long now = System.currentTimeMillis();
        ReconciliationMetrics cached = lastMetricsSnapshot.get();
        if (cached != null && (now - lastMetricsSampleAtEpochMillis.get()) < 1_000L) {
            return cached;
        }
        try {
            ReconciliationMetrics fresh = collectMetrics();
            lastMetricsSnapshot.set(fresh);
            lastMetricsSampleAtEpochMillis.set(now);
            return fresh;
        } catch (RuntimeException exception) {
            if (cached != null) {
                return cached;
            }
            throw exception;
        }
    }

    private <T> T cachedSnapshot(
            AtomicReference<T> reference,
            AtomicLong sampledAtEpochMillis,
            long ttlMillis,
            Supplier<T> supplier
    ) {
        long now = System.currentTimeMillis();
        T cached = reference.get();
        if (cached != null && (now - sampledAtEpochMillis.get()) < ttlMillis) {
            return cached;
        }
        try {
            T fresh = supplier.get();
            reference.set(fresh);
            sampledAtEpochMillis.set(now);
            return fresh;
        } catch (RuntimeException exception) {
            if (cached != null) {
                return cached;
            }
            throw exception;
        }
    }

    public StoragePerformanceSnapshot storagePerformance() {
        return storagePerformanceSnapshotSupplier.get();
    }

    public ProjectionRefreshSnapshot projectionRefresh() {
        ProjectionRefreshSnapshot snapshot = projectionRefreshSnapshotSupplier.get();
        return snapshot == null ? ProjectionRefreshSnapshot.empty() : snapshot;
    }

    public List<ProjectionRefreshFailureEntry> projectionRefreshFailures(int limit) {
        return projectionRefreshFailureSupplier.apply(Math.max(1, limit));
    }

    public ProjectionRefreshReplayResult replayProjectionRefreshFailure(String entryId) {
        return projectionRefreshReplayFunction.apply(entryId);
    }

    public DeploymentStatusSnapshot deploymentStatus() {
        return new DeploymentStatusSnapshot(
                cacheDatabaseConfig.adminHttp().enabled(),
                cacheDatabaseConfig.adminHttp().dashboardEnabled(),
                cacheDatabaseConfig.writeBehind().enabled(),
                cacheDatabaseConfig.writeBehind().workerThreads(),
                cacheDatabaseConfig.writeBehind().activeStreamKeys().size(),
                cacheDatabaseConfig.writeBehind().dedicatedWriteConsumerGroupEnabled(),
                cacheDatabaseConfig.writeBehind().durableCompactionEnabled(),
                cacheDatabaseConfig.writeBehind().postgresCopyBulkLoadEnabled(),
                cacheDatabaseConfig.writeBehind().postgresMultiRowFlushEnabled(),
                cacheDatabaseConfig.redisGuardrail().enabled(),
                cacheDatabaseConfig.redisGuardrail().automaticRuntimeProfileSwitchingEnabled(),
                cacheDatabaseConfig.schemaBootstrap().mode().name(),
                cacheDatabaseConfig.schemaBootstrap().autoApplyOnStart(),
                cacheDatabaseConfig.queryIndex().learnedStatisticsEnabled(),
                cacheDatabaseConfig.queryIndex().plannerStatisticsPersisted(),
                cacheDatabaseConfig.keyspace().keyPrefix(),
                Instant.now(),
                cacheDatabaseConfig.writeBehind().activeStreamKeys()
        );
    }

    public SchemaStatusSnapshot schemaStatus() {
        return cachedSnapshot(
                lastSchemaStatusSnapshot,
                lastSchemaStatusSampleAtEpochMillis,
                5_000L,
                this::collectSchemaStatus
        );
    }

    private SchemaStatusSnapshot collectSchemaStatus() {
        SchemaBootstrapResult validation = schemaAdmin.validate();
        SchemaMigrationPlan plan = schemaAdmin.planMigration();
        int createTableSteps = 0;
        int addColumnSteps = 0;
        for (SchemaMigrationStep step : plan.steps()) {
            String reason = defaultString(step.reason()).toLowerCase();
            if (reason.contains("create missing table")) {
                createTableSteps++;
            } else if (reason.contains("add missing column")) {
                addColumnSteps++;
            }
        }
        return new SchemaStatusSnapshot(
                cacheDatabaseConfig.schemaBootstrap().mode().name(),
                cacheDatabaseConfig.schemaBootstrap().autoApplyOnStart(),
                cacheDatabaseConfig.schemaBootstrap().includeVersionColumn(),
                cacheDatabaseConfig.schemaBootstrap().includeDeletedColumn(),
                cacheDatabaseConfig.schemaBootstrap().schemaName(),
                validation.success(),
                validation.issues().size(),
                validation.processedEntityCount(),
                validation.validatedTableCount(),
                plan.stepCount(),
                createTableSteps,
                addColumnSteps,
                schemaAdmin.exportDdl().size(),
                Instant.now()
        );
    }

    public ApiProductSnapshot apiProduct() {
        ArrayList<ApiEntitySnapshot> entities = new ArrayList<>();
        for (EntityBinding<?, ?> binding : entityRegistry.all()) {
            entities.add(new ApiEntitySnapshot(
                    binding.metadata().entityName(),
                    binding.metadata().tableName(),
                    binding.metadata().columns().size(),
                    binding.cachePolicy().hotEntityLimit(),
                    binding.cachePolicy().pageSize(),
                    binding.cachePolicy().entityTtlSeconds(),
                    binding.cachePolicy().pageTtlSeconds(),
                    !(binding.relationBatchLoader() instanceof NoOpRelationBatchLoader<?>),
                    !(binding.pageLoader() instanceof NoOpEntityPageLoader<?>)
            ));
        }
        entities.sort(java.util.Comparator.comparing(ApiEntitySnapshot::entityName));
        return new ApiProductSnapshot(
                entities.size(),
                cacheDatabaseConfig.resourceLimits().maxRegisteredEntities(),
                cacheDatabaseConfig.resourceLimits().maxColumnsPerOperation(),
                Instant.now(),
                List.copyOf(entities)
        );
    }

    public List<ProductionReportSnapshot> productionReports() {
        return productionReportCatalog.latestReports();
    }

    public MigrationPlanner.Template migrationPlannerTemplate() {
        return migrationPlanner.template(entityRegistry);
    }

    public MigrationPlanner.Result planMigration(MigrationPlanner.Request request) {
        return migrationPlanner.plan(request);
    }

    public MigrationSchemaDiscovery.Result discoverMigrationSchema() {
        return migrationSchemaDiscovery.discover();
    }

    public void configureMigrationPlannerDemo(MigrationPlannerDemoSupport support) {
        this.migrationPlannerDemoSupport = support;
    }

    public MigrationPlannerDemoSupport.Descriptor migrationPlannerDemoDescriptor() {
        MigrationPlannerDemoSupport support = migrationPlannerDemoSupport;
        if (support == null) {
            return new MigrationPlannerDemoSupport.Descriptor(
                    false,
                    "Migration planner demo",
                    "No migration planner demo dataset is configured for this runtime.",
                    120,
                    12,
                    1500,
                    MigrationPlanner.Request.defaults()
            );
        }
        return support.descriptor();
    }

    public MigrationPlannerDemoSupport.BootstrapResult bootstrapMigrationPlannerDemo(MigrationPlannerDemoSupport.BootstrapRequest request) {
        MigrationPlannerDemoSupport support = migrationPlannerDemoSupport;
        if (support == null) {
            throw new IllegalStateException("Migration planner demo bootstrap is not configured for this runtime.");
        }
        MigrationPlannerDemoSupport.BootstrapResult result = support.bootstrap(request.normalize());
        persistDiagnostics(
                "migration-demo-bootstrap",
                "root=" + result.rootSurface()
                        + ", child=" + result.childSurface()
                        + ", customers=" + result.customerCount()
                        + ", orders=" + result.orderCount()
                        + ", hotCustomerOrders=" + result.hottestCustomerOrderCount()
        );
        return result;
    }

    public MigrationScaffoldGenerator.Result generateMigrationScaffold(MigrationScaffoldGenerator.Request request) {
        return migrationScaffoldGenerator.generate(request);
    }

    public MigrationWarmRunner.Result warmMigration(MigrationWarmRunner.Request request) {
        MigrationWarmRunner.Result result = migrationWarmRunner.execute(request);
        persistDiagnostics(
                "migration-warm",
                "child=" + result.childEntityName()
                        + ", childRows=" + result.childRowsHydrated()
                        + ", root=" + result.rootEntityName()
                        + ", rootRows=" + result.rootRowsHydrated()
                        + ", dryRun=" + result.dryRun()
        );
        return result;
    }

    public MigrationComparisonRunner.Result compareMigration(MigrationComparisonRunner.Request request) {
        MigrationComparisonRunner.Result result = migrationComparisonRunner.execute(request);
        persistDiagnostics(
                "migration-compare",
                "route=" + result.cacheRouteLabel()
                        + ", readiness=" + result.assessment().readiness().name()
                        + ", baselineP95Ns=" + result.baselineMetrics().p95LatencyNanos()
                        + ", cacheP95Ns=" + result.cacheMetrics().p95LatencyNanos()
                        + ", exactMatches=" + result.sampleComparisons().stream().filter(MigrationComparisonRunner.SampleComparison::exactMatch).count()
                        + "/" + result.sampleComparisons().size()
        );
        return result;
    }

    public EffectiveTuningSnapshot effectiveTuning() {
        ArrayList<EffectiveTuningEntry> items = new ArrayList<>();
        Set<String> modeledProperties = new TreeSet<>();

        addTuning(items, modeledProperties, "write-behind", "cachedb.config.writeBehind.enabled",
                cacheDatabaseConfig.writeBehind().enabled(), "Enables the write-behind pipeline.");
        addTuning(items, modeledProperties, "write-behind", "cachedb.config.writeBehind.workerThreads",
                cacheDatabaseConfig.writeBehind().workerThreads(), "Write-behind worker thread count.");
        addTuning(items, modeledProperties, "write-behind", "cachedb.config.writeBehind.batchSize",
                cacheDatabaseConfig.writeBehind().batchSize(), "Base Redis stream read batch size.");
        addTuning(items, modeledProperties, "write-behind", "cachedb.config.writeBehind.maxFlushBatchSize",
                cacheDatabaseConfig.writeBehind().maxFlushBatchSize(), "Maximum PostgreSQL flush batch size.");
        addTuning(items, modeledProperties, "write-behind", "cachedb.config.writeBehind.postgresCopyThreshold",
                cacheDatabaseConfig.writeBehind().postgresCopyThreshold(), "Row threshold before COPY bulk path.");
        addTuning(items, modeledProperties, "write-behind", "cachedb.config.writeBehind.compactionShardCount",
                cacheDatabaseConfig.writeBehind().compactionShardCount(), "Compaction shard count.");
        addTuning(items, modeledProperties, "guardrail", "cachedb.config.redisGuardrail.enabled",
                cacheDatabaseConfig.redisGuardrail().enabled(), "Enables Redis guardrails.");
        addTuning(items, modeledProperties, "guardrail", "cachedb.config.redisGuardrail.usedMemoryWarnBytes",
                cacheDatabaseConfig.redisGuardrail().usedMemoryWarnBytes(), "Warning threshold for Redis used memory.");
        addTuning(items, modeledProperties, "guardrail", "cachedb.config.redisGuardrail.usedMemoryCriticalBytes",
                cacheDatabaseConfig.redisGuardrail().usedMemoryCriticalBytes(), "Critical threshold for Redis used memory.");
        addTuning(items, modeledProperties, "guardrail", "cachedb.config.redisGuardrail.writeBehindBacklogWarnThreshold",
                cacheDatabaseConfig.redisGuardrail().writeBehindBacklogWarnThreshold(), "Warning threshold for write-behind backlog.");
        addTuning(items, modeledProperties, "guardrail", "cachedb.config.redisGuardrail.writeBehindBacklogCriticalThreshold",
                cacheDatabaseConfig.redisGuardrail().writeBehindBacklogCriticalThreshold(), "Critical threshold for write-behind backlog.");
        addTuning(items, modeledProperties, "guardrail", "cachedb.config.redisGuardrail.rejectWritesOnHardLimit",
                cacheDatabaseConfig.redisGuardrail().rejectWritesOnHardLimit(), "Rejects writes when hard limits are exceeded.");
        addTuning(items, modeledProperties, "guardrail", "cachedb.config.redisGuardrail.automaticRuntimeProfileSwitchingEnabled",
                cacheDatabaseConfig.redisGuardrail().automaticRuntimeProfileSwitchingEnabled(), "Enables automatic runtime profile switching.");
        addTuning(items, modeledProperties, "dead-letter", "cachedb.config.deadLetterRecovery.enabled",
                cacheDatabaseConfig.deadLetterRecovery().enabled(), "Enables the DLQ recovery worker.");
        addTuning(items, modeledProperties, "dead-letter", "cachedb.config.deadLetterRecovery.workerThreads",
                cacheDatabaseConfig.deadLetterRecovery().workerThreads(), "DLQ recovery worker count.");
        addTuning(items, modeledProperties, "dead-letter", "cachedb.config.deadLetterRecovery.maxReplayRetries",
                cacheDatabaseConfig.deadLetterRecovery().maxReplayRetries(), "Maximum replay retries for DLQ entries.");
        addTuning(items, modeledProperties, "query-index", "cachedb.config.queryIndex.exactIndexEnabled",
                cacheDatabaseConfig.queryIndex().exactIndexEnabled(), "Enables exact-match indexes.");
        addTuning(items, modeledProperties, "query-index", "cachedb.config.queryIndex.rangeIndexEnabled",
                cacheDatabaseConfig.queryIndex().rangeIndexEnabled(), "Enables range indexes.");
        addTuning(items, modeledProperties, "query-index", "cachedb.config.queryIndex.learnedStatisticsEnabled",
                cacheDatabaseConfig.queryIndex().learnedStatisticsEnabled(), "Enables learned planner statistics.");
        addTuning(items, modeledProperties, "query-index", "cachedb.config.queryIndex.plannerStatisticsPersisted",
                cacheDatabaseConfig.queryIndex().plannerStatisticsPersisted(), "Persists planner statistics in Redis.");
        addTuning(items, modeledProperties, "cache", "cachedb.config.resourceLimits.defaultCachePolicy.hotEntityLimit",
                cacheDatabaseConfig.resourceLimits().defaultCachePolicy().hotEntityLimit(), "Default hot-entity limit.");
        addTuning(items, modeledProperties, "cache", "cachedb.config.resourceLimits.defaultCachePolicy.pageSize",
                cacheDatabaseConfig.resourceLimits().defaultCachePolicy().pageSize(), "Default page size.");
        addTuning(items, modeledProperties, "cache", "cachedb.config.resourceLimits.defaultCachePolicy.entityTtlSeconds",
                cacheDatabaseConfig.resourceLimits().defaultCachePolicy().entityTtlSeconds(), "Default entity TTL in seconds.");
        addTuning(items, modeledProperties, "cache", "cachedb.config.resourceLimits.defaultCachePolicy.pageTtlSeconds",
                cacheDatabaseConfig.resourceLimits().defaultCachePolicy().pageTtlSeconds(), "Default page TTL in seconds.");
        addTuning(items, modeledProperties, "page-cache", "cachedb.config.pageCache.readThroughEnabled",
                cacheDatabaseConfig.pageCache().readThroughEnabled(), "Enables page-cache read-through.");
        addTuning(items, modeledProperties, "page-cache", "cachedb.config.pageCache.evictionBatchSize",
                cacheDatabaseConfig.pageCache().evictionBatchSize(), "Page-cache eviction batch size.");
        addTuning(items, modeledProperties, "keyspace", "cachedb.config.keyspace.keyPrefix",
                cacheDatabaseConfig.keyspace().keyPrefix(), "Global Redis key prefix.");
        addTuning(items, modeledProperties, "admin-monitoring", "cachedb.config.adminMonitoring.writeBehindWarnThreshold",
                cacheDatabaseConfig.adminMonitoring().writeBehindWarnThreshold(), "Warning threshold for write-behind incidents.");
        addTuning(items, modeledProperties, "admin-monitoring", "cachedb.config.adminMonitoring.writeBehindCriticalThreshold",
                cacheDatabaseConfig.adminMonitoring().writeBehindCriticalThreshold(), "Critical threshold for write-behind incidents.");
        addTuning(items, modeledProperties, "admin-monitoring", "cachedb.config.adminMonitoring.historySampleIntervalMillis",
                cacheDatabaseConfig.adminMonitoring().historySampleIntervalMillis(), "Monitoring history sample interval.");
        addTuning(items, modeledProperties, "admin-monitoring", "cachedb.config.adminMonitoring.historyMaxSamples",
                cacheDatabaseConfig.adminMonitoring().historyMaxSamples(), "Monitoring history retention.");
        addTuning(items, modeledProperties, "admin-monitoring", "cachedb.config.adminMonitoring.telemetryTtlSeconds",
                cacheDatabaseConfig.adminMonitoring().telemetryTtlSeconds(), "TTL for Redis-backed admin telemetry streams and snapshots.");
        addTuning(items, modeledProperties, "admin-monitoring", "cachedb.config.adminMonitoring.monitoringHistoryStreamKey",
                cacheDatabaseConfig.adminMonitoring().monitoringHistoryStreamKey(), "Redis stream key for monitoring history samples.");
        addTuning(items, modeledProperties, "admin-monitoring", "cachedb.config.adminMonitoring.alertRouteHistoryStreamKey",
                cacheDatabaseConfig.adminMonitoring().alertRouteHistoryStreamKey(), "Redis stream key for alert route history samples.");
        addTuning(items, modeledProperties, "admin-monitoring", "cachedb.config.adminMonitoring.performanceHistoryStreamKey",
                cacheDatabaseConfig.adminMonitoring().performanceHistoryStreamKey(), "Redis stream key for performance history samples.");
        addTuning(items, modeledProperties, "admin-monitoring", "cachedb.config.adminMonitoring.performanceSnapshotKey",
                cacheDatabaseConfig.adminMonitoring().performanceSnapshotKey(), "Redis hash key for the current storage-performance snapshot.");
        addTuning(items, modeledProperties, "admin-monitoring", "cachedb.config.adminMonitoring.incidentTtlSeconds",
                cacheDatabaseConfig.adminMonitoring().incidentTtlSeconds(), "TTL for incident stream entries and cooldown keys.");
        addTuning(items, modeledProperties, "admin-reporting", "cachedb.config.adminReportJob.diagnosticsTtlSeconds",
                cacheDatabaseConfig.adminReportJob().diagnosticsTtlSeconds(), "TTL for diagnostics stream entries.");
        addTuning(items, modeledProperties, "admin-http", "cachedb.config.adminHttp.enabled",
                cacheDatabaseConfig.adminHttp().enabled(), "Enables the admin HTTP server.");
        addTuning(items, modeledProperties, "admin-http", "cachedb.config.adminHttp.host",
                cacheDatabaseConfig.adminHttp().host(), "Admin HTTP bind host.");
        addTuning(items, modeledProperties, "admin-http", "cachedb.config.adminHttp.port",
                cacheDatabaseConfig.adminHttp().port(), "Admin HTTP port.");
        addTuning(items, modeledProperties, "admin-http", "cachedb.config.adminHttp.dashboardEnabled",
                cacheDatabaseConfig.adminHttp().dashboardEnabled(), "Enables the built-in admin dashboard.");
        addTuning(items, modeledProperties, "schema", "cachedb.config.schemaBootstrap.mode",
                cacheDatabaseConfig.schemaBootstrap().mode().name(), "Schema bootstrap mode.");
        addTuning(items, modeledProperties, "schema", "cachedb.config.schemaBootstrap.autoApplyOnStart",
                cacheDatabaseConfig.schemaBootstrap().autoApplyOnStart(), "Applies schema bootstrap on start.");
        addTuning(items, modeledProperties, "schema", "cachedb.config.schemaBootstrap.schemaName",
                defaultString(cacheDatabaseConfig.schemaBootstrap().schemaName()), "Target PostgreSQL schema.");

        int explicitOverrideCount = 0;
        TreeSet<String> propertyNames = new TreeSet<>();
        for (String propertyName : System.getProperties().stringPropertyNames()) {
            if (propertyName.startsWith("cachedb.")) {
                propertyNames.add(propertyName);
            }
        }
        for (String propertyName : propertyNames) {
            explicitOverrideCount++;
            if (!modeledProperties.contains(propertyName)) {
                items.add(new EffectiveTuningEntry(
                        "explicit-property",
                        propertyName,
                        defaultString(System.getProperty(propertyName)),
                        "explicit override",
                        "Explicit JVM/system property override."
                ));
            }
        }

        items.sort(Comparator
                .comparing(EffectiveTuningEntry::group)
                .thenComparing(EffectiveTuningEntry::property));

        return new EffectiveTuningSnapshot(
                Instant.now(),
                explicitOverrideCount,
                items.size(),
                List.copyOf(items)
        );
    }

    public RuntimeProfileDetailsSnapshot runtimeProfileDetails() {
        RedisRuntimeProfileSnapshot snapshot = redisRuntimeProfileSnapshotSupplier.get();
        Map<String, String> values = runtimeProfilePropertySupplier.get();
        ArrayList<RuntimeProfilePropertySnapshot> properties = new ArrayList<>();
        properties.add(runtimeProfileProperty(values, "hotEntityLimitFactor", "ratio",
                "Hot entity limit multiplier applied to cache policies under the active runtime profile."));
        properties.add(runtimeProfileProperty(values, "pageSizeFactor", "ratio",
                "Page size multiplier applied to cache policies under the active runtime profile."));
        properties.add(runtimeProfileProperty(values, "entityTtlCapSeconds", "seconds",
                "Upper TTL cap for entity entries while this runtime profile is active."));
        properties.add(runtimeProfileProperty(values, "pageTtlCapSeconds", "seconds",
                "Upper TTL cap for page entries while this runtime profile is active."));
        properties.add(runtimeProfileProperty(values, "highSleepMillisBonus", "milliseconds",
                "Extra producer delay added during WARN pressure."));
        properties.add(runtimeProfileProperty(values, "criticalSleepMillisBonus", "milliseconds",
                "Extra producer delay added during CRITICAL pressure."));
        properties.add(runtimeProfileProperty(values, "effectiveHighSleepMillis", "milliseconds",
                "Effective producer delay currently used for WARN pressure."));
        properties.add(runtimeProfileProperty(values, "effectiveCriticalSleepMillis", "milliseconds",
                "Effective producer delay currently used for CRITICAL pressure."));
        properties.add(runtimeProfileProperty(values, "automaticSwitchingEnabled", "flag",
                "Whether the runtime profile is allowed to switch automatically under pressure."));
        return new RuntimeProfileDetailsSnapshot(
                defaultString(values.getOrDefault("profile", snapshot.activeProfile())),
                defaultString(values.getOrDefault("mode", manualRuntimeProfileOverrideSupplier.get() == null ? "AUTO" : "MANUAL")),
                Boolean.parseBoolean(values.getOrDefault(
                        "automaticSwitchingEnabled",
                        String.valueOf(cacheDatabaseConfig.redisGuardrail().automaticRuntimeProfileSwitchingEnabled())
                )),
                defaultString(manualRuntimeProfileOverrideSupplier.get()),
                defaultString(snapshot.lastObservedPressureLevel()),
                snapshot.switchCount(),
                snapshot.lastSwitchedAtEpochMillis(),
                List.copyOf(properties)
        );
    }

    public RuntimeProfileDetailsSnapshot setRuntimeProfile(String profileName) {
        runtimeProfileOverrideSetter.accept(profileName);
        return runtimeProfileDetails();
    }

    public RuntimeProfileDetailsSnapshot clearRuntimeProfileOverride() {
        runtimeProfileOverrideClearer.run();
        return runtimeProfileDetails();
    }

    public List<MonitoringHistoryPoint> monitoringHistory(int limit) {
        return monitoringHistorySupplier.apply(limit);
    }

    public List<AlertRouteHistoryPoint> alertRouteHistory(int limit) {
        return alertRouteHistorySupplier.apply(limit);
    }

    public List<PerformanceHistoryPoint> performanceHistory(int limit) {
        return performanceHistorySupplier.apply(limit);
    }

    public AdminTelemetryResetSnapshot resetTelemetry() {
        long diagnosticsEntriesCleared = RedisTelemetrySupport.streamLengthSafely(jedis, diagnosticsStreamKey);
        long incidentEntriesCleared = RedisTelemetrySupport.streamLengthSafely(jedis, monitoringConfig.incidentStreamKey());
        RedisTelemetrySupport.deleteKeySafely(jedis, diagnosticsStreamKey);
        RedisTelemetrySupport.deleteKeySafely(jedis, monitoringConfig.incidentStreamKey());
        try {
            java.util.Set<String> cooldownKeys = jedis.keys(monitoringConfig.incidentStreamKey() + ":cooldown:*");
            if (!cooldownKeys.isEmpty()) {
                RedisTelemetrySupport.deleteKeysSafely(jedis, cooldownKeys.toArray(String[]::new));
            }
        } catch (RuntimeException ignored) {
            // Best-effort cleanup; telemetry reset should not fail only because cooldown keys could not be scanned.
        }
        AdminTelemetryResetSnapshot bufferReset;
        try {
            bufferReset = telemetryResetSupplier.get();
        } catch (RuntimeException ignored) {
            bufferReset = new AdminTelemetryResetSnapshot(0L, 0L, 0, 0, 0L, Instant.now());
        }
        return new AdminTelemetryResetSnapshot(
                diagnosticsEntriesCleared,
                incidentEntriesCleared,
                bufferReset.monitoringHistorySamplesCleared(),
                bufferReset.alertRouteHistorySamplesCleared(),
                bufferReset.storagePerformanceOperationsCleared(),
                Instant.now()
        );
    }

    public AdminTelemetryResetSnapshot resetTelemetryBuffersOnly() {
        AdminTelemetryResetSnapshot bufferReset = telemetryResetSupplier.get();
        return new AdminTelemetryResetSnapshot(
                0L,
                0L,
                bufferReset.monitoringHistorySamplesCleared(),
                bufferReset.alertRouteHistorySamplesCleared(),
                bufferReset.storagePerformanceOperationsCleared(),
                Instant.now()
        );
    }

    public PerformanceTelemetryResetSnapshot resetPerformanceTelemetry() {
        return performanceResetSupplier.get();
    }

    public ReconciliationHealth health() {
        ReconciliationMetrics metrics = metrics();
        ArrayList<String> issues = new ArrayList<>();
        AdminHealthStatus status = AdminHealthStatus.UP;

        if (metrics.writeBehindStreamLength() >= monitoringConfig.writeBehindCriticalThreshold()) {
            issues.add("Write-behind backlog is above critical threshold");
            status = AdminHealthStatus.DOWN;
        } else if (metrics.writeBehindStreamLength() >= monitoringConfig.writeBehindWarnThreshold()) {
            issues.add("Write-behind backlog is above warning threshold");
            status = elevate(status, AdminHealthStatus.DEGRADED);
        }

        if (metrics.deadLetterStreamLength() >= monitoringConfig.deadLetterCriticalThreshold()) {
            issues.add("Dead-letter backlog is above critical threshold");
            status = AdminHealthStatus.DOWN;
        } else if (metrics.deadLetterStreamLength() >= monitoringConfig.deadLetterWarnThreshold()) {
            issues.add("Dead-letter backlog is above warning threshold");
            status = elevate(status, AdminHealthStatus.DEGRADED);
        }

        if (metrics.deadLetterRecoverySnapshot().failedCount() >= monitoringConfig.recoveryFailedCriticalThreshold()) {
            issues.add("Recovery worker failures are above critical threshold");
            status = AdminHealthStatus.DOWN;
        } else if (metrics.deadLetterRecoverySnapshot().failedCount() >= monitoringConfig.recoveryFailedWarnThreshold()) {
            issues.add("Recovery worker failures are above warning threshold");
            status = elevate(status, AdminHealthStatus.DEGRADED);
        }

        if (redisGuardrailConfig.usedMemoryCriticalBytes() > 0
                && metrics.redisGuardrailSnapshot().usedMemoryBytes() >= redisGuardrailConfig.usedMemoryCriticalBytes()) {
            issues.add("Redis used memory is above critical threshold");
            status = AdminHealthStatus.DOWN;
        } else if (redisGuardrailConfig.usedMemoryWarnBytes() > 0
                && metrics.redisGuardrailSnapshot().usedMemoryBytes() >= redisGuardrailConfig.usedMemoryWarnBytes()) {
            issues.add("Redis used memory is above warning threshold");
            status = elevate(status, AdminHealthStatus.DEGRADED);
        }

        if (redisGuardrailConfig.compactionPendingCriticalThreshold() > 0
                && metrics.redisGuardrailSnapshot().compactionPendingCount() >= redisGuardrailConfig.compactionPendingCriticalThreshold()) {
            issues.add("Compaction pending count is above critical threshold");
            status = AdminHealthStatus.DOWN;
        } else if (redisGuardrailConfig.compactionPendingWarnThreshold() > 0
                && metrics.redisGuardrailSnapshot().compactionPendingCount() >= redisGuardrailConfig.compactionPendingWarnThreshold()) {
            issues.add("Compaction pending count is above warning threshold");
            status = elevate(status, AdminHealthStatus.DEGRADED);
        }
        if (metrics.redisGuardrailSnapshot().hardRejectedWriteCount() > 0) {
            issues.add("Redis hard limit rejected producer writes");
            status = AdminHealthStatus.DOWN;
        }

        if (cacheDatabaseConfig.projectionRefresh().deadLetterCriticalThreshold() > 0
                && metrics.projectionRefreshSnapshot().deadLetterStreamLength() >= cacheDatabaseConfig.projectionRefresh().deadLetterCriticalThreshold()) {
            issues.add("Projection refresh dead-letter backlog is above critical threshold");
            status = elevate(status, AdminHealthStatus.DEGRADED);
        } else if (cacheDatabaseConfig.projectionRefresh().deadLetterWarnThreshold() > 0
                && metrics.projectionRefreshSnapshot().deadLetterStreamLength() >= cacheDatabaseConfig.projectionRefresh().deadLetterWarnThreshold()) {
            issues.add("Projection refresh dead-letter backlog is above warning threshold");
            status = elevate(status, AdminHealthStatus.DEGRADED);
        }

        if (recentError(metrics.writeBehindWorkerSnapshot().lastErrorAtEpochMillis())) {
            issues.add("Write-behind worker has a recent error");
            status = elevate(status, AdminHealthStatus.DEGRADED);
        }
        if (recentError(metrics.deadLetterRecoverySnapshot().lastErrorAtEpochMillis())) {
            issues.add("Dead-letter recovery worker has a recent error");
            status = elevate(status, AdminHealthStatus.DEGRADED);
        }
        if (recentError(metrics.recoveryCleanupSnapshot().lastErrorAtEpochMillis())) {
            issues.add("Recovery cleanup worker has a recent error");
            status = elevate(status, AdminHealthStatus.DEGRADED);
        }
        if (recentError(metrics.adminReportJobSnapshot().lastErrorAtEpochMillis())) {
            issues.add("Admin report worker has a recent error");
            status = elevate(status, AdminHealthStatus.DEGRADED);
        }
        if (recentError(metrics.projectionRefreshSnapshot().lastErrorAtEpochMillis())) {
            issues.add("Projection refresh worker has a recent error");
            status = elevate(status, AdminHealthStatus.DEGRADED);
        }

        return new ReconciliationHealth(status, List.copyOf(issues), metrics);
    }

    public AdminDiagnosticsRecord persistDiagnostics(String source, String note) {
        ReconciliationHealth currentHealth = health();
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("source", source == null ? "" : source);
        fields.put("note", note == null ? "" : note);
        fields.put("status", currentHealth.status().name());
        fields.put("recordedAt", Instant.now().toString());
        fields.put("writeBehindStreamLength", String.valueOf(currentHealth.metrics().writeBehindStreamLength()));
        fields.put("deadLetterStreamLength", String.valueOf(currentHealth.metrics().deadLetterStreamLength()));
        fields.put("reconciliationStreamLength", String.valueOf(currentHealth.metrics().reconciliationStreamLength()));
        fields.put("archiveStreamLength", String.valueOf(currentHealth.metrics().archiveStreamLength()));
        fields.put("cleanupRunCount", String.valueOf(currentHealth.metrics().recoveryCleanupSnapshot().runCount()));
        fields.put("reportRunCount", String.valueOf(currentHealth.metrics().adminReportJobSnapshot().runCount()));
        fields.put("runtimeProfile", currentHealth.metrics().redisRuntimeProfileSnapshot().activeProfile());
        fields.put("runtimePressureLevel", currentHealth.metrics().redisRuntimeProfileSnapshot().lastObservedPressureLevel());
        fields.put("runtimeProfileSwitchCount", String.valueOf(currentHealth.metrics().redisRuntimeProfileSnapshot().switchCount()));
        fields.put("runtimeProfileLastSwitchedAt", String.valueOf(currentHealth.metrics().redisRuntimeProfileSnapshot().lastSwitchedAtEpochMillis()));
        String entryId = jedis.xadd(diagnosticsStreamKey, XAddParams.xAddParams(), fields).toString();
        if (diagnosticsMaxLength > 0) {
            jedis.xtrim(diagnosticsStreamKey, diagnosticsMaxLength, true);
        }
        RedisTelemetrySupport.expireIfNeeded(jedis, diagnosticsStreamKey, cacheDatabaseConfig.adminReportJob().diagnosticsTtlSeconds());
        return new AdminDiagnosticsRecord(
                entryId,
                fields.get("source"),
                fields.get("note"),
                currentHealth.status(),
                Instant.parse(fields.get("recordedAt")),
                Map.copyOf(fields)
        );
    }

    public List<AdminDiagnosticsRecord> diagnostics(int limit) {
        int maxItems = Math.max(1, limit);
        List<AdminDiagnosticsRecord> snapshot = cachedSnapshot(
                lastDiagnosticsSnapshot,
                lastDiagnosticsSampleAtEpochMillis,
                1_500L,
                () -> jedis.xrevrange(diagnosticsStreamKey, "+", "-", Math.max(20, maxItems)).stream()
                        .map(this::toDiagnosticsRecord)
                        .toList()
        );
        if (snapshot.size() <= maxItems) {
            return snapshot;
        }
        return List.copyOf(snapshot.subList(0, maxItems));
    }

    public List<RuntimeProfileChurnRecord> runtimeProfileChurn(int limit) {
        int maxItems = Math.max(1, limit);
        List<RuntimeProfileChurnRecord> snapshot = cachedSnapshot(
                lastRuntimeProfileChurnSnapshot,
                lastRuntimeProfileChurnSampleAtEpochMillis,
                1_500L,
                () -> jedis.xrevrange(diagnosticsStreamKey, "+", "-", Math.max(20, maxItems * 5)).stream()
                        .filter(entry -> "RUNTIME_PROFILE_SWITCH".equals(entry.getFields().get("eventType")))
                        .limit(maxItems)
                        .map(this::toRuntimeProfileChurnRecord)
                        .toList()
        );
        if (snapshot.size() <= maxItems) {
            return snapshot;
        }
        return List.copyOf(snapshot.subList(0, maxItems));
    }

    public QueryIndexRebuildResult rebuildQueryIndexes(String entityName, String note) {
        QueryIndexRebuildResult result = indexMaintenance.rebuildEntity(entityName, note);
        persistDiagnostics("query-index-rebuild", "entity=" + entityName + ", rebuilt=" + result.rebuiltEntityCount() + ", note=" + note);
        return result;
    }

    public List<QueryIndexRebuildResult> rebuildAllQueryIndexes(String note) {
        List<QueryIndexRebuildResult> results = indexMaintenance.rebuildAll(note);
        persistDiagnostics("query-index-rebuild", "all-entities rebuilt=" + results.size() + ", note=" + note);
        return results;
    }

    public List<String> degradedQueryIndexes() {
        return cachedSnapshot(
                lastDegradedQueryIndexesSnapshot,
                lastDegradedQueryIndexesSampleAtEpochMillis,
                2_000L,
                () -> List.copyOf(indexMaintenance.degradedEntities())
        );
    }

    public SchemaMigrationPlan schemaMigrationPlan() {
        return schemaAdmin.planMigration();
    }

    public SchemaMigrationPlan applySchemaMigrationPlan() {
        SchemaMigrationPlan plan = schemaAdmin.applyMigrationPlan();
        persistDiagnostics("schema-migration", "steps=" + plan.stepCount());
        return plan;
    }

    public List<SchemaMigrationHistoryEntry> schemaMigrationHistory(int limit) {
        return schemaAdmin.migrationHistory(limit);
    }

    public Map<String, String> schemaDdl() {
        return schemaAdmin.exportDdl();
    }

    public List<StarterProfileSnapshot> starterProfiles() {
        return CacheDatabaseProfiles.catalog();
    }

    public List<MonitoringServiceSnapshot> monitoringServices() {
        return cachedSnapshot(
                lastMonitoringServicesSnapshot,
                lastMonitoringServicesSampleAtEpochMillis,
                1_500L,
                this::collectMonitoringServices
        );
    }

    private List<MonitoringServiceSnapshot> collectMonitoringServices() {
        ReconciliationHealth health = health();
        ReconciliationMetrics metrics = health.metrics();
        ArrayList<MonitoringServiceSnapshot> services = new ArrayList<>();
        String writeBehindError = workerErrorSummary(
                metrics.writeBehindWorkerSnapshot().lastErrorType(),
                metrics.writeBehindWorkerSnapshot().lastErrorOrigin()
        );
        services.add(new MonitoringServiceSnapshot(
                "write-behind",
                serviceStatus(
                        metrics.redisGuardrailSnapshot().hardRejectedWriteCount() > 0,
                        metrics.writeBehindStreamLength() >= monitoringConfig.writeBehindWarnThreshold()
                                || recentError(metrics.writeBehindWorkerSnapshot().lastErrorAtEpochMillis())
                ),
                "backlog=" + metrics.writeBehindStreamLength(),
                "flushed=" + metrics.writeBehindWorkerSnapshot().flushedCount()
                        + ", deadLetter=" + metrics.writeBehindWorkerSnapshot().deadLetterCount()
                        + writeBehindError
        ));
        String recoveryError = workerErrorSummary(
                metrics.deadLetterRecoverySnapshot().lastErrorType(),
                metrics.deadLetterRecoverySnapshot().lastErrorOrigin()
        );
        services.add(new MonitoringServiceSnapshot(
                "recovery",
                serviceStatus(
                        metrics.deadLetterRecoverySnapshot().failedCount() >= monitoringConfig.recoveryFailedCriticalThreshold(),
                        metrics.deadLetterStreamLength() >= monitoringConfig.deadLetterWarnThreshold()
                                || recentError(metrics.deadLetterRecoverySnapshot().lastErrorAtEpochMillis())
                ),
                "deadLetter=" + metrics.deadLetterStreamLength(),
                "replayed=" + metrics.deadLetterRecoverySnapshot().replayedCount()
                        + ", failed=" + metrics.deadLetterRecoverySnapshot().failedCount()
                        + recoveryError
        ));
        services.add(new MonitoringServiceSnapshot(
                "redis-guardrail",
                serviceStatus(
                        redisGuardrailConfig.usedMemoryCriticalBytes() > 0
                                && metrics.redisGuardrailSnapshot().usedMemoryBytes() >= redisGuardrailConfig.usedMemoryCriticalBytes(),
                        redisGuardrailConfig.usedMemoryWarnBytes() > 0
                                && metrics.redisGuardrailSnapshot().usedMemoryBytes() >= redisGuardrailConfig.usedMemoryWarnBytes()
                ),
                "memory=" + metrics.redisGuardrailSnapshot().usedMemoryBytes(),
                "pending=" + metrics.redisGuardrailSnapshot().compactionPendingCount()
                        + ", profile=" + metrics.redisRuntimeProfileSnapshot().activeProfile()
        ));
        List<String> degradedIndexes = degradedQueryIndexes();
        services.add(new MonitoringServiceSnapshot(
                "query-layer",
                serviceStatus(false, !degradedIndexes.isEmpty()),
                "degradedIndexes=" + degradedIndexes.size(),
                degradedIndexes.isEmpty() ? "planner healthy" : String.join(", ", degradedIndexes.stream().limit(3).toList())
        ));
        String cleanupError = workerErrorSummary(
                metrics.recoveryCleanupSnapshot().lastErrorType(),
                metrics.recoveryCleanupSnapshot().lastErrorOrigin()
        );
        services.add(new MonitoringServiceSnapshot(
                "cleanup",
                serviceStatus(false, recentError(metrics.recoveryCleanupSnapshot().lastErrorAtEpochMillis())),
                "runs=" + metrics.recoveryCleanupSnapshot().runCount(),
                "prunedDeadLetters=" + metrics.recoveryCleanupSnapshot().prunedDeadLetterCount()
                        + ", prunedArchive=" + metrics.recoveryCleanupSnapshot().prunedArchiveCount()
                        + cleanupError
        ));
        SchemaStatusSnapshot schemaStatus = schemaStatus();
        services.add(new MonitoringServiceSnapshot(
                "schema",
                serviceStatus(!schemaStatus.validationSucceeded(), schemaStatus.migrationStepCount() > 0),
                "migrationSteps=" + schemaStatus.migrationStepCount(),
                "validatedTables=" + schemaStatus.validatedTableCount()
                        + ", issues=" + schemaStatus.validationIssueCount()
        ));
        services.add(new MonitoringServiceSnapshot(
                "incident-delivery",
                serviceStatus(totalIncidentDeliveryDropped(metrics) > 0, totalIncidentDeliveryFailed(metrics) > 0),
                "enqueued=" + metrics.incidentDeliverySnapshot().enqueuedCount(),
                "delivered=" + totalIncidentDeliveryDelivered(metrics)
                        + ", failed=" + totalIncidentDeliveryFailed(metrics)
        ));
        String deliveryRecoveryError = workerErrorSummary(
                metrics.incidentDeliverySnapshot().recovery().lastErrorType(),
                metrics.incidentDeliverySnapshot().recovery().lastErrorOrigin()
        );
        services.add(new MonitoringServiceSnapshot(
                "incident-delivery-recovery",
                serviceStatus(false, recentError(metrics.incidentDeliverySnapshot().recovery().lastErrorAtEpochMillis())),
                "replayed=" + metrics.incidentDeliverySnapshot().recovery().replayedCount(),
                "failedReplay=" + metrics.incidentDeliverySnapshot().recovery().failedReplayCount()
                        + ", deadLetter=" + metrics.incidentDeliverySnapshot().recovery().deadLetterCount()
                        + deliveryRecoveryError
        ));
        String adminReportError = workerErrorSummary(
                metrics.adminReportJobSnapshot().lastErrorType(),
                metrics.adminReportJobSnapshot().lastErrorOrigin()
        );
        services.add(new MonitoringServiceSnapshot(
                "admin-report-job",
                serviceStatus(false, recentError(metrics.adminReportJobSnapshot().lastErrorAtEpochMillis())),
                "runs=" + metrics.adminReportJobSnapshot().runCount(),
                "writtenFiles=" + metrics.adminReportJobSnapshot().writtenFileCount()
                        + adminReportError
        ));
        String projectionRefreshError = workerErrorSummary(
                metrics.projectionRefreshSnapshot().lastErrorType(),
                metrics.projectionRefreshSnapshot().lastErrorOrigin()
        );
        services.add(new MonitoringServiceSnapshot(
                "projection-refresh",
                serviceStatus(
                        cacheDatabaseConfig.projectionRefresh().deadLetterCriticalThreshold() > 0
                                && metrics.projectionRefreshSnapshot().deadLetterStreamLength() >= cacheDatabaseConfig.projectionRefresh().deadLetterCriticalThreshold(),
                        (cacheDatabaseConfig.projectionRefresh().deadLetterWarnThreshold() > 0
                                && metrics.projectionRefreshSnapshot().deadLetterStreamLength() >= cacheDatabaseConfig.projectionRefresh().deadLetterWarnThreshold())
                                || recentError(metrics.projectionRefreshSnapshot().lastErrorAtEpochMillis())
                ),
                "stream=" + metrics.projectionRefreshSnapshot().streamLength()
                        + ", pending=" + metrics.projectionRefreshSnapshot().pendingCount()
                        + ", lagMs=" + metrics.projectionRefreshSnapshot().lagEstimateMillis(),
                "processed=" + metrics.projectionRefreshSnapshot().processedCount()
                        + ", retried=" + metrics.projectionRefreshSnapshot().retriedCount()
                        + ", dlq=" + metrics.projectionRefreshSnapshot().deadLetterStreamLength()
                        + ", replayed=" + metrics.projectionRefreshSnapshot().replayedCount()
                        + projectionRefreshError
        ));
        return List.copyOf(services);
    }

    public List<BackgroundWorkerErrorSnapshot> backgroundWorkerErrors() {
        return cachedSnapshot(
                lastBackgroundWorkerErrorsSnapshot,
                lastBackgroundWorkerErrorsSampleAtEpochMillis,
                1_500L,
                this::collectBackgroundWorkerErrors
        );
    }

    private List<BackgroundWorkerErrorSnapshot> collectBackgroundWorkerErrors() {
        ReconciliationMetrics metrics = metrics();
        ArrayList<BackgroundWorkerErrorSnapshot> items = new ArrayList<>();
        addBackgroundWorkerError(
                items,
                "write-behind-worker",
                "write-behind",
                metrics.writeBehindWorkerSnapshot().lastErrorAtEpochMillis(),
                metrics.writeBehindWorkerSnapshot().lastErrorType(),
                metrics.writeBehindWorkerSnapshot().lastErrorRootType(),
                metrics.writeBehindWorkerSnapshot().lastErrorMessage(),
                metrics.writeBehindWorkerSnapshot().lastErrorRootMessage(),
                metrics.writeBehindWorkerSnapshot().lastErrorOrigin(),
                metrics.writeBehindWorkerSnapshot().lastErrorStackTrace()
        );
        addBackgroundWorkerError(
                items,
                "dead-letter-recovery-worker",
                "recovery",
                metrics.deadLetterRecoverySnapshot().lastErrorAtEpochMillis(),
                metrics.deadLetterRecoverySnapshot().lastErrorType(),
                metrics.deadLetterRecoverySnapshot().lastErrorRootType(),
                metrics.deadLetterRecoverySnapshot().lastErrorMessage(),
                metrics.deadLetterRecoverySnapshot().lastErrorRootMessage(),
                metrics.deadLetterRecoverySnapshot().lastErrorOrigin(),
                metrics.deadLetterRecoverySnapshot().lastErrorStackTrace()
        );
        addBackgroundWorkerError(
                items,
                "recovery-cleanup-worker",
                "cleanup",
                metrics.recoveryCleanupSnapshot().lastErrorAtEpochMillis(),
                metrics.recoveryCleanupSnapshot().lastErrorType(),
                metrics.recoveryCleanupSnapshot().lastErrorRootType(),
                metrics.recoveryCleanupSnapshot().lastErrorMessage(),
                metrics.recoveryCleanupSnapshot().lastErrorRootMessage(),
                metrics.recoveryCleanupSnapshot().lastErrorOrigin(),
                metrics.recoveryCleanupSnapshot().lastErrorStackTrace()
        );
        addBackgroundWorkerError(
                items,
                "incident-delivery-recovery-worker",
                "incident-delivery-recovery",
                metrics.incidentDeliverySnapshot().recovery().lastErrorAtEpochMillis(),
                metrics.incidentDeliverySnapshot().recovery().lastErrorType(),
                metrics.incidentDeliverySnapshot().recovery().lastErrorRootType(),
                metrics.incidentDeliverySnapshot().recovery().lastErrorMessage(),
                metrics.incidentDeliverySnapshot().recovery().lastErrorRootMessage(),
                metrics.incidentDeliverySnapshot().recovery().lastErrorOrigin(),
                metrics.incidentDeliverySnapshot().recovery().lastErrorStackTrace()
        );
        addBackgroundWorkerError(
                items,
                "admin-report-job",
                "admin-report-job",
                metrics.adminReportJobSnapshot().lastErrorAtEpochMillis(),
                metrics.adminReportJobSnapshot().lastErrorType(),
                metrics.adminReportJobSnapshot().lastErrorRootType(),
                metrics.adminReportJobSnapshot().lastErrorMessage(),
                metrics.adminReportJobSnapshot().lastErrorRootMessage(),
                metrics.adminReportJobSnapshot().lastErrorOrigin(),
                metrics.adminReportJobSnapshot().lastErrorStackTrace()
        );
        addBackgroundWorkerError(
                items,
                "projection-refresh-worker",
                "projection-refresh",
                metrics.projectionRefreshSnapshot().lastErrorAtEpochMillis(),
                metrics.projectionRefreshSnapshot().lastErrorType(),
                metrics.projectionRefreshSnapshot().lastErrorRootType(),
                metrics.projectionRefreshSnapshot().lastErrorMessage(),
                metrics.projectionRefreshSnapshot().lastErrorRootMessage(),
                metrics.projectionRefreshSnapshot().lastErrorOrigin(),
                ""
        );
        items.sort(Comparator.comparingLong(BackgroundWorkerErrorSnapshot::lastErrorAtEpochMillis).reversed());
        return List.copyOf(items);
    }

    public MonitoringTriageSnapshot triage() {
        return cachedSnapshot(
                lastTriageSnapshot,
                lastTriageSampleAtEpochMillis,
                1_500L,
                this::collectTriage
        );
    }

    private MonitoringTriageSnapshot collectTriage() {
        ReconciliationHealth health = health();
        ReconciliationMetrics metrics = health.metrics();
        ArrayList<String> evidence = new ArrayList<>();
        String primaryBottleneck = "healthy";
        String suspectedCause = "No active production-grade signal exceeds current thresholds.";

        if (metrics.redisGuardrailSnapshot().hardRejectedWriteCount() > 0) {
            primaryBottleneck = "producer-guardrail";
            suspectedCause = "Redis hard limit is rejecting producer writes.";
            evidence.add("hardRejectedWriteCount=" + metrics.redisGuardrailSnapshot().hardRejectedWriteCount());
        } else if (metrics.writeBehindStreamLength() >= monitoringConfig.writeBehindCriticalThreshold()) {
            primaryBottleneck = "write-behind";
            suspectedCause = "Write-behind backlog is above the critical threshold.";
            evidence.add("writeBehindBacklog=" + metrics.writeBehindStreamLength());
            evidence.add("lastObservedBacklog=" + metrics.writeBehindWorkerSnapshot().lastObservedBacklog());
        } else if (metrics.writeBehindStreamLength() >= monitoringConfig.writeBehindWarnThreshold()) {
            primaryBottleneck = "write-behind";
            suspectedCause = "Write-behind backlog is above the warning threshold.";
            evidence.add("writeBehindBacklog=" + metrics.writeBehindStreamLength());
            evidence.add("lastObservedBacklog=" + metrics.writeBehindWorkerSnapshot().lastObservedBacklog());
        } else if (metrics.deadLetterStreamLength() >= monitoringConfig.deadLetterWarnThreshold()) {
            primaryBottleneck = "recovery";
            suspectedCause = "Dead-letter backlog suggests recovery throughput or replay correctness pressure.";
            evidence.add("deadLetterBacklog=" + metrics.deadLetterStreamLength());
            evidence.add("recoveryFailedCount=" + metrics.deadLetterRecoverySnapshot().failedCount());
        } else if (redisGuardrailConfig.usedMemoryWarnBytes() > 0
                && metrics.redisGuardrailSnapshot().usedMemoryBytes() >= redisGuardrailConfig.usedMemoryWarnBytes()) {
            primaryBottleneck = "redis-memory";
            suspectedCause = "Redis memory is near the configured guardrail threshold.";
            evidence.add("usedMemoryBytes=" + metrics.redisGuardrailSnapshot().usedMemoryBytes());
            evidence.add("compactionPendingCount=" + metrics.redisGuardrailSnapshot().compactionPendingCount());
        } else if (cacheDatabaseConfig.projectionRefresh().deadLetterWarnThreshold() > 0
                && metrics.projectionRefreshSnapshot().deadLetterStreamLength() >= cacheDatabaseConfig.projectionRefresh().deadLetterWarnThreshold()) {
            primaryBottleneck = "projection-refresh";
            suspectedCause = "Projection refresh dead-letter backlog suggests stale read models or poisoned refresh events.";
            evidence.add("projectionRefreshDlq=" + metrics.projectionRefreshSnapshot().deadLetterStreamLength());
            evidence.add("projectionRefreshFailed=" + metrics.projectionRefreshSnapshot().failedCount());
        } else if (!degradedQueryIndexes().isEmpty()) {
            primaryBottleneck = "query-index";
            suspectedCause = "Query index maintenance is degraded and the system may be falling back to scans.";
            evidence.add("degradedIndexes=" + degradedQueryIndexes().size());
            evidence.add("runtimeProfile=" + metrics.redisRuntimeProfileSnapshot().activeProfile());
        } else if (recentError(metrics.writeBehindWorkerSnapshot().lastErrorAtEpochMillis())
                || recentError(metrics.deadLetterRecoverySnapshot().lastErrorAtEpochMillis())
                || recentError(metrics.recoveryCleanupSnapshot().lastErrorAtEpochMillis())
                || recentError(metrics.projectionRefreshSnapshot().lastErrorAtEpochMillis())) {
            primaryBottleneck = "worker-errors";
            suspectedCause = inferredWorkerErrorCause(metrics);
            evidence.add("writeBehindLastError=" + metrics.writeBehindWorkerSnapshot().lastErrorType());
            evidence.add("writeBehindLastErrorMessage=" + metrics.writeBehindWorkerSnapshot().lastErrorMessage());
            evidence.add("recoveryLastError=" + metrics.deadLetterRecoverySnapshot().lastErrorType());
            evidence.add("recoveryLastErrorMessage=" + metrics.deadLetterRecoverySnapshot().lastErrorMessage());
            evidence.add("projectionRefreshLastError=" + metrics.projectionRefreshSnapshot().lastErrorType());
            evidence.add("projectionRefreshLastErrorMessage=" + metrics.projectionRefreshSnapshot().lastErrorMessage());
        } else {
            evidence.add("health=" + health.status().name());
            evidence.add("runtimeProfile=" + metrics.redisRuntimeProfileSnapshot().activeProfile());
            evidence.add("writeBehindBacklog=" + metrics.writeBehindStreamLength());
        }

        if (evidence.isEmpty()) {
            evidence.add("health=" + health.status().name());
        }
        return new MonitoringTriageSnapshot(
                health.status().name(),
                primaryBottleneck,
                suspectedCause,
                List.copyOf(evidence),
                Instant.now()
        );
    }

    private String inferredWorkerErrorCause(ReconciliationMetrics metrics) {
        String writeBehindMessage = metrics.writeBehindWorkerSnapshot().lastErrorMessage() == null
                ? ""
                : metrics.writeBehindWorkerSnapshot().lastErrorMessage();
        if (writeBehindMessage.contains("ON CONFLICT DO UPDATE command cannot affect row a second time")) {
            return "Write-behind batch sent the same entity id to PostgreSQL more than once in a single bulk upsert statement.";
        }
        String projectionRefreshMessage = defaultString(metrics.projectionRefreshSnapshot().lastErrorMessage());
        if (!projectionRefreshMessage.isBlank()) {
            return "Projection refresh worker observed a recent error and read-model updates may be lagging behind writes.";
        }
        return "A recent worker error was observed even though backlog thresholds are not yet critical.";
    }

    private void addBackgroundWorkerError(
            List<BackgroundWorkerErrorSnapshot> items,
            String workerName,
            String serviceName,
            long lastErrorAtEpochMillis,
            String errorType,
            String rootErrorType,
            String errorMessage,
            String rootErrorMessage,
            String origin,
            String stackTrace
    ) {
        if (lastErrorAtEpochMillis <= 0) {
            return;
        }
        boolean recent = recentError(lastErrorAtEpochMillis);
        items.add(new BackgroundWorkerErrorSnapshot(
                workerName,
                serviceName,
                recent ? AdminHealthStatus.DEGRADED.name() : AdminHealthStatus.UP.name(),
                recent,
                lastErrorAtEpochMillis,
                defaultString(errorType),
                defaultString(rootErrorType),
                defaultString(errorMessage),
                defaultString(rootErrorMessage),
                defaultString(origin),
                defaultString(stackTrace)
        ));
    }

    private String workerErrorSummary(String errorType, String origin) {
        if ((errorType == null || errorType.isBlank()) && (origin == null || origin.isBlank())) {
            return "";
        }
        String type = defaultString(errorType);
        String source = defaultString(origin);
        if (source.isBlank()) {
            return ", recentError=" + type;
        }
        return ", recentError=" + type + "@" + source;
    }

    public List<AlertRouteSnapshot> alertRoutes() {
        return cachedSnapshot(
                lastAlertRoutesSnapshot,
                lastAlertRoutesSampleAtEpochMillis,
                1_500L,
                this::collectAlertRoutes
        );
    }

    private List<AlertRouteSnapshot> collectAlertRoutes() {
        ReconciliationMetrics metrics = metrics();
        java.util.Map<String, com.reactor.cachedb.core.queue.IncidentChannelSnapshot> channelMap = new java.util.LinkedHashMap<>();
        for (com.reactor.cachedb.core.queue.IncidentChannelSnapshot channel : metrics.incidentDeliverySnapshot().channels()) {
            channelMap.put(channel.channelName(), channel);
        }
        ArrayList<AlertRouteSnapshot> routes = new ArrayList<>();
        com.reactor.cachedb.core.queue.IncidentChannelSnapshot webhookChannel = channelMap.get("webhook");
        routes.add(new AlertRouteSnapshot(
                "webhook",
                monitoringConfig.incidentWebhook().enabled(),
                defaultString(monitoringConfig.incidentWebhook().endpointUrl()),
                "http-webhook",
                "Immediate notify on persisted incidents",
                escalationLevel(webhookChannel, monitoringConfig.incidentWebhook().enabled()),
                "Persisted incident stream",
                "retries=" + monitoringConfig.incidentWebhook().maxRetries()
                        + ", backoffMs=" + monitoringConfig.incidentWebhook().retryBackoffMillis(),
                monitoringConfig.incidentDeliveryDlq().enabled() ? "delivery-dlq" : "none",
                channelStatus(webhookChannel),
                webhookChannel == null ? 0L : webhookChannel.deliveredCount(),
                webhookChannel == null ? 0L : webhookChannel.failedCount(),
                webhookChannel == null ? 0L : webhookChannel.droppedCount(),
                webhookChannel == null ? 0L : webhookChannel.lastDeliveredAtEpochMillis(),
                webhookChannel == null ? 0L : webhookChannel.lastErrorAtEpochMillis(),
                webhookChannel == null ? "" : defaultString(webhookChannel.lastErrorType())
        ));
        com.reactor.cachedb.core.queue.IncidentChannelSnapshot queueChannel = channelMap.get("queue");
        routes.add(new AlertRouteSnapshot(
                "queue",
                monitoringConfig.incidentQueue().enabled(),
                defaultString(monitoringConfig.incidentQueue().streamKey()),
                "redis-stream",
                "Queue downstream incident processors",
                escalationLevel(queueChannel, monitoringConfig.incidentQueue().enabled()),
                "Persisted incident stream",
                "retries=" + monitoringConfig.incidentQueue().maxRetries()
                        + ", backoffMs=" + monitoringConfig.incidentQueue().retryBackoffMillis(),
                "none",
                channelStatus(queueChannel),
                queueChannel == null ? 0L : queueChannel.deliveredCount(),
                queueChannel == null ? 0L : queueChannel.failedCount(),
                queueChannel == null ? 0L : queueChannel.droppedCount(),
                queueChannel == null ? 0L : queueChannel.lastDeliveredAtEpochMillis(),
                queueChannel == null ? 0L : queueChannel.lastErrorAtEpochMillis(),
                queueChannel == null ? "" : defaultString(queueChannel.lastErrorType())
        ));
        com.reactor.cachedb.core.queue.IncidentChannelSnapshot smtpChannel = channelMap.get("smtp");
        routes.add(new AlertRouteSnapshot(
                "smtp",
                monitoringConfig.incidentEmail().enabled(),
                defaultString(monitoringConfig.incidentEmail().smtpHost())
                        + (monitoringConfig.incidentEmail().smtpPort() > 0 ? ":" + monitoringConfig.incidentEmail().smtpPort() : ""),
                "smtp-email",
                "Operator-facing email notifications",
                escalationLevel(smtpChannel, monitoringConfig.incidentEmail().enabled()),
                "Persisted incident stream",
                "retries=" + monitoringConfig.incidentEmail().maxRetries()
                        + ", backoffMs=" + monitoringConfig.incidentEmail().retryBackoffMillis(),
                monitoringConfig.incidentDeliveryDlq().enabled() ? "delivery-dlq" : "none",
                channelStatus(smtpChannel),
                smtpChannel == null ? 0L : smtpChannel.deliveredCount(),
                smtpChannel == null ? 0L : smtpChannel.failedCount(),
                smtpChannel == null ? 0L : smtpChannel.droppedCount(),
                smtpChannel == null ? 0L : smtpChannel.lastDeliveredAtEpochMillis(),
                smtpChannel == null ? 0L : smtpChannel.lastErrorAtEpochMillis(),
                smtpChannel == null ? "" : defaultString(smtpChannel.lastErrorType())
        ));
        routes.add(new AlertRouteSnapshot(
                "delivery-dlq",
                monitoringConfig.incidentDeliveryDlq().enabled(),
                defaultString(monitoringConfig.incidentDeliveryDlq().streamKey()),
                "redis-stream-dlq",
                "Recovery path when alert delivery itself fails",
                metrics.incidentDeliverySnapshot().recovery().failedReplayCount() > 0 ? "WARNING" : "INFO",
                "Alert delivery failures only",
                "replayAttempts=" + monitoringConfig.incidentDeliveryDlq().maxReplayAttempts()
                        + ", claimIdleMs=" + monitoringConfig.incidentDeliveryDlq().claimIdleMillis(),
                defaultString(monitoringConfig.incidentDeliveryDlq().recoveryStreamKey()),
                metrics.incidentDeliverySnapshot().recovery().failedReplayCount() > 0 ? AdminHealthStatus.DEGRADED.name() : AdminHealthStatus.UP.name(),
                metrics.incidentDeliverySnapshot().recovery().replayedCount(),
                metrics.incidentDeliverySnapshot().recovery().failedReplayCount(),
                0L,
                0L,
                metrics.incidentDeliverySnapshot().recovery().lastErrorAtEpochMillis(),
                defaultString(metrics.incidentDeliverySnapshot().recovery().lastErrorType())
        ));
        return List.copyOf(routes);
    }

    public List<RunbookSnapshot> runbooks() {
        return List.of(
                new RunbookSnapshot(
                        "WRITE_BEHIND_BACKLOG",
                        "Write-behind backlog triage",
                        "Backlog crosses warning/critical thresholds",
                        "Inspect /api/metrics and worker snapshot, then confirm drain slope and runtime profile.",
                        "/api/metrics"
                ),
                new RunbookSnapshot(
                        "DEAD_LETTER_BACKLOG",
                        "Dead-letter recovery triage",
                        "Dead-letter stream grows or replay failures increase",
                        "Review /api/incidents, then inspect reconciliation and delivery recovery snapshots.",
                        "/api/incidents"
                ),
                new RunbookSnapshot(
                        "REDIS_MEMORY_PRESSURE",
                        "Redis memory pressure triage",
                        "Redis used memory or compaction pending rises into warn/critical band",
                        "Inspect /api/metrics and runtime profile churn, confirm whether shedding/profile switch occurred.",
                        "/api/profile-churn"
                ),
                new RunbookSnapshot(
                        "QUERY_INDEX_DEGRADED",
                        "Query index degradation triage",
                        "Explain/debug path falls back to full scans or degraded indexes appear",
                        "Inspect degraded entities and trigger targeted rebuild only after confirming current pressure level.",
                        "/api/query-index/rebuild"
                ),
                new RunbookSnapshot(
                        "SCHEMA_MIGRATION",
                        "Schema lifecycle triage",
                        "Schema validation fails or migration history shows failed apply",
                        "Inspect schema status, plan, and DDL before applying additional migration steps.",
                        "/api/schema/status"
                )
        );
    }

    public String exportPrometheusAlertRules() {
        StringBuilder builder = new StringBuilder();
        builder.append("groups:\n");
        builder.append("  - name: cachedb.rules\n");
        builder.append("    rules:\n");
        appendAlertRule(builder, "CacheDbWriteBehindBacklogWarning",
                "cachedb_write_behind_stream_length >= " + monitoringConfig.writeBehindWarnThreshold(),
                "warning",
                "Write-behind backlog is above warning threshold");
        appendAlertRule(builder, "CacheDbWriteBehindBacklogCritical",
                "cachedb_write_behind_stream_length >= " + monitoringConfig.writeBehindCriticalThreshold(),
                "critical",
                "Write-behind backlog is above critical threshold");
        appendAlertRule(builder, "CacheDbDeadLetterCritical",
                "cachedb_dead_letter_stream_length >= " + monitoringConfig.deadLetterCriticalThreshold(),
                "critical",
                "Dead-letter backlog is above critical threshold");
        if (redisGuardrailConfig.usedMemoryWarnBytes() > 0) {
            appendAlertRule(builder, "CacheDbRedisMemoryWarning",
                    "cachedb_redis_used_memory_bytes >= " + redisGuardrailConfig.usedMemoryWarnBytes(),
                    "warning",
                    "Redis memory is above warning threshold");
        }
        if (redisGuardrailConfig.usedMemoryCriticalBytes() > 0) {
            appendAlertRule(builder, "CacheDbRedisMemoryCritical",
                    "cachedb_redis_used_memory_bytes >= " + redisGuardrailConfig.usedMemoryCriticalBytes(),
                    "critical",
                    "Redis memory is above critical threshold");
        }
        appendAlertRule(builder, "CacheDbHardRejectedWrites",
                "cachedb_redis_hard_rejected_writes_total > 0",
                "critical",
                "Redis hard limit rejected writes");
        if (cacheDatabaseConfig.projectionRefresh().deadLetterWarnThreshold() > 0) {
            appendAlertRule(builder, "CacheDbProjectionRefreshDlqWarning",
                    "cachedb_projection_refresh_dead_letter_stream_length >= " + cacheDatabaseConfig.projectionRefresh().deadLetterWarnThreshold(),
                    "warning",
                    "Projection refresh dead-letter backlog is above warning threshold");
        }
        if (cacheDatabaseConfig.projectionRefresh().deadLetterCriticalThreshold() > 0) {
            appendAlertRule(builder, "CacheDbProjectionRefreshDlqCritical",
                    "cachedb_projection_refresh_dead_letter_stream_length >= " + cacheDatabaseConfig.projectionRefresh().deadLetterCriticalThreshold(),
                    "critical",
                    "Projection refresh dead-letter backlog is above critical threshold");
        }
        return builder.toString();
    }

    public List<AdminAlertRule> alertRules() {
        return List.of(
                new AdminAlertRule("WRITE_BEHIND_BACKLOG", "Write-behind backlog threshold", AdminIncidentSeverity.WARNING, AdminIncidentSeverity.CRITICAL),
                new AdminAlertRule("DEAD_LETTER_BACKLOG", "Dead-letter backlog threshold", AdminIncidentSeverity.WARNING, AdminIncidentSeverity.CRITICAL),
                new AdminAlertRule("RECOVERY_FAILURES", "Recovery failure threshold", AdminIncidentSeverity.WARNING, AdminIncidentSeverity.CRITICAL),
                new AdminAlertRule("REDIS_MEMORY_PRESSURE", "Redis used memory threshold", AdminIncidentSeverity.WARNING, AdminIncidentSeverity.CRITICAL),
                new AdminAlertRule("COMPACTION_PENDING_BACKLOG", "Compaction pending threshold", AdminIncidentSeverity.WARNING, AdminIncidentSeverity.CRITICAL),
                new AdminAlertRule("REDIS_HARD_REJECTIONS", "Redis hard limit rejected writes", AdminIncidentSeverity.CRITICAL, AdminIncidentSeverity.CRITICAL),
                new AdminAlertRule("RECENT_WORKER_ERROR", "Recent worker error detected", AdminIncidentSeverity.WARNING, AdminIncidentSeverity.CRITICAL),
                new AdminAlertRule("PROJECTION_REFRESH_DLQ", "Projection refresh dead-letter backlog", AdminIncidentSeverity.WARNING, AdminIncidentSeverity.CRITICAL),
                new AdminAlertRule("PROJECTION_REFRESH_RECENT_ERROR", "Projection refresh worker error detected", AdminIncidentSeverity.WARNING, AdminIncidentSeverity.CRITICAL)
        );
    }

    public List<AdminIncident> incidents() {
        return cachedSnapshot(
                lastIncidentsSnapshot,
                lastIncidentsSampleAtEpochMillis,
                1_500L,
                this::collectIncidents
        );
    }

    private List<AdminIncident> collectIncidents() {
        ReconciliationMetrics metrics = metrics();
        ArrayList<AdminIncident> incidents = new ArrayList<>();
        Instant now = Instant.now();

        if (metrics.writeBehindStreamLength() >= monitoringConfig.writeBehindCriticalThreshold()) {
            incidents.add(incident("WRITE_BEHIND_BACKLOG", "Write-behind backlog exceeded critical threshold", AdminIncidentSeverity.CRITICAL, now, Map.of("value", String.valueOf(metrics.writeBehindStreamLength()))));
        } else if (metrics.writeBehindStreamLength() >= monitoringConfig.writeBehindWarnThreshold()) {
            incidents.add(incident("WRITE_BEHIND_BACKLOG", "Write-behind backlog exceeded warning threshold", AdminIncidentSeverity.WARNING, now, Map.of("value", String.valueOf(metrics.writeBehindStreamLength()))));
        }

        if (metrics.deadLetterStreamLength() >= monitoringConfig.deadLetterCriticalThreshold()) {
            incidents.add(incident("DEAD_LETTER_BACKLOG", "Dead-letter backlog exceeded critical threshold", AdminIncidentSeverity.CRITICAL, now, Map.of("value", String.valueOf(metrics.deadLetterStreamLength()))));
        } else if (metrics.deadLetterStreamLength() >= monitoringConfig.deadLetterWarnThreshold()) {
            incidents.add(incident("DEAD_LETTER_BACKLOG", "Dead-letter backlog exceeded warning threshold", AdminIncidentSeverity.WARNING, now, Map.of("value", String.valueOf(metrics.deadLetterStreamLength()))));
        }

        if (metrics.deadLetterRecoverySnapshot().failedCount() >= monitoringConfig.recoveryFailedCriticalThreshold()) {
            incidents.add(incident("RECOVERY_FAILURES", "Recovery failure count exceeded critical threshold", AdminIncidentSeverity.CRITICAL, now, Map.of("value", String.valueOf(metrics.deadLetterRecoverySnapshot().failedCount()))));
        } else if (metrics.deadLetterRecoverySnapshot().failedCount() >= monitoringConfig.recoveryFailedWarnThreshold()) {
            incidents.add(incident("RECOVERY_FAILURES", "Recovery failure count exceeded warning threshold", AdminIncidentSeverity.WARNING, now, Map.of("value", String.valueOf(metrics.deadLetterRecoverySnapshot().failedCount()))));
        }

        if (redisGuardrailConfig.usedMemoryCriticalBytes() > 0
                && metrics.redisGuardrailSnapshot().usedMemoryBytes() >= redisGuardrailConfig.usedMemoryCriticalBytes()) {
            incidents.add(incident("REDIS_MEMORY_PRESSURE", "Redis used memory exceeded critical threshold", AdminIncidentSeverity.CRITICAL, now, Map.of("value", String.valueOf(metrics.redisGuardrailSnapshot().usedMemoryBytes()))));
        } else if (redisGuardrailConfig.usedMemoryWarnBytes() > 0
                && metrics.redisGuardrailSnapshot().usedMemoryBytes() >= redisGuardrailConfig.usedMemoryWarnBytes()) {
            incidents.add(incident("REDIS_MEMORY_PRESSURE", "Redis used memory exceeded warning threshold", AdminIncidentSeverity.WARNING, now, Map.of("value", String.valueOf(metrics.redisGuardrailSnapshot().usedMemoryBytes()))));
        }

        if (redisGuardrailConfig.compactionPendingCriticalThreshold() > 0
                && metrics.redisGuardrailSnapshot().compactionPendingCount() >= redisGuardrailConfig.compactionPendingCriticalThreshold()) {
            incidents.add(incident("COMPACTION_PENDING_BACKLOG", "Compaction pending count exceeded critical threshold", AdminIncidentSeverity.CRITICAL, now, Map.of("value", String.valueOf(metrics.redisGuardrailSnapshot().compactionPendingCount()))));
        } else if (redisGuardrailConfig.compactionPendingWarnThreshold() > 0
                && metrics.redisGuardrailSnapshot().compactionPendingCount() >= redisGuardrailConfig.compactionPendingWarnThreshold()) {
            incidents.add(incident("COMPACTION_PENDING_BACKLOG", "Compaction pending count exceeded warning threshold", AdminIncidentSeverity.WARNING, now, Map.of("value", String.valueOf(metrics.redisGuardrailSnapshot().compactionPendingCount()))));
        }
        if (metrics.redisGuardrailSnapshot().hardRejectedWriteCount() > 0) {
            incidents.add(incident("REDIS_HARD_REJECTIONS", "Redis hard limit rejected producer writes", AdminIncidentSeverity.CRITICAL, now, Map.of("value", String.valueOf(metrics.redisGuardrailSnapshot().hardRejectedWriteCount()))));
        }

        if (cacheDatabaseConfig.projectionRefresh().deadLetterCriticalThreshold() > 0
                && metrics.projectionRefreshSnapshot().deadLetterStreamLength() >= cacheDatabaseConfig.projectionRefresh().deadLetterCriticalThreshold()) {
            incidents.add(incident("PROJECTION_REFRESH_DLQ", "Projection refresh dead-letter backlog exceeded critical threshold", AdminIncidentSeverity.CRITICAL, now, Map.of("value", String.valueOf(metrics.projectionRefreshSnapshot().deadLetterStreamLength()))));
        } else if (cacheDatabaseConfig.projectionRefresh().deadLetterWarnThreshold() > 0
                && metrics.projectionRefreshSnapshot().deadLetterStreamLength() >= cacheDatabaseConfig.projectionRefresh().deadLetterWarnThreshold()) {
            incidents.add(incident("PROJECTION_REFRESH_DLQ", "Projection refresh dead-letter backlog exceeded warning threshold", AdminIncidentSeverity.WARNING, now, Map.of("value", String.valueOf(metrics.projectionRefreshSnapshot().deadLetterStreamLength()))));
        }

        if (recentError(metrics.writeBehindWorkerSnapshot().lastErrorAtEpochMillis())
                || recentError(metrics.deadLetterRecoverySnapshot().lastErrorAtEpochMillis())
                || recentError(metrics.recoveryCleanupSnapshot().lastErrorAtEpochMillis())
                || recentError(metrics.adminReportJobSnapshot().lastErrorAtEpochMillis())) {
            incidents.add(incident("RECENT_WORKER_ERROR", "At least one background worker reported a recent error", AdminIncidentSeverity.WARNING, now, Map.of()));
        }
        if (recentError(metrics.projectionRefreshSnapshot().lastErrorAtEpochMillis())) {
            incidents.add(incident(
                    "PROJECTION_REFRESH_RECENT_ERROR",
                    "Projection refresh worker reported a recent error",
                    AdminIncidentSeverity.WARNING,
                    now,
                    Map.of("type", defaultString(metrics.projectionRefreshSnapshot().lastErrorType()))
            ));
        }

        return List.copyOf(incidents);
    }

    public List<AdminIncidentRecord> incidentHistory(int limit) {
        int safeLimit = Math.max(1, limit);
        try {
            List<AdminIncidentRecord> fresh = jedis.xrevrange(monitoringConfig.incidentStreamKey(), "+", "-", safeLimit).stream()
                    .map(this::toIncidentRecord)
                    .toList();
            lastIncidentHistory.set(fresh);
            return fresh;
        } catch (RuntimeException exception) {
            List<AdminIncidentRecord> cached = lastIncidentHistory.get();
            if (cached == null || cached.isEmpty()) {
                return List.of();
            }
            return cached.stream()
                    .limit(safeLimit)
                    .toList();
        }
    }

    public List<IncidentSeverityTrendPoint> incidentSeverityHistory(int limit) {
        List<AdminIncidentRecord> records = incidentHistory(Math.max(1, limit));
        if (records.isEmpty()) {
            return List.of();
        }
        long bucketMillis = Math.max(10_000L, monitoringConfig.historySampleIntervalMillis());
        LinkedHashMap<Long, BucketCounts> buckets = new LinkedHashMap<>();
        ArrayList<AdminIncidentRecord> ordered = new ArrayList<>(records);
        Collections.reverse(ordered);
        for (AdminIncidentRecord record : ordered) {
            long bucket = (record.recordedAt().toEpochMilli() / bucketMillis) * bucketMillis;
            BucketCounts counts = buckets.computeIfAbsent(bucket, ignored -> new BucketCounts());
            counts.total++;
            switch (record.severity()) {
                case INFO -> counts.info++;
                case WARNING -> counts.warning++;
                case CRITICAL -> counts.critical++;
            }
        }
        return buckets.entrySet().stream()
                .map(entry -> new IncidentSeverityTrendPoint(
                        Instant.ofEpochMilli(entry.getKey()),
                        entry.getValue().info,
                        entry.getValue().warning,
                        entry.getValue().critical,
                        entry.getValue().total
                ))
                .toList();
    }

    public List<FailingSignalSnapshot> topFailingSignals(int limit) {
        int safeLimit = Math.max(1, limit);
        try {
            LinkedHashMap<String, SignalAggregate> aggregates = new LinkedHashMap<>();
            for (AdminIncident incident : incidents()) {
                SignalAggregate aggregate = aggregates.computeIfAbsent(incident.code(), SignalAggregate::new);
                aggregate.description = incident.description();
                aggregate.activeCount++;
                aggregate.severity = maxSeverity(aggregate.severity, incident.severity());
                aggregate.lastSeenAt = maxInstant(aggregate.lastSeenAt, incident.detectedAt());
            }
            for (AdminIncidentRecord record : incidentHistory(Math.max(24, safeLimit * 8))) {
                SignalAggregate aggregate = aggregates.computeIfAbsent(record.code(), SignalAggregate::new);
                if (aggregate.description == null || aggregate.description.isBlank()) {
                    aggregate.description = record.description();
                }
                aggregate.recentCount++;
                aggregate.severity = maxSeverity(aggregate.severity, record.severity());
                aggregate.lastSeenAt = maxInstant(aggregate.lastSeenAt, record.recordedAt());
            }
            List<FailingSignalSnapshot> fresh = aggregates.values().stream()
                    .sorted(Comparator
                            .comparingInt((SignalAggregate value) -> severityRank(value.severity)).reversed()
                            .thenComparingLong(value -> value.activeCount).reversed()
                            .thenComparingLong(value -> value.recentCount).reversed()
                            .thenComparing(value -> value.lastSeenAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(safeLimit)
                    .map(value -> new FailingSignalSnapshot(
                            value.code,
                            value.severity == null ? AdminIncidentSeverity.INFO.name() : value.severity.name(),
                            value.activeCount,
                            value.recentCount,
                            summarizeSignal(value),
                            value.lastSeenAt == null ? Instant.EPOCH : value.lastSeenAt
                    ))
                    .toList();
            lastFailingSignals.set(fresh);
            return fresh;
        } catch (RuntimeException exception) {
            List<FailingSignalSnapshot> cached = lastFailingSignals.get();
            if (cached == null || cached.isEmpty()) {
                return List.of();
            }
            return cached.stream()
                    .limit(safeLimit)
                    .toList();
        }
    }

    public List<AdminIncidentRecord> persistIncidents(String source) {
        ArrayList<AdminIncidentRecord> persisted = new ArrayList<>();
        for (AdminIncident incident : incidents()) {
            String cooldownKey = monitoringConfig.incidentStreamKey() + ":cooldown:" + incident.code();
            String lastRecorded = jedis.get(cooldownKey);
            long now = System.currentTimeMillis();
            if (lastRecorded != null && !lastRecorded.isBlank() && now - Long.parseLong(lastRecorded) < monitoringConfig.incidentCooldownMillis()) {
                continue;
            }

            LinkedHashMap<String, String> fields = new LinkedHashMap<>();
            fields.put("code", incident.code());
            fields.put("description", incident.description());
            fields.put("severity", incident.severity().name());
            fields.put("source", source == null ? "" : source);
            fields.put("recordedAt", incident.detectedAt().toString());
            fields.putAll(incident.fields());
            String entryId = jedis.xadd(monitoringConfig.incidentStreamKey(), XAddParams.xAddParams(), fields).toString();
            if (monitoringConfig.incidentMaxLength() > 0) {
                jedis.xtrim(monitoringConfig.incidentStreamKey(), monitoringConfig.incidentMaxLength(), true);
            }
            RedisTelemetrySupport.expireIfNeeded(jedis, monitoringConfig.incidentStreamKey(), monitoringConfig.incidentTtlSeconds());
            jedis.psetex(
                    cooldownKey,
                    Math.max(1L, Math.max(monitoringConfig.incidentCooldownMillis(), monitoringConfig.incidentTtlSeconds() * 1_000L)),
                    String.valueOf(now)
            );
            persisted.add(new AdminIncidentRecord(
                    entryId,
                    incident.code(),
                    incident.description(),
                    incident.severity(),
                    fields.get("source"),
                    Instant.parse(fields.get("recordedAt")),
                    Map.copyOf(fields)
            ));
            incidentNotifier.accept(persisted.get(persisted.size() - 1));
        }
        return List.copyOf(persisted);
    }

    public AdminExportResult exportDiagnostics(AdminExportFormat format, int limit) {
        List<AdminDiagnosticsRecord> items = diagnostics(limit);
        List<String> headers = List.of(
                "entryId", "source", "note", "status", "recordedAt",
                "writeBehindStreamLength", "deadLetterStreamLength",
                "reconciliationStreamLength", "archiveStreamLength",
                "cleanupRunCount", "reportRunCount",
                "runtimeProfile", "runtimePressureLevel",
                "runtimeProfileSwitchCount", "runtimeProfileLastSwitchedAt",
                "eventType", "fromProfile", "toProfile", "pressureLevel"
        );
        List<List<String>> rows = items.stream()
                .map(record -> List.of(
                        record.entryId(),
                        record.source(),
                        record.note(),
                        record.status().name(),
                        record.recordedAt().toString(),
                        record.fields().getOrDefault("writeBehindStreamLength", "0"),
                        record.fields().getOrDefault("deadLetterStreamLength", "0"),
                        record.fields().getOrDefault("reconciliationStreamLength", "0"),
                        record.fields().getOrDefault("archiveStreamLength", "0"),
                        record.fields().getOrDefault("cleanupRunCount", "0"),
                        record.fields().getOrDefault("reportRunCount", "0"),
                        record.fields().getOrDefault("runtimeProfile", ""),
                        record.fields().getOrDefault("runtimePressureLevel", ""),
                        record.fields().getOrDefault("runtimeProfileSwitchCount", "0"),
                        record.fields().getOrDefault("runtimeProfileLastSwitchedAt", "0"),
                        record.fields().getOrDefault("eventType", ""),
                        record.fields().getOrDefault("fromProfile", ""),
                        record.fields().getOrDefault("toProfile", ""),
                        record.fields().getOrDefault("pressureLevel", "")
                ))
                .toList();
        return new AdminExportResult(
                "diagnostics." + format.fileExtension(),
                format,
                format.contentType(),
                renderTable("diagnostics", "limit=" + limit, headers, rows, format)
        );
    }

    public AdminExportResult exportIncidents(AdminExportFormat format, int limit) {
        List<AdminIncidentRecord> items = incidentHistory(limit);
        List<String> headers = List.of("entryId", "code", "description", "severity", "source", "recordedAt");
        List<List<String>> rows = items.stream()
                .map(record -> List.of(
                        record.entryId(),
                        record.code(),
                        record.description(),
                        record.severity().name(),
                        record.source(),
                        record.recordedAt().toString()
                ))
                .toList();
        return new AdminExportResult(
                "incidents." + format.fileExtension(),
                format,
                format.contentType(),
                renderTable("incidents", "limit=" + limit, headers, rows, format)
        );
    }

    public AdminExportResult exportRuntimeProfileChurn(AdminExportFormat format, int limit) {
        List<RuntimeProfileChurnRecord> items = runtimeProfileChurn(limit);
        List<String> headers = List.of(
                "entryId", "source", "note", "fromProfile", "toProfile",
                "pressureLevel", "recordedAt", "usedMemoryBytes",
                "writeBehindBacklog", "compactionPendingCount", "switchCount"
        );
        List<List<String>> rows = items.stream()
                .map(record -> List.of(
                        record.entryId(),
                        record.source(),
                        record.note(),
                        record.fromProfile(),
                        record.toProfile(),
                        record.pressureLevel(),
                        record.recordedAt().toString(),
                        record.fields().getOrDefault("usedMemoryBytes", "0"),
                        record.fields().getOrDefault("writeBehindBacklog", "0"),
                        record.fields().getOrDefault("compactionPendingCount", "0"),
                        record.fields().getOrDefault("switchCount", "0")
                ))
                .toList();
        return new AdminExportResult(
                "runtime-profile-churn." + format.fileExtension(),
                format,
                format.contentType(),
                renderTable("runtime-profile-churn", "limit=" + limit, headers, rows, format)
        );
    }

    public Path writeDeadLetterReport(Path outputPath, DeadLetterQuery query, AdminExportFormat format) throws IOException {
        return writeExport(outputPath, exportDeadLetters(query, format));
    }

    public Path writeReconciliationReport(Path outputPath, ReconciliationQuery query, AdminExportFormat format) throws IOException {
        return writeExport(outputPath, exportReconciliation(query, format));
    }

    public Path writeArchiveReport(Path outputPath, ReconciliationQuery query, AdminExportFormat format) throws IOException {
        return writeExport(outputPath, exportArchive(query, format));
    }

    public Path writeDiagnosticsReport(Path outputPath, AdminExportFormat format, int limit) throws IOException {
        return writeExport(outputPath, exportDiagnostics(format, limit));
    }

    public Path writeIncidentReport(Path outputPath, AdminExportFormat format, int limit) throws IOException {
        return writeExport(outputPath, exportIncidents(format, limit));
    }

    public Path writeRuntimeProfileChurnReport(Path outputPath, AdminExportFormat format, int limit) throws IOException {
        return writeExport(outputPath, exportRuntimeProfileChurn(format, limit));
    }

    private long streamLength(String streamKey) {
        try {
            return jedis.xlen(streamKey);
        } catch (RuntimeException exception) {
            return 0L;
        }
    }

    private long streamLength(List<String> streamKeys) {
        long total = 0L;
        for (String streamKey : streamKeys) {
            total += streamLength(streamKey);
        }
        return total;
    }

    private long writeBehindBacklog() {
        com.reactor.cachedb.redis.RedisKeyStrategy keyStrategy = new com.reactor.cachedb.redis.RedisKeyStrategy(keyPrefix);
        try {
            Map<String, String> stats = jedis.hgetAll(keyStrategy.compactionStatsKey());
            long pendingCount = parseLong(stats.get("pendingCount"));
            if (pendingCount > 0L) {
                return pendingCount;
            }
            if (!stats.isEmpty()) {
                return 0L;
            }
        } catch (RuntimeException ignored) {
            // Fall back to stream lengths below.
        }
        return streamLength(writeBehindStreamKeys);
    }

    private ReconciliationMetrics collectMetrics() {
        return new ReconciliationMetrics(
                writeBehindBacklog(),
                streamLength(deadLetterStreamKey),
                streamLength(reconciliationStreamKey),
                streamLength(archiveStreamKey),
                streamLength(diagnosticsStreamKey),
                projectionRefresh(),
                writeBehindSnapshotSupplier.get(),
                deadLetterRecoverySnapshotSupplier.get(),
                recoveryCleanupSnapshotSupplier.get(),
                adminReportJobSnapshotSupplier.get(),
                incidentDeliverySnapshotSupplier.get(),
                plannerStatisticsSnapshot(),
                redisGuardrailSnapshotSupplier.get(),
                redisRuntimeProfileSnapshotSupplier.get()
        );
    }

    private PlannerStatisticsSnapshot plannerStatisticsSnapshot() {
        return new PlannerStatisticsSnapshot(
                keyCount(keyPrefix + ":*:index:stats:estimate:*"),
                keyCount(keyPrefix + ":*:index:stats:histogram:*"),
                keyCount(keyPrefix + ":*:index:stats:learned:*")
        );
    }

    private long keyCount(String pattern) {
        try {
            return jedis.keys(pattern).size();
        } catch (RuntimeException exception) {
            return 0L;
        }
    }

    private boolean recentError(long lastErrorAtEpochMillis) {
        return lastErrorAtEpochMillis > 0
                && System.currentTimeMillis() - lastErrorAtEpochMillis <= monitoringConfig.recentErrorWindowMillis();
    }

    private AdminDiagnosticsRecord toDiagnosticsRecord(StreamEntry entry) {
        Map<String, String> fields = Map.copyOf(entry.getFields());
        return new AdminDiagnosticsRecord(
                entry.getID().toString(),
                fields.getOrDefault("source", ""),
                fields.getOrDefault("note", ""),
                AdminHealthStatus.valueOf(fields.getOrDefault("status", "UP")),
                Instant.parse(fields.getOrDefault("recordedAt", Instant.EPOCH.toString())),
                fields
        );
    }

    private AdminIncidentRecord toIncidentRecord(StreamEntry entry) {
        Map<String, String> fields = Map.copyOf(entry.getFields());
        return new AdminIncidentRecord(
                entry.getID().toString(),
                fields.getOrDefault("code", ""),
                fields.getOrDefault("description", ""),
                AdminIncidentSeverity.valueOf(fields.getOrDefault("severity", "INFO")),
                fields.getOrDefault("source", ""),
                Instant.parse(fields.getOrDefault("recordedAt", Instant.EPOCH.toString())),
                fields
        );
    }

    private RuntimeProfileChurnRecord toRuntimeProfileChurnRecord(StreamEntry entry) {
        Map<String, String> fields = Map.copyOf(entry.getFields());
        return new RuntimeProfileChurnRecord(
                entry.getID().toString(),
                fields.getOrDefault("source", ""),
                fields.getOrDefault("note", ""),
                fields.getOrDefault("fromProfile", ""),
                fields.getOrDefault("toProfile", ""),
                fields.getOrDefault("pressureLevel", ""),
                Instant.parse(fields.getOrDefault("recordedAt", Instant.EPOCH.toString())),
                fields
        );
    }

    private AdminIncident incident(
            String code,
            String description,
            AdminIncidentSeverity severity,
            Instant detectedAt,
            Map<String, String> fields
    ) {
        return new AdminIncident(code, description, severity, detectedAt, fields);
    }

    private Path writeExport(Path outputPath, AdminExportResult exportResult) throws IOException {
        Path normalized = outputPath.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(normalized, exportResult.content());
        return normalized;
    }

    private AdminHealthStatus elevate(AdminHealthStatus current, AdminHealthStatus candidate) {
        if (candidate == AdminHealthStatus.DOWN || current == AdminHealthStatus.DOWN) {
            return AdminHealthStatus.DOWN;
        }
        if (candidate == AdminHealthStatus.DEGRADED || current == AdminHealthStatus.DEGRADED) {
            return AdminHealthStatus.DEGRADED;
        }
        return AdminHealthStatus.UP;
    }

    private List<DeadLetterEntry> collectDeadLetters(DeadLetterQuery query) {
        ArrayList<DeadLetterEntry> entries = new ArrayList<>();
        String cursor = query.cursor();
        while (true) {
            StreamPage<DeadLetterEntry> page = deadLetterManagement.queryDeadLetters(new DeadLetterQuery(
                    query.limit(),
                    cursor,
                    query.entityName(),
                    query.operationType(),
                    query.entityId(),
                    query.errorType()
            ));
            entries.addAll(page.items());
            if (!page.hasMore() || page.nextCursor() == null) {
                return List.copyOf(entries);
            }
            cursor = page.nextCursor();
        }
    }

    private List<ReconciliationRecord> collectReconciliation(ReconciliationQuery query) {
        ArrayList<ReconciliationRecord> entries = new ArrayList<>();
        String cursor = query.cursor();
        while (true) {
            StreamPage<ReconciliationRecord> page = deadLetterManagement.queryReconciliation(new ReconciliationQuery(
                    query.limit(),
                    cursor,
                    query.status(),
                    query.entityName(),
                    query.operationType(),
                    query.entityId()
            ));
            entries.addAll(page.items());
            if (!page.hasMore() || page.nextCursor() == null) {
                return List.copyOf(entries);
            }
            cursor = page.nextCursor();
        }
    }

    private List<DeadLetterArchiveRecord> collectArchive(ReconciliationQuery query) {
        ArrayList<DeadLetterArchiveRecord> entries = new ArrayList<>();
        String cursor = query.cursor();
        while (true) {
            StreamPage<DeadLetterArchiveRecord> page = deadLetterManagement.queryArchive(new ReconciliationQuery(
                    query.limit(),
                    cursor,
                    query.status(),
                    query.entityName(),
                    query.operationType(),
                    query.entityId()
            ));
            entries.addAll(page.items());
            if (!page.hasMore() || page.nextCursor() == null) {
                return List.copyOf(entries);
            }
            cursor = page.nextCursor();
        }
    }

    private String renderDeadLetters(List<DeadLetterEntry> items, DeadLetterQuery query, AdminExportFormat format) {
        List<String> headers = List.of(
                "entryId", "entity", "operationType", "entityId", "version",
                "errorType", "errorMessage", "failureCategory", "sqlState",
                "retryable", "attempts", "createdAt"
        );
        List<List<String>> rows = items.stream()
                .map(entry -> List.of(
                        entry.entryId(),
                        entry.operation().entityName(),
                        entry.operation().type().name(),
                        entry.operation().id(),
                        String.valueOf(entry.operation().version()),
                        entry.errorType(),
                        entry.errorMessage(),
                        entry.failureDetails().category().name(),
                        entry.failureDetails().sqlState(),
                        String.valueOf(entry.failureDetails().retryable()),
                        String.valueOf(entry.deadLetterAttempts()),
                        entry.createdAt().toString()
                ))
                .toList();
        return renderTable("deadLetters", describeDeadLetterQuery(query), headers, rows, format);
    }

    private String renderReconciliation(List<ReconciliationRecord> items, ReconciliationQuery query, AdminExportFormat format) {
        List<String> headers = List.of(
                "entryId", "status", "entity", "operationType", "entityId",
                "version", "replayCategory", "replaySqlState", "retryable", "reconciledAt"
        );
        List<List<String>> rows = items.stream()
                .map(record -> List.of(
                        record.entryId(),
                        record.status(),
                        record.entityName(),
                        record.operationType(),
                        record.entityId(),
                        String.valueOf(record.version()),
                        record.replayFailureDetails().category().name(),
                        record.replayFailureDetails().sqlState(),
                        String.valueOf(record.replayFailureDetails().retryable()),
                        record.reconciledAt().toString()
                ))
                .toList();
        return renderTable("reconciliation", describeReconciliationQuery(query), headers, rows, format);
    }

    private String renderArchive(List<DeadLetterArchiveRecord> items, ReconciliationQuery query, AdminExportFormat format) {
        List<String> headers = List.of(
                "entryId", "sourceEntryId", "status", "entity",
                "operationType", "entityId", "version", "archivedAt", "note"
        );
        List<List<String>> rows = items.stream()
                .map(record -> List.of(
                        record.entryId(),
                        record.sourceEntryId(),
                        record.status(),
                        record.entityName(),
                        record.operationType(),
                        record.entityId(),
                        String.valueOf(record.version()),
                        record.archivedAt().toString(),
                        record.fields().getOrDefault("note", "")
                ))
                .toList();
        return renderTable("archive", describeReconciliationQuery(query), headers, rows, format);
    }

    private String renderTable(
            String name,
            String queryDescription,
            List<String> headers,
            List<List<String>> rows,
            AdminExportFormat format
    ) {
        return switch (format) {
            case JSON -> renderJson(name, queryDescription, headers, rows);
            case CSV -> renderCsv(headers, rows);
            case MARKDOWN -> renderMarkdown(name, queryDescription, headers, rows);
        };
    }

    private String renderJson(String name, String queryDescription, List<String> headers, List<List<String>> rows) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"report\":\"").append(jsonEscape(name)).append("\",");
        builder.append("\"query\":\"").append(jsonEscape(queryDescription)).append("\",");
        builder.append("\"count\":").append(rows.size()).append(",");
        builder.append("\"items\":[");
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            if (rowIndex > 0) {
                builder.append(',');
            }
            builder.append('{');
            List<String> row = rows.get(rowIndex);
            for (int columnIndex = 0; columnIndex < headers.size(); columnIndex++) {
                if (columnIndex > 0) {
                    builder.append(',');
                }
                builder.append("\"").append(jsonEscape(headers.get(columnIndex))).append("\":");
                builder.append("\"").append(jsonEscape(row.get(columnIndex))).append("\"");
            }
            builder.append('}');
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderCsv(List<String> headers, List<List<String>> rows) {
        StringBuilder builder = new StringBuilder();
        builder.append(String.join(",", headers)).append('\n');
        for (List<String> row : rows) {
            for (int index = 0; index < row.size(); index++) {
                if (index > 0) {
                    builder.append(',');
                }
                builder.append(csvEscape(row.get(index)));
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    private String renderMarkdown(String name, String queryDescription, List<String> headers, List<List<String>> rows) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(name).append('\n');
        builder.append('\n');
        builder.append("Query: ").append(queryDescription).append('\n');
        builder.append("Count: ").append(rows.size()).append('\n');
        builder.append('\n');
        builder.append("| ").append(String.join(" | ", headers)).append(" |\n");
        builder.append("| ");
        for (int index = 0; index < headers.size(); index++) {
            if (index > 0) {
                builder.append(" | ");
            }
            builder.append("---");
        }
        builder.append(" |\n");
        for (List<String> row : rows) {
            builder.append("| ");
            for (int index = 0; index < row.size(); index++) {
                if (index > 0) {
                    builder.append(" | ");
                }
                builder.append(markdownEscape(row.get(index)));
            }
            builder.append(" |\n");
        }
        return builder.toString();
    }

    private String describeDeadLetterQuery(DeadLetterQuery query) {
        return "entity=" + blankAsAny(query.entityName())
                + ", operation=" + blankAsAny(query.operationType())
                + ", id=" + blankAsAny(query.entityId())
                + ", error=" + blankAsAny(query.errorType());
    }

    private String describeReconciliationQuery(ReconciliationQuery query) {
        return "status=" + blankAsAny(query.status())
                + ", entity=" + blankAsAny(query.entityName())
                + ", operation=" + blankAsAny(query.operationType())
                + ", id=" + blankAsAny(query.entityId());
    }

    private String blankAsAny(String value) {
        return value == null || value.isBlank() ? "*" : value;
    }

    private String csvEscape(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private String jsonEscape(String value) {
        String safe = value == null ? "" : value;
        return safe
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private String markdownEscape(String value) {
        String safe = value == null ? "" : value;
        return safe.replace("|", "\\|").replace("\n", " ");
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private void addTuning(
            List<EffectiveTuningEntry> items,
            Set<String> modeledProperties,
            String group,
            String property,
            Object value,
            String description
    ) {
        modeledProperties.add(property);
        items.add(new EffectiveTuningEntry(
                group,
                property,
                String.valueOf(value),
                tuningSource(property),
                description
        ));
    }

    private String tuningSource(String property) {
        return System.getProperty(property) == null ? "default/configured" : "explicit override";
    }

    private RuntimeProfilePropertySnapshot runtimeProfileProperty(
            Map<String, String> values,
            String property,
            String unit,
            String description
    ) {
        return new RuntimeProfilePropertySnapshot(
                property,
                defaultString(values.get(property)),
                unit,
                description
        );
    }

    private String summarizeSignal(SignalAggregate aggregate) {
        return defaultString(aggregate.description)
                + " (active=" + aggregate.activeCount
                + ", recent=" + aggregate.recentCount
                + ")";
    }

    private Instant maxInstant(Instant left, Instant right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }

    private AdminIncidentSeverity maxSeverity(AdminIncidentSeverity left, AdminIncidentSeverity right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return severityRank(left) >= severityRank(right) ? left : right;
    }

    private int severityRank(AdminIncidentSeverity severity) {
        if (severity == null) {
            return -1;
        }
        return switch (severity) {
            case INFO -> 0;
            case WARNING -> 1;
            case CRITICAL -> 2;
        };
    }

    private String serviceStatus(boolean down, boolean degraded) {
        if (down) {
            return AdminHealthStatus.DOWN.name();
        }
        if (degraded) {
            return AdminHealthStatus.DEGRADED.name();
        }
        return AdminHealthStatus.UP.name();
    }

    private String channelStatus(com.reactor.cachedb.core.queue.IncidentChannelSnapshot channel) {
        if (channel == null) {
            return AdminHealthStatus.UP.name();
        }
        if (channel.droppedCount() > 0) {
            return AdminHealthStatus.DOWN.name();
        }
        if (channel.failedCount() > 0) {
            return AdminHealthStatus.DEGRADED.name();
        }
        return AdminHealthStatus.UP.name();
    }

    private String escalationLevel(com.reactor.cachedb.core.queue.IncidentChannelSnapshot channel, boolean enabled) {
        if (!enabled) {
            return "OFF";
        }
        String status = channelStatus(channel);
        if (AdminHealthStatus.DOWN.name().equals(status)) {
            return "CRITICAL";
        }
        if (AdminHealthStatus.DEGRADED.name().equals(status)) {
            return "WARNING";
        }
        return "INFO";
    }

    private long totalIncidentDeliveryDelivered(ReconciliationMetrics metrics) {
        return metrics.incidentDeliverySnapshot().channels().stream()
                .mapToLong(channel -> channel.deliveredCount())
                .sum();
    }

    private long totalIncidentDeliveryFailed(ReconciliationMetrics metrics) {
        return metrics.incidentDeliverySnapshot().channels().stream()
                .mapToLong(channel -> channel.failedCount())
                .sum();
    }

    private long totalIncidentDeliveryDropped(ReconciliationMetrics metrics) {
        return metrics.incidentDeliverySnapshot().droppedBeforeDeliveryCount()
                + metrics.incidentDeliverySnapshot().channels().stream()
                .mapToLong(channel -> channel.droppedCount())
                .sum();
    }

    private void appendAlertRule(StringBuilder builder, String alert, String expression, String severity, String summary) {
        builder.append("      - alert: ").append(alert).append('\n');
        builder.append("        expr: ").append(expression).append('\n');
        builder.append("        for: 1m\n");
        builder.append("        labels:\n");
        builder.append("          severity: ").append(severity).append('\n');
        builder.append("        annotations:\n");
        builder.append("          summary: ").append(summary).append('\n');
    }

    private static final class BucketCounts {
        private long info;
        private long warning;
        private long critical;
        private long total;
    }

    private static final class SignalAggregate {
        private final String code;
        private String description;
        private AdminIncidentSeverity severity;
        private long activeCount;
        private long recentCount;
        private Instant lastSeenAt;

        private SignalAggregate(String code) {
            this.code = code;
        }
    }
}
