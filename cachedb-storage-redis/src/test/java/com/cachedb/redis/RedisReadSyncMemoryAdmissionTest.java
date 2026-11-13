package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import static com.reactor.cachedb.redis.RedisReadCoalescerTest.until;

import com.reactor.cachedb.core.config.RedisReadCoalescingConfig;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.exceptions.JedisConnectionException;

class RedisReadSyncMemoryAdmissionTest {
    private static CompletableFuture<RedisPointRead> sync(RedisReadCoalescer reader, String entity, String tombstone) {
        var result = new CompletableFuture<RedisPointRead>();
        Thread.ofVirtual().start(() -> {
            try { result.complete(reader.readSync(entity, tombstone, false)); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        return result;
    }

    @Test void seventeenthDefaultReadQueuesBehindSixteenWholeReplyReservationsThenSucceeds() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(16);
        try (var reader = new RedisReadCoalescer(client, RedisReadCoalescingConfig.builder().enabled(true).build(), 32)) {
            try {
                var active = new ArrayList<CompletableFuture<RedisPointRead>>();
                for (int i = 0; i < 16; i++) active.add(reader.readAsync("a" + i, "t" + i, false));
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                assertEquals(16, reader.state().inFlight());
                assertEquals(256L * 1024 * 1024, reader.deliveryMemory().reservedBytes());
                var queued = sync(reader, "q", "t");
                until(() -> reader.state().queued() == 1 || queued.isDone());
                assertFalse(queued.isDone(), "Ordinary memory pressure must use available queue admission");
                assertEquals(1, reader.state().queued());
                assertEquals(512L + 2L * ("q".length() + "t".length()), reader.state().queuedBytes());
                assertEquals(16, client.calls.get());
                client.release();
                assertNull(queued.get(3, TimeUnit.SECONDS).payload());
                for (var read : active) read.get(3, TimeUnit.SECONDS);
                until(() -> reader.state().queued() == 0 && reader.deliveryMemory().reservedBytes() == 0);
                assertTrue(client.peak.get() <= 16);
                assertEquals(0, reader.state().syncDirect());
            } finally { client.release(); }
        }
    }

    @Test void queuedNormalSyncDeadlineRemovesOnlyItsOwnChargeWhileMemoryIsStillPhysicallyOwned() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        var config = RedisReadCoalescingConfig.builder().enabled(true).maxInFlightBatches(1).maxReplyBytes(1024)
                .maxLiveReplyBytes(1024).timeoutMillis(500).build();
        try (var reader = new RedisReadCoalescer(client, config, 32)) {
            try {
                var first = reader.readAsync("a", "t", false);
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                var normal = sync(reader, "q", "t");
                until(() -> reader.state().queued() == 1 || normal.isDone());
                assertFalse(normal.isDone());
                assertInstanceOf(JedisConnectionException.class,
                        assertThrows(ExecutionException.class, () -> normal.get(2, TimeUnit.SECONDS)).getCause());
                assertEquals(0, reader.state().queued());
                assertEquals(0, reader.state().queuedBytes());
                assertEquals(1, reader.state().inFlight());
                assertEquals(1024, reader.deliveryMemory().reservedBytes());
                assertFalse(reader.physicalCompletion(first).isDone());
                client.release();
                reader.physicalCompletion(first).get(2, TimeUnit.SECONDS);
            } finally { client.release(); }
        }
    }

    @Test void physicalCompletionCallbackAlsoOwnsReplyCreditAndMustNeverWaitOnItself() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        client.put("a", "x".repeat(1024));
        try (var reader = new RedisReadCoalescer(client, RedisReadCoalescingConfig.builder().enabled(true)
                .maxInFlightBatches(1).maxReplyBytes(1024).maxLiveReplyBytes(1024).build(), 32)) {
            try {
                var first = reader.readAsync("a", "t", false);
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                var nested = reader.physicalCompletion(first).thenRun(() -> {
                    var rejected = assertThrows(RejectedExecutionException.class,
                            () -> reader.readSync("nested", "tn", false));
                    assertTrue(rejected.getMessage().contains("callback"));
                    assertEquals(0, reader.state().queued());
                });
                client.release();
                nested.get(2, TimeUnit.SECONDS);
                first.get(2, TimeUnit.SECONDS);
                until(() -> reader.deliveryMemory().reservedBytes() == 0);
                assertNull(reader.readSync("normal", "t", false).payload());
                assertEquals(2, client.calls.get());
            } finally { client.release(); }
        }
    }

    @Test void fullQueueSyncOverflowCannotAllocateReplyOrIncrementPhysicalCounterWithoutCredit() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        try (var reader = new RedisReadCoalescer(client, RedisReadCoalescingConfig.builder().enabled(true)
                .maxInFlightBatches(1).maxQueuedRequests(0).maxReplyBytes(1024)
                .maxLiveReplyBytes(1024).build(), 32)) {
            try {
                var first = reader.readAsync("a", "t", false);
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                var overflow = sync(reader, "overflow", "t");
                until(() -> reader.state().syncOverflowWaiters() == 1 || overflow.isDone());
                assertFalse(overflow.isDone(), "Only the existing caller waits; no uncredited transport starts");
                assertEquals(1, reader.state().syncOverflowWaiters());
                assertEquals(512L + 2L * ("overflow".length() + "t".length()), reader.state().syncOverflowBytes());
                assertEquals(0, reader.state().syncDirect());
                assertEquals(0, reader.state().queued());
                assertEquals(1, reader.state().inFlight());
                assertEquals(1024, reader.deliveryMemory().reservedBytes());
                assertEquals(1, client.calls.get());
                client.release();
                first.get(2, TimeUnit.SECONDS);
                assertNull(overflow.get(2, TimeUnit.SECONDS).payload());
                until(() -> reader.state().syncOverflowWaiters() == 0 && reader.state().syncDirect() == 0);
                assertEquals(0, reader.state().syncOverflowBytes());
            } finally { client.release(); }
        }
    }
}
