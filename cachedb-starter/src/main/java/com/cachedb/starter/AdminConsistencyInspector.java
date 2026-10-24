package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.AdminMonitoringConfig;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.RedisGuardrailConfig;
import com.reactor.cachedb.core.queue.*;
import com.reactor.cachedb.redis.RedisClientOperations;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded consistency sampling, independent of HTTP rendering and export state. */
final class AdminConsistencyInspector {
    private static final int ADMIN_KEY_SCAN_CAP = 50_000;
    private static final int ADMIN_METRICS_SCAN_COUNT = 2_000;
    private static final int ADMIN_METRICS_SCAN_STEPS = 1;
    private final RedisClientOperations jedis;
    private final AdminMonitoringConfig monitoringConfig;
    private final RedisGuardrailConfig redisGuardrailConfig;
    private final CacheDatabaseConfig cacheDatabaseConfig;
    private final Map<String, IncrementalKeyScan> plannerKeyScans = new ConcurrentHashMap<>();
    AdminConsistencyInspector(RedisClientOperations jedis, AdminMonitoringConfig monitoringConfig,
            RedisGuardrailConfig redisGuardrailConfig, CacheDatabaseConfig cacheDatabaseConfig) {
        this.jedis = jedis;
        this.monitoringConfig = monitoringConfig;
        this.redisGuardrailConfig = redisGuardrailConfig;
        this.cacheDatabaseConfig = cacheDatabaseConfig;
    }

    ReconciliationHealth buildHealth(ReconciliationMetrics metrics) {
        ArrayList<String> issues = new ArrayList<>();
        AdminHealthStatus status = AdminHealthStatus.UP;

        if (metrics.writeBehindStreamLength() >= monitoringConfig.writeBehindCriticalThreshold()) {
            issues.add("Write-behind backlog is above critical threshold");
            status = AdminHealthStatus.DOWN;
        } else if (metrics.writeBehindStreamLength()
                >= monitoringConfig.writeBehindWarnThreshold()) {
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

        if (metrics.deadLetterRecoverySnapshot().failedCount()
                >= monitoringConfig.recoveryFailedCriticalThreshold()) {
            issues.add("Recovery worker failures are above critical threshold");
            status = AdminHealthStatus.DOWN;
        } else if (metrics.deadLetterRecoverySnapshot().failedCount()
                >= monitoringConfig.recoveryFailedWarnThreshold()) {
            issues.add("Recovery worker failures are above warning threshold");
            status = elevate(status, AdminHealthStatus.DEGRADED);
        }

        if (redisGuardrailConfig.usedMemoryCriticalBytes() > 0
                && metrics.redisGuardrailSnapshot().usedMemoryBytes()
                        >= redisGuardrailConfig.usedMemoryCriticalBytes()) {
            issues.add("Redis used memory is above critical threshold");
            status = AdminHealthStatus.DOWN;
        } else if (redisGuardrailConfig.usedMemoryWarnBytes() > 0
                && metrics.redisGuardrailSnapshot().usedMemoryBytes()
                        >= redisGuardrailConfig.usedMemoryWarnBytes()) {
            issues.add("Redis used memory is above warning threshold");
            status = elevate(status, AdminHealthStatus.DEGRADED);
        }

        if (redisGuardrailConfig.compactionPendingCriticalThreshold() > 0
                && metrics.redisGuardrailSnapshot().compactionPendingCount()
                        >= redisGuardrailConfig.compactionPendingCriticalThreshold()) {
            issues.add("Compaction pending count is above critical threshold");
            status = AdminHealthStatus.DOWN;
        } else if (redisGuardrailConfig.compactionPendingWarnThreshold() > 0
                && metrics.redisGuardrailSnapshot().compactionPendingCount()
                        >= redisGuardrailConfig.compactionPendingWarnThreshold()) {
            issues.add("Compaction pending count is above warning threshold");
            status = elevate(status, AdminHealthStatus.DEGRADED);
        }
        if (metrics.redisGuardrailSnapshot().hardRejectedWriteCount() > 0) {
            issues.add("Redis hard limit rejected producer writes");
            status = AdminHealthStatus.DOWN;
        }

        if (cacheDatabaseConfig.projectionRefresh().deadLetterCriticalThreshold() > 0
                && metrics.projectionRefreshSnapshot().deadLetterStreamLength()
                        >= cacheDatabaseConfig.projectionRefresh().deadLetterCriticalThreshold()) {
            issues.add("Projection refresh dead-letter backlog is above critical threshold");
            status = elevate(status, AdminHealthStatus.DEGRADED);
        } else if (cacheDatabaseConfig.projectionRefresh().deadLetterWarnThreshold() > 0
                && metrics.projectionRefreshSnapshot().deadLetterStreamLength()
                        >= cacheDatabaseConfig.projectionRefresh().deadLetterWarnThreshold()) {
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
    long keyCount(String pattern) {
        try {
            return scanKeyCountIncrementally(pattern, ADMIN_KEY_SCAN_CAP);
        } catch (RuntimeException exception) {
            IncrementalKeyScan scan = plannerKeyScans.get(pattern);
            return scan == null ? 0L : scan.completedCount();
        }
    }

    private long scanKeyCountIncrementally(String pattern, int maxKeys) {
        IncrementalKeyScan progress =
                plannerKeyScans.computeIfAbsent(pattern, ignored -> new IncrementalKeyScan());
        redis.clients.jedis.params.ScanParams params =
                new redis.clients.jedis.params.ScanParams()
                        .match(pattern)
                        .count(ADMIN_METRICS_SCAN_COUNT);
        synchronized (progress) {
            for (int step = 0; step < ADMIN_METRICS_SCAN_STEPS; step++) {
                redis.clients.jedis.resps.ScanResult<String> scan =
                        jedis.scan(progress.cursor(), params);
                progress.add(scan.getResult().size());
                if (progress.currentCount() >= maxKeys) {
                    return progress.complete(maxKeys);
                }
                progress.cursor(scan.getCursor());
                if ("0".equals(scan.getCursor())) {
                    return progress.complete(progress.currentCount());
                }
            }
            return progress.completedCount();
        }
    }
    private static final class IncrementalKeyScan {
        private String cursor = "0";
        private long currentCount;
        private long completedCount;

        String cursor() {
            return cursor;
        }

        void cursor(String cursor) {
            this.cursor = cursor;
        }

        long currentCount() {
            return currentCount;
        }

        long completedCount() {
            synchronized (this) {
                return completedCount;
            }
        }

        void add(long count) {
            currentCount += count;
        }

        long complete(long count) {
            completedCount = count;
            currentCount = 0L;
            cursor = "0";
            return completedCount;
        }
    }
    private boolean recentError(long lastErrorAtEpochMillis) {
        return lastErrorAtEpochMillis > 0
                && System.currentTimeMillis() - lastErrorAtEpochMillis
                        <= monitoringConfig.recentErrorWindowMillis();
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
}
