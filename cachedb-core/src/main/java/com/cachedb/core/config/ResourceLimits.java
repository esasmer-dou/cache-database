package com.reactor.cachedb.core.config;

import com.reactor.cachedb.core.cache.CachePolicy;

public record ResourceLimits(
        CachePolicy defaultCachePolicy,
        int maxRegisteredEntities,
        int maxColumnsPerOperation
) {
    public static Builder builder() {
        return new Builder();
    }

    public static ResourceLimits defaults() {
        return builder().build();
    }

    public static final class Builder {
        private CachePolicy defaultCachePolicy = CachePolicy.defaults();
        private int maxRegisteredEntities = 1_000;
        private int maxColumnsPerOperation = 256;

        public Builder defaultCachePolicy(CachePolicy defaultCachePolicy) {
            this.defaultCachePolicy = defaultCachePolicy;
            return this;
        }

        public Builder maxRegisteredEntities(int maxRegisteredEntities) {
            this.maxRegisteredEntities = maxRegisteredEntities;
            return this;
        }

        public Builder maxColumnsPerOperation(int maxColumnsPerOperation) {
            this.maxColumnsPerOperation = maxColumnsPerOperation;
            return this;
        }

        public ResourceLimits build() {
            return new ResourceLimits(defaultCachePolicy, maxRegisteredEntities, maxColumnsPerOperation);
        }
    }
}
