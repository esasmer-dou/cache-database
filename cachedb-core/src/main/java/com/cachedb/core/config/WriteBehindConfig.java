package com.reactor.cachedb.core.config;

import java.util.List;

public record WriteBehindConfig(
        boolean enabled,
        int workerThreads,
        int batchSize,
        boolean dedicatedWriteConsumerGroupEnabled,
        boolean durableCompactionEnabled,
        boolean batchFlushEnabled,
        boolean tableAwareBatchingEnabled,
        int flushGroupParallelism,
        int flushPipelineDepth,
        boolean coalescingEnabled,
        int maxFlushBatchSize,
        boolean batchStaleCheckEnabled,
        long adaptiveBacklogHighWatermark,
        long adaptiveBacklogCriticalWatermark,
        int adaptiveHighFlushBatchSize,
        int adaptiveCriticalFlushBatchSize,
        boolean postgresMultiRowFlushEnabled,
        int postgresMultiRowStatementRowLimit,
        boolean postgresCopyBulkLoadEnabled,
        int postgresCopyThreshold,
        long blockTimeoutMillis,
        long idleSleepMillis,
        int maxFlushRetries,
        long retryBackoffMillis,
        String streamKey,
        String consumerGroup,
        String consumerNamePrefix,
        String compactionStreamKey,
        String compactionConsumerGroup,
        String compactionConsumerNamePrefix,
        int compactionShardCount,
        boolean autoCreateConsumerGroup,
        long shutdownAwaitMillis,
        boolean daemonThreads,
        boolean recoverPendingEntries,
        long claimIdleMillis,
        int claimBatchSize,
        List<WriteRetryPolicyOverride> retryOverrides,
        List<EntityFlushPolicy> entityFlushPolicies,
        long deadLetterMaxLength,
        String deadLetterStreamKey,
        long compactionMaxLength
) {
    public String activeStreamKey() {
        return dedicatedWriteConsumerGroupEnabled ? compactionStreamKey : streamKey;
    }

    public List<String> activeStreamKeys() {
        if (!dedicatedWriteConsumerGroupEnabled) {
            return List.of(streamKey);
        }
        if (compactionShardCount <= 1) {
            return List.of(compactionStreamKey);
        }
        java.util.ArrayList<String> streamKeys = new java.util.ArrayList<>(compactionShardCount);
        for (int shard = 0; shard < compactionShardCount; shard++) {
            streamKeys.add(compactionStreamKey + ":" + shard);
        }
        return List.copyOf(streamKeys);
    }

    public String activeConsumerGroup() {
        return dedicatedWriteConsumerGroupEnabled ? compactionConsumerGroup : consumerGroup;
    }

    public String activeConsumerNamePrefix() {
        return dedicatedWriteConsumerGroupEnabled ? compactionConsumerNamePrefix : consumerNamePrefix;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static WriteBehindConfig defaults() {
        return builder().build();
    }

    public static final class Builder {
        private boolean enabled = true;
        private int workerThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        private int batchSize = 128;
        private boolean dedicatedWriteConsumerGroupEnabled = true;
        private boolean durableCompactionEnabled = true;
        private boolean batchFlushEnabled = true;
        private boolean tableAwareBatchingEnabled = true;
        private int flushGroupParallelism = 4;
        private int flushPipelineDepth = 4;
        private boolean coalescingEnabled = true;
        private int maxFlushBatchSize = 128;
        private boolean batchStaleCheckEnabled = true;
        private long adaptiveBacklogHighWatermark = 250;
        private long adaptiveBacklogCriticalWatermark = 750;
        private int adaptiveHighFlushBatchSize = 256;
        private int adaptiveCriticalFlushBatchSize = 512;
        private boolean postgresMultiRowFlushEnabled = true;
        private int postgresMultiRowStatementRowLimit = 64;
        private boolean postgresCopyBulkLoadEnabled = true;
        private int postgresCopyThreshold = 128;
        private long blockTimeoutMillis = 2_000;
        private long idleSleepMillis = 250;
        private int maxFlushRetries = 3;
        private long retryBackoffMillis = 1_000;
        private String streamKey = "cachedb:stream:write-behind";
        private String consumerGroup = "cachedb-write-behind";
        private String consumerNamePrefix = "cachedb-worker";
        private String compactionStreamKey = "cachedb:stream:write-behind:compaction";
        private String compactionConsumerGroup = "cachedb-write-behind-compaction";
        private String compactionConsumerNamePrefix = "cachedb-compaction-worker";
        private int compactionShardCount = 4;
        private boolean autoCreateConsumerGroup = true;
        private long shutdownAwaitMillis = 10_000;
        private boolean daemonThreads = true;
        private boolean recoverPendingEntries = true;
        private long claimIdleMillis = 5_000;
        private int claimBatchSize = 100;
        private List<WriteRetryPolicyOverride> retryOverrides = List.of();
        private List<EntityFlushPolicy> entityFlushPolicies = List.of();
        private long deadLetterMaxLength = 10_000;
        private String deadLetterStreamKey = "cachedb:stream:write-behind:dlq";
        private long compactionMaxLength = 10_000;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder workerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
            return this;
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder dedicatedWriteConsumerGroupEnabled(boolean dedicatedWriteConsumerGroupEnabled) {
            this.dedicatedWriteConsumerGroupEnabled = dedicatedWriteConsumerGroupEnabled;
            return this;
        }

        public Builder durableCompactionEnabled(boolean durableCompactionEnabled) {
            this.durableCompactionEnabled = durableCompactionEnabled;
            return this;
        }

        public Builder batchFlushEnabled(boolean batchFlushEnabled) {
            this.batchFlushEnabled = batchFlushEnabled;
            return this;
        }

        public Builder tableAwareBatchingEnabled(boolean tableAwareBatchingEnabled) {
            this.tableAwareBatchingEnabled = tableAwareBatchingEnabled;
            return this;
        }

        public Builder flushGroupParallelism(int flushGroupParallelism) {
            this.flushGroupParallelism = flushGroupParallelism;
            return this;
        }

        public Builder flushPipelineDepth(int flushPipelineDepth) {
            this.flushPipelineDepth = flushPipelineDepth;
            return this;
        }

        public Builder coalescingEnabled(boolean coalescingEnabled) {
            this.coalescingEnabled = coalescingEnabled;
            return this;
        }

        public Builder maxFlushBatchSize(int maxFlushBatchSize) {
            this.maxFlushBatchSize = maxFlushBatchSize;
            return this;
        }

        public Builder batchStaleCheckEnabled(boolean batchStaleCheckEnabled) {
            this.batchStaleCheckEnabled = batchStaleCheckEnabled;
            return this;
        }

        public Builder adaptiveBacklogHighWatermark(long adaptiveBacklogHighWatermark) {
            this.adaptiveBacklogHighWatermark = adaptiveBacklogHighWatermark;
            return this;
        }

        public Builder adaptiveBacklogCriticalWatermark(long adaptiveBacklogCriticalWatermark) {
            this.adaptiveBacklogCriticalWatermark = adaptiveBacklogCriticalWatermark;
            return this;
        }

        public Builder adaptiveHighFlushBatchSize(int adaptiveHighFlushBatchSize) {
            this.adaptiveHighFlushBatchSize = adaptiveHighFlushBatchSize;
            return this;
        }

        public Builder adaptiveCriticalFlushBatchSize(int adaptiveCriticalFlushBatchSize) {
            this.adaptiveCriticalFlushBatchSize = adaptiveCriticalFlushBatchSize;
            return this;
        }

        public Builder postgresMultiRowFlushEnabled(boolean postgresMultiRowFlushEnabled) {
            this.postgresMultiRowFlushEnabled = postgresMultiRowFlushEnabled;
            return this;
        }

        public Builder postgresMultiRowStatementRowLimit(int postgresMultiRowStatementRowLimit) {
            this.postgresMultiRowStatementRowLimit = postgresMultiRowStatementRowLimit;
            return this;
        }

        public Builder postgresCopyBulkLoadEnabled(boolean postgresCopyBulkLoadEnabled) {
            this.postgresCopyBulkLoadEnabled = postgresCopyBulkLoadEnabled;
            return this;
        }

        public Builder postgresCopyThreshold(int postgresCopyThreshold) {
            this.postgresCopyThreshold = postgresCopyThreshold;
            return this;
        }

        public Builder blockTimeoutMillis(long blockTimeoutMillis) {
            this.blockTimeoutMillis = blockTimeoutMillis;
            return this;
        }

        public Builder idleSleepMillis(long idleSleepMillis) {
            this.idleSleepMillis = idleSleepMillis;
            return this;
        }

        public Builder maxFlushRetries(int maxFlushRetries) {
            this.maxFlushRetries = maxFlushRetries;
            return this;
        }

        public Builder retryBackoffMillis(long retryBackoffMillis) {
            this.retryBackoffMillis = retryBackoffMillis;
            return this;
        }

        public Builder streamKey(String streamKey) {
            this.streamKey = streamKey;
            return this;
        }

        public Builder consumerGroup(String consumerGroup) {
            this.consumerGroup = consumerGroup;
            return this;
        }

        public Builder consumerNamePrefix(String consumerNamePrefix) {
            this.consumerNamePrefix = consumerNamePrefix;
            return this;
        }

        public Builder compactionStreamKey(String compactionStreamKey) {
            this.compactionStreamKey = compactionStreamKey;
            return this;
        }

        public Builder compactionConsumerGroup(String compactionConsumerGroup) {
            this.compactionConsumerGroup = compactionConsumerGroup;
            return this;
        }

        public Builder compactionConsumerNamePrefix(String compactionConsumerNamePrefix) {
            this.compactionConsumerNamePrefix = compactionConsumerNamePrefix;
            return this;
        }

        public Builder compactionShardCount(int compactionShardCount) {
            this.compactionShardCount = compactionShardCount;
            return this;
        }

        public Builder autoCreateConsumerGroup(boolean autoCreateConsumerGroup) {
            this.autoCreateConsumerGroup = autoCreateConsumerGroup;
            return this;
        }

        public Builder shutdownAwaitMillis(long shutdownAwaitMillis) {
            this.shutdownAwaitMillis = shutdownAwaitMillis;
            return this;
        }

        public Builder daemonThreads(boolean daemonThreads) {
            this.daemonThreads = daemonThreads;
            return this;
        }

        public Builder recoverPendingEntries(boolean recoverPendingEntries) {
            this.recoverPendingEntries = recoverPendingEntries;
            return this;
        }

        public Builder claimIdleMillis(long claimIdleMillis) {
            this.claimIdleMillis = claimIdleMillis;
            return this;
        }

        public Builder claimBatchSize(int claimBatchSize) {
            this.claimBatchSize = claimBatchSize;
            return this;
        }

        public Builder retryOverrides(List<WriteRetryPolicyOverride> retryOverrides) {
            this.retryOverrides = List.copyOf(retryOverrides);
            return this;
        }

        public Builder entityFlushPolicies(List<EntityFlushPolicy> entityFlushPolicies) {
            this.entityFlushPolicies = List.copyOf(entityFlushPolicies);
            return this;
        }

        public Builder deadLetterMaxLength(long deadLetterMaxLength) {
            this.deadLetterMaxLength = deadLetterMaxLength;
            return this;
        }

        public Builder deadLetterStreamKey(String deadLetterStreamKey) {
            this.deadLetterStreamKey = deadLetterStreamKey;
            return this;
        }

        public Builder compactionMaxLength(long compactionMaxLength) {
            this.compactionMaxLength = compactionMaxLength;
            return this;
        }

        public WriteBehindConfig build() {
            return new WriteBehindConfig(
                    enabled,
                    workerThreads,
                    batchSize,
                    dedicatedWriteConsumerGroupEnabled,
                    durableCompactionEnabled,
                    batchFlushEnabled,
                    tableAwareBatchingEnabled,
                    flushGroupParallelism,
                    flushPipelineDepth,
                    coalescingEnabled,
                    maxFlushBatchSize,
                    batchStaleCheckEnabled,
                    adaptiveBacklogHighWatermark,
                    adaptiveBacklogCriticalWatermark,
                    adaptiveHighFlushBatchSize,
                    adaptiveCriticalFlushBatchSize,
                    postgresMultiRowFlushEnabled,
                    postgresMultiRowStatementRowLimit,
                    postgresCopyBulkLoadEnabled,
                    postgresCopyThreshold,
                    blockTimeoutMillis,
                    idleSleepMillis,
                    maxFlushRetries,
                    retryBackoffMillis,
                    streamKey,
                    consumerGroup,
                    consumerNamePrefix,
                    compactionStreamKey,
                    compactionConsumerGroup,
                    compactionConsumerNamePrefix,
                    compactionShardCount,
                    autoCreateConsumerGroup,
                    shutdownAwaitMillis,
                    daemonThreads,
                    recoverPendingEntries,
                    claimIdleMillis,
                    claimBatchSize,
                    retryOverrides,
                    entityFlushPolicies,
                    deadLetterMaxLength,
                    deadLetterStreamKey,
                    compactionMaxLength
            );
        }
    }
}
