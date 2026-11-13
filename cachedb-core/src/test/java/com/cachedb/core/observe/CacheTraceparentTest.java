package com.reactor.cachedb.core.observe;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.trace.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CacheTraceparentTest {
    static final String VALID = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-00";
    @Test void onlyBoundedValidNonzeroLowercaseW3cV00HeadersAreAccepted() {
        assertTrue(CacheTraceparent.valid(VALID));
        for (String value : List.of("", VALID.toUpperCase(Locale.ROOT), " " + VALID, VALID + "-extra",
                "ff" + VALID.substring(2), "01" + VALID.substring(2), "x".repeat(129),
                "00-" + "0".repeat(32) + "-0123456789abcdef-01",
                "00-0123456789abcdef0123456789abcdef-" + "0".repeat(16) + "-01")) {
            assertFalse(CacheTraceparent.valid(value), value);
        }
        assertFalse(CacheTraceparent.valid(null));
        assertFalse(new CacheSpanLink(VALID).sampled());
        assertTrue(new CacheSpanLink(VALID.substring(0, 54) + "1").sampled());
    }
    @Test void enrichmentPreservesExistingMetadataAndItsCodecAndNeverManufacturesSampling() {
        var existing = Map.of("correlationId", "correlation", "baggage", "kept");
        var merged = CacheTraceparent.withTraceparent(existing, VALID);
        assertEquals("correlation", merged.get("correlationId"));
        assertEquals(VALID, merged.get("traceparent"));
        assertEquals(merged, TraceMetadataCodec.decode(TraceMetadataCodec.encode(merged)));
        assertFalse(existing.containsKey("traceparent"));
        var full = new HashMap<String, String>();
        for (int i = 0; i < 8; i++) full.put("k" + i, "v");
        assertThrows(IllegalArgumentException.class, () -> CacheTraceparent.withTraceparent(full, VALID));
        assertThrows(IllegalArgumentException.class, () -> CacheTraceparent.withTraceparent(existing, "invalid"));
    }
    @Test void originLinksHaveIndependentInputAndUniqueLinkBounds() {
        var values = new ArrayList<String>();
        values.add(null); values.add("bad");
        for (int i = 1; i <= 100; i++) {
            String value = "00-%032x-%016x-01".formatted(i, i);
            values.add(value); values.add(value);
        }
        var links = CacheTraceparent.originLinks(values);
        assertEquals(32, links.size());
        assertEquals(32, links.stream().map(CacheSpanLink::spanId).distinct().count());
        assertThrows(UnsupportedOperationException.class, () -> links.add(new CacheSpanLink(VALID)));
        assertThrows(IllegalArgumentException.class, () -> CacheTraceparent.originLinks(Collections.nCopies(10_001, VALID)));
    }
    @Test void optionalHeaderBridgePreservesMetadataAndRemovesOnlyInvalidTraceparent() {
        try (var scope = TraceContexts.withMetadata(Map.of("correlationId", "kept", "traceparent", "bad"))) {
            var bridge = new ObservingTraceContextPropagator(TraceContexts.threadLocal(), () -> VALID);
            var values = bridge.capture();
            assertEquals("kept", values.get("correlationId")); assertEquals(VALID, values.get("traceparent"));
            assertEquals(1, bridge.invalidHeaders());
            try (var restored = bridge.restore(values)) { assertEquals(values, TraceContexts.current()); }
            assertEquals("bad", TraceContexts.current().get("traceparent"));
        }
    }
}
