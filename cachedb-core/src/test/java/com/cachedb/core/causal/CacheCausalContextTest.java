package com.reactor.cachedb.core.causal;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CacheCausalContextTest {
    @Test void wrappersCaptureAllThreeTaskTypesAndRestoreWorkerStateEvenOnFailure() throws Exception {
        Runnable runnable;
        java.util.concurrent.Callable<Long> callable;
        java.util.function.Supplier<Long> supplier;
        try (var capture = CacheCausalContext.withMinVersion("rows","1",7)) {
            runnable = CacheCausalContext.wrap((Runnable) () -> assertEquals(7,CacheCausalContext.requiredVersion("rows","1")));
            callable = CacheCausalContext.wrap((java.util.concurrent.Callable<Long>) () -> CacheCausalContext.requiredVersion("rows","1"));
            supplier = CacheCausalContext.wrap((java.util.function.Supplier<Long>) () -> CacheCausalContext.requiredVersion("rows","1"));
        }
        Runnable empty = CacheCausalContext.wrap((Runnable) () -> assertEquals("",CacheCausalContext.encodeToken()));
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> {
                try (var prior = CacheCausalContext.withMinVersion("rows","1",3)) {
                    runnable.run(); assertEquals(3,CacheCausalContext.requiredVersion("rows","1"));
                    assertEquals(7,callable.call()); assertEquals(7,supplier.get());
                    empty.run(); assertEquals(3,CacheCausalContext.requiredVersion("rows","1"));
                    assertThrows(IllegalArgumentException.class,() -> CacheCausalContext.wrap((Runnable) () -> {
                        CacheCausalContext.withMinVersion("leaked","id",2);
                        throw new IllegalArgumentException("task failed");
                    }).run());
                    assertEquals(3,CacheCausalContext.requiredVersion("rows","1"));
                    assertEquals(0,CacheCausalContext.requiredVersion("leaked","id"));
                }
                assertEquals("",CacheCausalContext.encodeToken());
                return null;
            }).get(5,java.util.concurrent.TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
    }

    @Test void wrappedTasksRespectNestingLimitAndScopeCloseOrder() {
        var scopes = new java.util.ArrayList<CacheCausalContext.Scope>();
        try {
            for (int i = 0; i < 64; i++) scopes.add(CacheCausalContext.withMinVersion("rows","1",1));
            var wrapped = CacheCausalContext.wrap((Runnable) () -> fail("Nesting must fail first"));
            assertThrows(IllegalStateException.class,wrapped::run);
            assertThrows(IllegalStateException.class,scopes.get(0)::close);
        } finally { for (int i = scopes.size()-1; i >= 0; i--) scopes.get(i).close(); }
    }
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
