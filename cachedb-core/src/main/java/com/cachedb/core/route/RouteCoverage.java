package com.reactor.cachedb.core.route;

import java.time.Duration;
import java.time.Instant;

/** Cluster-visible evidence describing which bounded route window is available in Redis. */
public record RouteCoverage(
        String routeName,
        String scope,
        RouteCoverageStatus status,
        long sourceRows,
        long submittedRows,
        Instant warmedAt,
        Instant updatedAt,
        String detail
) {
    public RouteCoverage {
        routeName = requireText(routeName, "routeName");
        scope = normalizeScope(scope);
        status = status == null ? RouteCoverageStatus.NOT_WARMED : status;
        sourceRows = Math.max(0L, sourceRows);
        submittedRows = Math.max(0L, submittedRows);
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
        detail = detail == null ? "" : detail;
    }

    public static RouteCoverage notWarmed(String routeName, String scope) {
        return new RouteCoverage(routeName, scope, RouteCoverageStatus.NOT_WARMED, 0, 0, null, Instant.now(), "");
    }

    public RouteCoverage withStaleness(Duration maxAge, Instant now) {
        if (status != RouteCoverageStatus.COMPLETE || warmedAt == null || maxAge == null || maxAge.isNegative()) {
            return this;
        }
        Instant effectiveNow = now == null ? Instant.now() : now;
        if (!warmedAt.plus(maxAge).isBefore(effectiveNow)) {
            return this;
        }
        return new RouteCoverage(routeName, scope, RouteCoverageStatus.STALE, sourceRows, submittedRows,
                warmedAt, updatedAt, "Coverage exceeded max staleness " + maxAge);
    }

    public boolean complete() {
        return status == RouteCoverageStatus.COMPLETE;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeScope(String value) {
        return value == null || value.isBlank() ? "global" : value.trim();
    }
}
