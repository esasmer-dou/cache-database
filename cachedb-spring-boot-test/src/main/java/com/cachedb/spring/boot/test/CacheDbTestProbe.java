package com.reactor.cachedb.spring.boot.test;

import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.route.RouteCoverage;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.CacheWarmPlan;
import com.reactor.cachedb.starter.CacheWarmResult;

import java.time.Duration;
import java.util.Objects;

public final class CacheDbTestProbe {
    private final CacheDatabase cacheDatabase;

    CacheDbTestProbe(CacheDatabase cacheDatabase) {
        this.cacheDatabase = Objects.requireNonNull(cacheDatabase, "cacheDatabase");
    }

    public CacheWarmResult warm(CacheWarmPlan plan) {
        return cacheDatabase.warm(plan);
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

    public RouteCoverage coverage(String routeName, String scope, Duration maxAge) {
        return cacheDatabase.routeCoverage(routeName, scope, maxAge);
    }

    public <T, ID> WriteReceipt<T, ID> awaitDurable(WriteReceipt<T, ID> receipt, Duration timeout) {
        if (!cacheDatabase.awaitDurable(receipt, timeout)) {
            throw new AssertionError("Write did not become SQL-durable within " + timeout + ": " + receipt);
        }
        return receipt;
    }

    public CacheDatabase cacheDatabase() {
        return cacheDatabase;
    }
}
