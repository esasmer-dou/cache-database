package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.query.QuerySpec;

import java.util.Objects;

public record CacheWarmPlan(
        String name,
        String entityName,
        QuerySpec querySpec,
        int maxRows,
        boolean forceImmediateProjectionRefresh,
        boolean reindexQueryIndexes
) {
    public CacheWarmPlan {
        entityName = requireText(entityName, "entityName");
        maxRows = Math.max(1, maxRows);
        name = normalizeName(name, entityName);
        querySpec = querySpec == null ? QuerySpec.builder().limit(Math.min(100, maxRows)).build() : querySpec;
    }

    public static Builder builder(String entityName) {
        return new Builder(entityName);
    }

    private static String normalizeName(String name, String entityName) {
        if (name == null || name.isBlank()) {
            return "warm-" + entityName;
        }
        return name.trim();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    public static final class Builder {
        private final String entityName;
        private String name;
        private QuerySpec querySpec;
        private int maxRows = 1_000;
        private boolean forceImmediateProjectionRefresh = true;
        private boolean reindexQueryIndexes = true;

        private Builder(String entityName) {
            this.entityName = Objects.requireNonNull(entityName, "entityName");
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder querySpec(QuerySpec querySpec) {
            this.querySpec = querySpec;
            return this;
        }

        public Builder maxRows(int maxRows) {
            this.maxRows = maxRows;
            return this;
        }

        public Builder forceImmediateProjectionRefresh(boolean forceImmediateProjectionRefresh) {
            this.forceImmediateProjectionRefresh = forceImmediateProjectionRefresh;
            return this;
        }

        public Builder reindexQueryIndexes(boolean reindexQueryIndexes) {
            this.reindexQueryIndexes = reindexQueryIndexes;
            return this;
        }

        public CacheWarmPlan build() {
            return new CacheWarmPlan(
                    name,
                    entityName,
                    querySpec,
                    maxRows,
                    forceImmediateProjectionRefresh,
                    reindexQueryIndexes
            );
        }
    }
}
