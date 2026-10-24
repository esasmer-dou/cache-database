package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.queue.StreamPage;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AdminWriteBehindInspectorTest {
    @Test void wideExportsUseThePayloadLimitNotOneProviderLimit() {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < 4096; i++) fields.put("c" + i, "value");
        assertEquals(List.of("row"), AdminWriteBehindInspector.collect(null,
                cursor -> new StreamPage<>(List.of("row"), null, false), row -> fields));
        fields.put("overflow", "value");
        assertThrows(IllegalStateException.class, () -> AdminWriteBehindInspector.collect(null,
                cursor -> new StreamPage<>(List.of("row"), null, false), row -> fields));
    }
    @Test void exportFollowsPagesWithoutDiscardingRows() {
        AtomicInteger calls = new AtomicInteger();
        var rows = AdminWriteBehindInspector.collect(null, cursor -> calls.getAndIncrement() == 0
                ? new StreamPage<>(List.of("first"), "next", true)
                : new StreamPage<>(List.of("last"), null, false), row -> Map.of("value", row));
        assertEquals(List.of("first", "last"), rows);
        assertEquals(2, calls.get());
    }
    @Test void refusesStuckCursorsAndOversizedExports() {
        assertThrows(IllegalStateException.class, () -> AdminWriteBehindInspector.collect("same",
                cursor -> new StreamPage<>(List.of("a"), "same", true), row -> Map.of()));
        assertThrows(IllegalStateException.class, () -> AdminWriteBehindInspector.collect(null,
                cursor -> new StreamPage<>(Collections.nCopies(50001, "a"), null, false), row -> Map.of()));
        assertThrows(IllegalStateException.class, () -> AdminWriteBehindInspector.collect(null,
                cursor -> new StreamPage<>(List.of("a"), null, false),
                row -> Map.of("value", "x".repeat((int) AdminWriteBehindInspector.MAX_EXPORT_CHARS + 1))));
    }
}
