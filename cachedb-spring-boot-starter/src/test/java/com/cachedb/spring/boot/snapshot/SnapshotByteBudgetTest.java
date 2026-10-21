package com.reactor.cachedb.spring.boot.snapshot;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.repository.WindowRequest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class SnapshotByteBudgetTest {
    @Test
    void cachedSizesPreserveExactUtf8BudgetAndDuplicateRootSemantics() {
        int budget = 4 * 1024 * 1024;
        for (String payload :
                List.of(
                        "a".repeat(budget),
                        "\u0130".repeat(budget / 2),
                        "\ud83d\ude80".repeat(budget / 4),
                        "\ud800".repeat(budget))) {
            var raw = new InMemorySnapshotRepository();
            raw.putSnapshot("a", payload, "p", 1);
            raw.putSnapshot("b", "x", "p", 2);
            assertEquals(payload, raw.findById("a").orElseThrow().payload());
            assertEquals(1, raw.lookupRawAll(List.of("a", "a")).size());
            assertEquals(1, raw.findPage("p", WindowRequest.first(1)).items().size());
            assertThrows(IllegalStateException.class, () -> raw.lookupRawAll(List.of("a", "b")));
            assertThrows(
                    IllegalStateException.class, () -> raw.findPage("p", WindowRequest.first(2)));
            raw.putSnapshot("a", "", "p", 1);
            assertEquals(2, raw.lookupRawAll(List.of("a", "b")).size());
            assertEquals(2, raw.findPage("p", WindowRequest.first(2)).items().size());
            raw.expire();
            assertFalse(raw.lookupRaw("a").isSnapshotAvailable());
        }
    }

    @Test
    void refreshUpdatesCachedSizesAndOversizeFailureLeavesRowsUnchanged() {
        var raw = new InMemorySnapshotRepository();
        raw.putSnapshot("a", "[]", "p", 1);
        String payload = "\u0130".repeat(2 * 1024 * 1024);
        raw.onRefresh(ids -> Map.of("a", payload));
        raw.refreshRoots(List.of("a"));
        assertEquals(payload, raw.lookupRawAll(List.of("a")).get("a").rawPayload());
        raw.onRefresh(ids -> Map.of("a", payload + "x"));
        assertThrows(IllegalArgumentException.class, () -> raw.refreshRoots(List.of("a")));
        assertEquals(payload, raw.lookupRaw("a").rawPayload());
        raw.onRefresh(ids -> Map.of("a", "[]"));
        raw.refreshRoots(List.of("a"));
        assertEquals("[]", raw.lookupRaw("a").rawPayload());
    }
}
