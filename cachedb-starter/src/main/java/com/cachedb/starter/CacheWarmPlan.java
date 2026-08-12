package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.query.QuerySpec;

import java.util.Objects;

public record CacheWarmPlan(
        String name,
        String entityName,
        QuerySpec querySpec,
        int maxRows,
        boolean forceImmediateProjectionRefresh,
        boolean reindexQueryIndexes,
        String coverageRouteName,
        String coverageScope,
        long coverageTtlSeconds,
        boolean projectionsOnly,
        String projectionName
) {
    public CacheWarmPlan(
            String name,
            String entityName,
            QuerySpec querySpec,
            int maxRows,
            boolean forceImmediateProjectionRefresh,
            boolean reindexQueryIndexes
    ) {
        this(name, entityName, querySpec, maxRows, forceImmediateProjectionRefresh, reindexQueryIndexes,
                "", "global", 86_400L, false, "");
    }

    public CacheWarmPlan {
        entityName = requireText(entityName, "entityName");
        if (maxRows <= 0 || maxRows > com.reactor.cachedb.core.query.QuerySpec.MAX_LIMIT) {
            throw new IllegalArgumentException("maxRows must be between 1 and "
                    + com.reactor.cachedb.core.query.QuerySpec.MAX_LIMIT);
        }
        name = normalizeName(name, entityName);
        querySpec = querySpec == null ? QuerySpec.builder().limit(Math.min(100, maxRows)).build() : querySpec;
        coverageRouteName = coverageRouteName == null ? "" : coverageRouteName.trim();
        coverageScope = coverageScope == null || coverageScope.isBlank() ? "global" : coverageScope.trim();
        coverageTtlSeconds = Math.max(60L, coverageTtlSeconds);
        projectionName = projectionName == null ? "" : projectionName.trim();
        if (projectionsOnly && projectionName.isBlank()) {
            throw new IllegalArgumentException("projectionName is required for projection-only warm plans");
        }
    }

    public static Builder builder(String entityName) {
        return new Builder(entityName);
    }

    public CacheWarmTarget target() {
        return projectionsOnly
                ? CacheWarmTarget.PROJECTIONS_ONLY
                : CacheWarmTarget.ENTITY_AND_PROJECTIONS;
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
        private String coverageRouteName = "";
        private String coverageScope = "global";
        private long coverageTtlSeconds = 86_400L;
        private boolean projectionsOnly;
        private String projectionName = "";

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

        public Builder coverage(String routeName, String scope, long ttlSeconds) {
            this.coverageRouteName = routeName;
            this.coverageScope = scope;
            this.coverageTtlSeconds = ttlSeconds;
            return this;
        }

        public Builder projectionsOnly(boolean projectionsOnly) {
            this.projectionsOnly = projectionsOnly;
            return this;
        }

        public Builder target(CacheWarmTarget target) {
            CacheWarmTarget resolved = Objects.requireNonNull(target, "target");
            this.projectionsOnly = resolved == CacheWarmTarget.PROJECTIONS_ONLY;
            return this;
        }

        public Builder projectionName(String projectionName) {
            this.projectionName = projectionName;
            return this;
        }

        public CacheWarmPlan build() {
            return new CacheWarmPlan(
                    name,
                    entityName,
                    querySpec,
                    maxRows,
                    forceImmediateProjectionRefresh,
                    reindexQueryIndexes,
                    coverageRouteName,
                    coverageScope,
                    coverageTtlSeconds,
                    projectionsOnly,
                    projectionName
            );
        }
    }
}
