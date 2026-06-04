package com.reactor.cachedb.core.cache;

public record CachePolicy(
        int hotEntityLimit,
        int pageSize,
        boolean lruEvictionEnabled,
        long entityTtlSeconds,
        long pageTtlSeconds,
        EntityHotPolicy hotPolicy
) {
    public CachePolicy(
            int hotEntityLimit,
            int pageSize,
            boolean lruEvictionEnabled,
            long entityTtlSeconds,
            long pageTtlSeconds
    ) {
        this(hotEntityLimit, pageSize, lruEvictionEnabled, entityTtlSeconds, pageTtlSeconds, EntityHotPolicy.countWindow());
    }

    public CachePolicy {
        hotPolicy = hotPolicy == null ? EntityHotPolicy.countWindow() : hotPolicy;
    }

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
        private EntityHotPolicy hotPolicy = EntityHotPolicy.countWindow();

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

        public Builder hotPolicy(EntityHotPolicy hotPolicy) {
            this.hotPolicy = hotPolicy;
            return this;
        }

        public Builder countWindow() {
            this.hotPolicy = EntityHotPolicy.countWindow();
            return this;
        }

        public Builder timeWindow(String timeColumn, long hotForSeconds) {
            this.hotPolicy = EntityHotPolicy.timeWindow(timeColumn, hotForSeconds);
            return this;
        }

        public Builder stateWindow(String stateColumn, java.util.Collection<String> stateValues) {
            this.hotPolicy = EntityHotPolicy.stateWindow(stateColumn, stateValues);
            return this;
        }

        public Builder customHotPredicate(CacheAdmissionPredicate predicate) {
            this.hotPolicy = EntityHotPolicy.customPredicate(predicate);
            return this;
        }

        public Builder compositeHotPolicy(java.util.Collection<EntityHotPolicy> children) {
            this.hotPolicy = EntityHotPolicy.allOf(children);
            return this;
        }

        public Builder compositeHotPolicy(EntityHotPolicyCompositeOperator operator, java.util.Collection<EntityHotPolicy> children) {
            this.hotPolicy = EntityHotPolicy.builder()
                    .mode(EntityHotPolicyMode.COMPOSITE)
                    .compositeOperator(operator)
                    .children(children)
                    .build();
            return this;
        }

        public CachePolicy build() {
            return new CachePolicy(
                    hotEntityLimit,
                    pageSize,
                    lruEvictionEnabled,
                    entityTtlSeconds,
                    pageTtlSeconds,
                    hotPolicy
            );
        }
    }
}
