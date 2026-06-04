package com.reactor.cachedb.core.route;

public record RouteCacheContract(
        String routeName,
        String entityName,
        String projectionName,
        int pageSize,
        int hotWindow,
        boolean projectionRequired,
        int maxColdReadSize,
        long memoryBudgetBytes,
        RouteCacheStrictMode strictMode,
        TenantCacheQuota tenantQuota
) {
    public RouteCacheContract {
        routeName = defaultString(routeName, "unnamed-route");
        entityName = defaultString(entityName, "");
        projectionName = defaultString(projectionName, "");
        pageSize = Math.max(1, pageSize);
        hotWindow = Math.max(pageSize, hotWindow);
        maxColdReadSize = Math.max(0, maxColdReadSize);
        memoryBudgetBytes = Math.max(0L, memoryBudgetBytes);
        strictMode = strictMode == null ? RouteCacheStrictMode.WARN : strictMode;
        tenantQuota = tenantQuota == null ? TenantCacheQuota.unbounded() : tenantQuota;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean strictProjectionRequired() {
        return projectionRequired && strictMode == RouteCacheStrictMode.FAIL_FAST;
    }

    public boolean allowsColdReadSize(int requestedRows) {
        return maxColdReadSize <= 0 || requestedRows <= maxColdReadSize;
    }

    public boolean matchesResolvedRoute(String resolvedRouteLabel) {
        if (!projectionRequired) {
            return true;
        }
        String normalized = resolvedRouteLabel == null ? "" : resolvedRouteLabel.trim();
        if (!normalized.startsWith("projection:")) {
            return false;
        }
        return projectionName.isBlank() || normalized.equals("projection:" + projectionName);
    }

    public void validateResolvedRoute(String resolvedRouteLabel) {
        if (strictMode != RouteCacheStrictMode.FAIL_FAST || matchesResolvedRoute(resolvedRouteLabel)) {
            return;
        }
        throw new IllegalStateException(
                "Route " + routeName + " requires projection"
                        + (projectionName.isBlank() ? "" : " " + projectionName)
                        + " but resolved route was " + safeRoute(resolvedRouteLabel)
                        + ". Production strict mode forbids entity-scan fallback."
        );
    }

    private static String safeRoute(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public static final class Builder {
        private String routeName = "unnamed-route";
        private String entityName = "";
        private String projectionName = "";
        private int pageSize = 100;
        private int hotWindow = 1_000;
        private boolean projectionRequired;
        private int maxColdReadSize;
        private long memoryBudgetBytes;
        private RouteCacheStrictMode strictMode = RouteCacheStrictMode.WARN;
        private TenantCacheQuota tenantQuota = TenantCacheQuota.unbounded();

        public Builder routeName(String routeName) {
            this.routeName = routeName;
            return this;
        }

        public Builder entityName(String entityName) {
            this.entityName = entityName;
            return this;
        }

        public Builder projectionName(String projectionName) {
            this.projectionName = projectionName;
            return this;
        }

        public Builder pageSize(int pageSize) {
            this.pageSize = pageSize;
            return this;
        }

        public Builder hotWindow(int hotWindow) {
            this.hotWindow = hotWindow;
            return this;
        }

        public Builder projectionRequired(boolean projectionRequired) {
            this.projectionRequired = projectionRequired;
            return this;
        }

        public Builder maxColdReadSize(int maxColdReadSize) {
            this.maxColdReadSize = maxColdReadSize;
            return this;
        }

        public Builder memoryBudgetBytes(long memoryBudgetBytes) {
            this.memoryBudgetBytes = memoryBudgetBytes;
            return this;
        }

        public Builder strictMode(RouteCacheStrictMode strictMode) {
            this.strictMode = strictMode;
            return this;
        }

        public Builder tenantQuota(TenantCacheQuota tenantQuota) {
            this.tenantQuota = tenantQuota;
            return this;
        }

        public RouteCacheContract build() {
            return new RouteCacheContract(
                    routeName,
                    entityName,
                    projectionName,
                    pageSize,
                    hotWindow,
                    projectionRequired,
                    maxColdReadSize,
                    memoryBudgetBytes,
                    strictMode,
                    tenantQuota
            );
        }
    }
}
