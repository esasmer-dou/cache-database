package com.reactor.cachedb.core.config;

import java.util.List;

public record RedisGuardrailConfig(
        boolean enabled,
        boolean producerBackpressureEnabled,
        long usedMemoryWarnBytes,
        long usedMemoryCriticalBytes,
        long writeBehindBacklogWarnThreshold,
        long writeBehindBacklogCriticalThreshold,
        long compactionPendingWarnThreshold,
        long compactionPendingCriticalThreshold,
        long writeBehindBacklogHardLimit,
        long compactionPendingHardLimit,
        long compactionPayloadHardLimit,
        boolean rejectWritesOnHardLimit,
        boolean shedPageCacheWritesOnHardLimit,
        boolean shedReadThroughCacheOnHardLimit,
        boolean shedHotSetTrackingOnHardLimit,
        boolean shedQueryIndexWritesOnHardLimit,
        boolean shedQueryIndexReadsOnHardLimit,
        boolean shedPlannerLearningOnHardLimit,
        List<HardLimitEntityPolicy> entityPolicies,
        List<HardLimitQueryPolicy> queryPolicies,
        long highSleepMillis,
        long criticalSleepMillis,
        long sampleIntervalMillis,
        boolean automaticRuntimeProfileSwitchingEnabled,
        int warnSamplesToBalanced,
        int criticalSamplesToAggressive,
        int warnSamplesToDeescalateAggressive,
        int normalSamplesToStandard,
        int compactionPayloadTtlSeconds,
        int compactionPendingTtlSeconds,
        int versionKeyTtlSeconds,
        int tombstoneTtlSeconds,
        boolean autoRecoverDegradedIndexesEnabled,
        long degradedIndexRebuildCooldownMillis
) {
    public static Builder builder() {
        return new Builder();
    }

    public static RedisGuardrailConfig defaults() {
        return builder().build();
    }

    public static final class Builder {
        private boolean enabled = true;
        private boolean producerBackpressureEnabled = true;
        private long usedMemoryWarnBytes = 0L;
        private long usedMemoryCriticalBytes = 0L;
        private long writeBehindBacklogWarnThreshold = 250L;
        private long writeBehindBacklogCriticalThreshold = 750L;
        private long compactionPendingWarnThreshold = 1_000L;
        private long compactionPendingCriticalThreshold = 5_000L;
        private long writeBehindBacklogHardLimit = 0L;
        private long compactionPendingHardLimit = 0L;
        private long compactionPayloadHardLimit = 0L;
        private boolean rejectWritesOnHardLimit = false;
        private boolean shedPageCacheWritesOnHardLimit = true;
        private boolean shedReadThroughCacheOnHardLimit = true;
        private boolean shedHotSetTrackingOnHardLimit = true;
        private boolean shedQueryIndexWritesOnHardLimit = true;
        private boolean shedQueryIndexReadsOnHardLimit = true;
        private boolean shedPlannerLearningOnHardLimit = true;
        private List<HardLimitEntityPolicy> entityPolicies = List.of();
        private List<HardLimitQueryPolicy> queryPolicies = List.of();
        private long highSleepMillis = 2L;
        private long criticalSleepMillis = 5L;
        private long sampleIntervalMillis = 500L;
        private boolean automaticRuntimeProfileSwitchingEnabled = true;
        private int warnSamplesToBalanced = 3;
        private int criticalSamplesToAggressive = 2;
        private int warnSamplesToDeescalateAggressive = 4;
        private int normalSamplesToStandard = 5;
        private int compactionPayloadTtlSeconds = 3_600;
        private int compactionPendingTtlSeconds = 3_600;
        private int versionKeyTtlSeconds = 86_400;
        private int tombstoneTtlSeconds = 86_400;
        private boolean autoRecoverDegradedIndexesEnabled = true;
        private long degradedIndexRebuildCooldownMillis = 30_000L;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder producerBackpressureEnabled(boolean producerBackpressureEnabled) {
            this.producerBackpressureEnabled = producerBackpressureEnabled;
            return this;
        }

        public Builder usedMemoryWarnBytes(long usedMemoryWarnBytes) {
            this.usedMemoryWarnBytes = usedMemoryWarnBytes;
            return this;
        }

        public Builder usedMemoryCriticalBytes(long usedMemoryCriticalBytes) {
            this.usedMemoryCriticalBytes = usedMemoryCriticalBytes;
            return this;
        }

        public Builder writeBehindBacklogWarnThreshold(long writeBehindBacklogWarnThreshold) {
            this.writeBehindBacklogWarnThreshold = writeBehindBacklogWarnThreshold;
            return this;
        }

        public Builder writeBehindBacklogCriticalThreshold(long writeBehindBacklogCriticalThreshold) {
            this.writeBehindBacklogCriticalThreshold = writeBehindBacklogCriticalThreshold;
            return this;
        }

        public Builder compactionPendingWarnThreshold(long compactionPendingWarnThreshold) {
            this.compactionPendingWarnThreshold = compactionPendingWarnThreshold;
            return this;
        }

        public Builder compactionPendingCriticalThreshold(long compactionPendingCriticalThreshold) {
            this.compactionPendingCriticalThreshold = compactionPendingCriticalThreshold;
            return this;
        }

        public Builder writeBehindBacklogHardLimit(long writeBehindBacklogHardLimit) {
            this.writeBehindBacklogHardLimit = writeBehindBacklogHardLimit;
            return this;
        }

        public Builder compactionPendingHardLimit(long compactionPendingHardLimit) {
            this.compactionPendingHardLimit = compactionPendingHardLimit;
            return this;
        }

        public Builder compactionPayloadHardLimit(long compactionPayloadHardLimit) {
            this.compactionPayloadHardLimit = compactionPayloadHardLimit;
            return this;
        }

        public Builder rejectWritesOnHardLimit(boolean rejectWritesOnHardLimit) {
            this.rejectWritesOnHardLimit = rejectWritesOnHardLimit;
            return this;
        }

        public Builder shedPageCacheWritesOnHardLimit(boolean shedPageCacheWritesOnHardLimit) {
            this.shedPageCacheWritesOnHardLimit = shedPageCacheWritesOnHardLimit;
            return this;
        }

        public Builder shedReadThroughCacheOnHardLimit(boolean shedReadThroughCacheOnHardLimit) {
            this.shedReadThroughCacheOnHardLimit = shedReadThroughCacheOnHardLimit;
            return this;
        }

        public Builder shedHotSetTrackingOnHardLimit(boolean shedHotSetTrackingOnHardLimit) {
            this.shedHotSetTrackingOnHardLimit = shedHotSetTrackingOnHardLimit;
            return this;
        }

        public Builder shedQueryIndexWritesOnHardLimit(boolean shedQueryIndexWritesOnHardLimit) {
            this.shedQueryIndexWritesOnHardLimit = shedQueryIndexWritesOnHardLimit;
            return this;
        }

        public Builder shedQueryIndexReadsOnHardLimit(boolean shedQueryIndexReadsOnHardLimit) {
            this.shedQueryIndexReadsOnHardLimit = shedQueryIndexReadsOnHardLimit;
            return this;
        }

        public Builder shedPlannerLearningOnHardLimit(boolean shedPlannerLearningOnHardLimit) {
            this.shedPlannerLearningOnHardLimit = shedPlannerLearningOnHardLimit;
            return this;
        }

        public Builder entityPolicies(List<HardLimitEntityPolicy> entityPolicies) {
            this.entityPolicies = List.copyOf(entityPolicies);
            return this;
        }

        public Builder queryPolicies(List<HardLimitQueryPolicy> queryPolicies) {
            this.queryPolicies = List.copyOf(queryPolicies);
            return this;
        }

        public Builder highSleepMillis(long highSleepMillis) {
            this.highSleepMillis = highSleepMillis;
            return this;
        }

        public Builder criticalSleepMillis(long criticalSleepMillis) {
            this.criticalSleepMillis = criticalSleepMillis;
            return this;
        }

        public Builder sampleIntervalMillis(long sampleIntervalMillis) {
            this.sampleIntervalMillis = sampleIntervalMillis;
            return this;
        }

        public Builder automaticRuntimeProfileSwitchingEnabled(boolean automaticRuntimeProfileSwitchingEnabled) {
            this.automaticRuntimeProfileSwitchingEnabled = automaticRuntimeProfileSwitchingEnabled;
            return this;
        }

        public Builder warnSamplesToBalanced(int warnSamplesToBalanced) {
            this.warnSamplesToBalanced = warnSamplesToBalanced;
            return this;
        }

        public Builder criticalSamplesToAggressive(int criticalSamplesToAggressive) {
            this.criticalSamplesToAggressive = criticalSamplesToAggressive;
            return this;
        }

        public Builder warnSamplesToDeescalateAggressive(int warnSamplesToDeescalateAggressive) {
            this.warnSamplesToDeescalateAggressive = warnSamplesToDeescalateAggressive;
            return this;
        }

        public Builder normalSamplesToStandard(int normalSamplesToStandard) {
            this.normalSamplesToStandard = normalSamplesToStandard;
            return this;
        }

        public Builder compactionPayloadTtlSeconds(int compactionPayloadTtlSeconds) {
            this.compactionPayloadTtlSeconds = compactionPayloadTtlSeconds;
            return this;
        }

        public Builder compactionPendingTtlSeconds(int compactionPendingTtlSeconds) {
            this.compactionPendingTtlSeconds = compactionPendingTtlSeconds;
            return this;
        }

        public Builder versionKeyTtlSeconds(int versionKeyTtlSeconds) {
            this.versionKeyTtlSeconds = versionKeyTtlSeconds;
            return this;
        }

        public Builder tombstoneTtlSeconds(int tombstoneTtlSeconds) {
            this.tombstoneTtlSeconds = tombstoneTtlSeconds;
            return this;
        }

        public Builder autoRecoverDegradedIndexesEnabled(boolean autoRecoverDegradedIndexesEnabled) {
            this.autoRecoverDegradedIndexesEnabled = autoRecoverDegradedIndexesEnabled;
            return this;
        }

        public Builder degradedIndexRebuildCooldownMillis(long degradedIndexRebuildCooldownMillis) {
            this.degradedIndexRebuildCooldownMillis = degradedIndexRebuildCooldownMillis;
            return this;
        }

        public RedisGuardrailConfig build() {
            return new RedisGuardrailConfig(
                    enabled,
                    producerBackpressureEnabled,
                    usedMemoryWarnBytes,
                    usedMemoryCriticalBytes,
                    writeBehindBacklogWarnThreshold,
                    writeBehindBacklogCriticalThreshold,
                    compactionPendingWarnThreshold,
                    compactionPendingCriticalThreshold,
                    writeBehindBacklogHardLimit,
                    compactionPendingHardLimit,
                    compactionPayloadHardLimit,
                    rejectWritesOnHardLimit,
                    shedPageCacheWritesOnHardLimit,
                    shedReadThroughCacheOnHardLimit,
                    shedHotSetTrackingOnHardLimit,
                    shedQueryIndexWritesOnHardLimit,
                    shedQueryIndexReadsOnHardLimit,
                    shedPlannerLearningOnHardLimit,
                    entityPolicies,
                    queryPolicies,
                    highSleepMillis,
                    criticalSleepMillis,
                    sampleIntervalMillis,
                    automaticRuntimeProfileSwitchingEnabled,
                    warnSamplesToBalanced,
                    criticalSamplesToAggressive,
                    warnSamplesToDeescalateAggressive,
                    normalSamplesToStandard,
                    compactionPayloadTtlSeconds,
                    compactionPendingTtlSeconds,
                    versionKeyTtlSeconds,
                    tombstoneTtlSeconds,
                    autoRecoverDegradedIndexesEnabled,
                    degradedIndexRebuildCooldownMillis
            );
        }
    }
}
