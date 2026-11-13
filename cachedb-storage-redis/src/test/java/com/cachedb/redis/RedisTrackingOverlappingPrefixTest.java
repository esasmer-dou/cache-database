package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.NearCachePolicy;
import com.reactor.cachedb.core.config.NearCacheInvalidationConfig;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;

class RedisTrackingOverlappingPrefixTest {
    private static RedisNearCacheCoordinator.Store store() {
        return new RedisNearCacheCoordinator.Store(new NearCachePolicy(64, 10, 1048576));
    }
    private static RedisTrackingCoordinator controller(RedisKeyStrategy keys,
            Map<String, RedisNearCacheCoordinator.Store> stores) {
        RedisClientOperations unused = new RedisClientOperations() {
            public redis.clients.jedis.UnifiedJedis commands() { throw new AssertionError("No I/O expected"); }
        };
        return new RedisTrackingCoordinator(unused, keys, NearCacheInvalidationConfig.defaults(),
                () -> stores, () -> stores.values().forEach(RedisNearCacheCoordinator.Store::clear));
    }
    @Test void overlappingNamespacesInvalidateBothStoresAndRejectBothPreReadLateFills() {
        var keys = new RedisKeyStrategy("overlap");
        String broad = "rows", nested = "rows:entity:nested";
        String broadId = "nested:entity:7", nestedId = "7";
        String physical = keys.entityKey(broad, broadId);
        assertEquals(physical, keys.entityKey(nested, nestedId));
        var first = store(); var second = store();
        var firstStamp = first.capture(broadId); var secondStamp = second.capture(nestedId);
        first.put(broadId, "old", -1, firstStamp); second.put(nestedId, "old", -1, secondStamp);
        assertEquals("old", first.get(broadId)); assertEquals("old", second.get(nestedId));
        try (var coordinator = controller(keys, Map.of(broad, first, nested, second))) {
            coordinator.acceptTrackingPayload(List.of(physical.getBytes(StandardCharsets.UTF_8)));
            assertNull(first.get(broadId)); assertNull(second.get(nestedId));
            assertNotEquals(firstStamp, first.capture(broadId)); assertNotEquals(secondStamp, second.capture(nestedId));
            first.put(broadId, "late", -1, firstStamp); second.put(nestedId, "late", -1, secondStamp);
            assertNull(first.get(broadId)); assertNull(second.get(nestedId));
            assertEquals(2, coordinator.invalidations(), "Each physical-key owner must be invalidated");
            assertEquals(0, coordinator.flushes(), "Ambiguity need not flush unrelated stores");
            first.put(broadId, "fresh", -1, first.capture(broadId));
            second.put(nestedId, "fresh", -1, second.capture(nestedId));
            assertEquals("fresh", first.get(broadId)); assertEquals("fresh", second.get(nestedId));
        }
    }
    @Test void customEqualPrefixesStillInvalidateEveryRegisteredStore() {
        var keys = new RedisKeyStrategy("equal") {
            @Override public String entityKey(String namespace, Object id) { return "equal:entity:" + id; }
        };
        var first = store(); var second = store();
        var a = first.capture("a:b"); var b = second.capture("a:b");
        first.put("a:b", "old", -1, a); second.put("a:b", "old", -1, b);
        try (var coordinator = controller(keys, Map.of("first", first, "second", second))) {
            coordinator.acceptTrackingMessage(keys.entityKey("first", "a:b"));
            assertNull(first.get("a:b")); assertNull(second.get("a:b"));
            first.put("a:b", "late", -1, a); second.put("a:b", "late", -1, b);
            assertNull(first.get("a:b")); assertNull(second.get("a:b"));
            assertEquals(2, coordinator.invalidations());
        }
    }
    @Test void unknownPhysicalKeysDoNotInvalidateOrAdvanceEitherStore() {
        var keys = new RedisKeyStrategy("unknown");
        var first = store(); var second = store();
        String a = "nested:entity:7", b = "7";
        var firstStamp = first.capture(a); var secondStamp = second.capture(b);
        first.put(a, "keep", -1, firstStamp); second.put(b, "keep", -1, secondStamp);
        try (var coordinator = controller(keys, Map.of("rows", first, "rows:entity:nested", second))) {
            coordinator.acceptTrackingMessage(keys.entityKey("unregistered", "7"));
            coordinator.acceptTrackingMessage(keys.versionKey("rows", a));
            assertEquals("keep", first.get(a)); assertEquals("keep", second.get(b));
            assertEquals(firstStamp, first.capture(a)); assertEquals(secondStamp, second.capture(b));
            assertEquals(0, coordinator.invalidations()); assertEquals(0, coordinator.flushes());
        }
    }
}
