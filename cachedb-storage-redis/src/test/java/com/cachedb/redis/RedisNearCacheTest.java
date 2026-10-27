package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.NearCachePolicy;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;

class RedisNearCacheTest {
    @Test void unusedCoordinatorNeverCreatesRefreshThreadsAndCloseIsIdempotent() {
        RedisClientOperations unused = new RedisClientOperations() {
            public redis.clients.jedis.UnifiedJedis commands() { throw new AssertionError("No Redis access expected"); }
        };
        var coordinator = new RedisNearCacheCoordinator(unused,new RedisKeyStrategy());
        String prefix = coordinator.refreshThreadPrefix();
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.getName().startsWith(prefix)));
        coordinator.close(); coordinator.close();
        coordinator.refresh(null,"ignored",() -> fail("Closed coordinator executed task"));
        assertEquals(0,coordinator.rejectedRefreshes());
    }

    @Test @EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
    void refreshBudgetsAreIndependentAndShutdownCancelsQueuedWork() throws Exception {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var first = new RedisNearCacheCoordinator(JedisPooledOperations.of(redis),new RedisKeyStrategy("budget-a-"+UUID.randomUUID()));
                var second = new RedisNearCacheCoordinator(JedisPooledOperations.of(redis),new RedisKeyStrategy("budget-b-"+UUID.randomUUID()))) {
            var policy = new NearCachePolicy(32,10,65536,1);
            var a = first.register("rows",policy); var b = second.register("rows",policy);
            long deadline = System.nanoTime()+5_000_000_000L;
            while ((!first.connected() || !second.connected()) && System.nanoTime()<deadline) Thread.sleep(2);
            assertTrue(first.connected()); assertTrue(second.connected());
            var gate = new java.util.concurrent.CountDownLatch(1);
            var starts = new java.util.concurrent.atomic.AtomicInteger();
            try {
                for (int i=0;i<600;i++) first.refresh(a,"key"+i,() -> {
                    starts.incrementAndGet();
                    try { gate.await(5,java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                });
                assertTrue(first.rejectedRefreshes()>0);
                var independent = new java.util.concurrent.CountDownLatch(1);
                second.refresh(b,"works",independent::countDown);
                assertTrue(independent.await(2,java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(0,second.rejectedRefreshes());
                String prefix = first.refreshThreadPrefix(); first.close(); first.close();
                deadline = System.nanoTime()+2_000_000_000L;
                while (Thread.getAllStackTraces().keySet().stream().anyMatch(thread -> thread.isAlive() && thread.getName().startsWith(prefix))
                        && System.nanoTime()<deadline) Thread.sleep(2);
                assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.isAlive() && thread.getName().startsWith(prefix)));
                int before = starts.get();
                first.refresh(a,"after-close",starts::incrementAndGet);
                assertEquals(before,starts.get());
            } finally { gate.countDown(); }
        }
    }
    @Test void sampledFrequencyProtectsHotKeysDuringBoundedColdScan() {
        var cache = new RedisNearCacheCoordinator.Store(new NearCachePolicy(64, 10, 1_048_576));
        var ids = java.util.stream.IntStream.range(0, 10000).mapToObj(i -> "key" + i)
                .filter(id -> (id.hashCode() & Integer.MAX_VALUE) % 16 == 0).limit(40).toList();
        for (int i = 0; i < 4; i++) cache.put(ids.get(i), "value", -1, cache.epoch());
        String hot = ids.get(0);
        for (int i = 0; i < 12; i++) assertEquals("value", cache.get(hot));
        for (int i = 4; i < ids.size(); i++) cache.put(ids.get(i), "scan", -1, cache.epoch());
        assertEquals("value", cache.get(hot));
        long retained = ids.stream().filter(id -> cache.get(id) != null).count();
        assertTrue(retained <= 4);
    }
    @Test void softTtlVersionAndPolicyBoundsRemainSeparateFromHardExpiry() throws Exception {
        assertEquals(0, new NearCachePolicy(32, 2, 65536).softTtlSeconds());
        assertThrows(IllegalArgumentException.class, () -> new NearCachePolicy(32, 2, 65536, -1));
        assertThrows(IllegalArgumentException.class, () -> new NearCachePolicy(32, 2, 65536, 2));
        var cache = new RedisNearCacheCoordinator.Store(new NearCachePolicy(32, 3, 65536, 1));
        cache.put("id", "v2", null, -1, cache.epoch(), 2);
        assertNull(cache.lookup("id", 3));
        assertEquals(2, cache.lookup("id", 2).version());
        cache.put("id", "stale", null, -1, cache.epoch(), 1);
        assertEquals("v2", cache.get("id"));
        Thread.sleep(1050);
        assertTrue(cache.lookup("id", 2).softExpired());
        assertEquals("v2", cache.get("id"));
    }
    @Test void evictionFollowsRecentAccessInsteadOfInsertionAndKeepsDecodedReference() {
        var cache = new RedisNearCacheCoordinator.Store(new NearCachePolicy(32, 10, 1_048_576));
        // All three identities hash to the same segment, whose capacity is two.
        String a = "Aa", b = "BB", c = "C#";
        Object decoded = new Object();
        cache.put(a, "first", decoded, -1, cache.epoch());
        cache.put(b, "second", -1, cache.epoch());
        assertEquals("first", cache.get(a));
        assertSame(decoded, cache.getDecoded(a, "first"));
        cache.put(c, "third", -1, cache.epoch());
        assertNull(cache.get(b));
        assertEquals("first", cache.get(a));
        assertEquals("third", cache.get(c));
        cache.invalidate(a);
        assertNull(cache.getDecoded(a, "first"));
    }
    @Test void strictTtlCapacityByteBudgetAndLateFillProtection() throws Exception {
        var cache = new RedisNearCacheCoordinator.Store(new NearCachePolicy(2, 1, 2048));
        long before = cache.epoch();
        cache.put("a", "one", -1, before);
        assertEquals("one", cache.get("a"));
        cache.invalidate("a");
        cache.put("a", "stale", -1, before);
        assertNull(cache.get("a"));
        cache.put("a", "fresh", 1, cache.epoch());
        Thread.sleep(5); assertNull(cache.get("a"));
        cache.put("a", "x".repeat(5000), -1, cache.epoch()); assertNull(cache.get("a"));
        cache.put("a", "fresh", -2, cache.epoch()); assertNull(cache.get("a"));
        assertThrows(IllegalArgumentException.class, () -> new NearCachePolicy(64_001, 1, 1024));
        assertThrows(IllegalArgumentException.class, () -> new NearCachePolicy(1, 61, 1024));
    }

    @Test @EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
    void refreshOverloadNeverBlocksCallersAndReleasesReservations() throws Exception {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var coordinator = new RedisNearCacheCoordinator(JedisPooledOperations.of(redis),new RedisKeyStrategy("refresh-" + UUID.randomUUID()))) {
            var store = coordinator.register("rows",new NearCachePolicy(32,10,65536,1));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!coordinator.connected() && System.nanoTime() < deadline) Thread.sleep(2);
            assertTrue(coordinator.connected());
            var gate = new java.util.concurrent.CountDownLatch(1);
            try {
                for (int i = 0; i < 1000; i++) coordinator.refresh(store,"id" + i,() -> {
                    try { gate.await(5,java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                });
                assertTrue(coordinator.rejectedRefreshes() > 0);
            } finally { gate.countDown(); }
            var completed = new java.util.concurrent.CountDownLatch(1);
            deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (completed.getCount() > 0 && System.nanoTime() < deadline) {
                coordinator.refresh(store,"retry",completed::countDown); Thread.sleep(2);
            }
            assertEquals(0,completed.getCount());
        }
    }

    @Test @EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
    void separateRuntimeSubscriptionsEvictPeerCopiesAndClearOnClose() throws Exception {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri())) {
            var keys = new RedisKeyStrategy("near-" + UUID.randomUUID());
            try (var first = new RedisNearCacheCoordinator(JedisPooledOperations.of(redis), keys);
                    var second = new RedisNearCacheCoordinator(JedisPooledOperations.of(redis), keys)) {
                var policy = new NearCachePolicy(32, 10, 65536);
                var a = first.register("products", policy); var b = second.register("products", policy);
                long deadline = System.nanoTime() + 5_000_000_000L;
                while ((!first.connected() || !second.connected()) && System.nanoTime() < deadline) Thread.sleep(2);
                assertTrue(first.connected()); assertTrue(second.connected());
                a.put("1", "old", -1, a.epoch()); b.put("1", "old", -1, b.epoch());
                redis.publish(keys.nearInvalidationChannel(), RedisNearCacheCoordinator.message("products", "1"));
                while ((a.get("1") != null || b.get("1") != null) && System.nanoTime() < deadline) Thread.sleep(2);
                assertNull(a.get("1")); assertNull(b.get("1"));
                b.put("1", "fresh", -1, b.epoch()); second.close(); assertNull(b.get("1"));
            }
        }
    }
}
