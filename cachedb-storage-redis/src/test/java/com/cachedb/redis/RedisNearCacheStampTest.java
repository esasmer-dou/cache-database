package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.NearCachePolicy;
import com.reactor.cachedb.core.config.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RedisNearCacheStampTest {
    private RedisNearCacheCoordinator.Store store() {
        return new RedisNearCacheCoordinator.Store(new NearCachePolicy(64, 10, 1048576));
    }
    @Test void sameSegmentInvalidationRejectsLateFillAndPreservesVersionGuard() {
        var store = store();
        var before = store.capture("Aa");
        store.invalidate("BB"); // Same String hash and segment, deliberately.
        store.put("Aa", "old", -1, before);
        assertNull(store.get("Aa"));
        var current = store.capture("Aa");
        store.put("Aa", "v2", null, -1, current, 2);
        store.put("Aa", "v1", null, -1, current, 1);
        assertEquals("v2", store.get("Aa"));
        assertNull(store.lookup("Aa", 3));
    }
    @Test void unrelatedSegmentKeepsNewStampButLegacyGlobalEpochStillRejects() {
        var store = store();
        String first = "Aa", other = null;
        for (int i = 0; i < 1024; i++) {
            String candidate = "k" + i;
            if ((candidate.hashCode() & Integer.MAX_VALUE) % 16 != (first.hashCode() & Integer.MAX_VALUE) % 16) {
                other = candidate; break;
            }
        }
        assertNotNull(other);
        var stamp = store.capture(first);
        long legacy = store.epoch();
        store.invalidate(other);
        store.put(first, "legacy", -1, legacy);
        assertNull(store.get(first));
        store.put(first, "new", -1, stamp);
        assertEquals("new", store.get(first));
        assertNotEquals(legacy, store.epoch());
    }
    @Test void clearRejectsEveryCapturedStampAndLegacyEpoch() {
        var store = store();
        var stamp = store.capture("id");
        long epoch = store.epoch();
        store.clear();
        assertNotEquals(stamp.clearEpoch(), store.capture("id").clearEpoch());
        store.put("id", "old", -1, stamp);
        store.put("id", "old", -1, epoch);
        assertNull(store.get("id"));
        store.put("id", "fresh", -1, store.capture("id"));
        assertEquals("fresh", store.get("id"));
    }
    @Test void parseOnlyKnownPrefixesAndPreserveColonIdsAndClusterTags() {
        for (RedisKeyStrategy keys : List.of(new RedisKeyStrategy("t2"),
                new ClusterHashTagKeyStrategy("t2"), new ClusterHashTagKeyStrategy("t2", "scope"))) {
            var parsed = keys.parseEntityKey(keys.entityKey("rows", "a:b"), List.of("rows")).orElseThrow();
            assertEquals("rows", parsed.namespace()); assertEquals("a:b", parsed.id());
            assertEquals(keys.entityKey("rows", ""), keys.entityKeyPrefix("rows"));
            assertTrue(keys.parseEntityKey(keys.entityKey("unknown", "id"), List.of("rows")).isEmpty());
            assertTrue(keys.parseEntityKey(keys.versionKey("rows", "id"), List.of("rows")).isEmpty());
            assertTrue(keys.parseEntityKey(null, List.of("rows")).isEmpty());
        }
    }
    @Test void trackingNullClearsAllAndUnknownKeysAreIgnored() {
        var store = store(); var keys = new RedisKeyStrategy("t2");
        RedisClientOperations unused = new RedisClientOperations() {
            public redis.clients.jedis.UnifiedJedis commands() { throw new AssertionError("No I/O expected"); }
        };
        try (var controller = new RedisTrackingCoordinator(unused, keys, NearCacheInvalidationConfig.defaults(),
                () -> Map.of("rows", store), store::clear)) {
            store.put("id", "value", -1, store.capture("id"));
            controller.acceptTrackingMessage(keys.entityKey("other", "id"));
            assertEquals("value", store.get("id")); assertEquals(0, controller.invalidations());
            var captured = store.capture("id");
            controller.acceptTrackingMessage(null);
            assertNull(store.get("id")); assertEquals(1, controller.flushes());
            store.put("id", "late", -1, captured); assertNull(store.get("id"));
            controller.acceptTrackingMessage(keys.entityKey("rows", "id"));
            assertEquals(1, controller.invalidations());
        }
    }
    @Test void trackingResp2ListFlattensEachKeyAndNullFlushesEverything() {
        var store = store(); var keys = new RedisKeyStrategy("t2");
        RedisClientOperations unused = new RedisClientOperations() {
            public redis.clients.jedis.UnifiedJedis commands() { throw new AssertionError("No I/O expected"); }
        };
        try (var controller = new RedisTrackingCoordinator(unused, keys, NearCacheInvalidationConfig.defaults(),
                () -> Map.of("rows", store), store::clear)) {
            for (String id : List.of("a:b", "second", "unaffected"))
                store.put(id, "value", -1, store.capture(id));
            controller.acceptTrackingPayload(List.of(
                    keys.entityKey("rows", "a:b").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    keys.entityKey("rows", "second").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    keys.versionKey("rows", "unaffected").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertNull(store.get("a:b")); assertNull(store.get("second"));
            assertEquals("value", store.get("unaffected")); assertEquals(2, controller.invalidations());
            controller.acceptTrackingPayload(null);
            assertNull(store.get("unaffected"));
            assertThrows(IllegalStateException.class,
                    () -> controller.acceptTrackingPayload(Collections.nCopies(64001, new byte[0])));
        }
    }
    @Test void topologyFingerprintIgnoresGossipAndRejectsMissingOrUnhealthyMasters() {
        String a = "a".repeat(40), b = "b".repeat(40);
        String first = a + " host:1@2 myself,master - 1 2 7 connected 0-8000\n"
                + b + " host:3@4 master - 3 4 8 connected 8001-16383\n";
        String second = b + " host:3@4 myself,master - 9 10 8 connected 8001-16383\n"
                + a + " host:1@2 master - 11 12 7 connected 0-8000\n";
        assertEquals(RedisTrackingTopology.parse(first, 64, 16), RedisTrackingTopology.parse(second, 64, 16));
        assertThrows(IllegalStateException.class, () -> RedisTrackingTopology.parse(first, 64, 1));
        assertThrows(IllegalStateException.class, () -> RedisTrackingTopology.parse(first.replace("connected", "disconnected"), 64, 16));
        assertThrows(IllegalStateException.class, () -> RedisTrackingTopology.parse("x".repeat(131073), 64, 16));
    }
    @Test void legacyOptOutRejectsAutoAndPubsubInsteadOfAllowingAsymmetricPeerFallback() {
        var defaults = NearCacheInvalidationConfig.defaults();
        for (var mode : List.of(NearCacheInvalidationMode.AUTO, NearCacheInvalidationMode.PUBSUB)) {
            var failure = assertThrows(IllegalArgumentException.class, () -> new NearCacheInvalidationConfig(mode, false,
                    defaults.maxMasters(), defaults.maxNodes(), defaults.maxNamespaces(),
                    defaults.ioTimeoutMillis(), defaults.heartbeatMillis(), defaults.healthTimeoutMillis(),
                    defaults.maxAttempts(), defaults.initialBackoffMillis(), defaults.maxBackoffMillis(), defaults.retryCooldownMillis()));
            assertTrue(failure.getMessage().contains("SERVER_TRACKING"));
        }
        var strict = assertDoesNotThrow(() -> new NearCacheInvalidationConfig(NearCacheInvalidationMode.SERVER_TRACKING, false,
                defaults.maxMasters(), defaults.maxNodes(), defaults.maxNamespaces(),
                defaults.ioTimeoutMillis(), defaults.heartbeatMillis(), defaults.healthTimeoutMillis(),
                defaults.maxAttempts(), defaults.initialBackoffMillis(), defaults.maxBackoffMillis(), defaults.retryCooldownMillis()));
        assertFalse(strict.publishLegacyInvalidations());
        assertThrows(IllegalArgumentException.class, () -> strict.withMode(NearCacheInvalidationMode.AUTO));
        assertThrows(IllegalArgumentException.class, () -> strict.withMode(NearCacheInvalidationMode.PUBSUB));
    }
    @Test void configurationHardBoundsAndDefaultMixedFleetSafety() {
        var config = NearCacheInvalidationConfig.defaults();
        assertEquals(NearCacheInvalidationMode.AUTO, config.mode());
        assertTrue(config.publishLegacyInvalidations());
        assertThrows(IllegalArgumentException.class, () -> new NearCacheInvalidationConfig(config.mode(), true,
                17, 64, 256, 500, 100, 1000, 5, 100, 5000, 30000));
        assertThrows(IllegalArgumentException.class, () -> new NearCacheInvalidationConfig(config.mode(), true,
                16, 64, 257, 500, 100, 1000, 5, 100, 5000, 30000));
    }
}
