package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import static com.reactor.cachedb.redis.RedisReadCoalescerTest.until;
import com.reactor.cachedb.core.config.RedisReadCoalescingConfig;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.exceptions.JedisConnectionException;

class RedisReadDeliveryMemoryTest {
    private static RedisReadCoalescingConfig.Builder config() {
        return RedisReadCoalescingConfig.builder().enabled(true).maxInFlightBatches(2).maxReplyBytes(1024)
                .maxLiveReplyBytes(2048).maxAsyncDirect(0).timeoutMillis(5000);
    }
    private static CompletableFuture<RedisPointRead> block(CompletableFuture<RedisPointRead> future,
            CountDownLatch entered, CountDownLatch release) {
        return future.whenComplete((value, error) -> {
            entered.countDown();
            try { assertTrue(release.await(8, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        });
    }

    @Test void physicalSlotsReleaseButLargeBlockedCallbacksStopFurtherReplyAllocation() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(2);
        client.put("a", "a".repeat(1024));
        client.put("b", "b".repeat(1024));
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var reader = new RedisReadCoalescer(client, config().build(), 32)) {
            try {
                var a = reader.readAsync("a", "ta", false);
                var b = reader.readAsync("b", "tb", false);
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                var ca = block(a, entered, release);
                var cb = block(b, entered, release);
                var queued = new ArrayList<CompletableFuture<RedisPointRead>>();
                for (int i = 0; i < 32; i++) queued.add(reader.readAsync("q" + i, "t" + i, false));
                client.release();
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(0, reader.state().inFlight());
                assertTrue(reader.physicalCompletion(a).isDone());
                assertTrue(reader.physicalCompletion(b).isDone());
                assertEquals(2048, reader.deliveryMemory().reservedBytes());
                assertEquals(2, reader.deliveryMemory().batches());
                assertEquals(32, reader.state().queued());
                assertEquals(2, client.calls.get(), "No worker/pool waiter may bypass the memory gate");
                var sync = new CompletableFuture<RedisPointRead>();
                Thread caller = Thread.ofVirtual().start(() -> {
                    try { sync.complete(reader.readSync("sync", "ts", false)); }
                    catch (Throwable failure) { sync.completeExceptionally(failure); }
                });
                until(() -> reader.state().queued() == 33 || sync.isDone());
                assertFalse(sync.isDone(), "A normal caller queues rather than failing at memory saturation");
                assertEquals(33, reader.state().queued());
                assertEquals(2, client.calls.get(), "Queued sync must not bypass reply credit");
                assertEquals(2048, reader.deliveryMemory().reservedBytes());
                release.countDown();
                ca.get(2, TimeUnit.SECONDS);
                cb.get(2, TimeUnit.SECONDS);
                assertNull(sync.get(2, TimeUnit.SECONDS).payload());
                caller.join(2000);
                assertFalse(caller.isAlive());
                for (var future : queued) assertNull(future.get(2, TimeUnit.SECONDS).payload());
                until(() -> reader.deliveryMemory().reservedBytes() == 0 && reader.deliveryMemory().batches() == 0);
            } finally { client.release(); release.countDown(); }
        }
    }

    @Test void memoryBlockedQueueStillExpiresWithoutCallbacksOnTheTimerOrPhysicalCounterForgery() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        client.put("a", "x".repeat(1024));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var reader = new RedisReadCoalescer(client,
                config().maxInFlightBatches(1).maxLiveReplyBytes(1024).timeoutMillis(500).build(), 32)) {
            try {
                var a = reader.readAsync("a", "t", false);
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                var callback = block(a, entered, release);
                client.release();
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var queued = reader.readAsync("later", "tl", false);
                assertEquals(0, reader.state().inFlight());
                assertInstanceOf(JedisConnectionException.class, assertThrows(ExecutionException.class,
                        () -> queued.get(2, TimeUnit.SECONDS)).getCause());
                assertTrue(reader.physicalCompletion(queued).isDone());
                assertEquals(1024, reader.deliveryMemory().reservedBytes());
                assertEquals(1, client.calls.get());
                assertFalse(callback.isDone());
                assertEquals(0, reader.state().queuedBytes());
                release.countDown();
                callback.get(2, TimeUnit.SECONDS);
                until(() -> reader.deliveryMemory().reservedBytes() == 0 && reader.state().asyncAdmitted() == 0);
            } finally { client.release(); release.countDown(); }
        }
    }

    @Test void nestedSyncFailsFastAtMemorySaturationInsteadOfWaitingOnItsOwnCallback() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        client.put("large", "x".repeat(1024));
        try (var reader = new RedisReadCoalescer(client,
                config().maxInFlightBatches(1).maxLiveReplyBytes(1024).build(), 32)) {
            try {
                var first = reader.readAsync("large", "t", false);
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                var nested = first.thenApply(ignored ->
                        assertThrows(RejectedExecutionException.class, () -> reader.readSync("nested", "tn", false)));
                client.release();
                assertTrue(nested.get(2, TimeUnit.SECONDS).getMessage().contains("memory"));
                assertEquals(1, client.calls.get());
                until(() -> reader.deliveryMemory().reservedBytes() == 0);
                assertNull(reader.readSync("after", "ta", false).payload());
            } finally { client.release(); }
        }
    }

    @Test void directAsyncAndCallerOwnedSyncCannotBypassAWholeReplyReservation() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        try (var reader = new RedisReadCoalescer(client,
                config().maxQueuedRequests(0).maxAsyncDirect(1024).maxLiveReplyBytes(1024).build(), 32)) {
            try {
                var first = reader.readAsync("first", "t", false);
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                assertEquals(1024, reader.deliveryMemory().reservedBytes(), "Reserve before STRLEN/GET");
                var rejected = reader.readAsync("overflow", "to", false);
                assertInstanceOf(RejectedExecutionException.class,
                        assertThrows(ExecutionException.class, rejected::get).getCause());
                var sync = new CompletableFuture<RedisPointRead>();
                Thread caller = Thread.ofVirtual().start(() -> {
                    try { sync.complete(reader.readSync("sync", "ts", false)); }
                    catch (Throwable failure) { sync.completeExceptionally(failure); }
                });
                until(() -> reader.state().syncOverflowWaiters() == 1 || sync.isDone());
                assertFalse(sync.isDone());
                assertEquals(1, reader.state().syncOverflowWaiters());
                assertEquals(0, reader.state().syncDirect());
                assertEquals(1024, reader.deliveryMemory().reservedBytes());
                assertEquals(1, client.calls.get());
                client.release();
                first.get(2, TimeUnit.SECONDS);
                assertNull(sync.get(2, TimeUnit.SECONDS).payload());
                caller.join(2000);
                assertFalse(caller.isAlive());
                until(() -> reader.deliveryMemory().reservedBytes() == 0 && reader.state().syncOverflowWaiters() == 0);
                assertEquals(0, reader.state().syncOverflowBytes());
            } finally { client.release(); }
        }
    }

    @Test void debatchingRetainsOnlyLiveRepliesAndCancelledDeferredIdentityHasNoPhysicalIo() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        for (String key : new String[] {"a", "b", "c"}) client.put(key, "x".repeat(700));
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var reader = new RedisReadCoalescer(client, config().maxInFlightBatches(1).build(), 32)) {
            try {
                var initial = reader.readAsync("initial", "t0", false);
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                var a = reader.readAsync("a", "ta", false);
                var b = reader.readAsync("b", "tb", false);
                var c = reader.readAsync("c", "tc", false);
                var ca = block(a, entered, release);
                var cb = block(b, entered, release);
                client.release();
                initial.get(2, TimeUnit.SECONDS);
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(1400, reader.deliveryMemory().reservedBytes());
                assertEquals(0, reader.state().inFlight());
                assertEquals(1, reader.state().pendingContinuations());
                assertEquals(4, client.calls.get());
                assertFalse(reader.physicalCompletion(c).isDone());
                assertTrue(c.cancel(false));
                assertTrue(reader.physicalCompletion(c).isDone());
                release.countDown();
                ca.get(2, TimeUnit.SECONDS);
                cb.get(2, TimeUnit.SECONDS);
                until(() -> reader.deliveryMemory().reservedBytes() == 0 && reader.state().asyncAdmitted() == 0);
                assertEquals(4, client.calls.get(), "A cancelled continuation must never materialize a reply");
            } finally { client.release(); release.countDown(); }
        }
    }

    @Test void closeDoesNotReleaseMemoryStillOwnedByABlockedCallback() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        client.put("a", "x".repeat(1024));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var reader = new RedisReadCoalescer(client, config().build(), 32);
        try {
            var a = reader.readAsync("a", "t", false);
            assertTrue(client.started.await(2, TimeUnit.SECONDS));
            var callback = block(a, entered, release);
            client.release();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            reader.close();
            assertEquals(0, reader.state().inFlight());
            assertEquals(1024, reader.deliveryMemory().reservedBytes());
            assertThrows(RejectedExecutionException.class, () -> reader.readSync("new", "tn", false));
            release.countDown();
            callback.get(2, TimeUnit.SECONDS);
            until(() -> reader.deliveryMemory().reservedBytes() == 0);
        } finally { client.release(); release.countDown(); reader.close(); }
    }

    @Test void byteCreditMeasuresBothPayloadAndTombstoneNotOnlyEntityCount() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        client.put("a", "x".repeat(600));
        client.put("ta", "y".repeat(400));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var reader = new RedisReadCoalescer(client, config().build(), 32)) {
            try {
                var read = reader.readAsync("a", "ta", false);
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                var callback = block(read, entered, release);
                client.release();
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(1000, reader.deliveryMemory().reservedBytes());
                release.countDown();
                callback.get(2, TimeUnit.SECONDS);
                until(() -> reader.deliveryMemory().reservedBytes() == 0);
            } finally { client.release(); release.countDown(); }
        }
    }

    @Test void globalReplyBudgetIsExplicitBoundedAndIndependentOfPoolDerivedConcurrency() {
        assertEquals(256L * 1024 * 1024, RedisReadCoalescingConfig.defaults().maxLiveReplyBytes());
        assertEquals(64, RedisReadCoalescingConfig.defaults().resolveMaxInFlight(128));
        assertThrows(IllegalArgumentException.class, () -> config().maxLiveReplyBytes(1023).build());
        assertThrows(IllegalArgumentException.class, () -> config().maxLiveReplyBytes(256L * 1024 * 1024 + 1).build());
        assertThrows(IllegalArgumentException.class, () -> config().maxLiveReplyBytes(0).build());
    }
}
