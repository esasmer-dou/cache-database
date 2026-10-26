package com.reactor.cachedb.core.trace;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TraceContexts {
    private static final ThreadLocal<Map<String, String>> CURRENT = new ThreadLocal<>();
    private static final TraceContextPropagator LOCAL = new TraceContextPropagator() {
        public Map<String, String> capture() { return current(); }
        public Scope restore(Map<String, String> metadata) { return withMetadata(metadata); }
    };

    private TraceContexts() {}
    public static TraceContextPropagator threadLocal() { return LOCAL; }
    public static Map<String, String> current() {
        Map<String, String> value = CURRENT.get();
        return value == null ? Map.of() : value;
    }

    public static Map<String, String> boundedCopy(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) return Map.of();
        if (metadata.size() > 8) throw new IllegalArgumentException("Trace context supports at most eight entries");
        var result = new LinkedHashMap<String, String>();
        metadata.forEach((key, value) -> {
            if (key == null || key.isBlank() || key.length() > 512 || value == null || value.length() > 512)
                throw new IllegalArgumentException("Invalid trace context entry");
            for (int i = 0; i < key.length(); i++) if (Character.isISOControl(key.charAt(i)))
                throw new IllegalArgumentException("Control characters in trace key");
            for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i)))
                throw new IllegalArgumentException("Control characters in trace value");
            result.put(key, value);
        });
        return Map.copyOf(result);
    }

    public static TraceContextPropagator.Scope withMetadata(Map<String, String> metadata) {
        Map<String, String> next = boundedCopy(metadata);
        Map<String, String> previous = CURRENT.get();
        if (next.isEmpty()) CURRENT.remove(); else CURRENT.set(next);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
}
