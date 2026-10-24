package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;
import redis.clients.jedis.params.ScanParams;

class RedisClusterOperationsTest {
    @Test
    void keyStrategyKeepsViewsAndStreamsInTheOwningSlot() {
        var keys = new ClusterHashTagKeyStrategy("cachedb");
        var first = keys.entityKey("orders", 1);
        var view = keys.indexAllKey("orders:projection:summary");
        assertEquals(
                redis.clients.jedis.util.JedisClusterCRC16.getSlot(first),
                redis.clients.jedis.util.JedisClusterCRC16.getSlot(view));
        assertTrue(keys.streamKey("orders").contains("{orders}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ClusterHashTagKeyStrategy("cachedb", "bad{tag"));
    }

    @Test
    @EnabledIfSystemProperty(named = "cachedb.redis.cluster", matches = "true")
    void liveClusterSupportsBoundedScanCrossSlotReadsAndAtomicSameSlotCalls() {
        var configuration =
                DefaultJedisClientConfig.builder()
                        .connectionTimeoutMillis(3000)
                        .socketTimeoutMillis(5000)
                        .hostAndPortMapper(
                                address ->
                                        System.getProperty("cachedb.redis.cluster.host") != null ? address :
                                        new HostAndPort(
                                                "127.0.0.1",
                                                address.getPort() < 10000
                                                        ? address.getPort() + 10000
                                                        : address.getPort()))
                        .build();
        try (var redis =
                new JedisClusterOperations(
                        new JedisCluster(
                                Set.of(new HostAndPort(System.getProperty("cachedb.redis.cluster.host", "127.0.0.1"), Integer.getInteger("cachedb.redis.cluster.port", 17001))), configuration))) {
            String prefix = "cluster-test-" + UUID.randomUUID();
            var keys = List.of(prefix + ":{alpha}:1", prefix + ":{beta}:2", prefix + ":{gamma}:3");
            try {
                try (var pipeline = redis.pipeline()) {
                    for (int i = 0; i < keys.size(); i++)
                        pipeline.set(keys.get(i), Integer.toString(i));
                    pipeline.sync();
                }
                assertEquals(List.of("0", "1", "2"), redis.mget(keys.toArray(String[]::new)));
                assertEquals(
                        "0",
                        redis.eval(
                                "return redis.call('GET',KEYS[1])",
                                List.of(keys.get(0)),
                                List.of()));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> redis.eval("return 1", keys, List.of()));
                String library = "cachedb_cluster_test";
                redis.functionLoadReplace(
                        "#!lua name="
                                + library
                                + "\n"
                                + "redis.register_function('cachedb_cluster_echo',"
                                + " function(keys,args) return redis.call('GET',keys[1]) end)");
                for (int i = 0; i < keys.size(); i++)
                    assertEquals(
                            Integer.toString(i),
                            redis.fcall("cachedb_cluster_echo", List.of(keys.get(i)), List.of()));
                Set<String> found = new HashSet<>();
                String cursor = "0";
                do {
                    var result =
                            redis.scan(cursor, new ScanParams().match(prefix + ":*").count(10));
                    cursor = result.getCursor();
                    found.addAll(result.getResult());
                } while (!cursor.equals("0"));
                assertEquals(new HashSet<>(keys), found);
            } finally {
                for (String key : keys) redis.commands().unlink(key);
            }
        }
    }
}
