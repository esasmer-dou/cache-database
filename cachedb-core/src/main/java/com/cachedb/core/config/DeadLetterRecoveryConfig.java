package com.reactor.cachedb.core.config;

import java.util.List;

public record DeadLetterRecoveryConfig(
        boolean enabled,
        int workerThreads,
        long blockTimeoutMillis,
        long idleSleepMillis,
        String consumerGroup,
        String consumerNamePrefix,
        boolean autoCreateConsumerGroup,
        long shutdownAwaitMillis,
        boolean daemonThreads,
        long claimIdleMillis,
        int claimBatchSize,
        int maxReplayRetries,
        long replayBackoffMillis,
        List<WriteRetryPolicyOverride> retryOverrides,
        String reconciliationStreamKey,
        boolean archiveResolvedEntries,
        String archiveStreamKey,
        boolean cleanupEnabled,
        long cleanupIntervalMillis,
        int cleanupBatchSize,
        long deadLetterRetentionMillis,
        long reconciliationRetentionMillis,
        long archiveRetentionMillis,
        long deadLetterMaxLength,
        long reconciliationMaxLength,
        long archiveMaxLength
) {
    public static Builder builder() {
        return new Builder();
    }

    public static DeadLetterRecoveryConfig defaults() {
        return builder().build();
    }

    public static final class Builder {
        private boolean enabled = true;
        private int workerThreads = 1;
        private long blockTimeoutMillis = 2_000;
        private long idleSleepMillis = 250;
        private String consumerGroup = "cachedb-write-behind-dlq";
        private String consumerNamePrefix = "cachedb-dlq-worker";
        private boolean autoCreateConsumerGroup = true;
        private long shutdownAwaitMillis = 10_000;
        private boolean daemonThreads = true;
        private long claimIdleMillis = 5_000;
        private int claimBatchSize = 100;
        private int maxReplayRetries = 3;
        private long replayBackoffMillis = 1_000;
        private List<WriteRetryPolicyOverride> retryOverrides = List.of();
        private String reconciliationStreamKey = "cachedb:stream:write-behind:reconciliation";
        private boolean archiveResolvedEntries = true;
        private String archiveStreamKey = "cachedb:stream:write-behind:archive";
        private boolean cleanupEnabled = true;
        private long cleanupIntervalMillis = 60_000;
        private int cleanupBatchSize = 250;
        private long deadLetterRetentionMillis = 0;
        private long reconciliationRetentionMillis = 7L * 24 * 60 * 60 * 1000;
        private long archiveRetentionMillis = 30L * 24 * 60 * 60 * 1000;
        private long deadLetterMaxLength = 10_000;
        private long reconciliationMaxLength = 10_000;
        private long archiveMaxLength = 10_000;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder workerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
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

        public Builder consumerGroup(String consumerGroup) {
            this.consumerGroup = consumerGroup;
            return this;
        }

        public Builder consumerNamePrefix(String consumerNamePrefix) {
            this.consumerNamePrefix = consumerNamePrefix;
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

        public Builder claimIdleMillis(long claimIdleMillis) {
            this.claimIdleMillis = claimIdleMillis;
            return this;
        }

        public Builder claimBatchSize(int claimBatchSize) {
            this.claimBatchSize = claimBatchSize;
            return this;
        }

        public Builder maxReplayRetries(int maxReplayRetries) {
            this.maxReplayRetries = maxReplayRetries;
            return this;
        }

        public Builder replayBackoffMillis(long replayBackoffMillis) {
            this.replayBackoffMillis = replayBackoffMillis;
            return this;
        }

        public Builder retryOverrides(List<WriteRetryPolicyOverride> retryOverrides) {
            this.retryOverrides = List.copyOf(retryOverrides);
            return this;
        }

        public Builder reconciliationStreamKey(String reconciliationStreamKey) {
            this.reconciliationStreamKey = reconciliationStreamKey;
            return this;
        }

        public Builder archiveResolvedEntries(boolean archiveResolvedEntries) {
            this.archiveResolvedEntries = archiveResolvedEntries;
            return this;
        }

        public Builder archiveStreamKey(String archiveStreamKey) {
            this.archiveStreamKey = archiveStreamKey;
            return this;
        }

        public Builder cleanupEnabled(boolean cleanupEnabled) {
            this.cleanupEnabled = cleanupEnabled;
            return this;
        }

        public Builder cleanupIntervalMillis(long cleanupIntervalMillis) {
            this.cleanupIntervalMillis = cleanupIntervalMillis;
            return this;
        }

        public Builder cleanupBatchSize(int cleanupBatchSize) {
            this.cleanupBatchSize = cleanupBatchSize;
            return this;
        }

        public Builder deadLetterRetentionMillis(long deadLetterRetentionMillis) {
            this.deadLetterRetentionMillis = deadLetterRetentionMillis;
            return this;
        }

        public Builder reconciliationRetentionMillis(long reconciliationRetentionMillis) {
            this.reconciliationRetentionMillis = reconciliationRetentionMillis;
            return this;
        }

        public Builder archiveRetentionMillis(long archiveRetentionMillis) {
            this.archiveRetentionMillis = archiveRetentionMillis;
            return this;
        }

        public Builder deadLetterMaxLength(long deadLetterMaxLength) {
            this.deadLetterMaxLength = deadLetterMaxLength;
            return this;
        }

        public Builder reconciliationMaxLength(long reconciliationMaxLength) {
            this.reconciliationMaxLength = reconciliationMaxLength;
            return this;
        }

        public Builder archiveMaxLength(long archiveMaxLength) {
            this.archiveMaxLength = archiveMaxLength;
            return this;
        }

        public DeadLetterRecoveryConfig build() {
            return new DeadLetterRecoveryConfig(
                    enabled,
                    workerThreads,
                    blockTimeoutMillis,
                    idleSleepMillis,
                    consumerGroup,
                    consumerNamePrefix,
                    autoCreateConsumerGroup,
                    shutdownAwaitMillis,
                    daemonThreads,
                    claimIdleMillis,
                    claimBatchSize,
                    maxReplayRetries,
                    replayBackoffMillis,
                    retryOverrides,
                    reconciliationStreamKey,
                    archiveResolvedEntries,
                    archiveStreamKey,
                    cleanupEnabled,
                    cleanupIntervalMillis,
                    cleanupBatchSize,
                    deadLetterRetentionMillis,
                    reconciliationRetentionMillis,
                    archiveRetentionMillis,
                    deadLetterMaxLength,
                    reconciliationMaxLength,
                    archiveMaxLength
            );
        }
    }
}
