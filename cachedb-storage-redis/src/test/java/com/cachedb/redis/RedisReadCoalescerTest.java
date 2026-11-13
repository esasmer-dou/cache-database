package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.config.RedisReadCoalescingConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;

class RedisReadCoalescerTest {
    static RedisReadCoalescingConfig.Builder config() {
        return RedisReadCoalescingConfig.builder().enabled(true).maxInFlightBatches(1).timeoutMillis(5000);
    }
    static void until(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(1);
        assertTrue(condition.getAsBoolean());
    }

    private record SyncRead(Thread caller, CompletableFuture<RedisPointRead> result) {}

    private static SyncRead startSyncRead(RedisReadCoalescer reader, String entity) {
        var result = new CompletableFuture<RedisPointRead>();
        Thread caller = Thread.ofVirtual().start(() -> {
            try { result.complete(reader.readSync(entity, "t", false)); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        return new SyncRead(caller, result);
    }

    private static void awaitRelease(CountDownLatch release) {
        try { assertTrue(release.await(10, TimeUnit.SECONDS), "Test transport/callback was not released"); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    @Test void sixtyFourMissesUseAtMostFivePipelinesAndMapEveryRawResult() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(4);
        try (var reader = new RedisReadCoalescer(client, config().maxInFlightBatches(4).build(), 32)) {
            var reads = new ArrayList<CompletableFuture<RedisPointRead>>();
            for (int i = 0; i < 64; i++) {
                client.put("e" + i, "row" + i);
                if (i % 3 == 0) client.put("t" + i, "deleted");
                reads.add(reader.readAsync("e" + i, "t" + i, i % 2 == 0));
            }
            assertTrue(client.started.await(2, TimeUnit.SECONDS));
            assertEquals(60, reader.state().queued());
            assertEquals(4, client.calls.get());
            client.release();
            for (int i = 0; i < reads.size(); i++) {
                RedisPointRead result = reads.get(i).get(5, TimeUnit.SECONDS);
                assertEquals("row" + i, result.payload());
                assertEquals(i % 3 == 0 ? "deleted" : null, result.tombstone());
                assertEquals(i % 2 == 0 ? -1 : -3, result.ttlMillis());
                assertTrue(result.observedAtNanos() != 0);
            }
            until(() -> reader.state().asyncAdmitted() == 0);
            assertTrue(client.calls.get() <= (64 + 511) / 512 + 4);
            assertTrue(client.peak.get() <= 4);
            assertEquals(0, reader.state().queuedBytes());
            assertEquals(64 - client.calls.get(), reader.metrics().coalescedRequests());
            assertEquals(64.0 / client.calls.get(), reader.metrics().averageBatchSize());
        } finally { client.release(); }
    }

    @Test void immediateSyncUsesItsOwnCallerAndAsyncNeverUsesApiCaller() throws Exception {
        var client = new RedisReadFakeClient();
        Thread caller = Thread.currentThread();
        try (var reader = new RedisReadCoalescer(client, config().build(), 32)) {
            assertNull(reader.readSync("e", "t", false).payload());
            assertSame(caller, client.threads.getFirst());
            reader.readAsync("e", "t", true).get(2, TimeUnit.SECONDS);
            assertNotSame(caller, client.threads.get(1));
            assertTrue(client.threads.get(1).isVirtual());
        }
    }

    @Test void queuedSyncWorkerReusesItsThreadWithoutHoldingEarlierCallersOrReplyCredit() throws Exception {
        assertQueuedSyncWorker(1024);
        assertQueuedSyncWorker(2048);
    }

    @Test void syncOnlyResultIsDeliveredBeforeReservedSuccessorStartup() throws Exception {
        assertSyncDeliveryBeforeSuccessorStartup(1024, false);
        assertSyncDeliveryBeforeSuccessorStartup(2048, false);
    }

    @Test void failedSuccessorStartupDoesNotStrandQueuedReadsOrReplyCredits() throws Exception {
        assertSyncDeliveryBeforeSuccessorStartup(2048, true);
    }

    private static void assertSyncDeliveryBeforeSuccessorStartup(long maxLiveReplyBytes,
            boolean failStartup) throws Exception {
        var client = new RedisReadFakeClient();
        var secondEntered = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        var startupEntered = new CountDownLatch(1);
        var releaseStartup = new CountDownLatch(1);
        var starts = new AtomicInteger();
        var startupFailure = new RejectedExecutionException("Controlled successor startup failure");
        client.beforeReply = call -> {
            if (call == 2) { secondEntered.countDown(); awaitRelease(releaseSecond); }
        };
        for (String key : List.of("first", "second", "successor", "last")) client.put(key, key);
        client.hold(1);
        Runnable beforeWorkerStart = () -> {
            if (starts.incrementAndGet() == 2) {
                startupEntered.countDown();
                awaitRelease(releaseStartup);
                if (failStartup) throw startupFailure;
            }
        };
        try (var reader = new RedisReadCoalescer(client, config().maxBatchKeys(1)
                .maxReplyBytes(1024).maxLiveReplyBytes(maxLiveReplyBytes).build(), 32,
                RedisReadCoalescer.HARD_SYNC_OVERFLOW_WAITERS,
                RedisReadCoalescer.HARD_SYNC_OVERFLOW_BYTES, beforeWorkerStart)) {
            try {
                var first = startSyncRead(reader, "first");
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                var second = startSyncRead(reader, "second");
                until(() -> reader.state().queued() == 1);
                client.release();
                assertTrue(secondEntered.await(2, TimeUnit.SECONDS));
                assertEquals("first", first.result().get(2, TimeUnit.SECONDS).payload());
                assertEquals(1, reader.state().queued());
                assertEquals(512L + 2L * ("second".length() + 1), reader.state().queuedBytes());
                var successor = reader.readAsync("successor", "t", false);
                // The second read retains its queue reservation until physical completion.
                until(() -> reader.state().queued() == 2);
                assertEquals(1024L + 2L * ("second".length() + "successor".length() + 2),
                        reader.state().queuedBytes());
                releaseSecond.countDown();
                assertTrue(startupEntered.await(2, TimeUnit.SECONDS));
                // The reserved successor has not started; the queued synchronous caller must still return.
                assertEquals("second", second.result().get(2, TimeUnit.SECONDS).payload());
                assertFalse(successor.isDone());
                assertEquals(2, client.calls.get());
                assertSame(first.caller(), client.threads.getFirst());
                assertNotSame(first.caller(), client.threads.get(1));
                assertNotSame(second.caller(), client.threads.get(1));
                assertTrue(client.threads.get(1).isVirtual());
                assertEquals(1, reader.state().inFlight());
                assertEquals(1, reader.state().deliveryBatches());
                assertEquals(1024, reader.state().reservedReplyBytes());
                assertEquals(1, reader.state().asyncAdmitted());
                assertEquals(1, reader.state().queued());
                assertEquals(512L + 2L * ("successor".length() + 1), reader.state().queuedBytes());
                SyncRead last = null;
                if (failStartup) {
                    last = startSyncRead(reader, "last");
                    until(() -> reader.state().queued() == 2);
                    assertEquals(1024L + 2L * ("successor".length() + "last".length() + 2),
                            reader.state().queuedBytes());
                }
                releaseStartup.countDown();
                if (failStartup) {
                    assertSame(startupFailure, assertThrows(ExecutionException.class,
                            () -> successor.get(2, TimeUnit.SECONDS)).getCause());
                    assertEquals("last", last.result().get(2, TimeUnit.SECONDS).payload());
                } else {
                    assertEquals("successor", successor.get(2, TimeUnit.SECONDS).payload());
                }
                until(() -> reader.state().inFlight() == 0 && reader.state().deliveryBatches() == 0
                        && reader.state().asyncAdmitted() == 0);
                assertEquals(3, client.calls.get());
                assertEquals(failStartup ? 3 : 2, starts.get());
                assertEquals(0, reader.state().reservedReplyBytes());
                assertEquals(0, reader.state().queued());
                assertEquals(0, reader.state().queuedBytes());
                assertEquals(0, reader.state().syncOverflowWaiters());
                assertEquals(0, reader.state().syncOverflowBytes());
            } finally {
                client.release();
                releaseSecond.countDown();
                releaseStartup.countDown();
            }
        }
    }

    private static void assertQueuedSyncWorker(long maxLiveReplyBytes) throws Exception {
        var client = new RedisReadFakeClient();
        var secondEntered = new CountDownLatch(1);
        var thirdEntered = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        var releaseThird = new CountDownLatch(1);
        client.beforeReply = call -> {
            if (call == 2) { secondEntered.countDown(); awaitRelease(releaseSecond); }
            if (call == 3) { thirdEntered.countDown(); awaitRelease(releaseThird); }
        };
        for (String key : List.of("first", "second", "third", "last")) client.put(key, key);
        client.hold(1);
        try (var reader = new RedisReadCoalescer(client, config().maxBatchKeys(1)
                .maxReplyBytes(1024).maxLiveReplyBytes(maxLiveReplyBytes).build(), 32)) {
            try {
                var first = startSyncRead(reader, "first");
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                var second = startSyncRead(reader, "second");
                until(() -> reader.state().queued() == 1);
                var third = startSyncRead(reader, "third");
                until(() -> reader.state().queued() == 2);
                client.release();
                assertTrue(secondEntered.await(2, TimeUnit.SECONDS));
                assertEquals("first", first.result().get(2, TimeUnit.SECONDS).payload());
                assertSame(first.caller(), client.threads.getFirst());
                assertNotSame(first.caller(), client.threads.get(1));
                assertTrue(client.threads.get(1).isVirtual());
                releaseSecond.countDown();
                assertTrue(thirdEntered.await(2, TimeUnit.SECONDS));
                assertEquals("second", second.result().get(2, TimeUnit.SECONDS).payload());
                assertSame(client.threads.get(1), client.threads.get(2));
                assertEquals(1, reader.state().inFlight());
                assertEquals(1, reader.state().deliveryBatches());
                assertEquals(1024, reader.state().reservedReplyBytes());

                var interrupted = startSyncRead(reader, "interrupted");
                until(() -> reader.state().queued() == 2);
                interrupted.caller().interrupt();
                assertInstanceOf(JedisConnectionException.class, assertThrows(ExecutionException.class,
                        () -> interrupted.result().get(2, TimeUnit.SECONDS)).getCause());
                assertEquals(1, reader.state().queued());
                assertEquals(1, reader.state().inFlight());
                var last = startSyncRead(reader, "last");
                until(() -> reader.state().queued() == 2);
                releaseThird.countDown();
                assertEquals("third", third.result().get(2, TimeUnit.SECONDS).payload());
                assertEquals("last", last.result().get(2, TimeUnit.SECONDS).payload());
                assertSame(client.threads.get(1), client.threads.get(3));
                until(() -> reader.state().inFlight() == 0 && reader.state().deliveryBatches() == 0);
                assertEquals(0, reader.state().queuedBytes());
                assertEquals(0, reader.state().reservedReplyBytes());
                assertEquals(4, client.calls.get());
                assertEquals(List.of("first", "second", "third", "last"), client.executions.stream()
                        .map(execution -> execution.getFirst().keys().getFirst()).toList());
                assertEquals(4, reader.metrics().batches());
                assertEquals(0, reader.metrics().coalescedRequests());
                assertEquals(0, reader.metrics().bypassed());
            } finally { client.release(); releaseSecond.countDown(); releaseThird.countDown(); }
        }
    }

    @Test void reusedSyncWorkerDoesNotRetryTransportFailureOrPoisonTheNextBatch() throws Exception {
        var client = new RedisReadFakeClient();
        var failure = new JedisConnectionException("No automatic retry");
        client.failures.put(3, failure);
        client.hold(1);
        try (var reader = new RedisReadCoalescer(client, config().maxBatchKeys(1).build(), 32)) {
            try {
                var first = startSyncRead(reader, "first");
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                var second = startSyncRead(reader, "second");
                until(() -> reader.state().queued() == 1);
                var failed = startSyncRead(reader, "failed");
                until(() -> reader.state().queued() == 2);
                var last = startSyncRead(reader, "last");
                until(() -> reader.state().queued() == 3);
                client.release();
                first.result().get(2, TimeUnit.SECONDS);
                second.result().get(2, TimeUnit.SECONDS);
                assertSame(failure, assertThrows(ExecutionException.class,
                        () -> failed.result().get(2, TimeUnit.SECONDS)).getCause());
                assertNull(last.result().get(2, TimeUnit.SECONDS).payload());
                assertSame(client.threads.get(1), client.threads.get(2));
                assertSame(client.threads.get(1), client.threads.get(3));
                until(() -> reader.state().inFlight() == 0 && reader.state().deliveryBatches() == 0);
                assertEquals(4, client.calls.get());
                assertEquals(4, reader.metrics().batches());
                assertEquals(0, reader.metrics().coalescedRequests());
                assertEquals(0, reader.metrics().bypassed());
                assertEquals(0, reader.state().queuedBytes());
                assertEquals(0, reader.state().reservedReplyBytes());
            } finally { client.release(); }
        }
    }

    @Test void mixedSuccessorAndItsSyncPeerDispatchBeforeBlockedAsyncCallback() throws Exception {
        var client = new RedisReadFakeClient();
        var callbackEntered = new CountDownLatch(1);
        var releaseCallback = new CountDownLatch(1);
        for (String key : List.of("first", "a", "b", "mixed", "async", "last")) client.put(key, key);
        client.hold(1);
        try (var reader = new RedisReadCoalescer(client, config().maxBatchKeys(2).maxReplyBytes(1024).build(), 32)) {
            try {
                var first = startSyncRead(reader, "first");
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                var a = startSyncRead(reader, "a");
                until(() -> reader.state().queued() == 1);
                var b = startSyncRead(reader, "b");
                until(() -> reader.state().queued() == 2);
                var mixed = startSyncRead(reader, "mixed");
                until(() -> reader.state().queued() == 3);
                var async = reader.readAsync("async", "t", false);
                var callback = async.thenRun(() -> { callbackEntered.countDown(); awaitRelease(releaseCallback); });
                var last = startSyncRead(reader, "last");
                until(() -> reader.state().queued() == 5);
                client.release();
                assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));
                assertEquals("first", first.result().get(2, TimeUnit.SECONDS).payload());
                assertEquals("a", a.result().get(2, TimeUnit.SECONDS).payload());
                assertEquals("b", b.result().get(2, TimeUnit.SECONDS).payload());
                assertEquals("mixed", mixed.result().get(2, TimeUnit.SECONDS).payload());
                assertEquals("last", last.result().get(2, TimeUnit.SECONDS).payload());
                assertNotSame(client.threads.get(1), client.threads.get(2));
                assertNotSame(client.threads.get(2), client.threads.get(3));
                until(() -> reader.state().inFlight() == 0 && reader.state().deliveryBatches() == 1);
                assertEquals(10, reader.state().reservedReplyBytes());
                assertEquals(1, reader.state().asyncAdmitted());
                assertEquals(0, reader.state().queuedBytes());
                assertFalse(callback.isDone());
                releaseCallback.countDown();
                callback.get(2, TimeUnit.SECONDS);
                assertEquals("async", async.get(2, TimeUnit.SECONDS).payload());
                until(() -> reader.state().asyncAdmitted() == 0 && reader.state().deliveryBatches() == 0);
                assertEquals(0, reader.state().reservedReplyBytes());
                assertEquals(4, client.calls.get());
                assertEquals(2, reader.metrics().coalescedRequests());
            } finally { client.release(); releaseCallback.countDown(); }
        }
    }

    @Test void retainedSyncWorkerKeepsPhysicalAndReplyReservationsThroughDeadlineAndClose() throws Exception {
        var client = new RedisReadFakeClient();
        var thirdEntered = new CountDownLatch(1);
        var releaseThird = new CountDownLatch(1);
        client.beforeReply = call -> {
            if (call == 3) { thirdEntered.countDown(); awaitRelease(releaseThird); }
        };
        client.hold(1);
        try (var reader = new RedisReadCoalescer(client, config().maxBatchKeys(1).maxReplyBytes(1024).build(), 32)) {
            try {
                var first = startSyncRead(reader, "first");
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                var second = startSyncRead(reader, "second");
                until(() -> reader.state().queued() == 1);
                var third = startSyncRead(reader, "third");
                until(() -> reader.state().queued() == 2);
                client.release();
                assertTrue(thirdEntered.await(2, TimeUnit.SECONDS));
                first.result().get(2, TimeUnit.SECONDS);
                second.result().get(2, TimeUnit.SECONDS);
                assertSame(client.threads.get(1), client.threads.get(2));
                var expired = startSyncRead(reader, "expired");
                until(() -> reader.state().queued() == 2);
                assertInstanceOf(JedisConnectionException.class, assertThrows(ExecutionException.class,
                        () -> third.result().get(6, TimeUnit.SECONDS)).getCause());
                assertInstanceOf(JedisConnectionException.class, assertThrows(ExecutionException.class,
                        () -> expired.result().get(2, TimeUnit.SECONDS)).getCause());
                assertEquals(1, reader.state().inFlight());
                assertEquals(512 + 2 * ("third".length() + 1), reader.state().queuedBytes());
                assertEquals(1024, reader.state().reservedReplyBytes());
                var closed = startSyncRead(reader, "closed");
                until(() -> reader.state().queued() == 2);
                assertTimeout(Duration.ofSeconds(3), reader::close);
                assertInstanceOf(RejectedExecutionException.class, assertThrows(ExecutionException.class,
                        () -> closed.result().get(2, TimeUnit.SECONDS)).getCause());
                assertEquals(1, reader.state().inFlight());
                assertEquals(1, reader.state().deliveryBatches());
                assertEquals(1024, reader.state().reservedReplyBytes());
                assertEquals(512 + 2 * ("third".length() + 1), reader.state().queuedBytes());
                assertEquals(1, client.active.get());
                assertThrows(RejectedExecutionException.class, () -> reader.readSync("new", "t", false));
                releaseThird.countDown();
                until(() -> reader.state().inFlight() == 0 && reader.state().deliveryBatches() == 0);
                assertEquals(0, reader.state().queuedBytes());
                assertEquals(0, reader.state().reservedReplyBytes());
                assertEquals(3, client.calls.get());
            } finally { client.release(); releaseThird.countDown(); }
        }
    }

    @Test void oneFailedBatchSharesTheExactExceptionWithoutPoisoningOtherBatches() throws Exception {
        var client = new RedisReadFakeClient();
        var failure = new JedisConnectionException("isolated transport error");
        client.failures.put(2, failure);
        client.hold(1);
        try (var reader = new RedisReadCoalescer(client, config().build(), 32)) {
            var first = reader.readAsync("first", "t", false);
            assertTrue(client.started.await(2, TimeUnit.SECONDS));
            var failed = List.of(reader.readAsync("a", "ta", false), reader.readAsync("b", "tb", true),
                    reader.readAsync("c", "tc", false));
            client.release();
            assertNull(first.get(2, TimeUnit.SECONDS).payload());
            for (var read : failed)
                assertSame(failure, assertThrows(ExecutionException.class, () -> read.get(2, TimeUnit.SECONDS)).getCause());
            assertNull(reader.readSync("later", "t", false).payload());
            client.failures.put(client.calls.get() + 1, failure);
            assertSame(failure, assertThrows(JedisConnectionException.class,
                    () -> reader.readSync("sync", "t", true)));
        } finally { client.release(); }
    }

    @Test void allRawSlotsAreReleasedAndDrainedBeforeNestedInlineCallbacks() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(2);
        var callbacksEntered = new CountDownLatch(2);
        try (var reader = new RedisReadCoalescer(client, config().maxInFlightBatches(2).build(), 32)) {
            var first = reader.readAsync("a", "ta", true);
            var second = reader.readAsync("b", "tb", false);
            assertTrue(client.started.await(2, TimeUnit.SECONDS));
            var queued = reader.readAsync("queued", "tq", true);
            var nested = new ArrayList<CompletableFuture<RedisPointRead>>();
            for (var read : List.of(first, second)) nested.add(read.thenApply(ignored -> {
                callbacksEntered.countDown();
                try { assertTrue(callbacksEntered.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                return reader.readSync("nested", "tn", true);
            }));
            client.release();
            for (var read : nested) assertNull(read.get(3, TimeUnit.SECONDS).payload());
            assertNull(queued.get(2, TimeUnit.SECONDS).payload());
            until(() -> reader.state().inFlight() == 0 && reader.state().asyncAdmitted() == 0);
        } finally { client.release(); }
    }

    @Test void queuedCancellationRestoresExactBytesWithoutCancellingSharedIo() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        try (var reader = new RedisReadCoalescer(client, config().maxQueuedRequests(2).build(), 32)) {
            var running = reader.readAsync("e", "t", true);
            assertTrue(client.started.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 1000; i++) {
                var queued = reader.readAsync("queued", "tombstone", true);
                assertEquals(512 + 2 * ("queued".length() + "tombstone".length()), reader.state().queuedBytes());
                assertTrue(queued.cancel(true));
                assertTrue(queued.cancel(false));
                assertEquals(0, reader.state().queued());
                assertEquals(0, reader.state().queuedBytes());
                assertEquals(1, reader.state().inFlight());
            }
            client.release();
            running.get(2, TimeUnit.SECONDS);
            until(() -> reader.state().asyncAdmitted() == 0);
        } finally { client.release(); }
    }

    @Test void activeCancellationKeepsPhysicalAdmissionUntilActualIoExits() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        try (var reader = new RedisReadCoalescer(client, config().build(), 32)) {
            try {
                var running = reader.readAsync("e", "t", true);
                var physical = reader.physicalCompletion(running);
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                assertTrue(running.cancel(true));
                assertTrue(running.isCancelled());
                assertFalse(physical.isDone());
                assertEquals(1, reader.state().inFlight());
                client.release();
                physical.get(2, TimeUnit.SECONDS);
                assertEquals(0, reader.state().inFlight());
            } finally { client.release(); }
        }
    }

    @Test void queuedCancellationFinishesPhysicalAdmissionWithoutInventingAnIoSlot() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        try (var reader = new RedisReadCoalescer(client, config().build(), 32);
                var other = new RedisReadCoalescer(new RedisReadFakeClient(), config().build(), 32)) {
            try {
                var running = reader.readAsync("e", "t", false);
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                var queued = reader.readAsync("q", "tq", false);
                var physical = reader.physicalCompletion(queued);
                assertThrows(IllegalArgumentException.class, () -> other.physicalCompletion(queued));
                assertFalse(physical.isDone());
                assertTrue(queued.cancel(false));
                assertTrue(physical.isDone());
                assertEquals(0, reader.state().queuedBytes());
                assertEquals(1, reader.state().inFlight());
                client.release();
                running.get(2, TimeUnit.SECONDS);
            } finally { client.release(); }
        }
    }

    @Test void overflowHasBoundedAsyncAdmissionButSyncRunsOnItsCaller() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(2);
        try (var reader = new RedisReadCoalescer(client,
                config().maxQueuedRequests(0).maxAsyncDirect(1).build(), 32)) {
            var first = reader.readAsync("a", "ta", true);
            var extra = reader.readAsync("b", "tb", true);
            assertTrue(client.started.await(2, TimeUnit.SECONDS));
            var rejected = reader.readAsync("c", "tc", false);
            assertInstanceOf(RejectedExecutionException.class,
                    assertThrows(ExecutionException.class, () -> rejected.get(1, TimeUnit.SECONDS)).getCause());
            assertEquals(1, reader.state().asyncDirect());
            var syncStarted = new CountDownLatch(1);
            var sync = new CompletableFuture<RedisPointRead>();
            Thread syncCaller = Thread.ofVirtual().start(() -> {
                syncStarted.countDown();
                try { sync.complete(reader.readSync("sync", "ts", true)); }
                catch (Throwable failure) { sync.completeExceptionally(failure); }
            });
            assertTrue(syncStarted.await(2, TimeUnit.SECONDS));
            until(() -> client.calls.get() == 3);
            assertTrue(client.threads.contains(syncCaller));
            assertEquals(1, reader.state().syncDirect());
            client.release();
            first.get(2, TimeUnit.SECONDS);
            extra.get(2, TimeUnit.SECONDS);
            sync.get(2, TimeUnit.SECONDS);
            until(() -> reader.state().asyncDirect() == 0 && reader.state().syncDirect() == 0);
            assertTrue(reader.metrics().bypassed() >= 2);
        } finally { client.release(); }
    }

    @Test void queueByteOverflowCannotExceedTheRetainedByteBudget() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        try (var reader = new RedisReadCoalescer(client,
                config().maxQueuedBytes(600).maxAsyncDirect(0).build(), 32)) {
            var first = reader.readAsync("a", "t", true);
            assertTrue(client.started.await(2, TimeUnit.SECONDS));
            var queued = reader.readAsync("b", "t", true);
            assertEquals(512L + 2L * ("b".length() + "t".length()), reader.state().queuedBytes());
            var rejected = reader.readAsync("c", "t", true);
            assertInstanceOf(RejectedExecutionException.class,
                    assertThrows(ExecutionException.class, rejected::get).getCause());
            assertEquals(512L + 2L * ("b".length() + "t".length()), reader.state().queuedBytes());
            client.release();
            first.get(2, TimeUnit.SECONDS);
            queued.get(2, TimeUnit.SECONDS);
            until(() -> reader.state().queuedBytes() == 0);
        } finally { client.release(); }
    }

    @Test void blockedInlineCallbacksCannotSpawnUnlimitedCompletionWorkers() throws Exception {
        var client = new RedisReadFakeClient();
        var callbacks = new CountDownLatch(2);
        var releaseCallbacks = new CountDownLatch(1);
        try (var reader = new RedisReadCoalescer(client,
                config().maxQueuedRequests(1).maxAsyncDirect(0).build(), 32)) {
            for (int i = 0; i < 2; i++) {
                client.hold(1);
                var future = reader.readAsync("e", "t", false);
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                future.thenRun(() -> {
                    callbacks.countDown();
                    try { releaseCallbacks.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                });
                client.release();
                int expected = i + 1;
                until(() -> callbacks.getCount() == 2 - expected && reader.state().inFlight() == 0);
                // Cancelling an already completed future must not prematurely release delivery admission.
                assertFalse(future.cancel(false));
            }
            assertTrue(callbacks.await(2, TimeUnit.SECONDS));
            var third = reader.readAsync("e", "t", false);
            assertInstanceOf(RejectedExecutionException.class,
                    assertThrows(ExecutionException.class, third::get).getCause());
            assertEquals(2, client.calls.get());
            releaseCallbacks.countDown();
            until(() -> reader.state().asyncAdmitted() == 0);
        } finally { client.release(); releaseCallbacks.countDown(); }
    }

    @Test void queuedSyncTimeoutDoesNotFreeTheRunningRawSlotOrCancelAnotherRequest() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        try (var reader = new RedisReadCoalescer(client, config().timeoutMillis(200).build(), 32)) {
            var running = reader.readAsync("e", "t", false);
            assertTrue(client.started.await(2, TimeUnit.SECONDS));
            assertThrows(JedisConnectionException.class, () -> reader.readSync("timeout", "t", true));
            assertEquals(0, reader.state().queued());
            assertEquals(0, reader.state().queuedBytes());
            assertEquals(1, reader.state().inFlight());
            var other = reader.readAsync("other", "to", false);
            client.release();
            other.get(2, TimeUnit.SECONDS);
            assertInstanceOf(JedisConnectionException.class,
                    assertThrows(ExecutionException.class, running::get).getCause());
        } finally { client.release(); }
    }

    @Test void closeRejectsQueueWithinTwoSecondsWithoutFakingRawRelease() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        var reader = new RedisReadCoalescer(client, config().build(), 32);
        try {
            var active = reader.readAsync("e", "t", false);
            assertTrue(client.started.await(2, TimeUnit.SECONDS));
            var queued = reader.readAsync("q", "tq", false);
            assertTimeout(Duration.ofSeconds(3), reader::close);
            assertEquals(1, reader.state().inFlight());
            assertEquals(0, reader.state().queuedBytes());
            assertInstanceOf(RejectedExecutionException.class,
                    assertThrows(ExecutionException.class, () -> queued.get(2, TimeUnit.SECONDS)).getCause());
            assertThrows(RejectedExecutionException.class, () -> reader.readSync("new", "t", false));
            assertInstanceOf(RejectedExecutionException.class, assertThrows(ExecutionException.class,
                    () -> reader.readAsync("new", "t", false).get()).getCause());
            client.release();
            active.get(2, TimeUnit.SECONDS);
            until(() -> reader.state().inFlight() == 0 && reader.state().asyncAdmitted() == 0);
        } finally { client.release(); reader.close(); }
    }

    @Test void rawAsyncTransportDoesNotInheritCallerContextAndDoesNotInspectPayloads() throws Exception {
        var client = new RedisReadFakeClient();
        var tenant = new InheritableThreadLocal<String>();
        tenant.set("caller-tenant");
        client.context = tenant;
        client.put("global", "unclassified tenant/content stays raw");
        try (var reader = new RedisReadCoalescer(client, config().build(), 32)) {
            assertEquals("unclassified tenant/content stays raw",
                    reader.readAsync("global", "t", false).get(2, TimeUnit.SECONDS).payload());
            assertEquals(List.of("null"), client.inherited);
            assertEquals("caller-tenant", tenant.get());
            reader.readSync("global", "t", false);
            assertEquals("caller-tenant", client.inherited.get(1));
        } finally { tenant.remove(); }
    }

    @Test void physicalKeyLimitIsExplicitly1024For512Identities() {
        var client = new RedisReadFakeClient();
        var pairs = new ArrayList<RedisBoundedPointRead.KeyPair>();
        for (int i = 0; i < 512; i++) pairs.add(new RedisBoundedPointRead.KeyPair("e" + i, "t" + i, true));
        assertEquals(512, RedisBoundedPointRead.read(client, pairs, 16L * 1024 * 1024).length);
        assertEquals(1024, client.executions.getFirst().getFirst().keys().size());
        pairs.add(new RedisBoundedPointRead.KeyPair("over", "t", false));
        assertThrows(IllegalArgumentException.class, () -> RedisBoundedPointRead.read(client, pairs, 16L * 1024 * 1024));
    }

    @Test void slotSkewOneMiBRowAnd511SmallRowsDoNotReceiveStaticQuotas() {
        var client = new RedisReadFakeClient(true);
        var pairs = new ArrayList<RedisBoundedPointRead.KeyPair>();
        String large = "x".repeat(1024 * 1024);
        for (int i = 0; i < 512; i++) {
            String entity = "{slot-" + i + "}:e";
            client.put(entity, i == 0 ? large : "small");
            pairs.add(new RedisBoundedPointRead.KeyPair(entity, "{slot-" + i + "}:t", false));
        }
        RedisPointRead[] reads = RedisBoundedPointRead.read(client, pairs, 16L * 1024 * 1024);
        assertEquals(large, reads[0].payload());
        for (int i = 1; i < reads.length; i++) assertEquals("small", reads[i].payload());
        assertTrue(client.calls.get() >= 480);
        long previous = 16L * 1024 * 1024;
        for (List<RedisReadFakeClient.Eval> pipeline : client.executions) {
            assertEquals(1, pipeline.size());
            long remaining = Long.parseLong(pipeline.getFirst().args().getFirst());
            assertTrue(remaining <= previous);
            previous = remaining;
        }
    }

    @Test void mixedLargeRowsOverBatchBudgetAreDebatchedAndOnlyOversizedPointFails() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        try (var reader = new RedisReadCoalescer(client, config().maxReplyBytes(1024).build(), 32)) {
            var first = reader.readAsync("initial", "t", false);
            assertTrue(client.started.await(2, TimeUnit.SECONDS));
            var reads = new ArrayList<CompletableFuture<RedisPointRead>>();
            for (int i = 0; i < 3; i++) {
                client.put("large" + i, "x".repeat(700));
                reads.add(reader.readAsync("large" + i, "t" + i, true));
            }
            client.put("oversized", "x".repeat(1100));
            var oversized = reader.readAsync("oversized", "to", true);
            client.put("after", "valid");
            var after = reader.readAsync("after", "ta", false);
            client.release();
            first.get(2, TimeUnit.SECONDS);
            for (var read : reads) assertEquals(700, read.get(3, TimeUnit.SECONDS).payload().length());
            assertInstanceOf(JedisDataException.class, assertThrows(ExecutionException.class,
                    () -> oversized.get(3, TimeUnit.SECONDS)).getCause());
            assertEquals("valid", after.get(3, TimeUnit.SECONDS).payload());
            assertTrue(client.calls.get() <= 2 + 5); // One initial, one failed batch, at most five point retries.
            assertEquals(5, reader.metrics().bypassed());
            until(() -> reader.state().asyncAdmitted() == 0 && reader.state().inFlight() == 0);
        } finally { client.release(); }
    }

    @Test void debatchedContinuationReleasesRawSlotBeforeNestedCallbackAndDoesNotRetryIoFailures() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        client.put("large-a", "a".repeat(700));
        client.put("large-b", "b".repeat(700));
        var transportFailure = new JedisConnectionException("No automatic retry");
        client.failures.put(3, transportFailure);
        try (var reader = new RedisReadCoalescer(client, config().maxReplyBytes(1024).build(), 32)) {
            var first = reader.readAsync("initial", "t", false);
            assertTrue(client.started.await(2, TimeUnit.SECONDS));
            var failed = reader.readAsync("large-a", "ta", false);
            var second = reader.readAsync("large-b", "tb", false);
            var nested = second.thenApply(ignored -> reader.readSync("nested", "tn", true));
            client.release();
            first.get(2, TimeUnit.SECONDS);
            assertSame(transportFailure, assertThrows(ExecutionException.class,
                    () -> failed.get(3, TimeUnit.SECONDS)).getCause());
            assertNull(nested.get(3, TimeUnit.SECONDS).payload());
            assertEquals(5, client.calls.get()); // initial, budget failure, failed point, successful point, nested point.
        } finally { client.release(); }
    }

    @Test void debatchedLargeReadsContinueWhileAnEarlierInlineCallbackIsBlocked() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        var enteredCallback = new CountDownLatch(1);
        var releaseCallback = new CountDownLatch(1);
        try (var reader = new RedisReadCoalescer(client, config().maxReplyBytes(1024).build(), 32)) {
            try {
                var first = reader.readAsync("initial", "t", false);
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                client.put("large-a", "a".repeat(700));
                client.put("large-b", "b".repeat(700));
                client.put("large-c", "c".repeat(700));
                var a = reader.readAsync("large-a", "ta", false);
                var b = reader.readAsync("large-b", "tb", false);
                var c = reader.readAsync("large-c", "tc", false);
                a.thenRun(() -> {
                    enteredCallback.countDown();
                    try { assertTrue(releaseCallback.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                });
                client.release();
                first.get(2, TimeUnit.SECONDS);
                assertTrue(enteredCallback.await(2, TimeUnit.SECONDS));
                assertEquals(700, b.get(2, TimeUnit.SECONDS).payload().length());
                assertEquals(700, c.get(2, TimeUnit.SECONDS).payload().length());
                until(() -> reader.state().inFlight() == 0 && reader.state().asyncAdmitted() == 1);
                assertEquals(1, reader.state().asyncAdmitted());
                assertEquals(5, client.calls.get());
                releaseCallback.countDown();
                until(() -> reader.state().asyncAdmitted() == 0);
            } finally { client.release(); releaseCallback.countDown(); }
        }
    }

    @Test void standaloneBinaryCommandPropagatesTransportFailureWithoutRetry() {
        var client = new RedisReadFakeClient();
        var failure = new JedisDataException("transport failure");
        client.failures.put(1, failure);
        var pair = new RedisBoundedPointRead.KeyPair("e", "t", false);
        assertSame(failure, assertThrows(JedisDataException.class,
                () -> RedisBoundedPointRead.read(client, List.of(pair), 1024)));
        assertEquals(1, client.commandEvals.get());
        assertEquals(1, client.calls.get());
    }

    @Test void singlePointExactlyAtContentBudgetSucceedsButItsTombstoneCanOverflowIt() {
        var client = new RedisReadFakeClient();
        client.put("e", "x".repeat(1024));
        var pair = new RedisBoundedPointRead.KeyPair("e", "t", false);
        assertEquals(1024, RedisBoundedPointRead.read(client, List.of(pair), 1024)[0].payload().length());
        assertEquals(1, client.commandEvals.get());
        client.put("t", "x");
        assertThrows(JedisDataException.class, () -> RedisBoundedPointRead.read(client, List.of(pair), 1024));
    }

    @Test void malformedOrOverBudgetBinaryReplyFailsTheWholeBatch() {
        var client = new RedisReadFakeClient();
        client.malformed = List.of(List.of("not binary"), List.of(-3L));
        assertThrows(IllegalStateException.class,
                () -> RedisBoundedPointRead.read(client, List.of(new RedisBoundedPointRead.KeyPair("e", "t", false)), 1024));
        client.malformed = List.of(List.of(new byte[1025], new byte[0]), List.of(-3L, -3L));
        assertThrows(IllegalStateException.class,
                () -> RedisBoundedPointRead.read(client, List.of(new RedisBoundedPointRead.KeyPair("e", "t", false)), 1024));
        assertTrue(RedisBoundedPointRead.SCRIPT.indexOf("'STRLEN'") < RedisBoundedPointRead.SCRIPT.indexOf("'MGET'"));
    }

    @Test void disabledRuntimeStillUsesBoundedLuaAndAsyncOverflowAdmission() throws Exception {
        var client = new RedisReadFakeClient();
        try (var runtime = new RedisReadRuntime(client, config().enabled(false).build(), 32)) {
            runtime.readSync("e", "t", true);
            runtime.readAsync("e", "t", false).get(2, TimeUnit.SECONDS);
            assertEquals(2, runtime.metrics().bypassed());
            assertEquals(2, runtime.metrics().batches());
            assertEquals(0, runtime.metrics().coalescedRequests());
        }
    }
}
