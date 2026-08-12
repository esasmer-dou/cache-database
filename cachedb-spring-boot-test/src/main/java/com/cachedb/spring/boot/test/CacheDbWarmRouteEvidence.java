package com.reactor.cachedb.spring.boot.test;

import com.reactor.cachedb.core.route.RouteCoverage;
import com.reactor.cachedb.starter.CacheWarmExecution;

import java.util.Objects;

/** Dry-run, apply, and Redis coverage evidence for one declarative warm route. */
public record CacheDbWarmRouteEvidence(
        CacheWarmExecution dryRun,
        CacheWarmExecution applied,
        RouteCoverage coverage
) {
    public CacheDbWarmRouteEvidence {
        Objects.requireNonNull(dryRun, "dryRun");
        Objects.requireNonNull(applied, "applied");
        Objects.requireNonNull(coverage, "coverage");
        if (!dryRun.dryRun() || applied.dryRun()) {
            throw new IllegalArgumentException("evidence requires DRY_RUN followed by APPLY");
        }
        if (!dryRun.plan().equals(applied.plan())) {
            throw new IllegalArgumentException("dry-run and apply must use the same warm plan");
        }
        if (dryRun.result().submittedRows() != 0) {
            throw new IllegalArgumentException("dry-run must not submit rows to Redis");
        }
        if (!coverage.complete()) {
            throw new IllegalArgumentException("coverage must be complete");
        }
        if (!coverage.routeName().equals(applied.routeName()) || !coverage.scope().equals(applied.scope())) {
            throw new IllegalArgumentException("coverage does not belong to the applied route and scope");
        }
    }
}
