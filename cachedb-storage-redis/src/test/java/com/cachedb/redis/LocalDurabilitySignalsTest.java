package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LocalDurabilitySignalsTest {
    @Test void signalsAreImmediateVersionedClientIsolatedAndRemovedOnClose() {
        Object client = new Object();
        Object other = new Object();
        int before = LocalDurabilitySignals.activeCount();
        try (var registration = LocalDurabilitySignals.register(client, "key", 2)) {
            assertEquals(before + 1, LocalDurabilitySignals.activeCount());
            LocalDurabilitySignals.advance(client, "key", 1);
            LocalDurabilitySignals.advance(other, "key", 10);
            assertFalse(registration.future().isDone());
            long start = System.nanoTime();
            LocalDurabilitySignals.advance(client, "key", 2);
            assertEquals(2, registration.future().join());
            assertTrue(System.nanoTime() - start < 5_000_000L, "Local notification exceeded 5 ms");
        }
        assertEquals(before, LocalDurabilitySignals.activeCount());
    }
}
