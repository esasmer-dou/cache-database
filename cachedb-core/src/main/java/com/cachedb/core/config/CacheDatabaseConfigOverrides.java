package com.reactor.cachedb.core.config;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.query.HardLimitQueryClass;
import com.reactor.cachedb.core.queue.AdminExportFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

public final class CacheDatabaseConfigOverrides {

    public static final String DEFAULT_PREFIX = "cachedb.config.";

    private CacheDatabaseConfigOverrides() {
    }

    public static CacheDatabaseConfig applySystemProperties(CacheDatabaseConfig baseConfig) {
        return apply(baseConfig, System.getProperties(), DEFAULT_PREFIX);
    }

    public static CacheDatabaseConfig applySystemProperties(CacheDatabaseConfig baseConfig, String prefix) {
        return apply(baseConfig, System.getProperties(), prefix);
    }

    public static CacheDatabaseConfig apply(CacheDatabaseConfig baseConfig, Properties properties, String prefix) {
        Objects.requireNonNull(baseConfig, "baseConfig");
        Objects.requireNonNull(properties, "properties");
        Lookup root = new Lookup(properties, prefix);
        return CacheDatabaseConfig.builder()
                .writeBehind(applyWriteBehind(baseConfig.writeBehind(), root.child("writeBehind.")))
                .resourceLimits(applyResourceLimits(baseConfig.resourceLimits(), root.child("resourceLimits.")))
                .keyspace(applyKeyspace(baseConfig.keyspace(), root.child("keyspace.")))
                .runtimeCoordination(applyRuntimeCoordination(baseConfig.runtimeCoordination(), root.child("runtimeCoordination.")))
                .redisFunctions(applyRedisFunctions(baseConfig.redisFunctions(), root.child("redisFunctions.")))
                .relations(applyRelations(baseConfig.relations(), root.child("relations.")))
                .pageCache(applyPageCache(baseConfig.pageCache(), root.child("pageCache.")))
                .queryIndex(applyQueryIndex(baseConfig.queryIndex(), root.child("queryIndex.")))
                .projectionRefresh(applyProjectionRefresh(baseConfig.projectionRefresh(), root.child("projectionRefresh.")))
                .redisGuardrail(applyRedisGuardrail(baseConfig.redisGuardrail(), root.child("redisGuardrail.")))
                .deadLetterRecovery(applyDeadLetterRecovery(baseConfig.deadLetterRecovery(), root.child("deadLetterRecovery.")))
                .adminMonitoring(applyAdminMonitoring(baseConfig.adminMonitoring(), root.child("adminMonitoring.")))
                .adminReportJob(applyAdminReportJob(baseConfig.adminReportJob(), root.child("adminReportJob.")))
                .adminHttp(applyAdminHttp(baseConfig.adminHttp(), root.child("adminHttp.")))
                .schemaBootstrap(applySchemaBootstrap(baseConfig.schemaBootstrap(), root.child("schemaBootstrap.")))
                .build();
    }

    private static RuntimeCoordinationConfig applyRuntimeCoordination(RuntimeCoordinationConfig base, Lookup lookup) {
        return RuntimeCoordinationConfig.builder()
                .instanceId(lookup.string("instanceId", base.instanceId()))
                .appendInstanceIdToConsumerNames(lookup.bool(
                        "appendInstanceIdToConsumerNames",
                        base.appendInstanceIdToConsumerNames()
                ))
                .leaderLeaseEnabled(lookup.bool("leaderLeaseEnabled", base.leaderLeaseEnabled()))
                .leaderLeaseSegment(lookup.string("leaderLeaseSegment", base.leaderLeaseSegment()))
                .leaderLeaseTtlMillis(lookup.longValue("leaderLeaseTtlMillis", base.leaderLeaseTtlMillis()))
                .leaderLeaseRenewIntervalMillis(lookup.longValue(
                        "leaderLeaseRenewIntervalMillis",
                        base.leaderLeaseRenewIntervalMillis()
                ))
                .build();
    }

    private static WriteBehindConfig applyWriteBehind(WriteBehindConfig base, Lookup lookup) {
        return WriteBehindConfig.builder()
                .enabled(lookup.bool("enabled", base.enabled()))
                .workerThreads(lookup.integer("workerThreads", base.workerThreads()))
                .batchSize(lookup.integer("batchSize", base.batchSize()))
                .dedicatedWriteConsumerGroupEnabled(lookup.bool("dedicatedWriteConsumerGroupEnabled", base.dedicatedWriteConsumerGroupEnabled()))
                .durableCompactionEnabled(lookup.bool("durableCompactionEnabled", base.durableCompactionEnabled()))
                .batchFlushEnabled(lookup.bool("batchFlushEnabled", base.batchFlushEnabled()))
                .tableAwareBatchingEnabled(lookup.bool("tableAwareBatchingEnabled", base.tableAwareBatchingEnabled()))
                .flushGroupParallelism(lookup.integer("flushGroupParallelism", base.flushGroupParallelism()))
                .flushPipelineDepth(lookup.integer("flushPipelineDepth", base.flushPipelineDepth()))
                .coalescingEnabled(lookup.bool("coalescingEnabled", base.coalescingEnabled()))
                .maxFlushBatchSize(lookup.integer("maxFlushBatchSize", base.maxFlushBatchSize()))
                .batchStaleCheckEnabled(lookup.bool("batchStaleCheckEnabled", base.batchStaleCheckEnabled()))
                .adaptiveBacklogHighWatermark(lookup.longValue("adaptiveBacklogHighWatermark", base.adaptiveBacklogHighWatermark()))
                .adaptiveBacklogCriticalWatermark(lookup.longValue("adaptiveBacklogCriticalWatermark", base.adaptiveBacklogCriticalWatermark()))
                .adaptiveHighFlushBatchSize(lookup.integer("adaptiveHighFlushBatchSize", base.adaptiveHighFlushBatchSize()))
                .adaptiveCriticalFlushBatchSize(lookup.integer("adaptiveCriticalFlushBatchSize", base.adaptiveCriticalFlushBatchSize()))
                .postgresMultiRowFlushEnabled(lookup.bool("postgresMultiRowFlushEnabled", base.postgresMultiRowFlushEnabled()))
                .postgresMultiRowStatementRowLimit(lookup.integer("postgresMultiRowStatementRowLimit", base.postgresMultiRowStatementRowLimit()))
                .postgresCopyBulkLoadEnabled(lookup.bool("postgresCopyBulkLoadEnabled", base.postgresCopyBulkLoadEnabled()))
                .postgresCopyThreshold(lookup.integer("postgresCopyThreshold", base.postgresCopyThreshold()))
                .blockTimeoutMillis(lookup.longValue("blockTimeoutMillis", base.blockTimeoutMillis()))
                .idleSleepMillis(lookup.longValue("idleSleepMillis", base.idleSleepMillis()))
                .maxFlushRetries(lookup.integer("maxFlushRetries", base.maxFlushRetries()))
                .retryBackoffMillis(lookup.longValue("retryBackoffMillis", base.retryBackoffMillis()))
                .streamKey(lookup.string("streamKey", base.streamKey()))
                .consumerGroup(lookup.string("consumerGroup", base.consumerGroup()))
                .consumerNamePrefix(lookup.string("consumerNamePrefix", base.consumerNamePrefix()))
                .compactionStreamKey(lookup.string("compactionStreamKey", base.compactionStreamKey()))
                .compactionConsumerGroup(lookup.string("compactionConsumerGroup", base.compactionConsumerGroup()))
                .compactionConsumerNamePrefix(lookup.string("compactionConsumerNamePrefix", base.compactionConsumerNamePrefix()))
                .compactionShardCount(lookup.integer("compactionShardCount", base.compactionShardCount()))
                .autoCreateConsumerGroup(lookup.bool("autoCreateConsumerGroup", base.autoCreateConsumerGroup()))
                .shutdownAwaitMillis(lookup.longValue("shutdownAwaitMillis", base.shutdownAwaitMillis()))
                .daemonThreads(lookup.bool("daemonThreads", base.daemonThreads()))
                .recoverPendingEntries(lookup.bool("recoverPendingEntries", base.recoverPendingEntries()))
                .claimIdleMillis(lookup.longValue("claimIdleMillis", base.claimIdleMillis()))
                .claimBatchSize(lookup.integer("claimBatchSize", base.claimBatchSize()))
                .retryOverrides(parseRetryOverrides(lookup.string("retryOverrides"), base.retryOverrides()))
                .entityFlushPolicies(parseEntityFlushPolicies(lookup.string("entityFlushPolicies"), base.entityFlushPolicies()))
                .deadLetterMaxLength(lookup.longValue("deadLetterMaxLength", base.deadLetterMaxLength()))
                .deadLetterStreamKey(lookup.string("deadLetterStreamKey", base.deadLetterStreamKey()))
                .compactionMaxLength(lookup.longValue("compactionMaxLength", base.compactionMaxLength()))
                .build();
    }

    private static ResourceLimits applyResourceLimits(ResourceLimits base, Lookup lookup) {
        return ResourceLimits.builder()
                .defaultCachePolicy(applyCachePolicy(base.defaultCachePolicy(), lookup.child("defaultCachePolicy.")))
                .maxRegisteredEntities(lookup.integer("maxRegisteredEntities", base.maxRegisteredEntities()))
                .maxColumnsPerOperation(lookup.integer("maxColumnsPerOperation", base.maxColumnsPerOperation()))
                .build();
    }

    private static CachePolicy applyCachePolicy(CachePolicy base, Lookup lookup) {
        return CachePolicy.builder()
                .hotEntityLimit(lookup.integer("hotEntityLimit", base.hotEntityLimit()))
                .pageSize(lookup.integer("pageSize", base.pageSize()))
                .lruEvictionEnabled(lookup.bool("lruEvictionEnabled", base.lruEvictionEnabled()))
                .entityTtlSeconds(lookup.longValue("entityTtlSeconds", base.entityTtlSeconds()))
                .pageTtlSeconds(lookup.longValue("pageTtlSeconds", base.pageTtlSeconds()))
                .build();
    }

    private static KeyspaceConfig applyKeyspace(KeyspaceConfig base, Lookup lookup) {
        return KeyspaceConfig.builder()
                .keyPrefix(lookup.string("keyPrefix", base.keyPrefix()))
                .entitySegment(lookup.string("entitySegment", base.entitySegment()))
                .pageSegment(lookup.string("pageSegment", base.pageSegment()))
                .versionSegment(lookup.string("versionSegment", base.versionSegment()))
                .tombstoneSegment(lookup.string("tombstoneSegment", base.tombstoneSegment()))
                .hotSetSegment(lookup.string("hotSetSegment", base.hotSetSegment()))
                .indexSegment(lookup.string("indexSegment", base.indexSegment()))
                .compactionSegment(lookup.string("compactionSegment", base.compactionSegment()))
                .build();
    }

    private static RedisFunctionsConfig applyRedisFunctions(RedisFunctionsConfig base, Lookup lookup) {
        return RedisFunctionsConfig.builder()
                .enabled(lookup.bool("enabled", base.enabled()))
                .autoLoadLibrary(lookup.bool("autoLoadLibrary", base.autoLoadLibrary()))
                .replaceLibraryOnLoad(lookup.bool("replaceLibraryOnLoad", base.replaceLibraryOnLoad()))
                .strictLoading(lookup.bool("strictLoading", base.strictLoading()))
                .libraryName(lookup.string("libraryName", base.libraryName()))
                .upsertFunctionName(lookup.string("upsertFunctionName", base.upsertFunctionName()))
                .deleteFunctionName(lookup.string("deleteFunctionName", base.deleteFunctionName()))
                .compactionCompleteFunctionName(lookup.string("compactionCompleteFunctionName", base.compactionCompleteFunctionName()))
                .templateResourcePath(lookup.string("templateResourcePath", base.templateResourcePath()))
                .sourceOverride(lookup.string("sourceOverride", base.sourceOverride()))
                .build();
    }

    private static RelationConfig applyRelations(RelationConfig base, Lookup lookup) {
        return RelationConfig.builder()
                .batchSize(lookup.integer("batchSize", base.batchSize()))
                .maxFetchDepth(lookup.integer("maxFetchDepth", base.maxFetchDepth()))
                .failOnMissingPreloader(lookup.bool("failOnMissingPreloader", base.failOnMissingPreloader()))
                .build();
    }

    private static PageCacheConfig applyPageCache(PageCacheConfig base, Lookup lookup) {
        return PageCacheConfig.builder()
                .readThroughEnabled(lookup.bool("readThroughEnabled", base.readThroughEnabled()))
                .failOnMissingPageLoader(lookup.bool("failOnMissingPageLoader", base.failOnMissingPageLoader()))
                .evictionBatchSize(lookup.integer("evictionBatchSize", base.evictionBatchSize()))
                .build();
    }

    private static QueryIndexConfig applyQueryIndex(QueryIndexConfig base, Lookup lookup) {
        return QueryIndexConfig.builder()
                .exactIndexEnabled(lookup.bool("exactIndexEnabled", base.exactIndexEnabled()))
                .rangeIndexEnabled(lookup.bool("rangeIndexEnabled", base.rangeIndexEnabled()))
                .prefixIndexEnabled(lookup.bool("prefixIndexEnabled", base.prefixIndexEnabled()))
                .textIndexEnabled(lookup.bool("textIndexEnabled", base.textIndexEnabled()))
                .plannerStatisticsEnabled(lookup.bool("plannerStatisticsEnabled", base.plannerStatisticsEnabled()))
                .plannerStatisticsPersisted(lookup.bool("plannerStatisticsPersisted", base.plannerStatisticsPersisted()))
                .plannerStatisticsTtlMillis(lookup.longValue("plannerStatisticsTtlMillis", base.plannerStatisticsTtlMillis()))
                .plannerStatisticsSampleSize(lookup.integer("plannerStatisticsSampleSize", base.plannerStatisticsSampleSize()))
                .learnedStatisticsEnabled(lookup.bool("learnedStatisticsEnabled", base.learnedStatisticsEnabled()))
                .learnedStatisticsWeight(lookup.doubleValue("learnedStatisticsWeight", base.learnedStatisticsWeight()))
                .cacheWarmingEnabled(lookup.bool("cacheWarmingEnabled", base.cacheWarmingEnabled()))
                .rangeHistogramBuckets(lookup.integer("rangeHistogramBuckets", base.rangeHistogramBuckets()))
                .prefixMaxLength(lookup.integer("prefixMaxLength", base.prefixMaxLength()))
                .textTokenMinLength(lookup.integer("textTokenMinLength", base.textTokenMinLength()))
                .textTokenMaxLength(lookup.integer("textTokenMaxLength", base.textTokenMaxLength()))
                .textMaxTokensPerValue(lookup.integer("textMaxTokensPerValue", base.textMaxTokensPerValue()))
                .build();
    }

    private static ProjectionRefreshConfig applyProjectionRefresh(ProjectionRefreshConfig base, Lookup lookup) {
        return ProjectionRefreshConfig.builder()
                .enabled(lookup.bool("enabled", base.enabled()))
                .streamKey(lookup.string("streamKey", base.streamKey()))
                .consumerGroup(lookup.string("consumerGroup", base.consumerGroup()))
                .consumerNamePrefix(lookup.string("consumerNamePrefix", base.consumerNamePrefix()))
                .batchSize(lookup.integer("batchSize", base.batchSize()))
                .blockTimeoutMillis(lookup.longValue("blockTimeoutMillis", base.blockTimeoutMillis()))
                .idleSleepMillis(lookup.longValue("idleSleepMillis", base.idleSleepMillis()))
                .autoCreateConsumerGroup(lookup.bool("autoCreateConsumerGroup", base.autoCreateConsumerGroup()))
                .recoverPendingEntries(lookup.bool("recoverPendingEntries", base.recoverPendingEntries()))
                .claimIdleMillis(lookup.longValue("claimIdleMillis", base.claimIdleMillis()))
                .claimBatchSize(lookup.integer("claimBatchSize", base.claimBatchSize()))
                .maxStreamLength(lookup.longValue("maxStreamLength", base.maxStreamLength()))
                .deadLetterEnabled(lookup.bool("deadLetterEnabled", base.deadLetterEnabled()))
                .deadLetterStreamKey(lookup.string("deadLetterStreamKey", base.deadLetterStreamKey()))
                .deadLetterMaxLength(lookup.longValue("deadLetterMaxLength", base.deadLetterMaxLength()))
                .maxAttempts(lookup.integer("maxAttempts", base.maxAttempts()))
                .deadLetterWarnThreshold(lookup.longValue("deadLetterWarnThreshold", base.deadLetterWarnThreshold()))
                .deadLetterCriticalThreshold(lookup.longValue("deadLetterCriticalThreshold", base.deadLetterCriticalThreshold()))
                .shutdownAwaitMillis(lookup.longValue("shutdownAwaitMillis", base.shutdownAwaitMillis()))
                .daemonThreads(lookup.bool("daemonThreads", base.daemonThreads()))
                .build();
    }

    private static RedisGuardrailConfig applyRedisGuardrail(RedisGuardrailConfig base, Lookup lookup) {
        return RedisGuardrailConfig.builder()
                .enabled(lookup.bool("enabled", base.enabled()))
                .producerBackpressureEnabled(lookup.bool("producerBackpressureEnabled", base.producerBackpressureEnabled()))
                .usedMemoryWarnBytes(lookup.longValue("usedMemoryWarnBytes", base.usedMemoryWarnBytes()))
                .usedMemoryCriticalBytes(lookup.longValue("usedMemoryCriticalBytes", base.usedMemoryCriticalBytes()))
                .writeBehindBacklogWarnThreshold(lookup.longValue("writeBehindBacklogWarnThreshold", base.writeBehindBacklogWarnThreshold()))
                .writeBehindBacklogCriticalThreshold(lookup.longValue("writeBehindBacklogCriticalThreshold", base.writeBehindBacklogCriticalThreshold()))
                .compactionPendingWarnThreshold(lookup.longValue("compactionPendingWarnThreshold", base.compactionPendingWarnThreshold()))
                .compactionPendingCriticalThreshold(lookup.longValue("compactionPendingCriticalThreshold", base.compactionPendingCriticalThreshold()))
                .writeBehindBacklogHardLimit(lookup.longValue("writeBehindBacklogHardLimit", base.writeBehindBacklogHardLimit()))
                .compactionPendingHardLimit(lookup.longValue("compactionPendingHardLimit", base.compactionPendingHardLimit()))
                .compactionPayloadHardLimit(lookup.longValue("compactionPayloadHardLimit", base.compactionPayloadHardLimit()))
                .rejectWritesOnHardLimit(lookup.bool("rejectWritesOnHardLimit", base.rejectWritesOnHardLimit()))
                .shedPageCacheWritesOnHardLimit(lookup.bool("shedPageCacheWritesOnHardLimit", base.shedPageCacheWritesOnHardLimit()))
                .shedReadThroughCacheOnHardLimit(lookup.bool("shedReadThroughCacheOnHardLimit", base.shedReadThroughCacheOnHardLimit()))
                .shedHotSetTrackingOnHardLimit(lookup.bool("shedHotSetTrackingOnHardLimit", base.shedHotSetTrackingOnHardLimit()))
                .shedQueryIndexWritesOnHardLimit(lookup.bool("shedQueryIndexWritesOnHardLimit", base.shedQueryIndexWritesOnHardLimit()))
                .shedQueryIndexReadsOnHardLimit(lookup.bool("shedQueryIndexReadsOnHardLimit", base.shedQueryIndexReadsOnHardLimit()))
                .shedPlannerLearningOnHardLimit(lookup.bool("shedPlannerLearningOnHardLimit", base.shedPlannerLearningOnHardLimit()))
                .entityPolicies(parseHardLimitEntityPolicies(lookup.string("entityPolicies"), base.entityPolicies()))
                .queryPolicies(parseHardLimitQueryPolicies(lookup.string("queryPolicies"), base.queryPolicies()))
                .highSleepMillis(lookup.longValue("highSleepMillis", base.highSleepMillis()))
                .criticalSleepMillis(lookup.longValue("criticalSleepMillis", base.criticalSleepMillis()))
                .sampleIntervalMillis(lookup.longValue("sampleIntervalMillis", base.sampleIntervalMillis()))
                .automaticRuntimeProfileSwitchingEnabled(lookup.bool("automaticRuntimeProfileSwitchingEnabled", base.automaticRuntimeProfileSwitchingEnabled()))
                .warnSamplesToBalanced(lookup.integer("warnSamplesToBalanced", base.warnSamplesToBalanced()))
                .criticalSamplesToAggressive(lookup.integer("criticalSamplesToAggressive", base.criticalSamplesToAggressive()))
                .warnSamplesToDeescalateAggressive(lookup.integer("warnSamplesToDeescalateAggressive", base.warnSamplesToDeescalateAggressive()))
                .normalSamplesToStandard(lookup.integer("normalSamplesToStandard", base.normalSamplesToStandard()))
                .compactionPayloadTtlSeconds(lookup.integer("compactionPayloadTtlSeconds", base.compactionPayloadTtlSeconds()))
                .compactionPendingTtlSeconds(lookup.integer("compactionPendingTtlSeconds", base.compactionPendingTtlSeconds()))
                .versionKeyTtlSeconds(lookup.integer("versionKeyTtlSeconds", base.versionKeyTtlSeconds()))
                .tombstoneTtlSeconds(lookup.integer("tombstoneTtlSeconds", base.tombstoneTtlSeconds()))
                .autoRecoverDegradedIndexesEnabled(lookup.bool("autoRecoverDegradedIndexesEnabled", base.autoRecoverDegradedIndexesEnabled()))
                .degradedIndexRebuildCooldownMillis(lookup.longValue("degradedIndexRebuildCooldownMillis", base.degradedIndexRebuildCooldownMillis()))
                .build();
    }

    private static DeadLetterRecoveryConfig applyDeadLetterRecovery(DeadLetterRecoveryConfig base, Lookup lookup) {
        return DeadLetterRecoveryConfig.builder()
                .enabled(lookup.bool("enabled", base.enabled()))
                .workerThreads(lookup.integer("workerThreads", base.workerThreads()))
                .blockTimeoutMillis(lookup.longValue("blockTimeoutMillis", base.blockTimeoutMillis()))
                .idleSleepMillis(lookup.longValue("idleSleepMillis", base.idleSleepMillis()))
                .consumerGroup(lookup.string("consumerGroup", base.consumerGroup()))
                .consumerNamePrefix(lookup.string("consumerNamePrefix", base.consumerNamePrefix()))
                .autoCreateConsumerGroup(lookup.bool("autoCreateConsumerGroup", base.autoCreateConsumerGroup()))
                .shutdownAwaitMillis(lookup.longValue("shutdownAwaitMillis", base.shutdownAwaitMillis()))
                .daemonThreads(lookup.bool("daemonThreads", base.daemonThreads()))
                .claimIdleMillis(lookup.longValue("claimIdleMillis", base.claimIdleMillis()))
                .claimBatchSize(lookup.integer("claimBatchSize", base.claimBatchSize()))
                .maxReplayRetries(lookup.integer("maxReplayRetries", base.maxReplayRetries()))
                .replayBackoffMillis(lookup.longValue("replayBackoffMillis", base.replayBackoffMillis()))
                .retryOverrides(parseRetryOverrides(lookup.string("retryOverrides"), base.retryOverrides()))
                .reconciliationStreamKey(lookup.string("reconciliationStreamKey", base.reconciliationStreamKey()))
                .archiveResolvedEntries(lookup.bool("archiveResolvedEntries", base.archiveResolvedEntries()))
                .archiveStreamKey(lookup.string("archiveStreamKey", base.archiveStreamKey()))
                .cleanupEnabled(lookup.bool("cleanupEnabled", base.cleanupEnabled()))
                .cleanupIntervalMillis(lookup.longValue("cleanupIntervalMillis", base.cleanupIntervalMillis()))
                .cleanupBatchSize(lookup.integer("cleanupBatchSize", base.cleanupBatchSize()))
                .deadLetterRetentionMillis(lookup.longValue("deadLetterRetentionMillis", base.deadLetterRetentionMillis()))
                .reconciliationRetentionMillis(lookup.longValue("reconciliationRetentionMillis", base.reconciliationRetentionMillis()))
                .archiveRetentionMillis(lookup.longValue("archiveRetentionMillis", base.archiveRetentionMillis()))
                .deadLetterMaxLength(lookup.longValue("deadLetterMaxLength", base.deadLetterMaxLength()))
                .reconciliationMaxLength(lookup.longValue("reconciliationMaxLength", base.reconciliationMaxLength()))
                .archiveMaxLength(lookup.longValue("archiveMaxLength", base.archiveMaxLength()))
                .build();
    }

    private static AdminMonitoringConfig applyAdminMonitoring(AdminMonitoringConfig base, Lookup lookup) {
        return AdminMonitoringConfig.builder()
                .enabled(lookup.bool("enabled", base.enabled()))
                .writeBehindWarnThreshold(lookup.longValue("writeBehindWarnThreshold", base.writeBehindWarnThreshold()))
                .writeBehindCriticalThreshold(lookup.longValue("writeBehindCriticalThreshold", base.writeBehindCriticalThreshold()))
                .deadLetterWarnThreshold(lookup.longValue("deadLetterWarnThreshold", base.deadLetterWarnThreshold()))
                .deadLetterCriticalThreshold(lookup.longValue("deadLetterCriticalThreshold", base.deadLetterCriticalThreshold()))
                .recoveryFailedWarnThreshold(lookup.longValue("recoveryFailedWarnThreshold", base.recoveryFailedWarnThreshold()))
                .recoveryFailedCriticalThreshold(lookup.longValue("recoveryFailedCriticalThreshold", base.recoveryFailedCriticalThreshold()))
                .recentErrorWindowMillis(lookup.longValue("recentErrorWindowMillis", base.recentErrorWindowMillis()))
                .historySampleIntervalMillis(lookup.longValue("historySampleIntervalMillis", base.historySampleIntervalMillis()))
                .historyMinSampleIntervalMillis(lookup.longValue("historyMinSampleIntervalMillis", base.historyMinSampleIntervalMillis()))
                .historyMaxSamples(lookup.integer("historyMaxSamples", base.historyMaxSamples()))
                .historyMinSamples(lookup.integer("historyMinSamples", base.historyMinSamples()))
                .alertRouteHistoryMinSamples(lookup.integer("alertRouteHistoryMinSamples", base.alertRouteHistoryMinSamples()))
                .alertRouteHistorySampleMultiplier(lookup.integer("alertRouteHistorySampleMultiplier", base.alertRouteHistorySampleMultiplier()))
                .telemetryTtlSeconds(lookup.longValue("telemetryTtlSeconds", base.telemetryTtlSeconds()))
                .monitoringHistoryStreamKey(lookup.string("monitoringHistoryStreamKey", base.monitoringHistoryStreamKey()))
                .alertRouteHistoryStreamKey(lookup.string("alertRouteHistoryStreamKey", base.alertRouteHistoryStreamKey()))
                .performanceHistoryStreamKey(lookup.string("performanceHistoryStreamKey", base.performanceHistoryStreamKey()))
                .performanceSnapshotKey(lookup.string("performanceSnapshotKey", base.performanceSnapshotKey()))
                .incidentTtlSeconds(lookup.longValue("incidentTtlSeconds", base.incidentTtlSeconds()))
                .incidentStreamKey(lookup.string("incidentStreamKey", base.incidentStreamKey()))
                .incidentMaxLength(lookup.longValue("incidentMaxLength", base.incidentMaxLength()))
                .incidentCooldownMillis(lookup.longValue("incidentCooldownMillis", base.incidentCooldownMillis()))
                .incidentDeliveryQueueFloor(lookup.integer("incidentDeliveryQueueFloor", base.incidentDeliveryQueueFloor()))
                .incidentDeliveryPollTimeoutMillis(lookup.longValue("incidentDeliveryPollTimeoutMillis", base.incidentDeliveryPollTimeoutMillis()))
                .incidentWebhook(applyIncidentWebhook(base.incidentWebhook(), lookup.child("incidentWebhook.")))
                .incidentQueue(applyIncidentQueue(base.incidentQueue(), lookup.child("incidentQueue.")))
                .incidentEmail(applyIncidentEmail(base.incidentEmail(), lookup.child("incidentEmail.")))
                .incidentDeliveryDlq(applyIncidentDeliveryDlq(base.incidentDeliveryDlq(), lookup.child("incidentDeliveryDlq.")))
                .build();
    }

    private static IncidentWebhookConfig applyIncidentWebhook(IncidentWebhookConfig base, Lookup lookup) {
        return IncidentWebhookConfig.builder()
                .enabled(lookup.bool("enabled", base.enabled()))
                .endpointUrl(lookup.string("endpointUrl", base.endpointUrl()))
                .connectTimeoutMillis(lookup.longValue("connectTimeoutMillis", base.connectTimeoutMillis()))
                .requestTimeoutMillis(lookup.longValue("requestTimeoutMillis", base.requestTimeoutMillis()))
                .workerThreads(lookup.integer("workerThreads", base.workerThreads()))
                .queueCapacity(lookup.integer("queueCapacity", base.queueCapacity()))
                .maxRetries(lookup.integer("maxRetries", base.maxRetries()))
                .retryBackoffMillis(lookup.longValue("retryBackoffMillis", base.retryBackoffMillis()))
                .headerName(lookup.string("headerName", base.headerName()))
                .headerValue(lookup.string("headerValue", base.headerValue()))
                .build();
    }

    private static IncidentQueueConfig applyIncidentQueue(IncidentQueueConfig base, Lookup lookup) {
        return IncidentQueueConfig.builder()
                .enabled(lookup.bool("enabled", base.enabled()))
                .streamKey(lookup.string("streamKey", base.streamKey()))
                .maxLength(lookup.longValue("maxLength", base.maxLength()))
                .maxRetries(lookup.integer("maxRetries", base.maxRetries()))
                .retryBackoffMillis(lookup.longValue("retryBackoffMillis", base.retryBackoffMillis()))
                .build();
    }

    private static IncidentEmailConfig applyIncidentEmail(IncidentEmailConfig base, Lookup lookup) {
        return IncidentEmailConfig.builder()
                .enabled(lookup.bool("enabled", base.enabled()))
                .smtpHost(lookup.string("smtpHost", base.smtpHost()))
                .smtpPort(lookup.integer("smtpPort", base.smtpPort()))
                .implicitTls(lookup.bool("implicitTls", base.implicitTls()))
                .startTls(lookup.bool("startTls", base.startTls()))
                .trustAllCertificates(lookup.bool("trustAllCertificates", base.trustAllCertificates()))
                .connectTimeoutMillis(lookup.longValue("connectTimeoutMillis", base.connectTimeoutMillis()))
                .readTimeoutMillis(lookup.longValue("readTimeoutMillis", base.readTimeoutMillis()))
                .maxRetries(lookup.integer("maxRetries", base.maxRetries()))
                .retryBackoffMillis(lookup.longValue("retryBackoffMillis", base.retryBackoffMillis()))
                .heloHost(lookup.string("heloHost", base.heloHost()))
                .username(lookup.string("username", base.username()))
                .password(lookup.string("password", base.password()))
                .authMechanism(lookup.string("authMechanism", base.authMechanism()))
                .trustStorePath(lookup.string("trustStorePath", base.trustStorePath()))
                .trustStorePassword(lookup.string("trustStorePassword", base.trustStorePassword()))
                .trustStoreType(lookup.string("trustStoreType", base.trustStoreType()))
                .pinnedServerCertificateSha256(parseStringList(lookup.string("pinnedServerCertificateSha256"), base.pinnedServerCertificateSha256()))
                .fromAddress(lookup.string("fromAddress", base.fromAddress()))
                .toAddresses(parseStringList(lookup.string("toAddresses"), base.toAddresses()))
                .subjectPrefix(lookup.string("subjectPrefix", base.subjectPrefix()))
                .build();
    }

    private static IncidentDeliveryDlqConfig applyIncidentDeliveryDlq(IncidentDeliveryDlqConfig base, Lookup lookup) {
        return IncidentDeliveryDlqConfig.builder()
                .enabled(lookup.bool("enabled", base.enabled()))
                .streamKey(lookup.string("streamKey", base.streamKey()))
                .recoveryStreamKey(lookup.string("recoveryStreamKey", base.recoveryStreamKey()))
                .consumerGroup(lookup.string("consumerGroup", base.consumerGroup()))
                .consumerNamePrefix(lookup.string("consumerNamePrefix", base.consumerNamePrefix()))
                .workerThreads(lookup.integer("workerThreads", base.workerThreads()))
                .batchSize(lookup.integer("batchSize", base.batchSize()))
                .blockTimeoutMillis(lookup.longValue("blockTimeoutMillis", base.blockTimeoutMillis()))
                .maxReplayAttempts(lookup.integer("maxReplayAttempts", base.maxReplayAttempts()))
                .autoCreateConsumerGroup(lookup.bool("autoCreateConsumerGroup", base.autoCreateConsumerGroup()))
                .claimAbandonedEntries(lookup.bool("claimAbandonedEntries", base.claimAbandonedEntries()))
                .claimIdleMillis(lookup.longValue("claimIdleMillis", base.claimIdleMillis()))
                .claimBatchSize(lookup.integer("claimBatchSize", base.claimBatchSize()))
                .idleSleepMillis(lookup.longValue("idleSleepMillis", base.idleSleepMillis()))
                .build();
    }

    private static AdminReportJobConfig applyAdminReportJob(AdminReportJobConfig base, Lookup lookup) {
        return AdminReportJobConfig.builder()
                .enabled(lookup.bool("enabled", base.enabled()))
                .intervalMillis(lookup.longValue("intervalMillis", base.intervalMillis()))
                .outputDirectory(lookup.string("outputDirectory", base.outputDirectory()))
                .format(lookup.enumValue("format", AdminExportFormat.class, base.format()))
                .queryLimit(lookup.integer("queryLimit", base.queryLimit()))
                .writeDeadLetters(lookup.bool("writeDeadLetters", base.writeDeadLetters()))
                .writeReconciliation(lookup.bool("writeReconciliation", base.writeReconciliation()))
                .writeArchive(lookup.bool("writeArchive", base.writeArchive()))
                .writeIncidents(lookup.bool("writeIncidents", base.writeIncidents()))
                .writeDiagnostics(lookup.bool("writeDiagnostics", base.writeDiagnostics()))
                .includeTimestampInFileName(lookup.bool("includeTimestampInFileName", base.includeTimestampInFileName()))
                .maxRetainedFilesPerReport(lookup.integer("maxRetainedFilesPerReport", base.maxRetainedFilesPerReport()))
                .fileRetentionMillis(lookup.longValue("fileRetentionMillis", base.fileRetentionMillis()))
                .persistDiagnostics(lookup.bool("persistDiagnostics", base.persistDiagnostics()))
                .diagnosticsStreamKey(lookup.string("diagnosticsStreamKey", base.diagnosticsStreamKey()))
                .diagnosticsMaxLength(lookup.longValue("diagnosticsMaxLength", base.diagnosticsMaxLength()))
                .diagnosticsTtlSeconds(lookup.longValue("diagnosticsTtlSeconds", base.diagnosticsTtlSeconds()))
                .build();
    }

    private static AdminHttpConfig applyAdminHttp(AdminHttpConfig base, Lookup lookup) {
        return AdminHttpConfig.builder()
                .enabled(lookup.bool("enabled", base.enabled()))
                .host(lookup.string("host", base.host()))
                .port(lookup.integer("port", base.port()))
                .backlog(lookup.integer("backlog", base.backlog()))
                .workerThreads(lookup.integer("workerThreads", base.workerThreads()))
                .dashboardEnabled(lookup.bool("dashboardEnabled", base.dashboardEnabled()))
                .corsEnabled(lookup.bool("corsEnabled", base.corsEnabled()))
                .dashboardTitle(lookup.string("dashboardTitle", base.dashboardTitle()))
                .build();
    }

    private static SchemaBootstrapConfig applySchemaBootstrap(SchemaBootstrapConfig base, Lookup lookup) {
        return SchemaBootstrapConfig.builder()
                .mode(lookup.enumValue("mode", SchemaBootstrapMode.class, base.mode()))
                .autoApplyOnStart(lookup.bool("autoApplyOnStart", base.autoApplyOnStart()))
                .includeVersionColumn(lookup.bool("includeVersionColumn", base.includeVersionColumn()))
                .includeDeletedColumn(lookup.bool("includeDeletedColumn", base.includeDeletedColumn()))
                .schemaName(lookup.string("schemaName", base.schemaName()))
                .build();
    }

    private static List<WriteRetryPolicyOverride> parseRetryOverrides(String rawValue, List<WriteRetryPolicyOverride> fallback) {
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }
        List<WriteRetryPolicyOverride> overrides = new ArrayList<>();
        for (String entry : splitEntries(rawValue)) {
            String[] parts = splitFields(entry, 4);
            WriteRetryPolicyOverride.Builder builder = WriteRetryPolicyOverride.builder()
                    .entityName(blankToNull(parts[0]))
                    .maxRetries(parseRequiredInteger(parts[2], "maxRetries"))
                    .backoffMillis(parseRequiredLong(parts[3], "backoffMillis"));
            if (parts[1] != null && !parts[1].isBlank() && !"*".equals(parts[1])) {
                builder.operationType(OperationType.valueOf(parts[1].trim().toUpperCase(Locale.ROOT)));
            }
            overrides.add(builder.build());
        }
        return List.copyOf(overrides);
    }

    private static List<EntityFlushPolicy> parseEntityFlushPolicies(String rawValue, List<EntityFlushPolicy> fallback) {
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }
        List<EntityFlushPolicy> policies = new ArrayList<>();
        for (String entry : splitEntries(rawValue)) {
            String[] parts = splitFields(entry, 9);
            OperationType operationType = parseNullableEnum(parts[1], OperationType.class);
            PersistenceSemantics semantics = parseNullableEnum(parts[8], PersistenceSemantics.class);
            policies.add(new EntityFlushPolicy(
                    required(parts[0], "entityName"),
                    operationType,
                    parseRequiredBoolean(parts[2], "stateCompactionEnabled"),
                    parseRequiredBoolean(parts[3], "preferCopy"),
                    parseRequiredBoolean(parts[4], "preferMultiRow"),
                    parseRequiredInteger(parts[5], "maxBatchSize"),
                    parseRequiredInteger(parts[6], "statementRowLimit"),
                    parseRequiredInteger(parts[7], "copyThreshold"),
                    semantics
            ));
        }
        return List.copyOf(policies);
    }

    private static List<HardLimitEntityPolicy> parseHardLimitEntityPolicies(String rawValue, List<HardLimitEntityPolicy> fallback) {
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }
        List<HardLimitEntityPolicy> policies = new ArrayList<>();
        for (String entry : splitEntries(rawValue)) {
            String[] parts = splitFields(entry, 8);
            policies.add(new HardLimitEntityPolicy(
                    required(parts[0], "namespace"),
                    parseRequiredBoolean(parts[1], "shedPageCacheWrites"),
                    parseRequiredBoolean(parts[2], "shedReadThroughCache"),
                    parseRequiredBoolean(parts[3], "shedHotSetTracking"),
                    parseRequiredBoolean(parts[4], "shedQueryIndexWrites"),
                    parseRequiredBoolean(parts[5], "shedQueryIndexReads"),
                    parseRequiredBoolean(parts[6], "shedPlannerLearning"),
                    parseRequiredBoolean(parts[7], "autoRebuildIndexes")
            ));
        }
        return List.copyOf(policies);
    }

    private static List<HardLimitQueryPolicy> parseHardLimitQueryPolicies(String rawValue, List<HardLimitQueryPolicy> fallback) {
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }
        List<HardLimitQueryPolicy> policies = new ArrayList<>();
        for (String entry : splitEntries(rawValue)) {
            String[] parts = splitFields(entry, 4);
            policies.add(new HardLimitQueryPolicy(
                    required(parts[0], "namespace"),
                    HardLimitQueryClass.valueOf(required(parts[1], "queryClass").toUpperCase(Locale.ROOT)),
                    parseRequiredBoolean(parts[2], "shedReads"),
                    parseRequiredBoolean(parts[3], "shedLearning")
            ));
        }
        return List.copyOf(policies);
    }

    private static List<String> parseStringList(String rawValue, List<String> fallback) {
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }
        return List.copyOf(splitSimpleList(rawValue));
    }

    private static List<String> splitEntries(String rawValue) {
        return splitSimpleList(rawValue.replace(';', '|'), "\\|");
    }

    private static List<String> splitSimpleList(String rawValue) {
        return splitSimpleList(rawValue, ",");
    }

    private static List<String> splitSimpleList(String rawValue, String delimiterRegex) {
        List<String> values = new ArrayList<>();
        for (String token : rawValue.split(delimiterRegex)) {
            if (token != null) {
                String normalized = token.trim();
                if (!normalized.isEmpty()) {
                    values.add(normalized);
                }
            }
        }
        return values;
    }

    private static String[] splitFields(String value, int expectedLength) {
        String[] rawParts = value.split(",", -1);
        if (rawParts.length != expectedLength) {
            throw new IllegalArgumentException(
                    "Expected " + expectedLength + " comma-separated fields but got " + rawParts.length + " in '" + value + "'"
            );
        }
        String[] normalized = new String[expectedLength];
        for (int index = 0; index < rawParts.length; index++) {
            normalized[index] = rawParts[index] == null ? null : rawParts[index].trim();
        }
        return normalized;
    }

    private static String required(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required value for " + fieldName);
        }
        return value.trim();
    }

    private static int parseRequiredInteger(String value, String fieldName) {
        return Integer.parseInt(required(value, fieldName));
    }

    private static long parseRequiredLong(String value, String fieldName) {
        return Long.parseLong(required(value, fieldName));
    }

    private static boolean parseRequiredBoolean(String value, String fieldName) {
        String normalized = required(value, fieldName).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true", "1", "yes", "y", "on" -> true;
            case "false", "0", "no", "n", "off" -> false;
            default -> throw new IllegalArgumentException("Invalid boolean '" + value + "' for " + fieldName);
        };
    }

    private static <T extends Enum<T>> T parseNullableEnum(String value, Class<T> enumType) {
        if (value == null || value.isBlank() || "*".equals(value)) {
            return null;
        }
        return Enum.valueOf(enumType, value.trim().toUpperCase(Locale.ROOT));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() || "*".equals(value) ? null : value.trim();
    }

    private static final class Lookup {
        private final Properties properties;
        private final String prefix;

        private Lookup(Properties properties, String prefix) {
            this.properties = properties;
            this.prefix = normalizePrefix(prefix);
        }

        private Lookup child(String childPrefix) {
            return new Lookup(properties, prefix + childPrefix);
        }

        private String string(String key) {
            String value = properties.getProperty(prefix + key);
            return value == null ? null : value.trim();
        }

        private String string(String key, String defaultValue) {
            String value = string(key);
            return value == null || value.isEmpty() ? defaultValue : value;
        }

        private boolean bool(String key, boolean defaultValue) {
            String value = string(key);
            return value == null || value.isEmpty() ? defaultValue : parseRequiredBoolean(value, prefix + key);
        }

        private int integer(String key, int defaultValue) {
            String value = string(key);
            return value == null || value.isEmpty() ? defaultValue : Integer.parseInt(value);
        }

        private long longValue(String key, long defaultValue) {
            String value = string(key);
            return value == null || value.isEmpty() ? defaultValue : Long.parseLong(value);
        }

        private double doubleValue(String key, double defaultValue) {
            String value = string(key);
            return value == null || value.isEmpty() ? defaultValue : Double.parseDouble(value);
        }

        private <T extends Enum<T>> T enumValue(String key, Class<T> enumType, T defaultValue) {
            String value = string(key);
            return value == null || value.isEmpty()
                    ? defaultValue
                    : Enum.valueOf(enumType, value.toUpperCase(Locale.ROOT));
        }

        private static String normalizePrefix(String prefix) {
            if (prefix == null || prefix.isBlank()) {
                return "";
            }
            return prefix.endsWith(".") ? prefix : prefix + ".";
        }
    }
}
