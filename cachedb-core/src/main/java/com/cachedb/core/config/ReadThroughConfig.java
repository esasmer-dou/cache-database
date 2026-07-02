package com.reactor.cachedb.core.config;

public record ReadThroughConfig(
        ReadThroughMode mode,
        boolean failOnMissingLoader,
        boolean hydrateLoadedEntities,
        int maxQueryLoadRows
) {
    public ReadThroughConfig {
        mode = mode == null ? ReadThroughMode.REDIS_ONLY : mode;
        maxQueryLoadRows = Math.max(1, maxQueryLoadRows);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ReadThroughConfig defaults() {
        return builder().build();
    }

    public static final class Builder {
        private ReadThroughMode mode = ReadThroughMode.REDIS_ONLY;
        private boolean failOnMissingLoader;
        private boolean hydrateLoadedEntities = true;
        private int maxQueryLoadRows = 500;

        public Builder mode(ReadThroughMode mode) {
            this.mode = mode;
            return this;
        }

        public Builder failOnMissingLoader(boolean failOnMissingLoader) {
            this.failOnMissingLoader = failOnMissingLoader;
            return this;
        }

        public Builder hydrateLoadedEntities(boolean hydrateLoadedEntities) {
            this.hydrateLoadedEntities = hydrateLoadedEntities;
            return this;
        }

        public Builder maxQueryLoadRows(int maxQueryLoadRows) {
            this.maxQueryLoadRows = maxQueryLoadRows;
            return this;
        }

        public ReadThroughConfig build() {
            return new ReadThroughConfig(
                    mode,
                    failOnMissingLoader,
                    hydrateLoadedEntities,
                    maxQueryLoadRows
            );
        }
    }
}
