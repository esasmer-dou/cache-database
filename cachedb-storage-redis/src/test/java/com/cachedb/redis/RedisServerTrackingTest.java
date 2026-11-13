package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.NearCachePolicy;
import com.reactor.cachedb.core.config.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;
import redis.clients.jedis.Protocol.Command;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class RedisServerTrackingTest {
    private static void await(BooleanSupplier condition, long millis) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean() && until - System.nanoTime() > 0) Thread.sleep(2);
        assertTrue(condition.getAsBoolean(), "Condition missed its bounded deadline");
    }
    private static final class Fixture implements AutoCloseable {
        final URI uri = URI.create(System.getProperty("cachedb.tracking.redis.uri", RedisTestEndpoint.uri()));
        final JedisPooled pooled = new JedisPooled(uri);
        final Jedis raw = new Jedis(uri);
        final RedisKeyStrategy keys = new RedisKeyStrategy("t2-" + UUID.randomUUID());
        final JedisPooledOperations operations = new JedisPooledOperations(pooled);
        final RedisNearCacheCoordinator coordinator;
        final RedisNearCacheCoordinator.Store store;
        Fixture(NearCacheInvalidationMode mode) throws InterruptedException { this(mode, true); }
        Fixture(NearCacheInvalidationMode mode, boolean publishLegacy) throws InterruptedException {
            var defaults = NearCacheInvalidationConfig.defaults();
            var config = new NearCacheInvalidationConfig(mode, publishLegacy,
                    defaults.maxMasters(), defaults.maxNodes(), defaults.maxNamespaces(),
                    defaults.ioTimeoutMillis(), defaults.heartbeatMillis(), defaults.healthTimeoutMillis(),
                    defaults.maxAttempts(), defaults.initialBackoffMillis(), defaults.maxBackoffMillis(), defaults.retryCooldownMillis());
            coordinator = new RedisNearCacheCoordinator(operations, keys, config);
            try {
                store = coordinator.register("rows", new NearCachePolicy(64, 10, 1048576));
                await(coordinator::connected, 10000);
            } catch (RuntimeException | InterruptedException | AssertionError failure) {
                coordinator.close(); raw.close(); pooled.close(); throw failure;
            }
        }
        String key() { return keys.entityKey("rows", "id"); }
        void seed() throws InterruptedException {
            long before = store.capture("id").segmentEpoch();
            raw.set(key(), "old");
            if (coordinator.trackingReady()) await(() -> store.capture("id").segmentEpoch() != before, 1000);
            store.put("id", "old", -1, store.capture("id"));
            assertEquals("old", store.get("id"));
        }
        List<Long> ids() { return coordinator.trackingClientIds().get("standalone"); }
        boolean replaced(List<Long> before) {
            var current = ids();
            return current != null && !current.equals(before);
        }
        @Override public void close() {
            coordinator.close();
            try { raw.del(key()); } finally { raw.close(); pooled.close(); }
        }
    }
    @Test void rawJedisWriteInvalidatesWithinOneSecondWithoutPublish() throws Exception {
        try (var f = new Fixture(NearCacheInvalidationMode.SERVER_TRACKING)) {
            assertTrue(f.coordinator.trackingReadySupplier().getAsBoolean());
            assertEquals(NearCacheInvalidationMode.SERVER_TRACKING, f.coordinator.invalidationMode());
            f.seed();
            long started = System.nanoTime();
            f.raw.set(f.key(), "new");
            await(() -> f.store.get("id") == null, 950);
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1));
            assertTrue(f.coordinator.invalidationsReceived() >= 2);
            assertEquals(0, f.pooled.getPool().getNumActive(), "Tracking must not reserve shared pool members");
            assertNotEquals(f.ids().get(0), f.ids().get(1));
        }
    }
    @Test void pubsubModeCannotObserveAnUnpublishedRawWrite() throws Exception {
        try (var f = new Fixture(NearCacheInvalidationMode.PUBSUB)) {
            f.seed(); f.raw.set(f.key(), "new"); Thread.sleep(750);
            assertEquals("old", f.store.get("id"));
            assertFalse(f.coordinator.trackingReady());
            assertTrue(f.coordinator.publishLegacyInvalidations());
        }
    }
    @Test void killingSubscriptionFlushesThenReconnectsWithFreshSubscriptionAndControlIds() throws Exception {
        try (var f = new Fixture(NearCacheInvalidationMode.SERVER_TRACKING)) {
            f.seed(); var before = f.ids();
            assertEquals(1L, f.raw.sendCommand(Command.CLIENT, "KILL", "ID", before.get(0).toString()));
            await(() -> f.store.get("id") == null, 1000);
            await(() -> f.coordinator.trackingReady() && f.coordinator.trackingReconnects() > 0
                    && f.replaced(before), 10000);
            assertNotEquals(before.get(0), f.ids().get(0)); assertNotEquals(before.get(1), f.ids().get(1));
            f.seed(); f.raw.set(f.key(), "after-reconnect"); await(() -> f.store.get("id") == null, 1000);
        }
    }
    @Test void killingControlAlsoDisablesAdmissionFlushesAndReconnects() throws Exception {
        try (var f = new Fixture(NearCacheInvalidationMode.SERVER_TRACKING)) {
            f.seed(); var before = f.ids();
            assertEquals(1L, f.raw.sendCommand(Command.CLIENT, "KILL", "ID", before.get(1).toString()));
            await(() -> f.store.get("id") == null, 1500);
            await(() -> f.coordinator.trackingReady() && f.replaced(before), 10000);
            assertTrue(f.coordinator.trackingReconnects() > 0);
        }
    }
    @Test void invalidationBetweenReadAndFillRejectsDeterministicLateFill() throws Exception {
        try (var f = new Fixture(NearCacheInvalidationMode.SERVER_TRACKING)) {
            f.seed(); f.store.invalidate("id");
            var read = new CountDownLatch(1); var resume = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            Thread worker = Thread.ofVirtual().inheritInheritableThreadLocals(false).start(() -> {
                try (var raw = new Jedis(f.uri)) {
                    var stamp = f.store.capture("id");
                    String stale = raw.get(f.key());
                    read.countDown();
                    assertTrue(resume.await(5, TimeUnit.SECONDS));
                    if (f.coordinator.connected()) f.store.put("id", stale, -1, stamp);
                } catch (Throwable error) { failure.set(error); read.countDown(); }
            });
            try {
                assertTrue(read.await(2, TimeUnit.SECONDS));
                long before = f.store.capture("id").segmentEpoch();
                f.raw.set(f.key(), "new");
                await(() -> f.store.capture("id").segmentEpoch() != before, 1000);
                resume.countDown(); assertTrue(worker.join(Duration.ofSeconds(2)));
                if (failure.get() != null) throw new AssertionError("Reader failed", failure.get());
                assertNull(f.store.get("id"), "Old reply must not be admitted after invalidation");
            } finally {
                resume.countDown(); worker.interrupt();
                assertTrue(worker.join(Duration.ofSeconds(2)));
            }
        }
    }
    @Test void publicationCanOnlyBeSuppressedDuringHealthyCompleteTracking() throws Exception {
        try (var f = new Fixture(NearCacheInvalidationMode.SERVER_TRACKING, false)) {
            assertFalse(f.coordinator.publishLegacyInvalidations());
            f.coordinator.close();
            assertFalse(f.coordinator.trackingReady());
            assertTrue(f.coordinator.publishLegacyInvalidations());
        }
    }
    @Test void dedicatedFactoryPreservesTheConfiguredConnectionAndDoesNotBorrow() throws Exception {
        URI uri = URI.create(System.getProperty("cachedb.tracking.redis.uri", RedisTestEndpoint.uri()));
        String key = "t2-factory-" + UUID.randomUUID();
        try (var pooled = new JedisPooled(uri); var raw = new Jedis(uri)) {
            raw.set(key, "configured-db");
            try {
                var operations = new JedisPooledOperations(pooled);
                var node = operations.trackingNodes(1).getFirst();
                try (var first = node.openDedicated(500); var second = node.openDedicated(500)) {
                    assertNotEquals(RedisTrackingTopology.id(first), RedisTrackingTopology.id(second));
                    assertEquals("configured-db", first.jedis().get(key));
                    assertEquals("configured-db", second.jedis().get(key));
                    assertEquals(0, pooled.getPool().getNumActive());
                }
                // AUTH/TLS/nonzero DB are exercised when the configured fixture URI enables them.
                assertEquals("configured-db", pooled.get(key));
            } finally { raw.del(key); }
        }
    }
}
