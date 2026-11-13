package com.reactor.cachedb.core.observe;

import com.reactor.cachedb.core.trace.*;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Optional consumer-supplied native header source; never manufactures IDs or sampling flags. */
public final class ObservingTraceContextPropagator implements TraceContextPropagator {
    private final TraceContextPropagator delegate;
    private final Supplier<String> activeTraceparent;
    private final boolean nativeHeaderAuthoritative;
    private final LongAdder invalidHeaders = new LongAdder();
    public ObservingTraceContextPropagator(TraceContextPropagator delegate, Supplier<String> activeTraceparent) {
        this(delegate, activeTraceparent, false);
    }
    /** Authoritative native mode ignores newly derived MDC headers when no real span is available. */
    public ObservingTraceContextPropagator(TraceContextPropagator delegate, Supplier<String> activeTraceparent,
            boolean nativeHeaderAuthoritative) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.activeTraceparent = Objects.requireNonNull(activeTraceparent, "activeTraceparent");
        this.nativeHeaderAuthoritative = nativeHeaderAuthoritative;
    }
    public long invalidHeaders() { return invalidHeaders.sum(); }
    private Map<String, String> checked(Map<String, String> metadata) {
        Map<String, String> values = TraceContexts.boundedCopy(metadata);
        String existing = values.get("traceparent");
        if (existing == null || CacheTraceparent.valid(existing)) return values;
        invalidHeaders.increment();
        var copy = new LinkedHashMap<>(values); copy.remove("traceparent"); return Map.copyOf(copy);
    }
    private Map<String, String> withoutDerivedHeader(Map<String, String> values) {
        if (!nativeHeaderAuthoritative || !values.containsKey("traceparent")) return values;
        String explicit = TraceContexts.current().get("traceparent");
        if (Objects.equals(explicit, values.get("traceparent"))) return values;
        var copy = new LinkedHashMap<>(values);
        if (CacheTraceparent.valid(explicit)) copy.put("traceparent", explicit); else copy.remove("traceparent");
        return Map.copyOf(copy);
    }
    @Override public Map<String, String> capture() {
        Map<String, String> values = checked(delegate.capture());
        String candidate;
        try { candidate = activeTraceparent.get(); }
        catch (RuntimeException invalidSource) { invalidHeaders.increment(); return withoutDerivedHeader(values); }
        if (candidate == null) return withoutDerivedHeader(values);
        if (!CacheTraceparent.valid(candidate)) { invalidHeaders.increment(); return withoutDerivedHeader(values); }
        try { return CacheTraceparent.withTraceparent(values, candidate); }
        catch (IllegalArgumentException metadataCapacityExceeded) {
            invalidHeaders.increment(); return withoutDerivedHeader(values);
        }
    }
    @Override public Scope restore(Map<String, String> metadata) { return delegate.restore(checked(metadata)); }
}
