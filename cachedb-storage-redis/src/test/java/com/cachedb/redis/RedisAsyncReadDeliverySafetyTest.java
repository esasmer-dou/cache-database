package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.async.CacheAsyncExecutor;
import com.reactor.cachedb.core.trace.TraceContexts;
import com.reactor.cachedb.core.trace.TraceContextPropagator;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.exceptions.JedisConnectionException;

class RedisAsyncReadDeliverySafetyTest {
    private static void until(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(1);
        assertTrue(condition.getAsBoolean());
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { throw new AssertionError(failure); }
    }
    private static RedisAsyncReadBudget budget(long timeout) {
        return new RedisAsyncReadBudget(RedisAsyncReadBudget.BULK_BYTES, timeout, 4);
    }

    @Test void globalBulkAdmissionPrecedesAnyInputCopyOrDecoderAllocation() {
        try (var budget = budget(5000)) {
            var first = budget.reserveBulk();
            var iterations = new AtomicInteger();
            assertThrows(RejectedExecutionException.class, () -> {
                var lease = budget.reserveBulk();
                try { iterations.incrementAndGet(); } finally { lease.close(); }
            });
            assertEquals(0, iterations.get());
            assertEquals(RedisAsyncReadBudget.BULK_BYTES, budget.snapshot().reservedBytes());
            first.close();
            assertEquals(0, budget.snapshot().reservedBytes());
        }
    }

    @Test void cumulativeInputChargeRejectsBeforeInvokingTheNextCodec() {
        try (var budget = budget(5000)) {
            var delivery = new RedisAsyncReadDelivery<String>(budget.reserveBulk());
            var decodes = new AtomicInteger();
            assertEquals("first", delivery.decode(9L * 1024 * 1024, () -> { decodes.incrementAndGet(); return "first"; }));
            assertThrows(IllegalArgumentException.class, () ->
                    delivery.decode(9L * 1024 * 1024, () -> { decodes.incrementAndGet(); return "second"; }));
            assertEquals(1, decodes.get());
            delivery.bind(CompletableFuture.completedFuture("done"), CompletableFuture.completedFuture(null));
            delivery.finishSetup();
            assertEquals(0, budget.snapshot().reservedBytes());
        }
    }

    @Test void inputCopySetupLifetimeRetainsCreditEvenWhenDataWasAlreadyPublished() {
        try (var budget = budget(5000)) {
            var delivery = new RedisAsyncReadDelivery<String>(budget.reserveBulk());
            delivery.bind(CompletableFuture.completedFuture("done"), CompletableFuture.completedFuture(null));
            assertEquals("done", delivery.future().join());
            assertEquals(1, budget.snapshot().deliveries());
            delivery.finishSetup();
            assertEquals(0, budget.snapshot().deliveries());
        }
    }

    @Test void blockedPublicCallbackRetainsGlobalResultCreditAfterProducerExit() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var budget = budget(5000)) {
            try {
                var delivery = new RedisAsyncReadDelivery<String>(budget.reserveBulk());
                var data = new CompletableFuture<String>();
                delivery.bind(data, CompletableFuture.completedFuture(null));
                delivery.finishSetup();
                var callback = delivery.future().thenRun(() -> { entered.countDown(); await(release); });
                Thread.ofVirtual().start(() -> data.complete("done"));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertThrows(RejectedExecutionException.class, budget::reserveBulk);
                assertEquals(RedisAsyncReadBudget.BULK_BYTES, budget.snapshot().reservedBytes());
                release.countDown();
                callback.get(2, TimeUnit.SECONDS);
                until(() -> budget.snapshot().reservedBytes() == 0);
            } finally { release.countDown(); }
        }
    }

    @Test void timeoutPublishesWithoutWaitingForMappingButRetainsRealProducerAdmission() throws Exception {
        try (var budget = budget(200)) {
            var delivery = new RedisAsyncReadDelivery<String>(budget.reserveBulk());
            var data = new CompletableFuture<String>();
            var producer = new CompletableFuture<Void>();
            delivery.bind(data, producer);
            delivery.finishSetup();
            var failure = assertThrows(ExecutionException.class, () -> delivery.future().get(2, TimeUnit.SECONDS));
            assertInstanceOf(JedisConnectionException.class, failure.getCause());
            assertInstanceOf(TimeoutException.class, failure.getCause().getCause());
            assertEquals(1, budget.snapshot().deliveries());
            assertEquals(RedisAsyncReadBudget.BULK_BYTES, budget.snapshot().reservedBytes());
            producer.complete(null);
            until(() -> budget.snapshot().deliveries() == 0);
        }
    }

    @Test void cancellationDetachesOneSourceButNeverPretendsThatItsProducerExited() throws Exception {
        try (var budget = budget(5000)) {
            var delivery = new RedisAsyncReadDelivery<String>(budget.reserveBulk());
            var data = new CompletableFuture<String>();
            var producer = new CompletableFuture<Void>();
            delivery.bind(data, producer);
            delivery.finishSetup();
            assertTrue(delivery.future().cancel(true));
            assertTrue(delivery.future().cancel(false));
            assertTrue(data.isCancelled());
            assertFalse(producer.isDone());
            assertEquals(1, budget.snapshot().deliveries());
            producer.complete(null);
            until(() -> budget.snapshot().deliveries() == 0);
        }
    }

    @Test void deadlineStillCompletesWhenAnExternalCancelHookThrows() throws Exception {
        try (var budget = budget(200)) {
            var delivery = new RedisAsyncReadDelivery<String>(budget.reserveBulk());
            var data = new CompletableFuture<String>() {
                @Override public boolean cancel(boolean interrupt) { throw new IllegalStateException("cancel hook"); }
            };
            var producer = new CompletableFuture<Void>();
            delivery.bind(data, producer);
            delivery.finishSetup();
            assertInstanceOf(JedisConnectionException.class,
                    assertThrows(ExecutionException.class, () -> delivery.future().get(2, TimeUnit.SECONDS)).getCause());
            assertEquals(1, budget.snapshot().deliveries());
            data.complete("late");
            producer.complete(null);
            until(() -> budget.snapshot().deliveries() == 0);
        }
    }

    @Test void blockedExpiryCallbackCannotBlockTheTimerOrReturnItsOwnCreditEarly() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var budget = new RedisAsyncReadBudget(2 * RedisAsyncReadBudget.BULK_BYTES, 200, 4)) {
            try {
                var first = new RedisAsyncReadDelivery<String>(budget.reserveBulk());
                var a = new CompletableFuture<String>();
                first.bind(a, CompletableFuture.completedFuture(null));
                first.finishSetup();
                var callback = first.future().whenComplete((value, failure) -> {
                    assertNotEquals("cachedb-native-read-deadlines", Thread.currentThread().getName());
                    entered.countDown(); await(release);
                });
                var second = new RedisAsyncReadDelivery<String>(budget.reserveBulk());
                second.bind(new CompletableFuture<>(), CompletableFuture.completedFuture(null));
                second.finishSetup();
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertInstanceOf(JedisConnectionException.class,
                        assertThrows(ExecutionException.class, () -> second.future().get(2, TimeUnit.SECONDS)).getCause());
                until(() -> budget.snapshot().expiryWorkers() == 1 && budget.snapshot().deliveries() == 1);
                assertEquals(1, budget.snapshot().expiryWorkers());
                assertThrows(RejectedExecutionException.class, () -> {
                    var lease = budget.reserveBulk();
                    try { budget.reserveBulk(); } finally { lease.close(); }
                });
                release.countDown();
                assertThrows(ExecutionException.class, () -> callback.get(2, TimeUnit.SECONDS));
                until(() -> budget.snapshot().deliveries() == 0 && budget.snapshot().expiryWorkers() == 0);
            } finally { release.countDown(); }
        }
    }

    @Test void borrowedExecutorThatNeverRunsStillHasFinitePublicReadDelivery() throws Exception {
        try (var budget = budget(200); var gate = new CacheAsyncExecutor(1, ignored -> {}, TraceContexts.threadLocal())) {
            var delivery = new RedisAsyncReadDelivery<String>(budget.reserveWorker());
            var operation = gate.<String>submitTracked(() -> { throw new AssertionError("Never dispatched"); });
            delivery.bind(operation.result(), operation.physical());
            delivery.finishSetup();
            assertInstanceOf(JedisConnectionException.class,
                    assertThrows(ExecutionException.class, () -> delivery.future().get(2, TimeUnit.SECONDS)).getCause());
            until(() -> budget.snapshot().deliveries() == 0);
            assertTrue(operation.physical().isDone());
            assertEquals(1, gate.snapshot().availablePermits());
            assertEquals(1, gate.snapshot().outstandingDispatches(), "Cancelled borrowed queue stub remains bounded");
        }
    }

    @Test void closeRejectsDeliveryWithoutReturningStillOwnedProducerMemory() throws Exception {
        var budget = budget(5000);
        try {
            var delivery = new RedisAsyncReadDelivery<String>(budget.reserveBulk());
            var producer = new CompletableFuture<Void>();
            delivery.bind(new CompletableFuture<>(), producer);
            delivery.finishSetup();
            budget.close();
            assertInstanceOf(RejectedExecutionException.class,
                    assertThrows(ExecutionException.class, () -> delivery.future().get(2, TimeUnit.SECONDS)).getCause());
            assertEquals(RedisAsyncReadBudget.BULK_BYTES, budget.snapshot().reservedBytes());
            assertThrows(RejectedExecutionException.class, budget::reservePoint);
            producer.complete(null);
            until(() -> budget.snapshot().reservedBytes() == 0);
        } finally { budget.close(); }
    }

    @Test void timeoutDoesNotStripCapturedContextFromRunningMapperAndRestoresCompletionThreadAtExit() throws Exception {
        var trace = new ThreadLocal<Map<String, String>>();
        var propagator = new TraceContextPropagator() {
            public Map<String, String> capture() { return trace.get() == null ? Map.of() : Map.copyOf(trace.get()); }
            public Scope restore(Map<String, String> metadata) {
                var old = trace.get();
                trace.set(metadata);
                return () -> { if (old == null) trace.remove(); else trace.set(old); };
            }
        };
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var budget = budget(200);
                var gate = new CacheAsyncExecutor(1, ignored -> { throw new AssertionError("No offload"); }, propagator)) {
            try {
                var delivery = new RedisAsyncReadDelivery<String>(budget.reservePoint());
                var data = new CompletableFuture<String>(); var io = new CompletableFuture<Void>();
                CompletableFuture<String> mapped;
                trace.set(Map.of("trace", "caller"));
                try (var tenant = CacheTenantContext.withTenant("caller");
                        var causal = CacheCausalContext.withMinVersion("rows", "id", 9)) {
                    mapped = gate.submitExternal(() -> new CacheAsyncExecutor.ExternalOperation<>(data, io), value -> {
                        assertEquals("caller", CacheTenantContext.currentTenant().orElseThrow());
                        assertEquals(9, CacheCausalContext.requiredVersion("rows", "id"));
                        assertEquals(Map.of("trace", "caller"), trace.get());
                        entered.countDown(); await(release);
                        assertEquals("caller", CacheTenantContext.currentTenant().orElseThrow());
                        return value;
                    });
                } finally { trace.remove(); }
                delivery.bind(gate, mapped); delivery.finishSetup(); io.complete(null);
                var restored = new CompletableFuture<Boolean>();
                Thread.ofVirtual().start(() -> {
                    trace.set(Map.of("trace", "worker"));
                    try (var tenant = CacheTenantContext.withTenant("worker")) {
                        data.complete("row");
                        restored.complete(Map.of("trace", "worker").equals(trace.get())
                                && "worker".equals(CacheTenantContext.currentTenant().orElseThrow())
                                && CacheCausalContext.requiredVersion("rows", "id") == 0);
                    } catch (Throwable failure) { restored.completeExceptionally(failure); }
                    finally { trace.remove(); }
                });
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertInstanceOf(JedisConnectionException.class,
                        assertThrows(ExecutionException.class, () -> delivery.future().get(2, TimeUnit.SECONDS)).getCause());
                assertEquals(1, budget.snapshot().deliveries());
                assertEquals(0, gate.snapshot().availablePermits());
                release.countDown();
                assertTrue(restored.get(2, TimeUnit.SECONDS));
                until(() -> budget.snapshot().deliveries() == 0);
                assertTrue(CacheTenantContext.currentTenant().isEmpty());
                assertNull(trace.get());
            } finally { release.countDown(); trace.remove(); }
        }
    }
}
