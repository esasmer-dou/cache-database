package com.reactor.cachedb.core.async;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.core.trace.TraceContextPropagator;
import com.reactor.cachedb.core.trace.TraceContexts;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CacheAsyncExternalCompletionTest {
    @Test void oneThousandExternalOperationsUseNoExecutorAndNoDispatchSlots() throws Exception {
        var dispatched = new AtomicInteger();
        var raw = new ArrayList<CompletableFuture<String>>();
        var physical = new ArrayList<CompletableFuture<Void>>();
        try (var gate = new CacheAsyncExecutor(1024, task -> {
            dispatched.incrementAndGet();
            throw new AssertionError("External native completion must never use an executor");
        }, TraceContexts.threadLocal())) {
            try {
                var results = new ArrayList<CompletableFuture<String>>();
                for (int i = 0; i < 1000; i++) {
                    var value = new CompletableFuture<String>();
                    var io = new CompletableFuture<Void>();
                    raw.add(value);
                    physical.add(io);
                    results.add(gate.submitExternal(() -> new CacheAsyncExecutor.ExternalOperation<>(value, io),
                            payload -> payload == null ? "miss" : payload));
                }
                assertEquals(24, gate.snapshot().availablePermits());
                assertEquals(0, gate.snapshot().outstandingDispatches());
                for (int i = 0; i < raw.size(); i++) {
                    raw.get(i).complete(null);
                    assertFalse(results.get(i).isDone(), "Success must await physical completion");
                    physical.get(i).complete(null);
                    assertEquals("miss", results.get(i).get(2, TimeUnit.SECONDS));
                }
                assertEquals(0, dispatched.get());
                assertEquals(1024, gate.snapshot().availablePermits());
                assertEquals(1000, gate.externalSnapshot().completed());
                assertEquals(1000, gate.externalSnapshot().accepted());
                assertEquals(0, gate.snapshot().accepted(), "Native admission is not an executor dispatch");
            } finally {
                raw.forEach(value -> value.complete(null));
                physical.forEach(value -> value.complete(null));
            }
        }
    }

    @Test void deadlineLikeFailureIsDeliveredBeforePhysicalIoWithoutFreeingItsPermit() throws Exception {
        var value = new CompletableFuture<String>();
        var io = new CompletableFuture<Void>();
        var failure = new IOException("delivery deadline, socket still running");
        try (var gate = new CacheAsyncExecutor(1, task -> { throw new AssertionError("No dispatch"); },
                TraceContexts.threadLocal())) {
            try {
                var result = gate.submitExternal(() -> new CacheAsyncExecutor.ExternalOperation<>(value, io), String::length);
                value.completeExceptionally(failure);
                assertSame(failure, assertThrows(ExecutionException.class,
                        () -> result.get(1, TimeUnit.SECONDS)).getCause());
                assertEquals(0, gate.snapshot().availablePermits());
                assertFalse(gate.awaitIdle(Duration.ofMillis(20)));
                assertFalse(io.isDone());
                var rejected = gate.submitExternal(() -> { fail("Do not start saturated work"); return null; }, row -> row);
                assertInstanceOf(CacheAsyncRejectedException.class,
                        assertThrows(ExecutionException.class, rejected::get).getCause());
                io.complete(null);
                assertTrue(gate.awaitIdle(Duration.ofSeconds(1)));
                assertEquals(1, gate.snapshot().availablePermits());
                assertEquals(1, gate.externalSnapshot().failed(), "Late physical completion must not count the error twice");
            } finally { io.complete(null); }
        }
    }

    @Test void cancellationDetachesOnlyItsSourceAndNeverFabricatesPhysicalCompletion() throws Exception {
        var first = new CompletableFuture<String>();
        var second = new CompletableFuture<String>();
        var firstIo = new CompletableFuture<Void>();
        var secondIo = new CompletableFuture<Void>();
        try (var gate = new CacheAsyncExecutor(2, task -> { throw new AssertionError("No dispatch"); },
                TraceContexts.threadLocal())) {
            try {
                var cancelled = gate.submitExternal(() -> new CacheAsyncExecutor.ExternalOperation<>(first, firstIo), row -> row);
                var other = gate.submitExternal(() -> new CacheAsyncExecutor.ExternalOperation<>(second, secondIo), row -> row);
                assertTrue(cancelled.cancel(true));
                assertTrue(first.isCancelled());
                assertFalse(second.isCancelled());
                assertEquals(0, gate.snapshot().availablePermits());
                assertFalse(firstIo.isDone());
                secondIo.complete(null);
                second.complete("independent");
                assertEquals("independent", other.get(1, TimeUnit.SECONDS));
                assertEquals(1, gate.snapshot().availablePermits());
                firstIo.complete(null);
                assertTrue(gate.awaitIdle(Duration.ofSeconds(1)));
                assertEquals(2, gate.snapshot().availablePermits());
            } finally { firstIo.complete(null); second.complete("cleanup"); secondIo.complete(null); }
        }
    }

    @Test void successReleasesAdmissionBeforeNestedInlineCallbackStartsAnotherOperation() throws Exception {
        var value = new CompletableFuture<String>();
        var io = new CompletableFuture<Void>();
        try (var gate = new CacheAsyncExecutor(1, task -> { throw new AssertionError("No dispatch"); },
                TraceContexts.threadLocal())) {
            try {
                var first = gate.submitExternal(() -> new CacheAsyncExecutor.ExternalOperation<>(value, io), row -> row);
                var nested = first.thenCompose(row -> gate.submitExternal(() -> new CacheAsyncExecutor.ExternalOperation<>(
                        CompletableFuture.completedFuture(row + ":nested"), CompletableFuture.completedFuture(null)), next -> next));
                io.complete(null);
                value.complete("first");
                assertEquals("first:nested", nested.get(1, TimeUnit.SECONDS));
                assertEquals(1, gate.snapshot().availablePermits());
            } finally { io.complete(null); value.complete("cleanup"); }
        }
    }

    @Test void rowMappingRequiresNoExtraPermitAndItsPhysicalStageIncludesContextualMapping() throws Exception {
        var value = new CompletableFuture<String>();
        var io = new CompletableFuture<Void>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var gate = new CacheAsyncExecutor(1, task -> { throw new AssertionError("No dispatch"); },
                TraceContexts.threadLocal())) {
            try {
                var mapped = gate.mapExternal(new CacheAsyncExecutor.ExternalOperation<>(value, io), row -> {
                    entered.countDown();
                    try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                    return row.length();
                });
                var outer = gate.submitExternal(() -> mapped, row -> row + 1);
                Thread.ofVirtual().start(() -> value.complete("row"));
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                io.complete(null);
                assertFalse(mapped.physical().isDone());
                assertFalse(outer.isDone());
                assertEquals(0, gate.snapshot().availablePermits());
                release.countDown();
                assertEquals(4, outer.get(1, TimeUnit.SECONDS));
                assertTrue(mapped.physical().isDone());
                assertEquals(1, gate.snapshot().availablePermits());
            } finally { release.countDown(); io.complete(null); value.complete("cleanup"); }
        }
    }

    @Test void configuredTraceTenantAndCausalAreCapturedAndWorkerContextIsRestored() throws Exception {
        var local = new ThreadLocal<Map<String, String>>();
        var propagator = new TraceContextPropagator() {
            public Map<String, String> capture() { return local.get() == null ? Map.of() : local.get(); }
            public Scope restore(Map<String, String> next) {
                var previous = local.get();
                local.set(next);
                return () -> { if (previous == null) local.remove(); else local.set(previous); };
            }
        };
        var value = new CompletableFuture<String>();
        var io = new CompletableFuture<Void>();
        try (var gate = new CacheAsyncExecutor(1, task -> { throw new AssertionError("No dispatch"); }, propagator)) {
            try {
                CompletableFuture<String> result;
                local.set(Map.of("trace", "request"));
                try (var tenant = CacheTenantContext.withTenant("request");
                        var causal = CacheCausalContext.withMinVersion("rows", "id", 7)) {
                    result = gate.submitExternal(() -> new CacheAsyncExecutor.ExternalOperation<>(value, io), row -> {
                        assertEquals("request", CacheTenantContext.currentTenant().orElseThrow());
                        assertEquals(7, CacheCausalContext.requiredVersion("rows", "id"));
                        assertEquals(Map.of("trace", "request"), local.get());
                        return row;
                    });
                }
                local.set(Map.of("trace", "worker"));
                try (var tenant = CacheTenantContext.withTenant("worker");
                        var causal = CacheCausalContext.withMinVersion("rows", "id", 3)) {
                    io.complete(null);
                    value.complete("done");
                    assertEquals("worker", CacheTenantContext.currentTenant().orElseThrow());
                    assertEquals(3, CacheCausalContext.requiredVersion("rows", "id"));
                    assertEquals(Map.of("trace", "worker"), local.get());
                }
                assertEquals("done", result.get(1, TimeUnit.SECONDS));
            } finally { io.complete(null); value.complete("cleanup"); local.remove(); }
        }
    }
}
