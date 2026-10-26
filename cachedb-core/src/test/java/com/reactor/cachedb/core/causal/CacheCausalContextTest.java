package com.reactor.cachedb.core.causal;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CacheCausalContextTest {
    @Test void tokensAreDeterministicCollisionFreeNestedAndNotInherited() throws Exception {
        String token;
        try (var outer = CacheCausalContext.withMinVersion("a:b", "c|; Ş", 2)) {
            try (var inner = CacheCausalContext.withMinVersion("a", "b:c|; Ş", 7)) {
                token = CacheCausalContext.encodeToken();
                assertEquals(2, CacheCausalContext.requiredVersion("a:b", "c|; Ş"));
                assertEquals(7, CacheCausalContext.requiredVersion("a", "b:c|; Ş"));
                var inherited = new AtomicReference<String>();
                Thread worker = new Thread(() -> inherited.set(CacheCausalContext.encodeToken())); worker.start(); worker.join();
                assertEquals("", inherited.get());
            }
            assertEquals(0, CacheCausalContext.requiredVersion("a", "b:c|; Ş"));
        }
        assertEquals("", CacheCausalContext.encodeToken());
        try (var scope = CacheCausalContext.withToken(token)) {
            assertEquals(token, CacheCausalContext.encodeToken());
            try (var nested = CacheCausalContext.withMinVersion("a:b", "c|; Ş", 1)) {
                assertEquals(2, CacheCausalContext.requiredVersion("a:b", "c|; Ş"));
            }
        }
    }
    @Test void malformedOversizedAndWrongScopeUseFailClosed() throws Exception {
        for (String token : new String[]{"x".repeat(4097), "ns|id|0", "ns|id|-1", "ns|id|9223372036854775808", "ns|id|2;", "%zz|id|1", "ns|id|1|extra"})
            assertThrows(IllegalArgumentException.class, () -> CacheCausalContext.withToken(token));
        String token = java.util.stream.IntStream.range(0, 129).mapToObj(i -> "n|" + i + "|1").collect(java.util.stream.Collectors.joining(";"));
        assertThrows(IllegalArgumentException.class, () -> CacheCausalContext.withToken(token));
        var first = CacheCausalContext.withMinVersion("n", "id", 1);
        var second = CacheCausalContext.withMinVersion("n", "id", 2);
        assertThrows(IllegalStateException.class, first::close); second.close(); first.close();
        assertEquals("", CacheCausalContext.encodeToken());
    }
}
