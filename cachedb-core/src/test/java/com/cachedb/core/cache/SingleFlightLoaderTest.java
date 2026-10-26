package com.reactor.cachedb.core.cache;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.config.SingleFlightConfig;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SingleFlightLoaderTest {
    @Test void sixtyFourCallersShareExactlyOneSourceLoad() throws Exception {
        var flights = new SingleFlightLoader<String, String>(SingleFlightConfig.defaults());
        var calls = new AtomicInteger();
        var release = new CountDownLatch(1);
        var start = new CyclicBarrier(64);
        var executor = Executors.newFixedThreadPool(64);
        try {
            var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < 64; i++) futures.add(executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return flights.load("same", () -> {
                    calls.incrementAndGet();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                    return "loaded";
                });
            }));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (flights.waiterCount() < 63 && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(63, flights.waiterCount());
            release.countDown();
            for (var future : futures) assertEquals("loaded", future.get(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            assertEquals(0, flights.inFlightCount());
            assertEquals(0, flights.waiterCount());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void errorsRecursionAndTimeoutNeverRetainFinishedFlights() throws Exception {
        var flights = new SingleFlightLoader<String, String>(new SingleFlightConfig(true, 1, 2, 10));
        assertThrows(IllegalArgumentException.class, () -> flights.load("error", () -> { throw new IllegalArgumentException("failed"); }));
        assertEquals(0, flights.inFlightCount());
        assertThrows(IllegalStateException.class, () -> flights.load("recursive", () -> flights.load("recursive", () -> "wrong")));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var leader = executor.submit(() -> flights.load("busy", () -> {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                return "done";
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> flights.load("other", () -> "wrong"));
            assertThrows(IllegalStateException.class, () -> flights.load("busy", () -> "wrong"));
            assertEquals(0, flights.waiterCount());
            release.countDown();
            assertEquals("done", leader.get(2, TimeUnit.SECONDS));
            assertEquals(0, flights.inFlightCount());
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
