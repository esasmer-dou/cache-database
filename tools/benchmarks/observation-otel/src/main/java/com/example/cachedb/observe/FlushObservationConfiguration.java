package com.example.cachedb.observe;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/** Import into a consumer application using the OpenTelemetry tracing backend. */
@Configuration(proxyBeanMethods = false)
public class FlushObservationConfiguration {
    @Bean
    com.reactor.cachedb.core.observe.CacheTraceparentSupplier cacheNativeTraceparentSupplier(Tracer tracer) {
        if (!(tracer instanceof OtelTracer))
            throw new IllegalStateException("The native header example requires the OTel backend");
        return () -> {
            var current = tracer.currentSpan();
            if (current == null) return null;
            var nativeContext = io.opentelemetry.api.trace.Span.current().getSpanContext();
            if (!nativeContext.isValid() || !nativeContext.getTraceId().equals(current.context().traceId())
                    || !nativeContext.getSpanId().equals(current.context().spanId())) return null;
            return "00-" + nativeContext.getTraceId() + "-" + nativeContext.getSpanId()
                    + "-" + nativeContext.getTraceFlags().asHex();
        };
    }

    @Bean
    @Order(MicrometerTracingAutoConfiguration.DEFAULT_TRACING_OBSERVATION_HANDLER_ORDER)
    LinkedFlushObservationHandler defaultTracingObservationHandler(Tracer tracer) {
        if (!(tracer instanceof OtelTracer otel))
            throw new IllegalStateException("The flush-link example requires the OTel backend");
        return new LinkedFlushObservationHandler(otel);
    }
}
