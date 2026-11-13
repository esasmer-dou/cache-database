package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.async.CacheAsyncExecutor;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.core.trace.TraceContexts;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RedisAsyncContinuationTest {
    private CacheAsyncExecutor gate() {
        return new CacheAsyncExecutor(1, task -> { throw new AssertionError("Continuation must not dispatch"); },
                TraceContexts.threadLocal());
    }

    @Test void nextStageUsesCapturedTenantButPublicCallbackDoesNotLeakIt() throws Exception {
        var first = new CompletableFuture<String>();
        try (var gate = gate()) {
            CompletableFuture<String> result;
            try (var tenant = CacheTenantContext.withTenant("caller")) {
                result = RedisAsyncContinuation.compose(gate, first, value -> {
                    assertEquals("caller", CacheTenantContext.currentTenant().orElseThrow());
                    return CompletableFuture.completedFuture(value + ":next");
                });
            }
            var observed = result.thenApply(value -> CacheTenantContext.currentTenant().orElseThrow());
            try (var tenant = CacheTenantContext.withTenant("worker")) {
                first.complete("row");
                assertEquals("row:next", result.get());
                assertEquals("worker", observed.get());
                assertEquals("worker", CacheTenantContext.currentTenant().orElseThrow());
            }
        }
    }

    @Test void cancellingFirstWaiterPreventsTheNextStageAndDoesNotTouchUnrelatedWork() {
        var first = new CompletableFuture<String>();
        var unrelated = new CompletableFuture<String>();
        var next = new AtomicInteger();
        try (var gate = gate()) {
            var result = RedisAsyncContinuation.compose(gate, first, row -> {
                next.incrementAndGet();
                return unrelated;
            });
            assertTrue(result.cancel(true));
            assertTrue(result.cancel(false));
            assertTrue(first.isCancelled());
            assertFalse(unrelated.isDone());
            assertEquals(0, next.get());
        }
    }

    @Test void cancellingAfterTransitionCancelsOnlyTheInstalledOwnedFuture() {
        var first = new CompletableFuture<String>();
        var next = new CompletableFuture<String>();
        try (var gate = gate()) {
            var result = RedisAsyncContinuation.compose(gate, first, row -> next);
            first.complete("row");
            assertFalse(result.isDone());
            assertTrue(result.cancel(true));
            assertTrue(next.isCancelled());
            assertFalse(first.isCancelled());
        }
    }

    @Test void sourceAndContinuationFailuresPreserveTheirOriginalException() {
        var failure = new IOException("exact failure");
        try (var gate = gate()) {
            var first = new CompletableFuture<String>();
            var result = RedisAsyncContinuation.compose(gate, first, row -> {
                throw new AssertionError("Do not continue after failure");
            });
            first.completeExceptionally(failure);
            assertSame(failure, assertThrows(ExecutionException.class, result::get).getCause());
            var second = RedisAsyncContinuation.compose(gate, CompletableFuture.completedFuture("row"),
                    row -> CompletableFuture.failedFuture(failure));
            assertSame(failure, assertThrows(ExecutionException.class, second::get).getCause());
        }
    }
}
