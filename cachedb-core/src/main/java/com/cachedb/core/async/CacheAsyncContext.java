package com.reactor.cachedb.core.async;

import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.route.RouteCacheContext;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.core.trace.TraceContextPropagator;
import com.reactor.cachedb.core.trace.TraceContexts;
import java.util.Objects;

/** Capture at registration, invoke at completion; empty captures mask pooled-worker state. */
public final class CacheAsyncContext {
    private CacheAsyncContext() {}

    public static Runnable wrap(TraceContextPropagator propagator, Runnable task) {
        Objects.requireNonNull(propagator, "propagator");
        Objects.requireNonNull(task, "task");
        var metadata = TraceContexts.boundedCopy(propagator.capture());
        var route = RouteCacheContext.currentContract();
        var observation = com.reactor.cachedb.core.observe.CacheObservationContext.capture();
        return CacheTenantContext.wrap(CacheCausalContext.wrap((Runnable) () -> {
            try (var trace = propagator.restore(metadata); var binding = observation.restore()) {
                RouteCacheContext.runWithContract(route, task);
            }
        }));
    }
}
