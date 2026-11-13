package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.async.CacheAsyncExecutor;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.core.trace.TraceContexts;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RedisAsyncReadWindowTest {
    private CacheAsyncExecutor gate() {
        return new CacheAsyncExecutor(4, task -> { throw new AssertionError("Native windows must not offload"); },
                TraceContexts.threadLocal());
    }
    private static final class Rows {
        final List<CompletableFuture<RedisAsyncReadWindow.Row<Integer>>> data = new ArrayList<>();
        final List<CompletableFuture<Void>> physical = new ArrayList<>();
        final AtomicInteger starts = new AtomicInteger();
        Rows(int count) {
            for (int i = 0; i < count; i++) { data.add(new CompletableFuture<>()); physical.add(new CompletableFuture<>()); }
        }
        CacheAsyncExecutor.ExternalOperation<RedisAsyncReadWindow.Row<Integer>> read(int id) {
            starts.incrementAndGet();
            return new CacheAsyncExecutor.ExternalOperation<>(data.get(id), physical.get(id));
        }
        void row(int id, long bytes) { data.get(id).complete(new RedisAsyncReadWindow.Row<>(id, bytes, true)); }
        void finish() {
            for (var io : physical) io.complete(null);
            for (int i = 0; i < data.size(); i++) row(i, 1);
        }
    }

    @Test void each512WindowWaitsForDataAndRealPhysicalCompletionAndPreservesOrder() throws Exception {
        var rows = new Rows(700);
        try (var gate = gate()) {
            try {
                var operation = new RedisAsyncReadWindow<>(IntStream.range(0, 700).boxed().toList(), gate, rows::read).start();
                assertEquals(512, rows.starts.get());
                for (int i = 511; i >= 0; i--) rows.row(i, 1);
                assertEquals(512, rows.starts.get(), "Data completion must not impersonate physical I/O exit");
                assertFalse(operation.result().isDone());
                for (int i = 0; i < 512; i++) rows.physical.get(i).complete(null);
                assertEquals(700, rows.starts.get());
                for (int i = 699; i >= 512; i--) { rows.physical.get(i).complete(null); rows.row(i, 1); }
                assertTrue(operation.physical().isDone());
                assertEquals(IntStream.range(0, 700).boxed().toList(),
                        operation.result().get(2, TimeUnit.SECONDS).stream().map(RedisAsyncReadWindow.Row::value).toList());
            } finally { rows.finish(); }
        }
    }

    @Test void earlyErrorCallbackCannotPreventIndependentPhysicalRelease() throws Exception {
        var rows = new Rows(2);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failure = new IOException("isolated row failure");
        try (var gate = gate()) {
            try {
                var operation = new RedisAsyncReadWindow<>(List.of(0, 1), gate, rows::read).start();
                var callback = operation.result().whenComplete((value, error) -> {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                });
                Thread.ofVirtual().start(() -> rows.data.get(0).completeExceptionally(failure));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertFalse(operation.physical().isDone());
                rows.physical.get(0).complete(null);
                rows.physical.get(1).complete(null);
                operation.physical().get(1, TimeUnit.SECONDS);
                assertFalse(callback.isDone());
                assertSame(failure, assertThrows(ExecutionException.class, operation.result()::get).getCause());
                assertTrue(rows.data.get(1).isCancelled());
                release.countDown();
                assertSame(failure, assertThrows(ExecutionException.class, callback::get).getCause());
            } finally { release.countDown(); rows.finish(); }
        }
    }

    @Test void cancellationDetachesOwnWindowWaitersWithoutClaimingPhysicalExitOrOpeningTheNextWindow() {
        var rows = new Rows(700);
        try (var gate = gate()) {
            try {
                var operation = new RedisAsyncReadWindow<>(IntStream.range(0, 700).boxed().toList(), gate, rows::read).start();
                assertTrue(operation.result().cancel(true));
                assertTrue(operation.result().cancel(false));
                for (int i = 0; i < 512; i++) assertTrue(rows.data.get(i).isCancelled());
                assertFalse(operation.physical().isDone());
                for (int i = 0; i < 512; i++) rows.physical.get(i).complete(null);
                assertTrue(operation.physical().isDone());
                assertEquals(512, rows.starts.get());
                assertFalse(rows.data.get(512).isDone());
            } finally { rows.finish(); }
        }
    }

    @Test void cumulativeDecodedOutputBudgetFailsWithoutAccumulatingUnboundedRawFutures() {
        var rows = new Rows(2);
        try (var gate = gate()) {
            try {
                var operation = new RedisAsyncReadWindow<>(List.of(0, 1), gate, rows::read).start();
                rows.row(0, 9L * 1024 * 1024);
                rows.row(1, 9L * 1024 * 1024);
                assertInstanceOf(IllegalArgumentException.class,
                        assertThrows(ExecutionException.class, operation.result()::get).getCause());
                assertFalse(operation.physical().isDone());
                rows.physical.forEach(io -> io.complete(null));
                assertTrue(operation.physical().isDone());
            } finally { rows.finish(); }
        }
    }

    @Test void laterWindowsRestoreOriginalCallerContextAndThenRestoreCompletionThreadContext() throws Exception {
        var rows = new Rows(700);
        try (var gate = gate()) {
            try {
                CacheAsyncExecutor.ExternalOperation<List<RedisAsyncReadWindow.Row<Integer>>> operation;
                try (var tenant = CacheTenantContext.withTenant("caller")) {
                    operation = new RedisAsyncReadWindow<Integer, Integer>(
                            IntStream.range(0, 700).boxed().toList(), gate, id -> {
                                assertEquals("caller", CacheTenantContext.currentTenant().orElseThrow());
                                return rows.read(id);
                            }).start();
                }
                try (var tenant = CacheTenantContext.withTenant("completion-worker")) {
                    rows.finish();
                    assertEquals("completion-worker", CacheTenantContext.currentTenant().orElseThrow());
                }
                assertEquals(700, operation.result().get(1, TimeUnit.SECONDS).size());
                assertEquals(700, rows.starts.get());
                assertTrue(CacheTenantContext.currentTenant().isEmpty());
            } finally { rows.finish(); }
        }
    }

    @Test void physicalFailurePublishesHonestExitBeforeCancellingDataRunsABlockedCallback() throws Exception {
        var rows = new Rows(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failure = new IOException("physical-stage failure");
        try (var gate = gate()) {
            try {
                var operation = new RedisAsyncReadWindow<>(List.of(0), gate, rows::read).start();
                var callback = operation.result().whenComplete((value, error) -> {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                });
                Thread.ofVirtual().start(() -> rows.physical.get(0).completeExceptionally(failure));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                operation.physical().get(1, TimeUnit.SECONDS);
                assertFalse(callback.isDone());
                assertSame(failure, assertThrows(ExecutionException.class, operation.result()::get).getCause());
                release.countDown();
                assertSame(failure, assertThrows(ExecutionException.class, callback::get).getCause());
            } finally { release.countDown(); rows.finish(); }
        }
    }

    @Test void emptyInputCompletesBothStagesWithoutAFactoryOrExecutorCall() {
        try (var gate = gate()) {
            var operation = new RedisAsyncReadWindow<String, Integer>(List.of(), gate,
                    id -> { throw new AssertionError("No reads"); }).start();
            assertEquals(List.of(), operation.result().join());
            assertTrue(operation.physical().isDone());
            assertEquals(0, gate.snapshot().accepted());
        }
    }
}
