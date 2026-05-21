package com.reactor.cachedb.core.config;

public record ReadShapeGuardrailConfig(
        boolean enabled,
        boolean rejectPageRequestOverLimit,
        boolean rejectLoadedPageOverLimit,
        boolean skipLoadedPageCacheOverLimit,
        boolean rejectEntityQueryOverLimit,
        boolean rejectProjectionQueryOverLimit,
        int hotSetHeadroom,
        int maxPageRequestSize,
        int maxLoadedPageSize,
        int maxEntityQueryLimit,
        int maxProjectionQueryLimit
) {
    public static Builder builder() {
        return new Builder();
    }

    public static ReadShapeGuardrailConfig defaults() {
        return builder().build();
    }

    public static final class Builder {
        private boolean enabled = true;
        private boolean rejectPageRequestOverLimit = true;
        private boolean rejectLoadedPageOverLimit = true;
        private boolean skipLoadedPageCacheOverLimit = true;
        private boolean rejectEntityQueryOverLimit = true;
        private boolean rejectProjectionQueryOverLimit = true;
        private int hotSetHeadroom = 1;
        private int maxPageRequestSize = 0;
        private int maxLoadedPageSize = 0;
        private int maxEntityQueryLimit = 0;
        private int maxProjectionQueryLimit = 1_000;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder rejectPageRequestOverLimit(boolean rejectPageRequestOverLimit) {
            this.rejectPageRequestOverLimit = rejectPageRequestOverLimit;
            return this;
        }

        public Builder rejectLoadedPageOverLimit(boolean rejectLoadedPageOverLimit) {
            this.rejectLoadedPageOverLimit = rejectLoadedPageOverLimit;
            return this;
        }

        public Builder skipLoadedPageCacheOverLimit(boolean skipLoadedPageCacheOverLimit) {
            this.skipLoadedPageCacheOverLimit = skipLoadedPageCacheOverLimit;
            return this;
        }

        public Builder rejectEntityQueryOverLimit(boolean rejectEntityQueryOverLimit) {
            this.rejectEntityQueryOverLimit = rejectEntityQueryOverLimit;
            return this;
        }

        public Builder rejectProjectionQueryOverLimit(boolean rejectProjectionQueryOverLimit) {
            this.rejectProjectionQueryOverLimit = rejectProjectionQueryOverLimit;
            return this;
        }

        public Builder hotSetHeadroom(int hotSetHeadroom) {
            this.hotSetHeadroom = hotSetHeadroom;
            return this;
        }

        public Builder maxPageRequestSize(int maxPageRequestSize) {
            this.maxPageRequestSize = maxPageRequestSize;
            return this;
        }

        public Builder maxLoadedPageSize(int maxLoadedPageSize) {
            this.maxLoadedPageSize = maxLoadedPageSize;
            return this;
        }

        public Builder maxEntityQueryLimit(int maxEntityQueryLimit) {
            this.maxEntityQueryLimit = maxEntityQueryLimit;
            return this;
        }

        public Builder maxProjectionQueryLimit(int maxProjectionQueryLimit) {
            this.maxProjectionQueryLimit = maxProjectionQueryLimit;
            return this;
        }

        public ReadShapeGuardrailConfig build() {
            return new ReadShapeGuardrailConfig(
                    enabled,
                    rejectPageRequestOverLimit,
                    rejectLoadedPageOverLimit,
                    skipLoadedPageCacheOverLimit,
                    rejectEntityQueryOverLimit,
                    rejectProjectionQueryOverLimit,
                    hotSetHeadroom,
                    maxPageRequestSize,
                    maxLoadedPageSize,
                    maxEntityQueryLimit,
                    maxProjectionQueryLimit
            );
        }
    }
}
