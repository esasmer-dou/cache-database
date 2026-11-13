package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import static com.reactor.cachedb.redis.RedisReadCoalescerTest.until;

import com.reactor.cachedb.core.config.RedisReadCoalescingConfig;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.exceptions.JedisConnectionException;

class RedisReadSyncOverflowWaitTest {
    private static final long SHORT_KEYS_BYTES = 512L + 2L * (1 + 1);

    private record Caller(Thread thread, CompletableFuture<RedisPointRead> result, AtomicBoolean interrupted) {}

    private static final class Fixture implements AutoCloseable {
        final RedisReadFakeClient client = new RedisReadFakeClient();
        final RedisReadCoalescer reader;
        final CompletableFuture<RedisPointRead> initial;

        Fixture(int waiters, long bytes, int queue, long timeout) throws Exception {
            client.hold(1);
            reader = new RedisReadCoalescer(client, RedisReadCoalescingConfig.builder().enabled(true)
                    .maxInFlightBatches(1).maxQueuedRequests(queue).maxAsyncDirect(0)
                    .maxReplyBytes(1024).maxLiveReplyBytes(1024).timeoutMillis(timeout).build(),
                    32, waiters, bytes);
            initial = reader.readAsync("initial", "t", false);
            assertTrue(client.started.await(2, TimeUnit.SECONDS));
        }

        Caller start(String key) {
            var result = new CompletableFuture<RedisPointRead>();
            var interrupted = new AtomicBoolean();
            Thread thread = Thread.ofVirtual().start(() -> {
                try { result.complete(reader.readSync(key, "t", false)); }
                catch (Throwable failure) {
                    interrupted.set(Thread.currentThread().isInterrupted());
                    result.completeExceptionally(failure);
                }
            });
            return new Caller(thread, result, interrupted);
        }

        @Override public void close() throws Exception {
            client.release();
            reader.physicalCompletion(initial).get(2, TimeUnit.SECONDS);
            reader.close();
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
    }

    private static Throwable error(Caller caller) {
        return assertThrows(ExecutionException.class, () -> caller.result().get(3, TimeUnit.SECONDS)).getCause();
    }

    @Test void callerOwnedOverflowWaitsWithoutAnIoCounterThenExecutesOnThatSameCaller() throws Exception {
        try (var f = new Fixture(4, 4096, 0, 5000)) {
            Caller caller = f.start("a");
            until(() -> f.reader.state().syncOverflowWaiters() == 1 || caller.result().isDone());
            assertFalse(caller.result().isDone());
            assertEquals(SHORT_KEYS_BYTES, f.reader.state().syncOverflowBytes());
            assertEquals(0, f.reader.state().queued());
            assertEquals(0, f.reader.state().syncDirect());
            assertEquals(1, f.reader.state().inFlight());
            assertEquals(1024, f.reader.deliveryMemory().reservedBytes());
            assertEquals(1, f.client.calls.get());
            f.client.release();
            assertNull(caller.result().get(2, TimeUnit.SECONDS).payload());
            assertSame(caller.thread(), f.client.threads.get(1), "Never dispatch a memory-waiting sync request");
            until(() -> f.reader.state().syncDirect() == 0 && f.reader.state().syncOverflowWaiters() == 0);
            assertEquals(0, f.reader.state().syncOverflowBytes());
        }
    }

    @Test void countCapRejectsOnlyAdditionalOverflowWithoutChangingExistingReservations() throws Exception {
        try (var f = new Fixture(2, 4096, 0, 5000)) {
            Caller a = f.start("a");
            until(() -> f.reader.state().syncOverflowWaiters() == 1);
            Caller b = f.start("b");
            until(() -> f.reader.state().syncOverflowWaiters() == 2);
            assertThrows(RejectedExecutionException.class, () -> f.reader.readSync("c", "t", false));
            assertEquals(2 * SHORT_KEYS_BYTES, f.reader.state().syncOverflowBytes());
            assertEquals(0, f.reader.state().syncDirect());
            assertEquals(1, f.client.calls.get());
            f.client.release();
            a.result().get(2, TimeUnit.SECONDS);
            b.result().get(2, TimeUnit.SECONDS);
            until(() -> f.reader.state().syncOverflowWaiters() == 0);
            assertEquals(0, f.reader.state().syncOverflowBytes());
        }
    }

    @Test void byteCapIsExactAndIndependentOfTheWaiterCountCap() throws Exception {
        try (var f = new Fixture(8, 2 * SHORT_KEYS_BYTES, 0, 5000)) {
            Caller a = f.start("a");
            until(() -> f.reader.state().syncOverflowWaiters() == 1);
            Caller b = f.start("b");
            until(() -> f.reader.state().syncOverflowWaiters() == 2);
            assertEquals(2 * SHORT_KEYS_BYTES, f.reader.state().syncOverflowBytes());
            assertThrows(RejectedExecutionException.class, () -> f.reader.readSync("c", "t", false));
            assertEquals(2, f.reader.state().syncOverflowWaiters());
            f.client.release();
            a.result().get(2, TimeUnit.SECONDS);
            b.result().get(2, TimeUnit.SECONDS);
            until(() -> f.reader.state().syncOverflowBytes() == 0);
        }
    }

    @Test void singleWaiterAboveByteCapIsRejectedWithoutEverLinkingOrRunning() throws Exception {
        try (var f = new Fixture(8, SHORT_KEYS_BYTES - 1, 0, 5000)) {
            assertThrows(RejectedExecutionException.class, () -> f.reader.readSync("a", "t", false));
            assertEquals(0, f.reader.state().syncOverflowWaiters());
            assertEquals(0, f.reader.state().syncOverflowBytes());
            assertEquals(0, f.reader.state().syncDirect());
            assertEquals(1, f.client.calls.get());
        }
    }

    @Test void overflowDeadlineUsesTheExistingRequestDeadlineAndLeavesActiveIoCounted() throws Exception {
        try (var f = new Fixture(4, 4096, 0, 500)) {
            Caller caller = f.start("a");
            until(() -> f.reader.state().syncOverflowWaiters() == 1 || caller.result().isDone());
            assertFalse(caller.result().isDone());
            var failure = assertInstanceOf(JedisConnectionException.class, error(caller));
            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertEquals(0, f.reader.state().syncOverflowWaiters());
            assertEquals(0, f.reader.state().syncOverflowBytes());
            assertEquals(0, f.reader.state().syncDirect());
            assertEquals(1, f.reader.state().inFlight());
            assertEquals(1024, f.reader.deliveryMemory().reservedBytes());
            assertFalse(f.reader.physicalCompletion(f.initial).isDone());
            assertEquals(1, f.client.calls.get());
        }
    }

    @Test void interruptingMiddleWaiterUnlinksItsExactBytesAndPreservesRemainingFifoOrder() throws Exception {
        try (var f = new Fixture(4, 4096, 0, 5000)) {
            Caller a = f.start("a");
            until(() -> f.reader.state().syncOverflowWaiters() == 1);
            Caller b = f.start("b");
            until(() -> f.reader.state().syncOverflowWaiters() == 2);
            Caller c = f.start("c");
            until(() -> f.reader.state().syncOverflowWaiters() == 3);
            b.thread().interrupt();
            var failure = assertInstanceOf(JedisConnectionException.class, error(b));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertTrue(b.interrupted().get(), "Cancellation must preserve the caller's interrupt");
            assertEquals(2, f.reader.state().syncOverflowWaiters());
            assertEquals(2 * SHORT_KEYS_BYTES, f.reader.state().syncOverflowBytes());
            assertEquals(1, f.client.calls.get());
            f.client.release();
            a.result().get(2, TimeUnit.SECONDS);
            c.result().get(2, TimeUnit.SECONDS);
            assertSame(a.thread(), f.client.threads.get(1));
            assertSame(c.thread(), f.client.threads.get(2));
            assertEquals(3, f.client.calls.get());
        }
    }

    @Test void cancellingCallerFutureWithInterruptDetachesOnlyThatOverflowWaiter() throws Exception {
        try (var f = new Fixture(4, 4096, 0, 5000);
                var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = callers.submit(() -> f.reader.readSync("a", "t", false));
            until(() -> f.reader.state().syncOverflowWaiters() == 1);
            assertTrue(future.cancel(true));
            until(() -> f.reader.state().syncOverflowWaiters() == 0);
            assertEquals(0, f.reader.state().syncOverflowBytes());
            assertEquals(0, f.reader.state().syncDirect());
            assertEquals(1, f.reader.state().inFlight());
            assertEquals(1024, f.reader.deliveryMemory().reservedBytes());
            assertEquals(1, f.client.calls.get());
            Caller other = f.start("b");
            until(() -> f.reader.state().syncOverflowWaiters() == 1);
            f.client.release();
            other.result().get(2, TimeUnit.SECONDS);
            assertEquals(2, f.client.calls.get());
        }
    }

    @Test void laterSynchronousArrivalsCannotBargeAheadOfTheOverflowHead() throws Exception {
        var secondEntered = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        try (var f = new Fixture(8, 8192, 0, 5000)) {
            try {
                f.client.beforeReply = call -> {
                    if (call == 2) { secondEntered.countDown(); await(releaseSecond); }
                };
                Caller a = f.start("a");
                until(() -> f.reader.state().syncOverflowWaiters() == 1);
                Caller b = f.start("b");
                until(() -> f.reader.state().syncOverflowWaiters() == 2);
                f.client.release();
                assertTrue(secondEntered.await(2, TimeUnit.SECONDS));
                Caller c = f.start("c");
                until(() -> f.reader.state().syncOverflowWaiters() == 2);
                assertEquals(2, f.client.calls.get());
                releaseSecond.countDown();
                for (Caller caller : List.of(a, b, c)) caller.result().get(2, TimeUnit.SECONDS);
                assertSame(a.thread(), f.client.threads.get(1));
                assertSame(b.thread(), f.client.threads.get(2));
                assertSame(c.thread(), f.client.threads.get(3));
                assertEquals(0, f.reader.state().syncOverflowBytes());
            } finally { releaseSecond.countDown(); }
        }
    }

    @Test void existingNormalFifoDrainsBeforeOverflowCreditAndKeepsItsOriginalBounds() throws Exception {
        var queuedEntered = new CountDownLatch(1);
        var releaseQueued = new CountDownLatch(1);
        try (var f = new Fixture(8, 8192, 1, 5000)) {
            try {
                f.client.beforeReply = call -> {
                    if (call == 2) { queuedEntered.countDown(); await(releaseQueued); }
                };
                var queued = f.reader.readAsync("q", "t", false);
                Caller overflow = f.start("a");
                until(() -> f.reader.state().syncOverflowWaiters() == 1);
                assertEquals(1, f.reader.state().queued());
                assertEquals(SHORT_KEYS_BYTES, f.reader.state().queuedBytes());
                assertEquals(SHORT_KEYS_BYTES, f.reader.state().syncOverflowBytes());
                f.client.release();
                assertTrue(queuedEntered.await(2, TimeUnit.SECONDS));
                assertEquals(0, f.reader.state().syncDirect());
                assertFalse(overflow.result().isDone());
                assertEquals("q", f.client.executions.get(1).getFirst().keys().getFirst());
                releaseQueued.countDown();
                queued.get(2, TimeUnit.SECONDS);
                overflow.result().get(2, TimeUnit.SECONDS);
                assertSame(overflow.thread(), f.client.threads.get(2));
                assertEquals(0, f.reader.state().queuedBytes());
                assertEquals(0, f.reader.state().syncOverflowBytes());
            } finally { releaseQueued.countDown(); }
        }
    }

    @Test void closeWakesEveryOverflowCallerButDoesNotReleaseTheBlockedPhysicalReply() throws Exception {
        try (var f = new Fixture(8, 8192, 0, 5000)) {
            Caller a = f.start("a");
            Caller b = f.start("b");
            Caller c = f.start("c");
            until(() -> f.reader.state().syncOverflowWaiters() == 3);
            long start = System.nanoTime();
            f.reader.close();
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(4));
            for (Caller caller : List.of(a, b, c))
                assertInstanceOf(RejectedExecutionException.class, error(caller));
            assertEquals(0, f.reader.state().syncOverflowWaiters());
            assertEquals(0, f.reader.state().syncOverflowBytes());
            assertEquals(0, f.reader.state().syncDirect());
            assertEquals(1, f.reader.state().inFlight());
            assertEquals(1024, f.reader.deliveryMemory().reservedBytes());
            assertFalse(f.reader.physicalCompletion(f.initial).isDone());
            assertEquals(1, f.client.calls.get());
        }
    }

    @Test void callbackReentryNeverWaitsBehindItsOwnReplyOrAnExistingOverflowCaller() throws Exception {
        var entered = new CountDownLatch(1);
        var attempt = new CountDownLatch(1);
        var releaseCallback = new CountDownLatch(1);
        try (var f = new Fixture(8, 8192, 0, 5000)) {
            try {
                f.client.put("initial", "x".repeat(1024));
                var nested = new CompletableFuture<RejectedExecutionException>();
                var callback = f.initial.thenRun(() -> {
                    entered.countDown();
                    await(attempt);
                    nested.complete(assertThrows(RejectedExecutionException.class,
                            () -> f.reader.readSync("nested", "t", false)));
                    await(releaseCallback);
                });
                f.client.release();
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(0, f.reader.state().inFlight());
                Caller normal = f.start("a");
                until(() -> f.reader.state().syncOverflowWaiters() == 1);
                attempt.countDown();
                assertTrue(nested.get(2, TimeUnit.SECONDS).getMessage().contains("callback"));
                assertEquals(1, f.reader.state().syncOverflowWaiters());
                assertEquals(1, f.client.calls.get());
                assertEquals(1024, f.reader.deliveryMemory().reservedBytes());
                assertFalse(normal.result().isDone());
                releaseCallback.countDown();
                callback.get(2, TimeUnit.SECONDS);
                normal.result().get(2, TimeUnit.SECONDS);
                until(() -> f.reader.state().syncOverflowWaiters() == 0 && f.reader.deliveryMemory().reservedBytes() == 0);
            } finally { attempt.countDown(); releaseCallback.countDown(); }
        }
    }

    @Test void overflowLimitsAreExplicitHardBoundsWithoutAddingPublicConfigurationComponents() {
        assertEquals(8192, RedisReadCoalescer.HARD_SYNC_OVERFLOW_WAITERS);
        assertEquals(16L * 1024 * 1024, RedisReadCoalescer.HARD_SYNC_OVERFLOW_BYTES);
        var client = new RedisReadFakeClient();
        var config = RedisReadCoalescingConfig.defaults();
        assertThrows(IllegalArgumentException.class, () -> new RedisReadCoalescer(client, config, 32, 8193, 1));
        assertThrows(IllegalArgumentException.class, () -> new RedisReadCoalescer(client, config, 32, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new RedisReadCoalescer(client, config, 32, 1, 16L * 1024 * 1024 + 1));
        assertThrows(IllegalArgumentException.class, () -> new RedisReadCoalescer(client, config, 32, 1, 0));
        try (var runtime = new RedisReadRuntime(client, config, 32)) {
            assertEquals(new RedisReadRuntime.SyncOverflowWaits(0, 0, 8192, 16L * 1024 * 1024),
                    runtime.syncOverflowWaits());
        }
    }
}
