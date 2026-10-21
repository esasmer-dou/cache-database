package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.api.CacheSession;
import com.reactor.cachedb.core.queue.*;
import com.reactor.cachedb.core.registry.EntityRegistry;
import com.reactor.cachedb.redis.*;

import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import javax.sql.DataSource;

/** Named infrastructure boundary; expensive migration/report services are created on first use. */
final class CacheDatabaseAdminContext {
    record Infrastructure(
            DataSource source,
            RedisClientOperations redis,
            RedisKeyStrategy keys,
            EntityRegistry registry,
            CacheSession session,
            DeadLetterManagement deadLetters,
            RedisIndexMaintenance indexes,
            CacheDatabaseSchemaAdmin schema,
            Consumer<AdminIncidentRecord> incidentNotifier) {}

    private final CacheDatabase database;
    private final Infrastructure infrastructure;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile CacheDatabaseAdmin admin;

    CacheDatabaseAdminContext(CacheDatabase database, Infrastructure infrastructure) {
        this.database = database;
        this.infrastructure = infrastructure;
    }

    CacheDatabaseAdmin get() {
        CacheDatabaseAdmin value = admin;
        if (value != null) return value;
        lock.lock();
        try {
            if (admin == null) admin = create();
            return admin;
        } finally {
            lock.unlock();
        }
    }

    private CacheDatabaseAdmin create() {
        var config = database.config();
        var i = infrastructure;
        var result =
                new CacheDatabaseAdmin(
                        i.deadLetters(),
                        i.redis(),
                        config.keyspace().keyPrefix(),
                        config.writeBehind().activeStreamKeys(),
                        config.writeBehind().deadLetterStreamKey(),
                        config.deadLetterRecovery().reconciliationStreamKey(),
                        config.deadLetterRecovery().archiveStreamKey(),
                        config.adminReportJob().diagnosticsStreamKey(),
                        config.adminReportJob().diagnosticsMaxLength(),
                        config.adminMonitoring(),
                        config.redisGuardrail(),
                        database::workerSnapshot,
                        database::deadLetterRecoverySnapshot,
                        database::recoveryCleanupSnapshot,
                        database::adminReportJobSnapshot,
                        database::incidentDeliverySnapshot,
                        database::redisGuardrailSnapshot,
                        database::redisRuntimeProfileSnapshot,
                        database::storagePerformanceSnapshot,
                        database::projectionRefreshSnapshot,
                        database::activeRuntimeProfileProperties,
                        database::manualRuntimeProfileOverride,
                        database::setManualRuntimeProfile,
                        database::clearManualRuntimeProfileOverride,
                        i.indexes(),
                        i.schema(),
                        i.incidentNotifier(),
                        i.source(),
                        config,
                        i.registry(),
                        i.session(),
                        new ProductionReportCatalog(
                                ProductionReportCatalog.defaultRoots(Path.of("").toAbsolutePath())),
                        database::monitoringHistory,
                        database::alertRouteHistory,
                        database::performanceHistory,
                        database::projectionRefreshFailures,
                        database::replayProjectionRefreshFailure,
                        database::resetAdminTelemetryBuffers,
                        database::resetPerformanceTelemetry);
        if (i.keys() instanceof ClusterHashTagKeyStrategy cluster && cluster.namespaceSharded()) {
            result.runtimeStreams(
                    () -> cluster.activeStreamKeys(config.writeBehind()),
                    () ->
                            RedisBacklogEstimator.estimateWriteBehindBacklog(
                                    i.redis(), cluster, config.writeBehind()));
        }
        return result;
    }
}
