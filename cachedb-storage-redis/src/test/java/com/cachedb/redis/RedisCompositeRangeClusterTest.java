package com.reactor.cachedb.redis;

import static com.reactor.cachedb.redis.RedisCompositeRangeTest.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;
import redis.clients.jedis.util.JedisClusterCRC16;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class RedisCompositeRangeClusterTest {
    @Test
    @EnabledIfSystemProperty(named = "cachedb.redis.cluster", matches = "true")
    void oneSlotRangePayloadsMutationAndDeletedCursorContinuation() {
        var config = DefaultJedisClientConfig.builder().connectionTimeoutMillis(3000).socketTimeoutMillis(5000)
                .hostAndPortMapper(address -> System.getProperty("cachedb.redis.cluster.host") != null ? address
                        : new HostAndPort("127.0.0.1", address.getPort() < 10000 ? address.getPort() + 10000 : address.getPort()))
                .build();
        try (var redis = new JedisClusterOperations(new JedisCluster(
                Set.of(new HostAndPort(System.getProperty("cachedb.redis.cluster.host", "127.0.0.1"),
                        Integer.getInteger("cachedb.redis.cluster.port", 17001))), config))) {
            String prefix = "f1-cluster-" + UUID.randomUUID();
            var keys = new ClusterHashTagKeyStrategy(prefix);
            String exact = exact(keys);
            String sorted = exact + ":sort";
            var fence = new RedisCompositeMigrationFence(redis, keys, "rows");
            for (String key : fence.identityKeys())
                assertEquals(JedisClusterCRC16.getSlot(sorted), JedisClusterCRC16.getSlot(key));
            assertEquals(JedisClusterCRC16.getSlot(sorted), JedisClusterCRC16.getSlot(keys.compositeIndexSignatureKey("rows")));
            try {
                var manager = manager(redis, keys, false, Map.of("tenant_status", "score"));
                assertTrue(manager.rebuildCompositeIndexesFenced(false, "empty-cluster-fixture").degradedCleared());
                for (String id : List.of("1", "10", "2", "20")) {
                    Row row = new Row(id, "a", "OPEN", 5, true);
                    redis.commands().set(keys.entityKey("rows", id), CODEC.toRedisValue(row));
                    manager.reindex(row);
                    assertFalse(redis.commands().exists(keys.indexDegradedKey("rows")));
                    assertEquals(JedisClusterCRC16.getSlot(sorted), JedisClusterCRC16.getSlot(keys.entityKey("rows", id)));
                    assertEquals(JedisClusterCRC16.getSlot(sorted), JedisClusterCRC16.getSlot(keys.tombstoneKey("rows", id)));
                }
                assertEquals(JedisClusterCRC16.getSlot(exact), JedisClusterCRC16.getSlot(sorted));
                var first = manager.resolveCompositeWindow(query().limitTo(2), null, row -> true).orElseThrow();
                assertEquals(List.of("1", "10"), first.rows().stream().map(row -> row.position().id()).toList());
                manager.removeById("10"); redis.commands().del(keys.entityKey("rows", "10"));
                var next = manager.resolveCompositeWindow(query(), first.continuation(), row -> true).orElseThrow();
                assertEquals(List.of("2", "20"), next.rows().stream().map(row -> row.position().id()).toList());
                manager.rebuildCompositeIndexesFenced(false, "f1-cluster");
                assertEquals(3, manager.resolveCompositeWindow(query(), null, row -> true).orElseThrow().rows().size());
            } finally {
                String cursor = "0"; int scans = 0;
                do {
                    var page = redis.scan(cursor, new redis.clients.jedis.params.ScanParams()
                            .match(prefix + ":*").count(256));
                    for (String key : page.getResult()) redis.commands().unlink(key);
                    cursor = page.getCursor();
                    if (++scans > 10_000) throw new IllegalStateException("Cluster fixture cleanup budget exceeded");
                } while (!cursor.equals("0"));
            }
        }
    }
}
