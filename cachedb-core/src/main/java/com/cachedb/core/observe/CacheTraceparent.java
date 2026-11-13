package com.reactor.cachedb.core.observe;

import com.reactor.cachedb.core.trace.TraceContexts;
import java.util.*;

/** Bounded W3C version-00 subset. Unknown versions are rejected, never truncated. */
public final class CacheTraceparent {
    public static final int MAX_LENGTH = 128;
    public static final int MAX_LINKS = 32;
    public static final int MAX_ORIGINS_EXAMINED = 10_000;
    private CacheTraceparent() {}
    public static boolean valid(String value) {
        if (value == null || value.length() > MAX_LENGTH || value.length() != 55
                || !value.startsWith("00-") || value.charAt(35) != '-' || value.charAt(52) != '-') return false;
        boolean traceNonzero = false, spanNonzero = false;
        for (int i = 3; i < 55; i++) {
            if (i == 35 || i == 52) continue;
            char c = value.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) return false;
            if (i < 35 && c != '0') traceNonzero = true;
            if (i > 35 && i < 52 && c != '0') spanNonzero = true;
        }
        return traceNonzero && spanNonzero;
    }
    /** Copies all pre-existing entries; capacity overflow fails rather than dropping fields. */
    public static Map<String, String> withTraceparent(Map<String, String> existing, String traceparent) {
        if (!valid(traceparent)) throw new IllegalArgumentException("Invalid W3C traceparent");
        var values = new LinkedHashMap<>(TraceContexts.boundedCopy(existing));
        if (!values.containsKey("traceparent") && values.size() == 8)
            throw new IllegalArgumentException("Trace metadata has no capacity for traceparent");
        values.put("traceparent", traceparent);
        return TraceContexts.boundedCopy(values);
    }
    /** Invalid legacy origins are ignored; input and unique exported links are both bounded. */
    public static List<CacheSpanLink> originLinks(Collection<String> traceparents) {
        Objects.requireNonNull(traceparents, "traceparents");
        if (traceparents.size() > MAX_ORIGINS_EXAMINED)
            throw new IllegalArgumentException("Flush origins exceed 10000");
        var unique = new LinkedHashMap<String, CacheSpanLink>();
        int examined = 0;
        for (String value : traceparents) {
            if (++examined > MAX_ORIGINS_EXAMINED) throw new IllegalArgumentException("Flush origins exceed 10000");
            if (!valid(value)) continue;
            String identity = value.substring(3, 52);
            if (!unique.containsKey(identity)) unique.put(identity, new CacheSpanLink(value));
            if (unique.size() == MAX_LINKS) break;
        }
        return List.copyOf(unique.values());
    }
}
