package com.reactor.cachedb.core.config;

public record AdminMonitoringConfig(
        boolean enabled,
        long writeBehindWarnThreshold,
        long writeBehindCriticalThreshold,
        long deadLetterWarnThreshold,
        long deadLetterCriticalThreshold,
        long recoveryFailedWarnThreshold,
        long recoveryFailedCriticalThreshold,
        long recentErrorWindowMillis,
        long historySampleIntervalMillis,
        long historyMinSampleIntervalMillis,
        int historyMaxSamples,
        int historyMinSamples,
        int alertRouteHistoryMinSamples,
        int alertRouteHistorySampleMultiplier,
        long telemetryTtlSeconds,
        String monitoringHistoryStreamKey,
        String alertRouteHistoryStreamKey,
        String performanceHistoryStreamKey,
        String performanceSnapshotKey,
        long incidentTtlSeconds,
        String incidentStreamKey,
        long incidentMaxLength,
        long incidentCooldownMillis,
        int incidentDeliveryQueueFloor,
        long incidentDeliveryPollTimeoutMillis,
        IncidentWebhookConfig incidentWebhook,
        IncidentQueueConfig incidentQueue,
        IncidentEmailConfig incidentEmail,
        IncidentDeliveryDlqConfig incidentDeliveryDlq
) {
    public static Builder builder() {
        return new Builder();
    }

    public static AdminMonitoringConfig defaults() {
        return builder().build();
    }

    public static final class Builder {
        private boolean enabled;
        private long writeBehindWarnThreshold = 250;
        private long writeBehindCriticalThreshold = 750;
        private long deadLetterWarnThreshold = 10;
        private long deadLetterCriticalThreshold = 100;
        private long recoveryFailedWarnThreshold = 10;
        private long recoveryFailedCriticalThreshold = 100;
        private long recentErrorWindowMillis = 60_000;
        private long historySampleIntervalMillis = 5_000;
        private long historyMinSampleIntervalMillis = 1_000;
        private int historyMaxSamples = 720;
        private int historyMinSamples = 32;
        private int alertRouteHistoryMinSamples = 64;
        private int alertRouteHistorySampleMultiplier = 4;
        private long telemetryTtlSeconds = 86_400;
        private String monitoringHistoryStreamKey = "cachedb:stream:admin:monitoring-history";
        private String alertRouteHistoryStreamKey = "cachedb:stream:admin:alert-route-history";
        private String performanceHistoryStreamKey = "cachedb:stream:admin:performance-history";
        private String performanceSnapshotKey = "cachedb:hash:admin:performance";
        private long incidentTtlSeconds = 86_400;
        private String incidentStreamKey = "cachedb:stream:admin:incidents";
        private long incidentMaxLength = 2_000;
        private long incidentCooldownMillis = 30_000;
        private int incidentDeliveryQueueFloor = 64;
        private long incidentDeliveryPollTimeoutMillis = 500;
        private IncidentWebhookConfig incidentWebhook = IncidentWebhookConfig.defaults();
        private IncidentQueueConfig incidentQueue = IncidentQueueConfig.defaults();
        private IncidentEmailConfig incidentEmail = IncidentEmailConfig.defaults();
        private IncidentDeliveryDlqConfig incidentDeliveryDlq = IncidentDeliveryDlqConfig.defaults();

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder writeBehindWarnThreshold(long writeBehindWarnThreshold) {
            this.writeBehindWarnThreshold = writeBehindWarnThreshold;
            return this;
        }

        public Builder writeBehindCriticalThreshold(long writeBehindCriticalThreshold) {
            this.writeBehindCriticalThreshold = writeBehindCriticalThreshold;
            return this;
        }

        public Builder deadLetterWarnThreshold(long deadLetterWarnThreshold) {
            this.deadLetterWarnThreshold = deadLetterWarnThreshold;
            return this;
        }

        public Builder deadLetterCriticalThreshold(long deadLetterCriticalThreshold) {
            this.deadLetterCriticalThreshold = deadLetterCriticalThreshold;
            return this;
        }

        public Builder recoveryFailedWarnThreshold(long recoveryFailedWarnThreshold) {
            this.recoveryFailedWarnThreshold = recoveryFailedWarnThreshold;
            return this;
        }

        public Builder recoveryFailedCriticalThreshold(long recoveryFailedCriticalThreshold) {
            this.recoveryFailedCriticalThreshold = recoveryFailedCriticalThreshold;
            return this;
        }

        public Builder recentErrorWindowMillis(long recentErrorWindowMillis) {
            this.recentErrorWindowMillis = recentErrorWindowMillis;
            return this;
        }

        public Builder historySampleIntervalMillis(long historySampleIntervalMillis) {
            this.historySampleIntervalMillis = historySampleIntervalMillis;
            return this;
        }

        public Builder historyMinSampleIntervalMillis(long historyMinSampleIntervalMillis) {
            this.historyMinSampleIntervalMillis = historyMinSampleIntervalMillis;
            return this;
        }

        public Builder historyMaxSamples(int historyMaxSamples) {
            this.historyMaxSamples = historyMaxSamples;
            return this;
        }

        public Builder historyMinSamples(int historyMinSamples) {
            this.historyMinSamples = historyMinSamples;
            return this;
        }

        public Builder alertRouteHistoryMinSamples(int alertRouteHistoryMinSamples) {
            this.alertRouteHistoryMinSamples = alertRouteHistoryMinSamples;
            return this;
        }

        public Builder alertRouteHistorySampleMultiplier(int alertRouteHistorySampleMultiplier) {
            this.alertRouteHistorySampleMultiplier = alertRouteHistorySampleMultiplier;
            return this;
        }

        public Builder telemetryTtlSeconds(long telemetryTtlSeconds) {
            this.telemetryTtlSeconds = telemetryTtlSeconds;
            return this;
        }

        public Builder monitoringHistoryStreamKey(String monitoringHistoryStreamKey) {
            this.monitoringHistoryStreamKey = monitoringHistoryStreamKey;
            return this;
        }

        public Builder alertRouteHistoryStreamKey(String alertRouteHistoryStreamKey) {
            this.alertRouteHistoryStreamKey = alertRouteHistoryStreamKey;
            return this;
        }

        public Builder performanceHistoryStreamKey(String performanceHistoryStreamKey) {
            this.performanceHistoryStreamKey = performanceHistoryStreamKey;
            return this;
        }

        public Builder performanceSnapshotKey(String performanceSnapshotKey) {
            this.performanceSnapshotKey = performanceSnapshotKey;
            return this;
        }

        public Builder incidentTtlSeconds(long incidentTtlSeconds) {
            this.incidentTtlSeconds = incidentTtlSeconds;
            return this;
        }

        public Builder incidentStreamKey(String incidentStreamKey) {
            this.incidentStreamKey = incidentStreamKey;
            return this;
        }

        public Builder incidentMaxLength(long incidentMaxLength) {
            this.incidentMaxLength = incidentMaxLength;
            return this;
        }

        public Builder incidentCooldownMillis(long incidentCooldownMillis) {
            this.incidentCooldownMillis = incidentCooldownMillis;
            return this;
        }

        public Builder incidentDeliveryQueueFloor(int incidentDeliveryQueueFloor) {
            this.incidentDeliveryQueueFloor = incidentDeliveryQueueFloor;
            return this;
        }

        public Builder incidentDeliveryPollTimeoutMillis(long incidentDeliveryPollTimeoutMillis) {
            this.incidentDeliveryPollTimeoutMillis = incidentDeliveryPollTimeoutMillis;
            return this;
        }

        public Builder incidentWebhook(IncidentWebhookConfig incidentWebhook) {
            this.incidentWebhook = incidentWebhook;
            return this;
        }

        public Builder incidentQueue(IncidentQueueConfig incidentQueue) {
            this.incidentQueue = incidentQueue;
            return this;
        }

        public Builder incidentEmail(IncidentEmailConfig incidentEmail) {
            this.incidentEmail = incidentEmail;
            return this;
        }

        public Builder incidentDeliveryDlq(IncidentDeliveryDlqConfig incidentDeliveryDlq) {
            this.incidentDeliveryDlq = incidentDeliveryDlq;
            return this;
        }

        public AdminMonitoringConfig build() {
            return new AdminMonitoringConfig(
                    enabled,
                    writeBehindWarnThreshold,
                    writeBehindCriticalThreshold,
                    deadLetterWarnThreshold,
                    deadLetterCriticalThreshold,
                    recoveryFailedWarnThreshold,
                    recoveryFailedCriticalThreshold,
                    recentErrorWindowMillis,
                    historySampleIntervalMillis,
                    historyMinSampleIntervalMillis,
                    historyMaxSamples,
                    historyMinSamples,
                    alertRouteHistoryMinSamples,
                    alertRouteHistorySampleMultiplier,
                    telemetryTtlSeconds,
                    monitoringHistoryStreamKey,
                    alertRouteHistoryStreamKey,
                    performanceHistoryStreamKey,
                    performanceSnapshotKey,
                    incidentTtlSeconds,
                    incidentStreamKey,
                    incidentMaxLength,
                    incidentCooldownMillis,
                    incidentDeliveryQueueFloor,
                    incidentDeliveryPollTimeoutMillis,
                    incidentWebhook,
                    incidentQueue,
                    incidentEmail,
                    incidentDeliveryDlq
            );
        }
    }
}
