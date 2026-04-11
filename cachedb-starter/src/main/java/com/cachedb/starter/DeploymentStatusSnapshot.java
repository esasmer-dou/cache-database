package com.reactor.cachedb.starter;

import java.time.Instant;
import java.util.List;

public record DeploymentStatusSnapshot(
        boolean adminHttpEnabled,
        boolean dashboardEnabled,
        boolean writeBehindEnabled,
        int writeBehindWorkerThreads,
        int activeWriteStreamCount,
        boolean dedicatedWriteConsumerGroupEnabled,
        boolean durableCompactionEnabled,
        boolean postgresCopyBulkLoadEnabled,
        boolean postgresMultiRowFlushEnabled,
        boolean redisGuardrailsEnabled,
        boolean automaticRuntimeProfileSwitchingEnabled,
        String schemaBootstrapMode,
        boolean schemaAutoApplyOnStart,
        boolean queryPlannerLearningEnabled,
        boolean queryPlannerPersistenceEnabled,
        String keyPrefix,
        Instant recordedAt,
        List<String> activeStreamKeys
) {
}
