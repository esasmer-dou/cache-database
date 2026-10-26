package com.reactor.cachedb.core.tenant;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;

class CacheTenantContextTest {
    @Test void propagatesExplicitAdministrationButDoesNotInheritAmbientPrivileges() throws Exception {
        Runnable noContext = CacheTenantContext.wrap(() -> assertFalse(CacheTenantContext.isAdministrative()));
        try (var admin = CacheTenantContext.administrative()) {
            assertTrue(CacheTenantContext.isAdministrative()); assertTrue(CacheTenantContext.currentTenant().isEmpty());
            noContext.run(); assertTrue(CacheTenantContext.isAdministrative());
            Callable<Boolean> captured = CacheTenantContext.wrapCallable(CacheTenantContext::isAdministrative);
            var executor = Executors.newSingleThreadExecutor();
            try { assertTrue(executor.submit(captured).get()); assertFalse(executor.submit((Callable<Boolean>) CacheTenantContext::isAdministrative).get()); }
            finally { executor.shutdownNow(); }
        }
        assertFalse(CacheTenantContext.isAdministrative());
    }
    @Test void restoresNestedScopesAndExceptionBoundaries() {
        assertTrue(CacheTenantContext.currentTenant().isEmpty());
        try (var outer = CacheTenantContext.withTenant("a")) {
            assertThrows(IllegalStateException.class, () -> {
                try (var inner = CacheTenantContext.withTenant("b")) {
                    assertEquals("b", CacheTenantContext.currentTenant().orElseThrow());
                    throw new IllegalStateException("abort");
                }
            });
            assertEquals("a", CacheTenantContext.currentTenant().orElseThrow());
        }
        assertTrue(CacheTenantContext.currentTenant().isEmpty());
    }
    @Test void capturesOnlyExplicitTasksAndDoesNotLeakToReusedThread() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            Callable<String> task;
            try (var scope = CacheTenantContext.withTenant("a")) {
                assertTrue(executor.submit(() -> CacheTenantContext.currentTenant().isEmpty()).get());
                task = CacheTenantContext.wrapCallable(() -> CacheTenantContext.currentTenant().orElseThrow());
            }
            assertEquals("a", executor.submit(task).get());
            assertTrue(executor.submit(() -> CacheTenantContext.currentTenant().isEmpty()).get());
        } finally { executor.shutdownNow(); }
    }
    @Test void rejectsOutOfOrderCloseAndInvalidIdentities() {
        var a = CacheTenantContext.withTenant("a"); var b = CacheTenantContext.withTenant("b");
        assertThrows(IllegalStateException.class, a::close);
        b.close(); a.close();
        assertThrows(IllegalArgumentException.class, () -> CacheTenantContext.withTenant(" "));
        assertThrows(IllegalArgumentException.class, () -> CacheTenantContext.withTenant("x".repeat(257)));
    }
}
