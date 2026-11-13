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

class RedisReadDebatchAdmissionTest {
    private static final long ROW_CHARGE = 512 + 2 * ("a".length() + "ta".length());

    private static final class Fixture implements AutoCloseable {
        final RedisReadFakeClient client = new RedisReadFakeClient();
        final CountDownLatch budgetEntered = new CountDownLatch(1), budgetRelease = new CountDownLatch(1);
        final CountDownLatch retryEntered = new CountDownLatch(1), retryRelease = new CountDownLatch(1);
        final RedisReadCoalescer reader;
        final CompletableFuture<RedisPointRead> initial, a, b;

        Fixture(int capacity, long bytes, long timeout) throws Exception {
            client.put("a", "a".repeat(700));
            client.put("b", "b".repeat(700));
            client.hold(1);
            client.beforeReply = call -> {
                if (call == 2) { budgetEntered.countDown(); await(budgetRelease); }
                if (call == 3) { retryEntered.countDown(); await(retryRelease); }
            };
            reader = new RedisReadCoalescer(client, RedisReadCoalescingConfig.builder().enabled(true)
                    .maxInFlightBatches(1).maxQueuedRequests(capacity).maxQueuedBytes(bytes)
                    .maxAsyncDirect(0).maxReplyBytes(1024).maxLiveReplyBytes(2048)
                    .timeoutMillis(timeout).build(), 32);
            initial = reader.readAsync("initial", "t0", false);
            assertTrue(client.started.await(2, TimeUnit.SECONDS));
            a = reader.readAsync("a", "ta", false);
            b = reader.readAsync("b", "tb", false);
            assertEquals(2, reader.state().queued());
            client.release();
            assertTrue(budgetEntered.await(2, TimeUnit.SECONDS));
            initial.get(2, TimeUnit.SECONDS);
        }

        void retry() throws Exception {
            budgetRelease.countDown();
            assertTrue(retryEntered.await(2, TimeUnit.SECONDS));
        }

        void rejected() throws Exception {
            var future = reader.readAsync("c", "tc", false);
            assertInstanceOf(RejectedExecutionException.class,
                    assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS)).getCause());
        }

        @Override public void close() {
            client.release(); budgetRelease.countDown(); retryRelease.countDown(); reader.close();
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(8, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
    }

    @Test void selectedBatchKeepsCountAndBytesBeforeItsBudgetFailure() throws Exception {
        try (var f = new Fixture(2, 2 * ROW_CHARGE, 5000)) {
            assertEquals(1, f.reader.state().inFlight());
            assertEquals(2, f.reader.state().queued(), "Selection must not release retry admission");
            assertEquals(2 * ROW_CHARGE, f.reader.state().queuedBytes());
            f.rejected();
            f.retry();
            assertEquals(2, f.reader.state().queued());
            assertEquals(2 * ROW_CHARGE, f.reader.state().queuedBytes());
            assertEquals(1, f.reader.state().pendingContinuations());
            f.rejected();
        }
    }

    @Test void byteLimitAloneAppliesToSelectedAndDebatchedWaiters() throws Exception {
        try (var f = new Fixture(4, 2 * ROW_CHARGE, 5000)) {
            f.rejected();
            f.retry();
            assertEquals(2, f.reader.state().queued());
            assertEquals(2 * ROW_CHARGE, f.reader.state().queuedBytes());
            f.rejected();
        }
    }

    @Test void cancellingWaitingRetryRemovesExactlyItsOriginalAdmission() throws Exception {
        try (var f = new Fixture(2, 2 * ROW_CHARGE, 5000)) {
            f.retry();
            assertFalse(f.reader.physicalCompletion(f.b).isDone());
            assertTrue(f.b.cancel(false));
            assertTrue(f.reader.physicalCompletion(f.b).isDone());
            assertTrue(f.b.cancel(false), "CompletableFuture cancellation remains idempotent");
            assertTrue(f.b.isCancelled());
            assertEquals(1, f.reader.state().queued());
            assertEquals(ROW_CHARGE, f.reader.state().queuedBytes());
            assertEquals(0, f.reader.state().pendingContinuations());
            f.retryRelease.countDown();
            assertEquals("a".repeat(700), f.a.get(2, TimeUnit.SECONDS).payload());
            until(() -> f.reader.state().queued() == 0 && f.reader.state().asyncAdmitted() == 0);
            assertEquals(0, f.reader.state().queuedBytes());
            assertEquals(3, f.client.calls.get(), "A detached retry must never perform I/O");
        }
    }

    @Test void activeRetryCancellationDoesNotForgePhysicalOrQueueCreditRelease() throws Exception {
        try (var f = new Fixture(2, 2 * ROW_CHARGE, 5000)) {
            f.retry();
            assertTrue(f.a.cancel(false));
            assertFalse(f.reader.physicalCompletion(f.a).isDone());
            assertEquals(2, f.reader.state().queued());
            assertEquals(2 * ROW_CHARGE, f.reader.state().queuedBytes());
            assertEquals(1, f.reader.state().inFlight());
            assertTrue(f.b.cancel(false));
            assertEquals(1, f.reader.state().queued());
            assertEquals(ROW_CHARGE, f.reader.state().queuedBytes());
            f.retryRelease.countDown();
            until(() -> f.reader.physicalCompletion(f.a).isDone()
                    && f.reader.state().asyncAdmitted() == 0);
            assertEquals(0, f.reader.state().queued());
            assertEquals(0, f.reader.state().queuedBytes());
            assertEquals(3, f.client.calls.get());
        }
    }

    @Test void timeoutDetachesWaitingRetryButKeepsBlockedRetryCharged() throws Exception {
        try (var f = new Fixture(2, 2 * ROW_CHARGE, 1200)) {
            f.retry();
            for (var read : java.util.List.of(f.a, f.b))
                assertInstanceOf(JedisConnectionException.class,
                        assertThrows(ExecutionException.class, () -> read.get(3, TimeUnit.SECONDS)).getCause());
            assertFalse(f.reader.physicalCompletion(f.a).isDone());
            assertTrue(f.reader.physicalCompletion(f.b).isDone());
            assertEquals(1, f.reader.state().queued());
            assertEquals(ROW_CHARGE, f.reader.state().queuedBytes());
            assertEquals(1, f.reader.state().inFlight());
            f.retryRelease.countDown();
            until(() -> f.reader.state().asyncAdmitted() == 0 && f.reader.state().queued() == 0);
            assertEquals(0, f.reader.state().queuedBytes());
            assertEquals(3, f.client.calls.get());
        }
    }

    @Test void closeRejectsWaitingRetryWithoutResettingLiveRetryCounters() throws Exception {
        try (var f = new Fixture(2, 2 * ROW_CHARGE, 10000)) {
            f.retry();
            long start = System.nanoTime();
            f.reader.close();
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(4));
            assertInstanceOf(RejectedExecutionException.class,
                    assertThrows(ExecutionException.class, () -> f.b.get(2, TimeUnit.SECONDS)).getCause());
            assertTrue(f.reader.physicalCompletion(f.b).isDone());
            assertFalse(f.reader.physicalCompletion(f.a).isDone());
            assertEquals(1, f.reader.state().queued());
            assertEquals(ROW_CHARGE, f.reader.state().queuedBytes());
            assertEquals(1, f.reader.state().inFlight());
            f.retryRelease.countDown();
            f.a.get(2, TimeUnit.SECONDS);
            until(() -> f.reader.state().queued() == 0 && f.reader.deliveryMemory().reservedBytes() == 0);
            assertEquals(0, f.reader.state().queuedBytes());
            assertEquals(3, f.client.calls.get());
        }
    }

    @Test void cancellationBeforeBudgetResultCannotReinsertOrDoubleReleaseAdmission() throws Exception {
        try (var f = new Fixture(2, 2 * ROW_CHARGE, 5000)) {
            assertTrue(f.b.cancel(false));
            assertFalse(f.reader.physicalCompletion(f.b).isDone());
            assertEquals(2, f.reader.state().queued());
            f.retry();
            until(() -> f.reader.physicalCompletion(f.b).isDone());
            assertEquals(1, f.reader.state().queued());
            assertEquals(ROW_CHARGE, f.reader.state().queuedBytes());
            f.retryRelease.countDown();
            f.a.get(2, TimeUnit.SECONDS);
            until(() -> f.reader.state().asyncAdmitted() == 0);
            assertEquals(0, f.reader.state().queuedBytes());
            assertEquals(3, f.client.calls.get());
        }
    }

    @Test void fiveHundredTwelveIdentityBudgetFailureHasAtMostFiveHundredTwelveSingletonRetries() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        try (var reader = new RedisReadCoalescer(client, RedisReadCoalescingConfig.builder().enabled(true)
                .maxInFlightBatches(1).maxBatchKeys(512).maxQueuedRequests(512).maxAsyncDirect(0)
                .maxReplyBytes(1024).maxLiveReplyBytes(2048).timeoutMillis(10000).build(), 32)) {
            try {
                var initial = reader.readAsync("initial", "t0", false);
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                var reads = new ArrayList<CompletableFuture<RedisPointRead>>();
                for (int i = 0; i < 512; i++) {
                    client.put("a" + i, "x".repeat(300));
                    reads.add(reader.readAsync("a" + i, "t" + i, false));
                }
                assertEquals(512, reader.state().queued());
                client.release();
                initial.get(2, TimeUnit.SECONDS);
                for (var read : reads) assertEquals(300, read.get(8, TimeUnit.SECONDS).payload().length());
                until(() -> reader.state().asyncAdmitted() == 0 && reader.state().queued() == 0);
                assertEquals(514, client.calls.get());
                assertEquals(1024, client.executions.get(1).getFirst().keys().size());
                for (var execution : client.executions.subList(2, client.executions.size()))
                    assertEquals(2, execution.getFirst().keys().size(), "Retries cannot merge again");
                assertEquals(0, reader.state().queuedBytes());
                assertEquals(0, reader.state().pendingContinuations());
                assertTrue(client.peak.get() <= 1);
            } finally { client.release(); }
        }
    }
}
