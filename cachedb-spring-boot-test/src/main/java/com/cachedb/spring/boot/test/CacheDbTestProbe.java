package com.reactor.cachedb.spring.boot.test;

import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.route.HotRoutePopulation;
import com.reactor.cachedb.core.route.RouteCoverage;
import com.reactor.cachedb.spring.boot.CacheDbRouteInventory;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.CacheWarmPlan;
import com.reactor.cachedb.starter.CacheWarmResult;
import com.reactor.cachedb.starter.CacheWarmExecution;
import com.reactor.cachedb.starter.CacheWarmExecutionMode;

import java.time.Duration;
import java.util.Objects;

public final class CacheDbTestProbe {
    private final CacheDatabase cacheDatabase;
    private final CacheDbRouteInventory routeInventory;

    CacheDbTestProbe(CacheDatabase cacheDatabase) {
        this(cacheDatabase, CacheDbRouteInventory.empty());
    }

    CacheDbTestProbe(CacheDatabase cacheDatabase, CacheDbRouteInventory routeInventory) {
        this.cacheDatabase = Objects.requireNonNull(cacheDatabase, "cacheDatabase");
        this.routeInventory = Objects.requireNonNull(routeInventory, "routeInventory");
    }

    public CacheWarmResult warm(CacheWarmPlan plan) {
        return cacheDatabase.warm(plan);
    }

    public CacheWarmExecution executeWarm(CacheWarmPlan plan, CacheWarmExecutionMode mode) {
        return cacheDatabase.executeWarm(plan, mode);
    }

    public CacheWarmResult warmAndRequireCoverage(CacheWarmPlan plan, Duration maxAge) {
        Objects.requireNonNull(plan, "plan");
        if (plan.coverageRouteName().isBlank()) {
            throw new IllegalArgumentException("Warm plan must declare route coverage for this assertion");
        }
        CacheWarmResult result = cacheDatabase.warm(plan);
        CacheDbAssertions.requireComplete(coverage(plan.coverageRouteName(), plan.coverageScope(), maxAge));
        return result;
    }

    /** Executes the production warm journey and returns shareable route evidence. */
    public CacheDbWarmRouteEvidence dryRunApplyAndRequireCoverage(CacheWarmPlan plan, Duration maxAge) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(maxAge, "maxAge");
        if (plan.coverageRouteName().isBlank()) {
            throw new IllegalArgumentException("Warm plan must declare route coverage for this journey");
        }
        CacheWarmExecution dryRun = cacheDatabase.executeWarm(plan, CacheWarmExecutionMode.DRY_RUN);
        if (dryRun.result().submittedRows() != 0) {
            throw new AssertionError("Dry-run mutated Redis for warm plan " + plan.name());
        }
        CacheWarmExecution applied = cacheDatabase.executeWarm(plan, CacheWarmExecutionMode.APPLY);
        RouteCoverage coverage = coverage(plan.coverageRouteName(), plan.coverageScope(), maxAge);
        CacheDbAssertions.requireComplete(coverage);
        return new CacheDbWarmRouteEvidence(dryRun, applied, coverage);
    }

    public RouteCoverage coverage(String routeName, String scope, Duration maxAge) {
        return cacheDatabase.routeCoverage(routeName, scope, maxAge);
    }

    public <T, ID> WriteReceipt<T, ID> awaitDurable(WriteReceipt<T, ID> receipt, Duration timeout) {
        try {
            return cacheDatabase.awaitDurableOrThrow(receipt, timeout);
        } catch (com.reactor.cachedb.core.repository.WriteDurabilityTimeoutException failure) {
            throw new AssertionError("Write did not become SQL-durable within " + timeout + ": " + receipt, failure);
        }
    }

    public CacheDatabase cacheDatabase() {
        return cacheDatabase;
    }

    public CacheDbRouteInventory routeInventory() {
        return routeInventory;
    }

    public CacheDbRouteInventory.RouteDescriptor requireDeclaredWarmRoute(String routeName) {
        return requireHotRoute(routeName, HotRoutePopulation.DECLARED_WARM);
    }

    public CacheDbRouteInventory.RouteDescriptor requireHotRoute(
            String routeName,
            HotRoutePopulation expectedPopulation
    ) {
        Objects.requireNonNull(expectedPopulation, "expectedPopulation");
        CacheDbRouteInventory.RouteDescriptor descriptor = routeInventory.hotRoute(routeName)
                .orElseThrow(() -> new AssertionError("Expected generated HOT route: " + routeName));
        HotRoutePopulation actual = descriptor.route().population();
        if (actual != expectedPopulation) {
            throw new AssertionError("Expected HOT route " + routeName + " population="
                    + expectedPopulation + " but was " + actual);
        }
        return descriptor;
    }
}
