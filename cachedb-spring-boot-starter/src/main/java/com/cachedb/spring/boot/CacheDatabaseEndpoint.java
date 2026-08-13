package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.starter.CacheDatabase;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Endpoint(id = "cachedb")
public final class CacheDatabaseEndpoint {
    static final int MAX_ROUTE_DETAILS = 250;
    static final int MAX_SCHEDULED_WARM_DETAILS = 100;

    private final CacheDatabase cacheDatabase;
    private final CacheDbProviderInfo providerInfo;
    private final CacheDbRouteInventory routeInventory;
    private final CacheScheduledWarmRegistry scheduledWarmRegistry;

    public CacheDatabaseEndpoint(CacheDatabase cacheDatabase, CacheDbProviderInfo providerInfo) {
        this(cacheDatabase, providerInfo, CacheDbRouteInventory.empty(), null);
    }

    public CacheDatabaseEndpoint(
            CacheDatabase cacheDatabase,
            CacheDbProviderInfo providerInfo,
            CacheDbRouteInventory routeInventory,
            CacheScheduledWarmRegistry scheduledWarmRegistry
    ) {
        this.cacheDatabase = Objects.requireNonNull(cacheDatabase, "cacheDatabase");
        this.providerInfo = Objects.requireNonNull(providerInfo, "providerInfo");
        this.routeInventory = Objects.requireNonNull(routeInventory, "routeInventory");
        this.scheduledWarmRegistry = scheduledWarmRegistry;
    }

    @ReadOperation
    public Map<String, Object> snapshot() {
        var worker = cacheDatabase.workerSnapshot();
        var projection = cacheDatabase.projectionRefreshSnapshot();
        var guardrail = cacheDatabase.redisGuardrailSnapshot();
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("capturedAt", Instant.now());
        result.put("instanceId", cacheDatabase.instanceId());
        result.put("provider", providerInfo);
        result.put("writeBehindBacklog", worker.lastObservedBacklog());
        result.put("flushedWrites", worker.flushedCount());
        result.put("deadLetters", worker.deadLetterCount());
        result.put("projectionPending", projection.pendingCount());
        result.put("projectionLagMillis", projection.lagEstimateMillis());
        result.put("redisUsedMemoryBytes", guardrail.usedMemoryBytes());
        result.put("redisPressure", guardrail.pressureLevel());
        result.put("declaredRepositories", routeInventory.repositoryCount());
        result.put("declaredRoutes", routeInventory.routeCount());
        result.put("declaredRouteKinds", routeInventory.counts());
        result.put("hotRoutePopulation", routeInventory.hotPopulationCounts());
        result.put("hotRouteAssessment", routeInventory.hotRouteAssessment());
        result.put("routeDetails", routeInventory.routes(MAX_ROUTE_DETAILS));
        result.put("routeDetailsTruncated", routeInventory.routeCount() > MAX_ROUTE_DETAILS);
        int scheduledWarmJobCount = scheduledWarmRegistry == null ? 0 : scheduledWarmRegistry.size();
        List<CacheScheduledWarmSnapshot> warmSnapshots = scheduledWarmRegistry == null
                ? List.of()
                : scheduledWarmRegistry.snapshots(MAX_SCHEDULED_WARM_DETAILS);
        result.put("scheduledWarmJobs", warmSnapshots);
        result.put("scheduledWarmJobsTruncated", scheduledWarmJobCount > MAX_SCHEDULED_WARM_DETAILS);
        result.put("scheduledWarmRunning", scheduledWarmRegistry == null ? 0 : scheduledWarmRegistry.runningCount());
        result.put("scheduledWarmFailures", scheduledWarmRegistry == null ? 0L : scheduledWarmRegistry.failureCount());
        result.put("scheduledWarmSkipped", scheduledWarmRegistry == null ? 0L : scheduledWarmRegistry.skippedCount());
        return Map.copyOf(result);
    }
}
