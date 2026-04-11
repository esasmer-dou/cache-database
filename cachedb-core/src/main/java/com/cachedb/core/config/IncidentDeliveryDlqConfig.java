package com.reactor.cachedb.core.config;

public record IncidentDeliveryDlqConfig(
        boolean enabled,
        String streamKey,
        String recoveryStreamKey,
        String consumerGroup,
        String consumerNamePrefix,
        int workerThreads,
        int batchSize,
        long blockTimeoutMillis,
        int maxReplayAttempts,
        boolean autoCreateConsumerGroup,
        boolean claimAbandonedEntries,
        long claimIdleMillis,
        int claimBatchSize,
        long idleSleepMillis
) {
    public static Builder builder() {
        return new Builder();
    }

    public static IncidentDeliveryDlqConfig defaults() {
        return builder().build();
    }

    public static final class Builder {
        private boolean enabled = true;
        private String streamKey = "cachedb:stream:admin:incident-delivery-dlq";
        private String recoveryStreamKey = "cachedb:stream:admin:incident-delivery-recovery";
        private String consumerGroup = "cachedb-incident-delivery-dlq";
        private String consumerNamePrefix = "cachedb-incident-delivery";
        private int workerThreads = 1;
        private int batchSize = 20;
        private long blockTimeoutMillis = 500;
        private int maxReplayAttempts = 3;
        private boolean autoCreateConsumerGroup = true;
        private boolean claimAbandonedEntries = true;
        private long claimIdleMillis = 1_000;
        private int claimBatchSize = 20;
        private long idleSleepMillis = 100;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder streamKey(String streamKey) {
            this.streamKey = streamKey;
            return this;
        }

        public Builder recoveryStreamKey(String recoveryStreamKey) {
            this.recoveryStreamKey = recoveryStreamKey;
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

        public Builder workerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
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

        public Builder maxReplayAttempts(int maxReplayAttempts) {
            this.maxReplayAttempts = maxReplayAttempts;
            return this;
        }

        public Builder autoCreateConsumerGroup(boolean autoCreateConsumerGroup) {
            this.autoCreateConsumerGroup = autoCreateConsumerGroup;
            return this;
        }

        public Builder claimAbandonedEntries(boolean claimAbandonedEntries) {
            this.claimAbandonedEntries = claimAbandonedEntries;
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

        public Builder idleSleepMillis(long idleSleepMillis) {
            this.idleSleepMillis = idleSleepMillis;
            return this;
        }

        public IncidentDeliveryDlqConfig build() {
            return new IncidentDeliveryDlqConfig(
                    enabled,
                    streamKey,
                    recoveryStreamKey,
                    consumerGroup,
                    consumerNamePrefix,
                    workerThreads,
                    batchSize,
                    blockTimeoutMillis,
                    maxReplayAttempts,
                    autoCreateConsumerGroup,
                    claimAbandonedEntries,
                    claimIdleMillis,
                    claimBatchSize,
                    idleSleepMillis
            );
        }
    }
}
