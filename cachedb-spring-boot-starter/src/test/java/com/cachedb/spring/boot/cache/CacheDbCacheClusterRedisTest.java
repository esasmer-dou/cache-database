package com.reactor.cachedb.spring.boot.cache;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.spring.boot.TestFixtureSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.util.JedisClusterCRC16;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
@EnabledIfSystemProperty(named = "cachedb.redis.cluster", matches = "true")
@Timeout(45)
class CacheDbCacheClusterRedisTest {
    @Test void tenantGenerationPayloadAndRevisionShareSlotAndPeerMutationsStayVisible() {
        try (var fixture = new CacheDbCacheRedisFixture(TestFixtureSettings.cluster())) {
            var configs = Map.of("cache", CacheDbCacheRedisTest.slowNear(true));
            var one = fixture.manager(configs);
            var two = fixture.manager(configs);
            CacheDbCacheRedisFixture.await(() -> one.invalidationConnected() && two.invalidationConnected());
            var a = one.getCache("cache");
            var b = two.getCache("cache");
            try (var ignored = CacheTenantContext.withTenant("tenant:{injected-slot}:x")) {
                long notifications = two.invalidationCoordinator().receivedNotifications();
                assertEquals("initial", a.get("key", () -> "initial"));
                // Drain the initial generation/key invalidations before measuring steady-state L1 hits.
                CacheDbCacheRedisFixture.await(() ->
                        two.invalidationCoordinator().receivedNotifications() >= notifications + 2);
                CacheDbCacheRedisFixture.await(() -> {
                    assertEquals("initial", b.get("key").get());
                    return b.statistics().nearHits() > 0;
                });
                long reads = fixture.evalCalls.get();
                long clients = fixture.commandsCalls.get();
                for (int i = 0; i < 100; i++) assertEquals("initial", b.get("key", String.class));
                assertEquals(reads, fixture.evalCalls.get(), "Cluster L1 hits must perform no EVAL");
                assertEquals(clients, fixture.commandsCalls.get(), "Cluster L1 hits must not access the client");
                int slot = -1;
                for (String key : fixture.ownedKeys) {
                    int actual = JedisClusterCRC16.getSlot(key);
                    if (slot == -1) slot = actual;
                    else assertEquals(slot, actual);
                }
                String revisionKey = fixture.ownedKeys.stream()
                        .filter(key -> key.endsWith(":revision:" + CacheDbCacheKeys.encode("key"))).findFirst().orElseThrow();
                two.invalidationCoordinator().trackingInvalidated(revisionKey);
                long refreshed = b.statistics().nearHits();
                CacheDbCacheRedisFixture.await(() -> {
                    assertEquals("initial", b.get("key", String.class));
                    return b.statistics().nearHits() > refreshed;
                });
                a.put("key", "new");
                CacheDbCacheRedisFixture.await(() -> "new".equals(b.get("key", String.class)));
                a.evict("key");
                CacheDbCacheRedisFixture.await(() -> b.get("key") == null);
                a.put("key", "again");
                a.clear();
                assertNull(b.get("key"));
            }
            try (var ignored = CacheTenantContext.withTenant("other")) {
                assertEquals("other-value", b.get("key", () -> "other-value"));
            }
        }
    }
}
