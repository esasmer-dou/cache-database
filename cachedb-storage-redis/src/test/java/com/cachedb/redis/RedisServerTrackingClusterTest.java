package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.NearCachePolicy;
import com.reactor.cachedb.core.config.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;
import redis.clients.jedis.Protocol.Command;
import redis.clients.jedis.util.JedisClusterCRC16;

@EnabledIfSystemProperty(named = "cachedb.redis.cluster", matches = "true")
class RedisServerTrackingClusterTest {
    private static void await(BooleanSupplier condition, long millis) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean() && until - System.nanoTime() > 0) Thread.sleep(2);
        assertTrue(condition.getAsBoolean(), "Cluster condition missed its bounded deadline");
    }
    private record Range(int first, int last, String master) {}
    private static final class Fixture implements AutoCloseable {
        final JedisCluster client;
        final JedisClusterOperations operations;
        final ClusterHashTagKeyStrategy keys = new ClusterHashTagKeyStrategy("t2-cluster-" + UUID.randomUUID());
        final Map<String, RedisTrackingNode> masters = new TreeMap<>();
        final Map<String, String> namespaces = new TreeMap<>();
        final Map<String, RedisNearCacheCoordinator.Store> stores = new TreeMap<>();
        final RedisNearCacheCoordinator coordinator;
        Fixture(boolean forceAutoFallback) throws InterruptedException {
            var config = DefaultJedisClientConfig.builder().connectionTimeoutMillis(2000).socketTimeoutMillis(1000)
                    .hostAndPortMapper(address -> System.getProperty("cachedb.redis.cluster.host") != null ? address :
                            new HostAndPort("127.0.0.1", address.getPort() < 10000 ? address.getPort() + 10000 : address.getPort()))
                    .build();
            client = new JedisCluster(Set.of(new HostAndPort(System.getProperty("cachedb.redis.cluster.host", "127.0.0.1"),
                    Integer.getInteger("cachedb.redis.cluster.port", 17001))), config);
            operations = new JedisClusterOperations(client);
            var settings = NearCacheInvalidationConfig.defaults();
            settings = forceAutoFallback
                    ? new NearCacheInvalidationConfig(NearCacheInvalidationMode.AUTO, true,
                            1, 64, 256, 500, 100, 1000, 5, 100, 5000, 30000)
                    : settings.withMode(NearCacheInvalidationMode.SERVER_TRACKING);
            coordinator = new RedisNearCacheCoordinator(operations, keys, settings);
            try {
                for (RedisTrackingNode node : operations.trackingNodes(64))
                    try (var control = node.openDedicated(500)) {
                        if (RedisTrackingTopology.master(control)) masters.put(RedisTrackingTopology.nodeId(control), node);
                    }
                assertTrue(masters.size() >= 3 && masters.size() <= 16, "Fixture must exercise at least three real masters");
                var ranges = new ArrayList<Range>();
                try (var control = masters.values().iterator().next().openDedicated(500)) {
                    Object raw = control.command(Command.CLUSTER, "SLOTS");
                    assertInstanceOf(List.class, raw);
                    var slots = (List<?>) raw;
                    assertTrue(slots.size() <= 16384);
                    for (Object item : slots) {
                        var range = (List<?>) item;
                        var master = (List<?>) range.get(2);
                        ranges.add(new Range(((Long) range.get(0)).intValue(), ((Long) range.get(1)).intValue(),
                                RedisTrackingTopology.text(master.get(2))));
                    }
                }
                for (int i = 0; i < 16384 && namespaces.size() < masters.size(); i++) {
                    String namespace = "rows" + i;
                    int slot = JedisClusterCRC16.getSlot(keys.entityKey(namespace, "id"));
                    for (Range range : ranges) if (slot >= range.first && slot <= range.last) {
                        namespaces.putIfAbsent(range.master, namespace); break;
                    }
                }
                assertEquals(masters.keySet(), namespaces.keySet(), "Every master must own a tested namespace");
                namespaces.forEach((master, namespace) ->
                        stores.put(master, coordinator.register(namespace, new NearCachePolicy(32, 10, 1048576))));
                await(coordinator::connected, 30000);
            } catch (RuntimeException | InterruptedException | AssertionError failure) {
                coordinator.close(); client.close(); throw failure;
            }
        }
        String key(String master) { return keys.entityKey(namespaces.get(master), "id"); }
        void seed(String master) throws InterruptedException {
            var store = stores.get(master);
            long before = store.capture("id").segmentEpoch();
            try (var writer = masters.get(master).openDedicated(500)) { writer.jedis().set(key(master), "old"); }
            if (coordinator.trackingReady()) await(() -> store.capture("id").segmentEpoch() != before, 1000);
            store.put("id", "old", -1, store.capture("id"));
            assertEquals("old", store.get("id"));
        }
        @Override public void close() {
            coordinator.close();
            try { for (String master : namespaces.keySet()) client.del(key(master)); }
            finally { client.close(); }
        }
    }
    @Test void rawWritesFromEveryMasterInvalidateWithoutPublishOrSharedPoolReservation() throws Exception {
        try (var f = new Fixture(false)) {
            assertEquals(f.masters.keySet(), f.coordinator.trackingClientIds().keySet());
            for (String master : f.masters.keySet()) {
                f.seed(master);
                long started = System.nanoTime();
                try (var writer = f.masters.get(master).openDedicated(500)) { writer.jedis().set(f.key(master), "new"); }
                await(() -> f.stores.get(master).get("id") == null, 950);
                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1));
            }
            for (var pool : f.client.getClusterNodes().values()) assertEquals(0, pool.getNumActive());
        }
    }
    @Test void topologyOrMovedHookFlushesAndRebuildsEverySubscriptionWithNewIds() throws Exception {
        try (var f = new Fixture(false)) {
            for (String master : f.masters.keySet()) f.seed(master);
            var before = f.coordinator.trackingClientIds();
            f.coordinator.topologyChanged(); // Invoke after Jedis' MOVED/cache refresh.
            for (var store : f.stores.values()) assertNull(store.get("id"));
            await(() -> f.coordinator.trackingReady() && !f.coordinator.trackingClientIds().equals(before), 30000);
            var after = f.coordinator.trackingClientIds();
            assertEquals(f.masters.keySet(), after.keySet());
            before.forEach((master, ids) -> {
                assertNotEquals(ids.get(0), after.get(master).get(0));
                assertNotEquals(ids.get(1), after.get(master).get(1));
            });
            assertTrue(f.coordinator.trackingReconnects() > 0);
        }
    }
    @Test void autoCannotAdmitPartialTrackingAndFallsBackToWorkingPubsub() throws Exception {
        try (var f = new Fixture(true)) {
            assertEquals(NearCacheInvalidationMode.PUBSUB, f.coordinator.invalidationMode());
            assertFalse(f.coordinator.trackingReady());
            assertTrue(f.coordinator.publishLegacyInvalidations());
            assertTrue(f.coordinator.trackingClientIds().isEmpty());
            String master = f.masters.keySet().iterator().next();
            f.seed(master);
            try (var writer = f.masters.get(master).openDedicated(500)) { writer.jedis().set(f.key(master), "new"); }
            Thread.sleep(750);
            assertEquals("old", f.stores.get(master).get("id"), "PUBSUB cannot observe an unpublished raw write");
            f.client.publish(f.keys.nearInvalidationChannel(),
                    RedisNearCacheCoordinator.message(f.namespaces.get(master), "id"));
            await(() -> f.stores.get(master).get("id") == null, 1000);
        }
    }
}
