package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import static com.reactor.cachedb.redis.RedisReadCoalescerTest.until;
import com.reactor.cachedb.core.config.RedisReadCoalescingConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.exceptions.JedisConnectionException;

class RedisReadAsyncDeadlineTest {
    private static RedisReadCoalescingConfig.Builder config() {
        return RedisReadCoalescingConfig.builder().enabled(true).maxInFlightBatches(1)
                .maxAsyncDirect(0).timeoutMillis(500);
    }
    private static void assertDeadline(CompletableFuture<?> future) {
        Throwable error = assertThrows(ExecutionException.class,
                () -> future.get(2, TimeUnit.SECONDS)).getCause();
        assertInstanceOf(JedisConnectionException.class, error);
        assertInstanceOf(TimeoutException.class, error.getCause());
    }

    @Test void blockedIoCannotPostponeActiveOrQueuedAsyncDeadlinesUntilSocketReturn() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1); // The fake socket is blocked for up to ten seconds, delivery timeout is only 500 ms.
        try (var reader = new RedisReadCoalescer(client, config().maxQueuedRequests(32).build(), 32)) {
            try {
                long started = System.nanoTime();
                var active = reader.readAsync("active", "ta", false);
                var physical = reader.physicalCompletion(active);
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                var queued = new ArrayList<CompletableFuture<RedisPointRead>>();
                for (int i = 0; i < 32; i++) queued.add(reader.readAsync("q" + i, "t" + i, false));
                assertEquals(33, reader.state().asyncAdmitted());
                assertTrue(reader.state().deadlineAlarms() <= reader.state().asyncAdmitted());
                assertDeadline(active);
                for (var future : queued) assertDeadline(future);
                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2));
                assertEquals(1, client.calls.get(), "Expired queued reads must not reach Redis");
                assertFalse(physical.isDone(), "Delivery timeout is NOT physical completion");
                assertEquals(1, reader.state().inFlight());
                until(() -> reader.state().queuedBytes() == 0 && reader.state().asyncAdmitted() == 1);
                assertEquals(0, reader.state().deadlineAlarms());
                client.release();
                physical.get(2, TimeUnit.SECONDS);
                until(() -> reader.state().inFlight() == 0 && reader.state().asyncAdmitted() == 0);
            } finally { client.release(); }
        }
    }

    @Test void blockedTimeoutCallbackNeverBlocksAlarmThreadOrOtherDeadlineDeliveries() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        var callbackEntered = new CountDownLatch(1);
        var callbackRelease = new CountDownLatch(1);
        try (var reader = new RedisReadCoalescer(client, config().maxQueuedRequests(2).build(), 32)) {
            try {
                var active = reader.readAsync("active", "ta", false);
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                var first = reader.readAsync("one", "t1", false);
                first.whenComplete((value, error) -> {
                    assertTrue(Thread.currentThread().isVirtual(), "Never run user callbacks on the timer thread");
                    callbackEntered.countDown();
                    try { assertTrue(callbackRelease.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                });
                var other = reader.readAsync("two", "t2", false);
                assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));
                assertDeadline(active);
                assertDeadline(other);
                assertFalse(reader.physicalCompletion(active).isDone());
                assertEquals(1, client.calls.get());
                until(() -> reader.state().asyncAdmitted() == 2 && reader.state().deadlineDeliveries() == 1); // Active I/O plus one blocked callback.
                assertEquals(1, reader.state().deadlineDeliveries());
                assertTrue(reader.state().deadlineDeliveries() <= reader.state().asyncAdmitted());
                assertEquals(0, reader.state().deadlineAlarms());
                callbackRelease.countDown();
                client.release();
                until(() -> reader.state().asyncAdmitted() == 0);
            } finally { callbackRelease.countDown(); client.release(); }
        }
    }

    @Test void timedOutOrCancelledActiveReadKeepsAdmissionUntilActualIoExit() throws Exception {
        for (boolean cancel : new boolean[] {false, true}) {
            var client = new RedisReadFakeClient();
            client.hold(1);
            try (var reader = new RedisReadCoalescer(client, config().maxQueuedRequests(0).build(), 32)) {
                try {
                    var active = reader.readAsync("e", "t", true);
                    assertTrue(client.started.await(2, TimeUnit.SECONDS));
                    if (cancel) assertTrue(active.cancel(true)); else assertDeadline(active);
                    assertEquals(1, reader.state().asyncAdmitted());
                    assertEquals(1, reader.state().inFlight());
                    var rejected = reader.readAsync("other", "to", false);
                    assertInstanceOf(RejectedExecutionException.class,
                            assertThrows(ExecutionException.class, rejected::get).getCause());
                    assertEquals(1, client.calls.get());
                    client.release();
                    reader.physicalCompletion(active).get(2, TimeUnit.SECONDS);
                    until(() -> reader.state().asyncAdmitted() == 0);
                    assertNull(reader.readAsync("after", "ta", false).get(2, TimeUnit.SECONDS).payload());
                } finally { client.release(); }
            }
        }
    }

    @Test void cancelledQueuedAlarmsAreRemovedExactlyRatherThanAccumulatingUntilTheirDeadline() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        try (var reader = new RedisReadCoalescer(client,
                config().timeoutMillis(5000).maxQueuedRequests(2).build(), 32)) {
            try {
                var active = reader.readAsync("active", "ta", false);
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                for (int i = 0; i < 1000; i++) {
                    var queued = reader.readAsync("q", "tq", false);
                    assertTrue(reader.state().deadlineAlarms() <= 2);
                    assertTrue(queued.cancel(false));
                    assertEquals(0, reader.state().queuedBytes());
                    assertEquals(1, reader.state().asyncAdmitted());
                    assertEquals(1, reader.state().deadlineAlarms());
                }
                client.release();
                active.get(2, TimeUnit.SECONDS);
                until(() -> reader.state().deadlineAlarms() == 0 && reader.state().asyncAdmitted() == 0);
            } finally { client.release(); }
        }
    }

    @Test void closePreservesActiveDeadlineAfterBoundedTwoSecondRawDrain() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        var reader = new RedisReadCoalescer(client, config().timeoutMillis(3000).build(), 32);
        try {
            var active = reader.readAsync("active", "ta", false);
            assertTrue(client.started.await(2, TimeUnit.SECONDS));
            assertTimeout(Duration.ofMillis(2500), reader::close);
            assertEquals(1, reader.state().inFlight());
            assertDeadline(active);
            assertFalse(reader.physicalCompletion(active).isDone());
            assertEquals(1, reader.state().asyncAdmitted());
            client.release();
            reader.physicalCompletion(active).get(2, TimeUnit.SECONDS);
            until(() -> reader.state().asyncAdmitted() == 0 && reader.state().deadlineAlarms() == 0);
        } finally { client.release(); reader.close(); }
    }

    @Test void rawPhysicalStagesAreVisibleBeforeAnEarlierInlineDataCallbackBlocks() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var reader = new RedisReadCoalescer(client, config().timeoutMillis(1000).build(), 32)) {
            try {
                var initial = reader.readAsync("initial", "t0", false);
                assertTrue(client.started.await(2, TimeUnit.SECONDS));
                var first = reader.readAsync("first", "t1", false);
                var second = reader.readAsync("second", "t2", false);
                var secondPhysical = reader.physicalCompletion(second);
                var blocked = first.whenComplete((value, error) -> {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                });
                client.release();
                initial.get(2, TimeUnit.SECONDS);
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertTrue(secondPhysical.isDone(), "Physical completion must not sit behind another data callback");
                assertDeadline(second);
                assertFalse(blocked.isDone());
                release.countDown();
                blocked.get(2, TimeUnit.SECONDS);
                until(() -> reader.state().asyncAdmitted() == 0);
            } finally { release.countDown(); client.release(); }
        }
    }
}
