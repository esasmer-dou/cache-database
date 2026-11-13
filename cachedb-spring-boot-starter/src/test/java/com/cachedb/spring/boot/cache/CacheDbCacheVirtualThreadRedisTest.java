package com.reactor.cachedb.spring.boot.cache;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
@Timeout(60)
class CacheDbCacheVirtualThreadRedisTest {
    @Test void hundredVirtualMissesUseOneLoaderAndDoNotPinCachedbStacks() throws Exception {
        assertTrue(FlightRecorder.isAvailable(), "JFR is required to verify virtual-thread pinning");
        assertTrue(FlightRecorder.getFlightRecorder().getEventTypes().stream()
                .anyMatch(type -> type.getName().equals("jdk.VirtualThreadPinned")), "The JDK must expose the virtual-thread pinning event");
        Path jfr = Files.createTempFile("cachedb-spring-cache-pinning-", ".jfr");
        try (var fixture = CacheDbCacheRedisFixture.standalone();
             var tasks = Executors.newVirtualThreadPerTaskExecutor();
             var recording = new Recording()) {
            recording.setMaxSize(16L * 1024 * 1024);
            recording.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ofMillis(20)).withStackTrace();
            recording.start();
            var manager = fixture.manager(Map.of("cache", CacheDbCacheRedisTest.config(false, false, false)));
            var cache = manager.getCache("cache");
            CountDownLatch ready = new CountDownLatch(100), start = new CountDownLatch(1);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                results.add(tasks.submit(() -> {
                    assertTrue(Thread.currentThread().isVirtual());
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return cache.get("same-key", () -> {
                        calls.incrementAndGet();
                        entered.countDown();
                        assertTrue(release.await(15, TimeUnit.SECONDS));
                        return "one-source-result";
                    });
                }));
            }
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                CacheDbCacheRedisFixture.await(() -> manager.flights().waiterCount() == 99);
                assertEquals(1, calls.get());
            } finally { start.countDown(); release.countDown(); }
            for (var result : results) assertEquals("one-source-result", result.get(10, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            assertEquals(0, manager.flights().inFlightCount());
            assertEquals(0, manager.flights().waiterCount());
            recording.stop();
            recording.dump(jfr);
            assertTrue(Files.size(jfr) <= 16L * 1024 * 1024);
            int events = 0;
            try (var reader = new RecordingFile(jfr)) {
                while (reader.hasMoreEvents()) {
                    assertTrue(++events <= 10000, "JFR fixture event budget exceeded");
                    var event = reader.readEvent();
                    if (!event.getEventType().getName().equals("jdk.VirtualThreadPinned") || event.getStackTrace() == null) continue;
                    assertFalse(event.getStackTrace().getFrames().stream()
                            .anyMatch(frame -> frame.getMethod().getType().getName().startsWith("com.reactor.cachedb")),
                            () -> "Pinned CacheDB stack: " + event);
                }
            }
        } finally { Files.deleteIfExists(jfr); }
    }

    @Test void virtualTenantMissesNeverShareFlightsOrThreadLocalState() throws Exception {
        try (var fixture = CacheDbCacheRedisFixture.standalone();
             var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var manager = fixture.manager(Map.of("cache", CacheDbCacheRedisTest.config(true, false, false)));
            var cache = manager.getCache("cache");
            CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                String tenant = i % 2 == 0 ? "tenant-a" : "tenant-b";
                results.add(tasks.submit(() -> {
                    try (var ignored = CacheTenantContext.withTenant(tenant)) {
                        return cache.get("same-key", () -> {
                            assertEquals(tenant, CacheTenantContext.currentTenant().orElseThrow());
                            calls.incrementAndGet();
                            entered.countDown();
                            assertTrue(release.await(15, TimeUnit.SECONDS));
                            return tenant;
                        });
                    } finally { assertTrue(CacheTenantContext.currentTenant().isEmpty()); }
                }));
            }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                CacheDbCacheRedisFixture.await(() -> manager.flights().waiterCount() == 98);
            } finally { release.countDown(); }
            for (int i = 0; i < results.size(); i++)
                assertEquals(i % 2 == 0 ? "tenant-a" : "tenant-b", results.get(i).get(10, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
            assertTrue(CacheTenantContext.currentTenant().isEmpty());
        }
    }

    @Test void interruptedFollowerLeavesNoReservationAndDoesNotCancelTheOwner() throws Exception {
        try (var fixture = CacheDbCacheRedisFixture.standalone();
             var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var manager = fixture.manager(Map.of("cache", CacheDbCacheRedisTest.config(false, false, false)));
            var cache = manager.getCache("cache");
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            Future<String> owner = tasks.submit(() -> cache.get("same", () -> {
                entered.countDown();
                assertTrue(release.await(15, TimeUnit.SECONDS));
                return "owner";
            }));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            AtomicReference<Thread> follower = new AtomicReference<>();
            Future<String> waiting = tasks.submit(() -> {
                follower.set(Thread.currentThread());
                try { return cache.get("same", () -> "must-not-run"); }
                finally { assertTrue(Thread.currentThread().isInterrupted()); }
            });
            try {
                CacheDbCacheRedisFixture.await(() -> manager.flights().waiterCount() == 1);
                follower.get().interrupt();
                ExecutionException interrupted = assertThrows(ExecutionException.class, () -> waiting.get(5, TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class, interrupted.getCause());
                assertEquals(0, manager.flights().waiterCount());
            } finally { release.countDown(); }
            assertEquals("owner", owner.get(10, TimeUnit.SECONDS));
            assertEquals(0, manager.flights().inFlightCount());
        }
    }

    @Test void flightKeyAndWaiterCapsAndFollowerTimeoutRejectWithoutLeakingReservations() throws Exception {
        try (var fixture = CacheDbCacheRedisFixture.standalone();
             var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var manager = new CacheDbCacheManager(fixture.tracked, fixture.tracked, fixture.prefix,
                    Map.of("cache", CacheDbCacheRedisTest.config(false, false, false)), List.of(), 16,
                    new com.reactor.cachedb.core.config.SingleFlightConfig(true, 1, 1, 3000));
            fixture.managers.add(manager);
            var cache = manager.getCache("cache");
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            Future<String> owner = tasks.submit(() -> cache.get("same", () -> {
                entered.countDown();
                assertTrue(release.await(15, TimeUnit.SECONDS));
                return "owner-result";
            }));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            Future<String> follower = tasks.submit(() -> cache.get("same", () -> "wrong"));
            try {
                CacheDbCacheRedisFixture.await(() -> manager.flights().waiterCount() == 1);
                assertThrows(IllegalStateException.class, () -> cache.get("same", () -> "wrong"));
                assertThrows(IllegalStateException.class, () -> cache.get("other-key", () -> "wrong"));
                ExecutionException timedOut = assertThrows(ExecutionException.class, () -> follower.get(6, TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class, timedOut.getCause());
                assertEquals(0, manager.flights().waiterCount());
                assertEquals(1, manager.flights().inFlightCount());
            } finally { release.countDown(); }
            assertEquals("owner-result", owner.get(10, TimeUnit.SECONDS));
            assertEquals(0, manager.flights().inFlightCount());
            assertEquals("other-recovered", cache.get("other-key", () -> "other-recovered"));
        }
    }

    @Test void twoManagersWithTheSharedClientCoalesceOneJvmKey() throws Exception {
        try (var fixture = CacheDbCacheRedisFixture.standalone();
             var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var configs = Map.of("cache", CacheDbCacheRedisTest.config(false, false, false));
            var one = fixture.manager(configs);
            var two = fixture.manager(configs);
            assertSame(one.flights(), two.flights());
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                var cache = (i % 2 == 0 ? one : two).getCache("cache");
                results.add(tasks.submit(() -> cache.get("shared-key", () -> {
                    calls.incrementAndGet();
                    entered.countDown();
                    assertTrue(release.await(15, TimeUnit.SECONDS));
                    return "shared-result";
                })));
            }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                CacheDbCacheRedisFixture.await(() -> one.flights().waiterCount() == 99);
            } finally { release.countDown(); }
            for (var result : results) assertEquals("shared-result", result.get(10, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            assertEquals(0, two.flights().inFlightCount());
        }
    }
}
