package com.reactor.cachedb.core.config;

public record RelationConfig(
        int batchSize,
        int maxFetchDepth,
        boolean failOnMissingPreloader
) {
    public static Builder builder() {
        return new Builder();
    }

    public static RelationConfig defaults() {
        return builder().build();
    }

    public static final class Builder {
        private int batchSize = 250;
        private int maxFetchDepth = 3;
        private boolean failOnMissingPreloader = false;

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder maxFetchDepth(int maxFetchDepth) {
            this.maxFetchDepth = maxFetchDepth;
            return this;
        }

        public Builder failOnMissingPreloader(boolean failOnMissingPreloader) {
            this.failOnMissingPreloader = failOnMissingPreloader;
            return this;
        }

        public RelationConfig build() {
            return new RelationConfig(batchSize, maxFetchDepth, failOnMissingPreloader);
        }
    }
}
