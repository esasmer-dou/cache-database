package com.reactor.cachedb.spring.boot.cache;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.config.SingleFlightConfig;
import com.reactor.cachedb.redis.RedisClientOperations;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisConnectionException;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
@Timeout(45)
class CacheDbCacheZeroRttRedisTest {
    private static final Map<String, CacheDbCacheConfiguration> CONFIGS = Map.of("cache", CacheDbCacheRedisTest.slowNear(false));

    private static final class PausedRead implements RedisClientOperations {
        final CacheDbCacheRedisFixture fixture;
        final AtomicBoolean pause = new AtomicBoolean(true);
        final CountDownLatch fetched = new CountDownLatch(1), release = new CountDownLatch(1);
        PausedRead(CacheDbCacheRedisFixture fixture) { this.fixture = fixture; }
        public UnifiedJedis commands() { return fixture.tracked.commands(); }
        @Override public Object eval(String script, List<String> keys, List<String> arguments) {
            Object response = fixture.tracked.eval(script, keys, arguments);
            if (arguments.size() > 7 && arguments.get(7).equals("READ") && response instanceof List<?> rows
                    && rows.size() == 7 && rows.get(4) != null && pause.compareAndSet(true, false)) {
                fetched.countDown();
                try { assertTrue(release.await(15, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
            return response;
        }
    }
    private static CacheDbCacheManager reader(CacheDbCacheRedisFixture fixture,
            RedisClientOperations primary, RedisClientOperations subscription) {
        var manager = new CacheDbCacheManager(primary, subscription, fixture.prefix, CONFIGS, List.of(),
                256, new SingleFlightConfig(true, 256, 4096, 10000));
        fixture.managers.add(manager);
        return manager;
    }
    private static void warm(CacheDbCache cache, String expected) {
        long before = cache.statistics().nearHits();
        CacheDbCacheRedisFixture.await(() -> {
            assertEquals(expected, cache.get("key", String.class));
            return cache.statistics().nearHits() > before;
        });
    }

    @Test void realPeerKeyNotificationRejectsAReplyFetchedBeforeTheWrite() throws Exception {
        try (var fixture = CacheDbCacheRedisFixture.standalone();
             var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = fixture.manager(CONFIGS);
            CacheDbCacheRedisFixture.await(writer::invalidationConnected);
            writer.getCache("cache").put("key", "old");
            var primary = new PausedRead(fixture);
            var peer = reader(fixture, primary, fixture.tracked);
            CacheDbCacheRedisFixture.await(peer::invalidationConnected);
            var cache = peer.getCache("cache");
            var pending = tasks.submit(() -> cache.get("key", String.class));
            try {
                assertTrue(primary.fetched.await(10, TimeUnit.SECONDS));
                long received = peer.invalidationCoordinator().receivedNotifications();
                writer.getCache("cache").put("key", "new");
                CacheDbCacheRedisFixture.await(() -> peer.invalidationCoordinator().receivedNotifications() > received);
            } finally { primary.release.countDown(); }
            assertEquals("old", pending.get(10, TimeUnit.SECONDS), "The read overlapped the write; its server snapshot may be old");
            assertTrue(peer.invalidationCoordinator().rejectedFills() > 0, "The old reply must not be admitted after KEY invalidation");
            assertEquals("new", cache.get("key", String.class));
            warm(cache, "new");
            long calls = fixture.evalCalls.get(), clients = fixture.commandsCalls.get();
            for (int i = 0; i < 100; i++) assertEquals("new", cache.get("key", String.class));
            assertEquals(calls, fixture.evalCalls.get());
            assertEquals(clients, fixture.commandsCalls.get());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void disconnectAndReconnectCannotAdmitAResponseFromThePreviousConnection(boolean reconnectBeforeReply) throws Exception {
        try (var transport = new CacheDbCacheTestSubscription();
             var fixture = CacheDbCacheRedisFixture.standalone();
             var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = fixture.manager(CONFIGS);
            writer.getCache("cache").put("key", "before-disconnect");
            var primary = new PausedRead(fixture);
            var peer = reader(fixture, primary, transport.operations);
            CacheDbCacheRedisFixture.await(peer::invalidationConnected);
            var cache = peer.getCache("cache");
            var pending = tasks.submit(() -> cache.get("key", String.class));
            try {
                assertTrue(primary.fetched.await(10, TimeUnit.SECONDS));
                transport.disconnect.countDown();
                CacheDbCacheRedisFixture.await(() -> !peer.invalidationConnected());
                writer.getCache("cache").put("key", "after-disconnect"); // Notification is deliberately lost during the gap.
                if (reconnectBeforeReply) {
                    transport.allowReconnect.countDown();
                    CacheDbCacheRedisFixture.await(peer::invalidationConnected);
                }
            } finally { primary.release.countDown(); }
            assertEquals("before-disconnect", pending.get(10, TimeUnit.SECONDS));
            assertTrue(peer.invalidationCoordinator().rejectedFills() > 0);
            long calls = fixture.evalCalls.get();
            assertEquals("after-disconnect", cache.get("key", String.class));
            assertTrue(fixture.evalCalls.get() > calls, "Old connection's response must not produce a zero-I/O stale hit");
            if (!reconnectBeforeReply) {
                assertFalse(peer.invalidationConnected());
                assertEquals(0, cache.statistics().nearHits(), "Admission stays blocked until subscribe ACK");
                transport.allowReconnect.countDown();
                CacheDbCacheRedisFixture.await(peer::invalidationConnected);
            }
            warm(cache, "after-disconnect");
            long afterWarm = fixture.evalCalls.get();
            assertEquals("after-disconnect", cache.get("key", String.class));
            assertEquals(afterWarm, fixture.evalCalls.get());
        }
    }

    @Test void observedTransportFailureDisablesWarmHitsAndSourceLoadingWhileRedisIsUnavailable() {
        try (var transport = new CacheDbCacheTestSubscription();
             var fixture = CacheDbCacheRedisFixture.standalone()) {
            AtomicBoolean unavailable = new AtomicBoolean();
            RedisClientOperations primary = new RedisClientOperations() {
                public UnifiedJedis commands() { return fixture.tracked.commands(); }
                @Override public Object eval(String script, List<String> keys, List<String> arguments) {
                    if (unavailable.get()) throw new JedisConnectionException("fixture primary unavailable");
                    return fixture.tracked.eval(script, keys, arguments);
                }
            };
            var peer = reader(fixture, primary, transport.operations);
            CacheDbCacheRedisFixture.await(peer::invalidationConnected);
            var cache = peer.getCache("cache");
            cache.put("key", "warm");
            warm(cache, "warm");
            peer.invalidationCoordinator().trackingDisconnected();
            assertFalse(peer.invalidationConnected());
            unavailable.set(true);
            assertThrows(RuntimeException.class, () -> cache.get("key"));
            AtomicInteger calls = new AtomicInteger();
            assertThrows(RuntimeException.class, () -> cache.get("key", () -> {
                calls.incrementAndGet(); return "must-not-run";
            }));
            assertEquals(0, calls.get());
            unavailable.set(false);
            transport.allowReconnect.countDown();
            peer.invalidationCoordinator().trackingConnected();
            CacheDbCacheRedisFixture.await(peer::invalidationConnected);
            assertEquals("warm", cache.get("key", String.class));
        }
    }

    @Test void nativeScalarAndEmptyKeysAndGeneratedTypedCompositeKeysHaveSeparateIdentities() {
        try (var fixture = CacheDbCacheRedisFixture.standalone()) {
            var cache = fixture.manager(CONFIGS).getCache("cache");
            var generator = new CacheDbKeyGenerator();
            Object empty = generator.generate(null, null);
            Object integer = generator.generate(null, null, 1);
            Object string = generator.generate(null, null, "1");
            Object composite = generator.generate(null, null, "a|b", 1);
            Object literal = generator.generate(null, null, composite);
            cache.put(empty, "empty");
            cache.put(integer, "integer");
            cache.put(string, "string");
            cache.put(composite, "composite");
            cache.put(literal, "literal");
            assertEquals("empty", cache.get(org.springframework.cache.interceptor.SimpleKey.EMPTY, String.class));
            assertEquals("integer", cache.get(1, String.class));
            assertEquals("string", cache.get("1", String.class));
            assertEquals("composite", cache.get(composite, String.class));
            assertEquals("literal", cache.get(literal, String.class));
        }
    }

    private static final class DelayedMiss implements RedisClientOperations {
        final CacheDbCacheRedisFixture fixture;
        final AtomicInteger misses = new AtomicInteger();
        final CountDownLatch fetched = new CountDownLatch(1), release = new CountDownLatch(1);
        DelayedMiss(CacheDbCacheRedisFixture fixture) { this.fixture = fixture; }
        public UnifiedJedis commands() { return fixture.tracked.commands(); }
        @Override public Object eval(String script, List<String> keys, List<String> arguments) {
            Object result = fixture.tracked.eval(script, keys, arguments);
            if (arguments.size() > 7 && arguments.get(7).equals("READ") && result instanceof List<?> row
                    && row.size() == 7 && row.get(4) == null && misses.incrementAndGet() == 2) {
                fetched.countDown();
                try { assertTrue(release.await(15, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
            return result;
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void delayedInitialMissAfterAnotherManagersCompletedLoadNeverCallsTheSourceAgain(boolean nullResult) throws Exception {
        try (var fixture = CacheDbCacheRedisFixture.standalone();
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var primary = new DelayedMiss(fixture);
            var configs = Map.of("cache", CacheDbCacheRedisFixture.config(false, false, false,
                    java.time.Duration.ofMinutes(2), 4096));
            var limits = new SingleFlightConfig(true, 8, 8, 5000);
            var first = new CacheDbCacheManager(primary, fixture.tracked, fixture.prefix, configs, List.of(), 8, limits);
            var second = new CacheDbCacheManager(primary, fixture.tracked, fixture.prefix, configs, List.of(), 8, limits);
            fixture.managers.add(first); fixture.managers.add(second);
            var sourceStarted = new CountDownLatch(1);
            var finishSource = new CountDownLatch(1);
            var sources = new AtomicInteger();
            String expected = nullResult ? null : "one-load";
            var owner = workers.submit(() -> first.getCache("cache").get("delayed", () -> {
                sources.incrementAndGet(); sourceStarted.countDown();
                assertTrue(finishSource.await(10, TimeUnit.SECONDS)); return expected;
            }));
            java.util.concurrent.Future<String> late = null;
            try {
                assertTrue(sourceStarted.await(10, TimeUnit.SECONDS));
                late = workers.submit(() -> second.getCache("cache").get("delayed", () -> {
                    sources.incrementAndGet(); return "duplicate";
                }));
                assertTrue(primary.fetched.await(10, TimeUnit.SECONDS));
                finishSource.countDown();
                assertEquals(expected, owner.get(10, TimeUnit.SECONDS));
            } finally { finishSource.countDown(); primary.release.countDown(); }
            assertEquals(expected, late.get(10, TimeUnit.SECONDS));
            assertEquals(1, sources.get());
            assertEquals(2, primary.misses.get(), "No second owner Redis miss is introduced");
            if (nullResult) {
                assertNull(second.getCache("cache").get("delayed", () -> { sources.incrementAndGet(); return null; }));
                assertEquals(2, sources.get(), "An independent later null call must not reuse the completed handoff");
            }
        }
    }

    @Test void configuredFlightResultLimitRejectsBeforeRedisPublication() {
        try (var fixture = CacheDbCacheRedisFixture.standalone()) {
            var configs = Map.of("cache", CacheDbCacheRedisFixture.config(false, false, false,
                    java.time.Duration.ofMinutes(1), 4096));
            var manager = new CacheDbCacheManager(fixture.tracked, fixture.tracked, fixture.prefix, configs, List.of(),
                    8, new SingleFlightConfig(true, 8, 8, 5000, 4));
            fixture.managers.add(manager);
            assertThrows(IllegalArgumentException.class, () -> manager.getCache("cache").get("limit", () -> "valid"));
            assertNull(manager.getCache("cache").get("limit"), "Oversized flight result must not already have been written");
        }
    }
}
