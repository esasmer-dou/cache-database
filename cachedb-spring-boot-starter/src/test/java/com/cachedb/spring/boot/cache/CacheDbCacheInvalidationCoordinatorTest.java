package com.reactor.cachedb.spring.boot.cache;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.NearCachePolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class CacheDbCacheInvalidationCoordinatorTest {
    private static final String HASH = "a".repeat(64);
    private static final String KEY = CacheDbCacheKeys.encode("key");
    private static CacheDbCacheStore.State state() {
        return new CacheDbCacheStore.State(new CacheDbCacheStore.Generation(UUID.randomUUID().toString(), "0"),
                UUID.randomUUID().toString());
    }

    @Test void keyAndScopeEpochsRejectLateFillAndClearDropsTheGenerationHint() {
        try (var transport = new CacheDbCacheTestSubscription();
             var coordinator = new CacheDbCacheInvalidationCoordinator(transport.operations, "test")) {
            var partition = coordinator.register("cache", CacheDbCacheRedisTest.slowNear(false));
            coordinator.freezeAndStartIfNearEnabled();
            CacheDbCacheRedisFixture.await(coordinator::connected);
            var first = coordinator.beforeFetch(partition, HASH, KEY);
            var value = state();
            assertTrue(partition.admit(first, value, "1encoded", 10000));
            assertEquals(value.generation(), coordinator.beforeFetch(partition, HASH, KEY).hint());
            var lateKey = coordinator.beforeFetch(partition, HASH, KEY);
            transport.event(coordinator.channel(), CacheDbCacheInvalidationCoordinator.message("K", "cache", HASH, KEY));
            assertNull(partition.lookup(HASH, KEY));
            assertFalse(partition.admit(lateKey, value, "1old", 10000));
            var fresh = coordinator.beforeFetch(partition, HASH, KEY);
            assertTrue(partition.admit(fresh, value, "1new", 10000));
            var lateClear = coordinator.beforeFetch(partition, HASH, KEY);
            transport.event(coordinator.channel(), CacheDbCacheInvalidationCoordinator.message("C", "cache", HASH, ""));
            assertNull(partition.lookup(HASH, KEY));
            assertFalse(partition.admit(lateClear, value, "1old", 10000));
            assertNull(coordinator.beforeFetch(partition, HASH, KEY).hint());
            assertEquals(2, coordinator.receivedNotifications());
            assertEquals(2, coordinator.rejectedFills());
        }
    }

    @Test void disconnectAndReconnectFlushBothGenerationsAndValuesAndNeverReviveAnOldTicket() {
        try (var transport = new CacheDbCacheTestSubscription();
             var coordinator = new CacheDbCacheInvalidationCoordinator(transport.operations, "test")) {
            var partition = coordinator.register("cache", CacheDbCacheRedisTest.slowNear(false));
            coordinator.freezeAndStartIfNearEnabled();
            CacheDbCacheRedisFixture.await(coordinator::connected);
            var ticket = coordinator.beforeFetch(partition, HASH, KEY);
            var value = state();
            assertTrue(partition.admit(ticket, value, "1old", 10000));
            transport.disconnect.countDown();
            CacheDbCacheRedisFixture.await(() -> !coordinator.connected());
            assertNull(partition.lookup(HASH, KEY));
            assertEquals(0, coordinator.generationScopes());
            assertFalse(partition.admit(ticket, value, "1late", 10000));
            var offline = coordinator.beforeFetch(partition, HASH, KEY);
            assertFalse(partition.admit(offline, value, "1offline", 10000));
            transport.allowReconnect.countDown();
            CacheDbCacheRedisFixture.await(coordinator::connected);
            assertFalse(partition.admit(ticket, value, "1pre-disconnect", 10000));
            assertFalse(partition.admit(offline, value, "1before-ack", 10000));
            assertNull(coordinator.beforeFetch(partition, HASH, KEY).hint());
        }
    }

    @Test void optionalTrackingHooksCannotBypassPubsubReadinessAndTrackExactRedisKeyIdentities() {
        try (var transport = new CacheDbCacheTestSubscription();
             var coordinator = new CacheDbCacheInvalidationCoordinator(transport.operations, "test")) {
            var partition = coordinator.register("cache", CacheDbCacheRedisTest.slowNear(false));
            coordinator.freezeAndStartIfNearEnabled();
            CacheDbCacheRedisFixture.await(coordinator::connected);
            var value = state();
            var ticket = coordinator.beforeFetch(partition, HASH, KEY);
            assertTrue(partition.admit(ticket, value, "1value", 10000));
            String stem = "test:scache:cache:{" + HASH + "}";
            coordinator.trackingInvalidated(stem + ":revision:" + KEY);
            assertNull(partition.lookup(HASH, KEY));
            coordinator.trackingDisconnected();
            assertFalse(coordinator.connected());
            assertFalse(partition.admit(ticket, value, "1old", 10000));
            coordinator.trackingConnected();
            assertTrue(coordinator.connected());
            var fresh = coordinator.beforeFetch(partition, HASH, KEY);
            assertTrue(partition.admit(fresh, value, "1fresh", 10000));
            coordinator.trackingInvalidated(stem + ":generation");
            assertNull(partition.lookup(HASH, KEY));
            assertNull(coordinator.beforeFetch(partition, HASH, KEY).hint());
            transport.disconnect.countDown();
            CacheDbCacheRedisFixture.await(() -> !coordinator.connected());
            coordinator.trackingConnected();
            assertFalse(coordinator.connected(), "Tracking ready alone must not enable L1 while pub/sub is disconnected");
        }
    }

    @Test void generationRegistryEntryAndByteBudgetsAreHardCaps() {
        try (var transport = new CacheDbCacheTestSubscription();
             var coordinator = new CacheDbCacheInvalidationCoordinator(transport.operations, "test")) {
            var config = new CacheDbCacheConfiguration(Duration.ofMinutes(2),
                    new NearCachePolicy(2, 30, 4096), false, true, 4096);
            List<CacheDbCacheInvalidationCoordinator.Partition> partitions = new ArrayList<>();
            for (int i = 0; i < 20; i++) partitions.add(coordinator.register("cache" + i, config));
            coordinator.freezeAndStartIfNearEnabled();
            CacheDbCacheRedisFixture.await(coordinator::connected);
            for (int i = 0; i < 5120; i++) {
                String hash = String.format("%064x", i);
                var partition = partitions.get(i / 256);
                var ticket = coordinator.beforeFetch(partition, hash, KEY);
                partition.observe(ticket, state().generation());
            }
            assertEquals(4096, coordinator.generationScopes());
            for (var partition : partitions) assertTrue(partition.scopes.size() <= 256);
            var partition = partitions.get(0);
            var value = state();
            for (int i = 0; i < 10; i++) {
                String key = CacheDbCacheKeys.encode(i);
                var ticket = coordinator.beforeFetch(partition, HASH, key);
                assertTrue(partition.admit(ticket, value, "1" + "x".repeat(64), 10000));
            }
            assertEquals(2, partition.entries.size());
            var tooLarge = coordinator.beforeFetch(partition, HASH, KEY);
            assertFalse(partition.admit(tooLarge, value, "1" + "x".repeat(4096), 10000));
            assertTrue(partition.bytes <= 4096);
        }
    }

    @Test void generationBearingClearReplacesScopeEpochAndPrimesTheNextAtomicMissHint() {
        try (var transport = new CacheDbCacheTestSubscription();
             var coordinator = new CacheDbCacheInvalidationCoordinator(transport.operations, "test")) {
            var partition = coordinator.register("cache", CacheDbCacheRedisTest.slowNear(false));
            coordinator.freezeAndStartIfNearEnabled();
            CacheDbCacheRedisFixture.await(coordinator::connected);
            var old = state();
            var ticket = coordinator.beforeFetch(partition, HASH, KEY);
            assertTrue(partition.admit(ticket, old, "1old", 10000));
            var next = new CacheDbCacheStore.Generation(old.generation().incarnation(), "1");
            String event = CacheDbCacheInvalidationCoordinator.message("C", "cache", HASH, "")
                    + next.incarnation() + "," + next.counter();
            transport.event(coordinator.channel(), event);
            assertNull(partition.lookup(HASH, KEY));
            assertFalse(partition.admit(ticket, old, "1late", 10000));
            assertEquals(next, coordinator.beforeFetch(partition, HASH, KEY).hint());
            assertEquals(1, coordinator.generationScopes());
            transport.event(coordinator.channel(), CacheDbCacheInvalidationCoordinator.message("C", "cache", "b".repeat(64), "")
                    + next.incarnation() + "," + next.counter());
            assertEquals(1, coordinator.generationScopes(), "Peer messages must not allocate unused tenant scopes");
        }
    }
}
