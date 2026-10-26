package com.reactor.cachedb.core.config;

import com.reactor.cachedb.core.cache.CachePolicy;

public record ResourceLimits(
        CachePolicy defaultCachePolicy,
        int maxRegisteredEntities,
        int maxColumnsPerOperation,
        int maxWatchersPerEntity
) {
    public ResourceLimits(CachePolicy defaultCachePolicy, int maxRegisteredEntities, int maxColumnsPerOperation) {
        this(defaultCachePolicy, maxRegisteredEntities, maxColumnsPerOperation, 64);
    }
    public ResourceLimits {
        if (maxWatchersPerEntity < 1 || maxWatchersPerEntity > 64) throw new IllegalArgumentException("maxWatchersPerEntity must be 1..64");
    }
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
        private int maxWatchersPerEntity = 64;
        public Builder maxWatchersPerEntity(int limit) { maxWatchersPerEntity = limit; return this; }

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
            return new ResourceLimits(defaultCachePolicy, maxRegisteredEntities, maxColumnsPerOperation, maxWatchersPerEntity);
        }
    }
}
