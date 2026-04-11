package com.reactor.cachedb.core.config;

public record PageCacheConfig(
        boolean readThroughEnabled,
        boolean failOnMissingPageLoader,
        int evictionBatchSize
) {
    public static Builder builder() {
        return new Builder();
    }

    public static PageCacheConfig defaults() {
        return builder().build();
    }

    public static final class Builder {
        private boolean readThroughEnabled = true;
        private boolean failOnMissingPageLoader = false;
        private int evictionBatchSize = 100;

        public Builder readThroughEnabled(boolean readThroughEnabled) {
            this.readThroughEnabled = readThroughEnabled;
            return this;
        }

        public Builder failOnMissingPageLoader(boolean failOnMissingPageLoader) {
            this.failOnMissingPageLoader = failOnMissingPageLoader;
            return this;
        }

        public Builder evictionBatchSize(int evictionBatchSize) {
            this.evictionBatchSize = evictionBatchSize;
            return this;
        }

        public PageCacheConfig build() {
            return new PageCacheConfig(readThroughEnabled, failOnMissingPageLoader, evictionBatchSize);
        }
    }
}
