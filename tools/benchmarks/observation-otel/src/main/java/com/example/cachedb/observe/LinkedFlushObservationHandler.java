package com.example.cachedb.observe;

import com.reactor.cachedb.core.observe.CacheOperation;
import com.reactor.cachedb.core.observe.CacheTraceparent;
import com.reactor.cachedb.spring.boot.observe.CacheMicrometerObservationContext;
import io.micrometer.observation.Observation;
import io.micrometer.tracing.Link;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import java.util.Objects;

/** Consumer-owned replacement for the default tracing handler, not a second span creator. */
public final class LinkedFlushObservationHandler extends DefaultTracingObservationHandler {
    public LinkedFlushObservationHandler(OtelTracer tracer) {
        super(Objects.requireNonNull(tracer, "tracer"));
    }

    private static boolean isFlush(Observation.Context context) {
        return context instanceof CacheMicrometerObservationContext cache
                && cache.operation() == CacheOperation.FLUSH_BATCH;
    }

    @Override public void onStart(Observation.Context context) {
        if (!isFlush(context)) {
            super.onStart(context);
            return;
        }
        var origins = ((CacheMicrometerObservationContext) context).originLinks();
        if (origins.size() > CacheTraceparent.MAX_LINKS)
            throw new IllegalArgumentException("At most 32 flush origins");
        var builder = getTracer().spanBuilder().name("cachedb.flush").setNoParent();
        int examined = 0;
        for (var origin : origins) {
            if (++examined > CacheTraceparent.MAX_LINKS)
                throw new IllegalArgumentException("At most 32 flush origins");
            var remote = SpanContext.createFromRemoteParent(origin.traceId(), origin.spanId(),
                    TraceFlags.fromHex(origin.traceparent(), 53), TraceState.getDefault());
            builder.addLink(new Link(OtelTraceContext.fromOtel(remote)));
        }
        getTracingContext(context).setSpan(builder.start());
    }

    @Override public String getSpanName(Observation.Context context) {
        return isFlush(context) ? "cachedb.flush" : super.getSpanName(context);
    }
}
