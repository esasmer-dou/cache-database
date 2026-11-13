package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import static com.reactor.cachedb.redis.RedisReadCoalescerTest.until;
import com.reactor.cachedb.core.config.RedisReadCoalescingConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import redis.clients.jedis.exceptions.JedisConnectionException;

@Timeout(40)
class RedisReadDisabledAsyncFifoTest {
    private static RedisReadCoalescingConfig.Builder disabled() {
        return RedisReadCoalescingConfig.builder().enabled(false).timeoutMillis(30_000);
    }

    @Test void thousandAsyncReadsQueueBehindSixteenRawSlotsWithoutMergingOrDirectOverflow() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(16);
        try (var reader = new RedisReadCoalescer(client, disabled().build(), 32)) {
            try {
                var reads = new ArrayList<CompletableFuture<RedisPointRead>>(1000);
                for (int i = 0; i < 1000; i++) {
                    client.put("e" + i, "row" + i);
                    reads.add(reader.readAsync("e" + i, "t" + i, true));
                }
                assertTrue(client.started.await(3, TimeUnit.SECONDS));
                assertEquals(16, client.calls.get());
                assertEquals(16, reader.state().inFlight());
                assertEquals(984, reader.state().queued());
                assertEquals(0, reader.state().asyncDirect());
                assertEquals(1000, reader.state().asyncAdmitted());
                client.release();
                for (int i = 0; i < reads.size(); i++)
                    assertEquals("row" + i, reads.get(i).get(10, TimeUnit.SECONDS).payload());
                until(() -> reader.state().asyncAdmitted() == 0 && reader.state().inFlight() == 0);
                assertTrue(client.peak.get() <= 16);
                assertEquals(1000, client.calls.get(), "Disabled merging must perform one logical point call per request");
                assertEquals(0, reader.metrics().coalescedRequests());
                assertEquals(1.0, reader.metrics().averageBatchSize());
                assertEquals(1000, reader.metrics().bypassed());
                for (var call : client.executions) {
                    assertEquals(1, call.size());
                    assertEquals(2, call.getFirst().keys().size(), "Never merge entity identities when disabled");
                }
            } finally { client.release(); }
        }
    }

    @Test void disabledQueueIsFifoAndCannotBeBypassedByAsyncDirectOverflow() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        try (var reader = new RedisReadCoalescer(client,
                disabled().maxInFlightBatches(1).maxQueuedRequests(3).maxAsyncDirect(1024).build(), 32)) {
            try {
                var reads = new ArrayList<CompletableFuture<RedisPointRead>>();
                for (int i = 0; i < 4; i++) reads.add(reader.readAsync("e" + i, "t" + i, false));
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                var rejected = reader.readAsync("must-not-barge", "t", false);
                assertInstanceOf(RejectedExecutionException.class,
                        assertThrows(ExecutionException.class, rejected::get).getCause());
                assertEquals(1, client.calls.get());
                assertEquals(0, reader.state().asyncDirect());
                client.release();
                for (var read : reads) read.get(5, TimeUnit.SECONDS);
                assertEquals(List.of("e0", "e1", "e2", "e3"), client.executions.stream()
                        .map(call -> call.getFirst().keys().getFirst()).toList());
                assertEquals(4, client.calls.get());
            } finally { client.release(); }
        }
    }

    @Test void cancelledQueueEntryRestoresBytesAndSingletonFailureDoesNotPoisonFollowingReads() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        var failure = new JedisConnectionException("one disabled singleton failure");
        client.failures.put(2, failure);
        try (var reader = new RedisReadCoalescer(client, disabled().maxInFlightBatches(1).build(), 32)) {
            try {
                var first = reader.readAsync("a", "ta", false);
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                var cancelled = reader.readAsync("b", "tb", true);
                var failed = reader.readAsync("c", "tc", true);
                var after = reader.readAsync("d", "td", false);
                long before = reader.state().queuedBytes();
                assertTrue(cancelled.cancel(true));
                assertEquals(before - (512 + 2 * ("b".length() + "tb".length())), reader.state().queuedBytes());
                assertTrue(reader.physicalCompletion(cancelled).isDone());
                client.release();
                first.get(3, TimeUnit.SECONDS);
                assertSame(failure, assertThrows(ExecutionException.class, failed::get).getCause());
                assertNull(after.get(3, TimeUnit.SECONDS).payload());
                assertEquals(List.of("a", "c", "d"), client.executions.stream()
                        .map(call -> call.getFirst().keys().getFirst()).toList());
                until(() -> reader.state().asyncAdmitted() == 0 && reader.state().queuedBytes() == 0);
            } finally { client.release(); }
        }
    }

    @Test void disabledQueuedAndActiveDeadlinesDoNotNeedBlockedIoToReturn() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        try (var reader = new RedisReadCoalescer(client,
                disabled().maxInFlightBatches(1).timeoutMillis(500).build(), 32)) {
            try {
                var active = reader.readAsync("a", "ta", false);
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                var queued = reader.readAsync("q", "tq", false);
                for (var result : List.of(active, queued)) {
                    Throwable problem = assertThrows(ExecutionException.class,
                            () -> result.get(2, TimeUnit.SECONDS)).getCause();
                    assertInstanceOf(JedisConnectionException.class, problem);
                    assertInstanceOf(TimeoutException.class, problem.getCause());
                }
                assertEquals(1, client.calls.get());
                assertFalse(reader.physicalCompletion(active).isDone());
                assertTrue(reader.physicalCompletion(queued).isDone());
                assertEquals(1, reader.state().inFlight());
                until(() -> reader.state().queuedBytes() == 0 && reader.state().asyncAdmitted() == 1);
                client.release();
                reader.physicalCompletion(active).get(2, TimeUnit.SECONDS);
                until(() -> reader.state().asyncAdmitted() == 0);
            } finally { client.release(); }
        }
    }

    @Test void disabledQueueByteOverflowRejectsInsteadOfBargingAndCancelRestoresCapacity() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        try (var reader = new RedisReadCoalescer(client,
                disabled().maxInFlightBatches(1).maxQueuedBytes(600).maxAsyncDirect(16).build(), 32)) {
            try {
                var active = reader.readAsync("a", "t", false);
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                var queued = reader.readAsync("b", "t", false);
                assertEquals(512L + 2L * ("b".length() + "t".length()), reader.state().queuedBytes());
                var rejected = reader.readAsync("c", "t", false);
                assertInstanceOf(RejectedExecutionException.class,
                        assertThrows(ExecutionException.class, rejected::get).getCause());
                assertEquals(1, client.calls.get());
                assertEquals(0, reader.state().asyncDirect());
                assertTrue(queued.cancel(false));
                assertEquals(0, reader.state().queuedBytes());
                var replacement = reader.readAsync("d", "t", false);
                assertEquals(512L + 2L * ("d".length() + "t".length()), reader.state().queuedBytes());
                client.release();
                active.get(3, TimeUnit.SECONDS);
                replacement.get(3, TimeUnit.SECONDS);
                assertEquals(2, client.calls.get());
            } finally { client.release(); }
        }
    }

    @Test void disabledSyncRawWrapperRemainsImmediateCallerOwnedWithoutFifoOrDeadlineAlarm() {
        var client = new RedisReadFakeClient();
        try (var reader = new RedisReadCoalescer(client, disabled().build(), 32)) {
            assertNull(reader.readSync("e", "t", false).payload());
            assertSame(Thread.currentThread(), client.threads.getFirst());
            assertEquals(1, client.calls.get());
            assertEquals(0, reader.state().queued());
            assertEquals(0, reader.state().asyncAdmitted());
            assertEquals(0, reader.state().deadlineAlarms());
        }
    }
}
