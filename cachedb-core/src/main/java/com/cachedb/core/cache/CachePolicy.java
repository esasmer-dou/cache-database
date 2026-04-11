package com.reactor.cachedb.core.cache;

public record CachePolicy(
        int hotEntityLimit,
        int pageSize,
        boolean lruEvictionEnabled,
        long entityTtlSeconds,
        long pageTtlSeconds
) {
    public static Builder builder() {
        return new Builder();
    }

    public static CachePolicy defaults() {
        return builder().build();
    }

    public static final class Builder {
        private int hotEntityLimit = 1_000;
        private int pageSize = 100;
        private boolean lruEvictionEnabled = true;
        private long entityTtlSeconds = 0;
        private long pageTtlSeconds = 60;

        public Builder hotEntityLimit(int hotEntityLimit) {
            this.hotEntityLimit = hotEntityLimit;
            return this;
        }

        public Builder pageSize(int pageSize) {
            this.pageSize = pageSize;
            return this;
        }

        public Builder lruEvictionEnabled(boolean lruEvictionEnabled) {
            this.lruEvictionEnabled = lruEvictionEnabled;
            return this;
        }

        public Builder entityTtlSeconds(long entityTtlSeconds) {
            this.entityTtlSeconds = entityTtlSeconds;
            return this;
        }

        public Builder pageTtlSeconds(long pageTtlSeconds) {
            this.pageTtlSeconds = pageTtlSeconds;
            return this;
        }

        public CachePolicy build() {
            return new CachePolicy(
                    hotEntityLimit,
                    pageSize,
                    lruEvictionEnabled,
                    entityTtlSeconds,
                    pageTtlSeconds
            );
        }
    }
}
