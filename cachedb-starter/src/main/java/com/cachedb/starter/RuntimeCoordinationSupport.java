package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.AdminMonitoringConfig;
import com.reactor.cachedb.core.config.DeadLetterRecoveryConfig;
import com.reactor.cachedb.core.config.IncidentDeliveryDlqConfig;
import com.reactor.cachedb.core.config.ProjectionRefreshConfig;
import com.reactor.cachedb.core.config.RuntimeCoordinationConfig;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.redis.RedisLeaderLease;
import redis.clients.jedis.JedisPooled;

import java.util.UUID;

final class RuntimeCoordinationSupport {

    private RuntimeCoordinationSupport() {
    }

    static String resolveInstanceId(RuntimeCoordinationConfig config) {
        if (config != null && config.instanceId() != null && !config.instanceId().isBlank()) {
            return sanitize(config.instanceId());
        }
        for (String candidate : new String[] {
                System.getenv("CACHE_DB_INSTANCE_ID"),
                System.getenv("HOSTNAME"),
                System.getenv("POD_NAME"),
                System.getenv("COMPUTERNAME")
        }) {
            if (candidate != null && !candidate.isBlank()) {
                return sanitize(candidate);
            }
        }
        return sanitize(UUID.randomUUID().toString());
    }

    static WriteBehindConfig withInstanceIdentity(
            WriteBehindConfig config,
            RuntimeCoordinationConfig coordination,
            String instanceId
    ) {
        return WriteBehindConfig.builder()
                .enabled(config.enabled())
                .workerThreads(config.workerThreads())
                .batchSize(config.batchSize())
                .dedicatedWriteConsumerGroupEnabled(config.dedicatedWriteConsumerGroupEnabled())
                .durableCompactionEnabled(config.durableCompactionEnabled())
                .batchFlushEnabled(config.batchFlushEnabled())
                .tableAwareBatchingEnabled(config.tableAwareBatchingEnabled())
                .flushGroupParallelism(config.flushGroupParallelism())
                .flushPipelineDepth(config.flushPipelineDepth())
                .coalescingEnabled(config.coalescingEnabled())
                .maxFlushBatchSize(config.maxFlushBatchSize())
                .batchStaleCheckEnabled(config.batchStaleCheckEnabled())
                .adaptiveBacklogHighWatermark(config.adaptiveBacklogHighWatermark())
                .adaptiveBacklogCriticalWatermark(config.adaptiveBacklogCriticalWatermark())
                .adaptiveHighFlushBatchSize(config.adaptiveHighFlushBatchSize())
                .adaptiveCriticalFlushBatchSize(config.adaptiveCriticalFlushBatchSize())
                .postgresMultiRowFlushEnabled(config.postgresMultiRowFlushEnabled())
                .postgresMultiRowStatementRowLimit(config.postgresMultiRowStatementRowLimit())
                .postgresCopyBulkLoadEnabled(config.postgresCopyBulkLoadEnabled())
                .postgresCopyThreshold(config.postgresCopyThreshold())
                .blockTimeoutMillis(config.blockTimeoutMillis())
                .idleSleepMillis(config.idleSleepMillis())
                .maxFlushRetries(config.maxFlushRetries())
                .retryBackoffMillis(config.retryBackoffMillis())
                .streamKey(config.streamKey())
                .consumerGroup(config.consumerGroup())
                .consumerNamePrefix(consumerNamePrefix(config.consumerNamePrefix(), coordination, instanceId))
                .compactionStreamKey(config.compactionStreamKey())
                .compactionConsumerGroup(config.compactionConsumerGroup())
                .compactionConsumerNamePrefix(consumerNamePrefix(config.compactionConsumerNamePrefix(), coordination, instanceId))
                .compactionShardCount(config.compactionShardCount())
                .autoCreateConsumerGroup(config.autoCreateConsumerGroup())
                .shutdownAwaitMillis(config.shutdownAwaitMillis())
                .daemonThreads(config.daemonThreads())
                .recoverPendingEntries(config.recoverPendingEntries())
                .claimIdleMillis(config.claimIdleMillis())
                .claimBatchSize(config.claimBatchSize())
                .retryOverrides(config.retryOverrides())
                .entityFlushPolicies(config.entityFlushPolicies())
                .deadLetterMaxLength(config.deadLetterMaxLength())
                .deadLetterStreamKey(config.deadLetterStreamKey())
                .compactionMaxLength(config.compactionMaxLength())
                .build();
    }

    static DeadLetterRecoveryConfig withInstanceIdentity(
            DeadLetterRecoveryConfig config,
            RuntimeCoordinationConfig coordination,
            String instanceId
    ) {
        return DeadLetterRecoveryConfig.builder()
                .enabled(config.enabled())
                .workerThreads(config.workerThreads())
                .blockTimeoutMillis(config.blockTimeoutMillis())
                .idleSleepMillis(config.idleSleepMillis())
                .consumerGroup(config.consumerGroup())
                .consumerNamePrefix(consumerNamePrefix(config.consumerNamePrefix(), coordination, instanceId))
                .autoCreateConsumerGroup(config.autoCreateConsumerGroup())
                .shutdownAwaitMillis(config.shutdownAwaitMillis())
                .daemonThreads(config.daemonThreads())
                .claimIdleMillis(config.claimIdleMillis())
                .claimBatchSize(config.claimBatchSize())
                .maxReplayRetries(config.maxReplayRetries())
                .replayBackoffMillis(config.replayBackoffMillis())
                .retryOverrides(config.retryOverrides())
                .reconciliationStreamKey(config.reconciliationStreamKey())
                .archiveResolvedEntries(config.archiveResolvedEntries())
                .archiveStreamKey(config.archiveStreamKey())
                .cleanupEnabled(config.cleanupEnabled())
                .cleanupIntervalMillis(config.cleanupIntervalMillis())
                .cleanupBatchSize(config.cleanupBatchSize())
                .deadLetterRetentionMillis(config.deadLetterRetentionMillis())
                .reconciliationRetentionMillis(config.reconciliationRetentionMillis())
                .archiveRetentionMillis(config.archiveRetentionMillis())
                .deadLetterMaxLength(config.deadLetterMaxLength())
                .reconciliationMaxLength(config.reconciliationMaxLength())
                .archiveMaxLength(config.archiveMaxLength())
                .build();
    }

    static ProjectionRefreshConfig withInstanceIdentity(
            ProjectionRefreshConfig config,
            RuntimeCoordinationConfig coordination,
            String instanceId
    ) {
        return ProjectionRefreshConfig.builder()
                .enabled(config.enabled())
                .streamKey(config.streamKey())
                .consumerGroup(config.consumerGroup())
                .consumerNamePrefix(consumerNamePrefix(config.consumerNamePrefix(), coordination, instanceId))
                .batchSize(config.batchSize())
                .blockTimeoutMillis(config.blockTimeoutMillis())
                .idleSleepMillis(config.idleSleepMillis())
                .autoCreateConsumerGroup(config.autoCreateConsumerGroup())
                .recoverPendingEntries(config.recoverPendingEntries())
                .claimIdleMillis(config.claimIdleMillis())
                .claimBatchSize(config.claimBatchSize())
                .maxStreamLength(config.maxStreamLength())
                .deadLetterEnabled(config.deadLetterEnabled())
                .deadLetterStreamKey(config.deadLetterStreamKey())
                .deadLetterMaxLength(config.deadLetterMaxLength())
                .maxAttempts(config.maxAttempts())
                .deadLetterWarnThreshold(config.deadLetterWarnThreshold())
                .deadLetterCriticalThreshold(config.deadLetterCriticalThreshold())
                .shutdownAwaitMillis(config.shutdownAwaitMillis())
                .daemonThreads(config.daemonThreads())
                .build();
    }

    static AdminMonitoringConfig withInstanceIdentity(
            AdminMonitoringConfig config,
            RuntimeCoordinationConfig coordination,
            String instanceId
    ) {
        IncidentDeliveryDlqConfig incidentDeliveryDlq = config.incidentDeliveryDlq();
        IncidentDeliveryDlqConfig effectiveDlq = IncidentDeliveryDlqConfig.builder()
                .enabled(incidentDeliveryDlq.enabled())
                .streamKey(incidentDeliveryDlq.streamKey())
                .recoveryStreamKey(incidentDeliveryDlq.recoveryStreamKey())
                .consumerGroup(incidentDeliveryDlq.consumerGroup())
                .consumerNamePrefix(consumerNamePrefix(incidentDeliveryDlq.consumerNamePrefix(), coordination, instanceId))
                .workerThreads(incidentDeliveryDlq.workerThreads())
                .batchSize(incidentDeliveryDlq.batchSize())
                .blockTimeoutMillis(incidentDeliveryDlq.blockTimeoutMillis())
                .maxReplayAttempts(incidentDeliveryDlq.maxReplayAttempts())
                .autoCreateConsumerGroup(incidentDeliveryDlq.autoCreateConsumerGroup())
                .claimAbandonedEntries(incidentDeliveryDlq.claimAbandonedEntries())
                .claimIdleMillis(incidentDeliveryDlq.claimIdleMillis())
                .claimBatchSize(incidentDeliveryDlq.claimBatchSize())
                .idleSleepMillis(incidentDeliveryDlq.idleSleepMillis())
                .build();
        return AdminMonitoringConfig.builder()
                .enabled(config.enabled())
                .writeBehindWarnThreshold(config.writeBehindWarnThreshold())
                .writeBehindCriticalThreshold(config.writeBehindCriticalThreshold())
                .deadLetterWarnThreshold(config.deadLetterWarnThreshold())
                .deadLetterCriticalThreshold(config.deadLetterCriticalThreshold())
                .recoveryFailedWarnThreshold(config.recoveryFailedWarnThreshold())
                .recoveryFailedCriticalThreshold(config.recoveryFailedCriticalThreshold())
                .recentErrorWindowMillis(config.recentErrorWindowMillis())
                .historySampleIntervalMillis(config.historySampleIntervalMillis())
                .historyMinSampleIntervalMillis(config.historyMinSampleIntervalMillis())
                .historyMaxSamples(config.historyMaxSamples())
                .historyMinSamples(config.historyMinSamples())
                .alertRouteHistoryMinSamples(config.alertRouteHistoryMinSamples())
                .alertRouteHistorySampleMultiplier(config.alertRouteHistorySampleMultiplier())
                .telemetryTtlSeconds(config.telemetryTtlSeconds())
                .monitoringHistoryStreamKey(config.monitoringHistoryStreamKey())
                .alertRouteHistoryStreamKey(config.alertRouteHistoryStreamKey())
                .performanceHistoryStreamKey(config.performanceHistoryStreamKey())
                .performanceSnapshotKey(config.performanceSnapshotKey())
                .incidentTtlSeconds(config.incidentTtlSeconds())
                .incidentStreamKey(config.incidentStreamKey())
                .incidentMaxLength(config.incidentMaxLength())
                .incidentCooldownMillis(config.incidentCooldownMillis())
                .incidentDeliveryQueueFloor(config.incidentDeliveryQueueFloor())
                .incidentDeliveryPollTimeoutMillis(config.incidentDeliveryPollTimeoutMillis())
                .incidentWebhook(config.incidentWebhook())
                .incidentQueue(config.incidentQueue())
                .incidentEmail(config.incidentEmail())
                .incidentDeliveryDlq(effectiveDlq)
                .build();
    }

    static RedisLeaderLease leaderLease(
            JedisPooled jedis,
            String keyPrefix,
            RuntimeCoordinationConfig coordination,
            String instanceId,
            String role
    ) {
        if (coordination == null || !coordination.leaderLeaseEnabled()) {
            return RedisLeaderLease.disabled();
        }
        String keyspacePrefix = keyPrefix == null || keyPrefix.isBlank() ? "cachedb" : keyPrefix;
        String segment = coordination.leaderLeaseSegment() == null || coordination.leaderLeaseSegment().isBlank()
                ? "coordination:leader"
                : coordination.leaderLeaseSegment();
        String leaseKey = keyspacePrefix + ":" + segment + ":" + sanitize(role);
        return RedisLeaderLease.create(
                jedis,
                leaseKey,
                instanceId,
                coordination.leaderLeaseTtlMillis(),
                coordination.leaderLeaseRenewIntervalMillis()
        );
    }

    static String consumerNamePrefix(String basePrefix, RuntimeCoordinationConfig coordination, String instanceId) {
        String normalizedPrefix = sanitize(basePrefix == null || basePrefix.isBlank() ? "cachedb-worker" : basePrefix);
        if (coordination == null || !coordination.appendInstanceIdToConsumerNames()) {
            return normalizedPrefix;
        }
        if (instanceId == null || instanceId.isBlank()) {
            return normalizedPrefix;
        }
        String normalizedInstanceId = sanitize(instanceId);
        if (normalizedPrefix.endsWith("-" + normalizedInstanceId)) {
            return normalizedPrefix;
        }
        return normalizedPrefix + "-" + normalizedInstanceId;
    }

    private static String sanitize(String value) {
        String normalized = value == null || value.isBlank() ? "default" : value.trim();
        StringBuilder builder = new StringBuilder(normalized.length());
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            if (Character.isLetterOrDigit(current) || current == '-' || current == '_' || current == '.') {
                builder.append(Character.toLowerCase(current));
            } else {
                builder.append('-');
            }
        }
        String sanitized = builder.toString().replaceAll("-{2,}", "-");
        sanitized = sanitized.replaceAll("^-+", "").replaceAll("-+$", "");
        return sanitized.isBlank() ? "default" : sanitized;
    }
}
