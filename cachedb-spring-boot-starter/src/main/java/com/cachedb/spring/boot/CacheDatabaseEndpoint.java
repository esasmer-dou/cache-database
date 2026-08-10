package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.starter.CacheDatabase;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Endpoint(id = "cachedb")
public final class CacheDatabaseEndpoint {
    private final CacheDatabase cacheDatabase;
    private final CacheDbProviderInfo providerInfo;

    public CacheDatabaseEndpoint(CacheDatabase cacheDatabase, CacheDbProviderInfo providerInfo) {
        this.cacheDatabase = cacheDatabase;
        this.providerInfo = providerInfo;
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
        return Map.copyOf(result);
    }
}
