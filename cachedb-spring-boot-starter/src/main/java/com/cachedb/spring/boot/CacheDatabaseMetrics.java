package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.core.route.HotRoutePopulation;
import com.reactor.cachedb.starter.CacheDatabase;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.Locale;
import java.util.Objects;

public final class CacheDatabaseMetrics implements MeterBinder {
    private final CacheDatabase cacheDatabase;
    private final CacheDbRouteInventory routeInventory;
    private final CacheScheduledWarmRegistry scheduledWarmRegistry;

    public CacheDatabaseMetrics(CacheDatabase cacheDatabase) {
        this(cacheDatabase, CacheDbRouteInventory.empty(), null);
    }

    public CacheDatabaseMetrics(
            CacheDatabase cacheDatabase,
            CacheDbRouteInventory routeInventory,
            CacheScheduledWarmRegistry scheduledWarmRegistry
    ) {
        this.cacheDatabase = Objects.requireNonNull(cacheDatabase, "cacheDatabase");
        this.routeInventory = Objects.requireNonNull(routeInventory, "routeInventory");
        this.scheduledWarmRegistry = scheduledWarmRegistry;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("cachedb.writebehind.backlog", cacheDatabase,
                        database -> database.workerSnapshot().lastObservedBacklog())
                .description("Last observed CacheDB write-behind stream backlog")
                .strongReference(true)
                .register(registry);
        FunctionCounter.builder("cachedb.writebehind.flushed", cacheDatabase,
                        database -> database.workerSnapshot().flushedCount())
                .description("Writes durably flushed to SQL")
                .register(registry);
        FunctionCounter.builder("cachedb.writebehind.deadletters", cacheDatabase,
                        database -> database.workerSnapshot().deadLetterCount())
                .description("Writes moved to the dead-letter stream")
                .register(registry);
        Gauge.builder("cachedb.redis.memory.used.bytes", cacheDatabase,
                        database -> database.redisGuardrailSnapshot().usedMemoryBytes())
                .baseUnit("bytes")
                .description("Redis used memory observed by CacheDB guardrails")
                .strongReference(true)
                .register(registry);
        Gauge.builder("cachedb.projection.lag", cacheDatabase,
                        database -> database.projectionRefreshSnapshot().lagEstimateMillis())
                .baseUnit("milliseconds")
                .description("Estimated projection refresh lag")
                .strongReference(true)
                .register(registry);
        Gauge.builder("cachedb.projection.pending", cacheDatabase,
                        database -> database.projectionRefreshSnapshot().pendingCount())
                .description("Pending projection refresh entries")
                .strongReference(true)
                .register(registry);
        Gauge.builder("cachedb.repositories.declared", routeInventory, CacheDbRouteInventory::repositoryCount)
                .description("Compile-time generated CacheDB repository catalogs")
                .strongReference(true)
                .register(registry);
        Gauge.builder("cachedb.routes.declared", routeInventory, CacheDbRouteInventory::routeCount)
                .description("Compile-time generated CacheDB repository routes")
                .strongReference(true)
                .register(registry);
        for (HotRoutePopulation population : HotRoutePopulation.values()) {
            if (population == HotRoutePopulation.NOT_APPLICABLE) {
                continue;
            }
            Gauge.builder("cachedb.routes.hot.population", routeInventory,
                            inventory -> inventory.hotPopulationCount(population))
                    .tag("strategy", population.name().toLowerCase(Locale.ROOT))
                    .description("Compile-time generated HOT routes by bounded population strategy")
                    .strongReference(true)
                    .register(registry);
        }
        Gauge.builder("cachedb.scheduled.warm.running", this, metrics -> metrics.runningWarmJobs())
                .description("Scheduled warm jobs currently running on this application instance")
                .strongReference(true)
                .register(registry);
        FunctionCounter.builder("cachedb.scheduled.warm.failures", this, metrics -> metrics.warmFailures())
                .description("Scheduled warm execution failures")
                .register(registry);
        FunctionCounter.builder("cachedb.scheduled.warm.skipped", this, metrics -> metrics.warmSkipped())
                .description("Scheduled warm executions skipped by due-time or distributed lease checks")
                .register(registry);
    }

    private double runningWarmJobs() {
        return scheduledWarmRegistry == null ? 0.0d : scheduledWarmRegistry.runningCount();
    }

    private double warmFailures() {
        return scheduledWarmRegistry == null ? 0.0d : scheduledWarmRegistry.failureCount();
    }

    private double warmSkipped() {
        return scheduledWarmRegistry == null ? 0.0d : scheduledWarmRegistry.skippedCount();
    }
}
