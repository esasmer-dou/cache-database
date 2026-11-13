package com.reactor.cachedb.reactor;

import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import reactor.util.context.ContextView;

/** Keys are subscriber data, not authorization. Never derive administrative authority from them. */
public final class CacheReactorContext {
    public static final String TENANT = "cachedb.tenant";
    public static final String CAUSAL_TOKEN = "cachedb.causal-token";
    private CacheReactorContext() {}

    static <V> CompletableFuture<V> call(ContextView context, Supplier<CompletableFuture<V>> operation) {
        String tenant = string(context, TENANT);
        String token = string(context, CAUSAL_TOKEN);
        // withToken merges; start from an empty causal scope, not a possibly foreign ambient one.
        try (var emptyTenant = CacheTenantContext.empty();
                var emptyCausal = CacheCausalContext.empty();
                var appliedTenant = tenant == null ? null : CacheTenantContext.withTenant(tenant);
                var appliedCausal = token == null ? null : CacheCausalContext.withToken(token)) {
            return Objects.requireNonNull(operation.get(), "async repository returned null future");
        }
    }

    private static String string(ContextView context, String key) {
        if (!context.hasKey(key)) return null;
        Object value = context.get(key);
        if (!(value instanceof String string)) throw new IllegalArgumentException("Expected String Reactor context: " + key);
        return string;
    }
}
