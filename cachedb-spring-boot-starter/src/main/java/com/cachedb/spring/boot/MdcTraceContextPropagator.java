package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.core.trace.TraceContextPropagator;
import com.reactor.cachedb.core.trace.TraceContexts;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;

/** Bounded allowlist: application MDC entries and secrets are never copied wholesale. */
public final class MdcTraceContextPropagator implements TraceContextPropagator {
    private static final String[] KEYS = {"traceparent", "tracestate", "baggage", "correlationId", "traceId", "spanId"};
    @Override public Map<String, String> capture() {
        Map<String, String> local = TraceContexts.current();
        LinkedHashMap<String, String> values = null;
        for (String key : KEYS) {
            String value = MDC.get(key);
            if (value != null && !value.isBlank()) {
                if (values == null) values = new LinkedHashMap<>(local);
                if (values.size() < 8 || values.containsKey(key)) values.put(key, value);
            }
        }
        if (values == null) return local;
        if (!values.containsKey("traceparent") && values.size() < 8 && hex(values.get("traceId"), 32)
                && hex(values.get("spanId"), 16))
            values.put("traceparent", "00-" + values.get("traceId") + "-" + values.get("spanId") + "-01");
        return TraceContexts.boundedCopy(values);
    }

    private static boolean hex(String value, int length) {
        if (value == null || value.length() != length) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) return false;
        }
        return true;
    }

    @Override public Scope restore(Map<String, String> metadata) {
        var values = TraceContexts.boundedCopy(metadata);
        var previous = new LinkedHashMap<String, String>();
        for (String key : KEYS) {
            previous.put(key, MDC.get(key));
            String value = values.get(key);
            if (value == null) MDC.remove(key); else MDC.put(key, value);
        }
        var context = TraceContexts.withMetadata(values);
        return () -> {
            try { context.close(); }
            finally { previous.forEach((key, value) -> { if (value == null) MDC.remove(key); else MDC.put(key, value); }); }
        };
    }
}
