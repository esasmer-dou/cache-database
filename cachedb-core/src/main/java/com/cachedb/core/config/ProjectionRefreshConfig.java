package com.reactor.cachedb.core.config;

public record ProjectionRefreshConfig(
        boolean enabled,
        String streamKey,
        String consumerGroup,
        String consumerNamePrefix,
        int batchSize,
        long blockTimeoutMillis,
        long idleSleepMillis,
        boolean autoCreateConsumerGroup,
        boolean recoverPendingEntries,
        long claimIdleMillis,
        int claimBatchSize,
        long maxStreamLength,
        boolean deadLetterEnabled,
        String deadLetterStreamKey,
        long deadLetterMaxLength,
        int maxAttempts,
        long deadLetterWarnThreshold,
        long deadLetterCriticalThreshold,
        long shutdownAwaitMillis,
        boolean daemonThreads
) {
    public static ProjectionRefreshConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean enabled = true;
        private String streamKey = "cachedb:stream:projection-refresh";
        private String consumerGroup = "cachedb-projection-refresh";
        private String consumerNamePrefix = "projection-refresh";
        private int batchSize = 100;
        private long blockTimeoutMillis = 1000L;
        private long idleSleepMillis = 250L;
        private boolean autoCreateConsumerGroup = true;
        private boolean recoverPendingEntries = true;
        private long claimIdleMillis = 30000L;
        private int claimBatchSize = 100;
        private long maxStreamLength = 100000L;
        private boolean deadLetterEnabled = true;
        private String deadLetterStreamKey = "cachedb:stream:projection-refresh-dlq";
        private long deadLetterMaxLength = 25000L;
        private int maxAttempts = 3;
        private long deadLetterWarnThreshold = 1L;
        private long deadLetterCriticalThreshold = 25L;
        private long shutdownAwaitMillis = 5000L;
        private boolean daemonThreads = true;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
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

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
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

        public Builder autoCreateConsumerGroup(boolean autoCreateConsumerGroup) {
            this.autoCreateConsumerGroup = autoCreateConsumerGroup;
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

        public Builder maxStreamLength(long maxStreamLength) {
            this.maxStreamLength = maxStreamLength;
            return this;
        }

        public Builder deadLetterEnabled(boolean deadLetterEnabled) {
            this.deadLetterEnabled = deadLetterEnabled;
            return this;
        }

        public Builder deadLetterStreamKey(String deadLetterStreamKey) {
            this.deadLetterStreamKey = deadLetterStreamKey;
            return this;
        }

        public Builder deadLetterMaxLength(long deadLetterMaxLength) {
            this.deadLetterMaxLength = deadLetterMaxLength;
            return this;
        }

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
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

        public Builder shutdownAwaitMillis(long shutdownAwaitMillis) {
            this.shutdownAwaitMillis = shutdownAwaitMillis;
            return this;
        }

        public Builder daemonThreads(boolean daemonThreads) {
            this.daemonThreads = daemonThreads;
            return this;
        }

        public ProjectionRefreshConfig build() {
            return new ProjectionRefreshConfig(
                    enabled,
                    streamKey,
                    consumerGroup,
                    consumerNamePrefix,
                    batchSize,
                    blockTimeoutMillis,
                    idleSleepMillis,
                    autoCreateConsumerGroup,
                    recoverPendingEntries,
                    claimIdleMillis,
                    claimBatchSize,
                    maxStreamLength,
                    deadLetterEnabled,
                    deadLetterStreamKey,
                    deadLetterMaxLength,
                    maxAttempts,
                    deadLetterWarnThreshold,
                    deadLetterCriticalThreshold,
                    shutdownAwaitMillis,
                    daemonThreads
            );
        }
    }
}
