package com.reactor.cachedb.core.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

class DurabilityWaitTest {
    @Test
    void backoffAndDeadlineUseMonotonicTimeWithoutWallClockSleeps() {
        AtomicLong now = new AtomicLong(Long.MAX_VALUE - 1_000_000);
        List<Long> sleeps = new ArrayList<>();
        AtomicInteger checks = new AtomicInteger();
        assertFalse(
                DurabilityWait.await(
                        () -> {
                            checks.incrementAndGet();
                            return false;
                        },
                        Duration.ofMillis(64),
                        now::get,
                        nanos -> {
                            sleeps.add(nanos);
                            now.addAndGet(nanos);
                        }));
        assertEquals(List.of(5_000_000L, 10_000_000L, 25_000_000L, 24_000_000L), sleeps);
        assertEquals(5, checks.get());
    }

    @Test
    void acknowledgementStopsPollingWithoutAnotherWait() {
        AtomicInteger checks = new AtomicInteger();
        AtomicLong now = new AtomicLong();
        assertTrue(
                DurabilityWait.await(
                        () -> checks.incrementAndGet() == 3,
                        Duration.ofSeconds(1),
                        now::get,
                        now::addAndGet));
        assertEquals(15_000_000, now.get());
        assertEquals(3, checks.get());
    }

    @Test
    void interruptionIsPreservedAndStopsFurtherChecks() {
        AtomicInteger checks = new AtomicInteger();
        try {
            assertFalse(
                    DurabilityWait.await(
                            () -> {
                                checks.incrementAndGet();
                                return false;
                            },
                            Duration.ofSeconds(1),
                            () -> 0,
                            nanos -> {
                                throw new InterruptedException("fixture interruption");
                            }));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, checks.get());
            assertFalse(
                    DurabilityWait.await(
                            () -> {
                                fail("An interrupted thread must not poll");
                                return true;
                            },
                            Duration.ofSeconds(1),
                            () -> 0,
                            nanos -> fail("Must not wait")));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void alreadyDurableDoesNotSleepAndFailuresAreNotRetried() {
        assertTrue(
                DurabilityWait.await(
                        () -> true,
                        Duration.ofSeconds(1),
                        () -> 0,
                        nanos -> fail("Already durable must not wait")));
        IllegalStateException failure = new IllegalStateException("status unavailable");
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                DurabilityWait.await(
                                        () -> {
                                            throw failure;
                                        },
                                        Duration.ofSeconds(1),
                                        () -> 0,
                                        nanos -> fail("Status failures must propagate"))));
    }
}
