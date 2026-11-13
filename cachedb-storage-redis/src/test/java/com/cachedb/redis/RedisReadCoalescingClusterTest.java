package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;

@EnabledIfSystemProperty(named = "cachedb.redis.cluster", matches = "true")
class RedisReadCoalescingClusterTest {
    static JedisClusterOperations connect() {
        var config = DefaultJedisClientConfig.builder().connectionTimeoutMillis(3000).socketTimeoutMillis(5000)
                .hostAndPortMapper(address -> System.getProperty("cachedb.redis.cluster.host") != null ? address
                        : new HostAndPort("127.0.0.1", address.getPort() < 10000 ? address.getPort() + 10000 : address.getPort()))
                .build();
        return new JedisClusterOperations(new JedisCluster(Set.of(new HostAndPort(
                System.getProperty("cachedb.redis.cluster.host", "127.0.0.1"),
                Integer.getInteger("cachedb.redis.cluster.port", 17001))), config));
    }

    @Test void colocatedAndSplitSlotPairsKeepResultsAndTtlWithoutCrossslot() throws Exception {
        String prefix = "t1-cluster-" + UUID.randomUUID();
        var keys = new ArrayList<String>();
        try (var client = connect(); var reader = new RedisReadRuntime(client, 32)) {
            try {
                var pairs = new ArrayList<RedisBoundedPointRead.KeyPair>();
                for (int i = 0; i < 32; i++) {
                    String entity = prefix + ":{entity-" + i + "}:e";
                    String tombstone = prefix + ":{" + (i % 2 == 0 ? "entity-" : "tomb-") + i + "}:t";
                    keys.add(entity);
                    keys.add(tombstone);
                    client.commands().setex(entity, 60, "row-" + i);
                    if (i % 3 == 0) client.commands().setex(tombstone, 60, "deleted-" + i);
                    pairs.add(new RedisBoundedPointRead.KeyPair(entity, tombstone, true));
                }
                RedisPointRead[] results = RedisBoundedPointRead.read(client, pairs, 16L * 1024 * 1024);
                for (int i = 0; i < results.length; i++) {
                    assertEquals("row-" + i, results[i].payload());
                    assertEquals(i % 3 == 0 ? "deleted-" + i : null, results[i].tombstone());
                    assertTrue(results[i].ttlMillis() > 0);
                    assertEquals(results[i].payload(), reader.readAsync(pairs.get(i).entity(), pairs.get(i).tombstone(), true)
                            .get(5, java.util.concurrent.TimeUnit.SECONDS).payload());
                }
            } finally { client.delete(keys.toArray(String[]::new)); }
        }
    }

    @Test void skewedOneMiBSlotAndManySmallSlotsDoNotGetDividedQuotas() {
        String prefix = "t1-cluster-skew-" + UUID.randomUUID();
        var keys = new ArrayList<String>();
        try (var client = connect()) {
            try {
                var pairs = new ArrayList<RedisBoundedPointRead.KeyPair>();
                for (int i = 0; i < 64; i++) {
                    String entity = prefix + ":{skew-" + i + "}:e", tombstone = prefix + ":{skew-" + i + "}:t";
                    keys.add(entity);
                    keys.add(tombstone);
                    client.commands().setex(entity, 60, i == 0 ? "x".repeat(1024 * 1024) : "small");
                    pairs.add(new RedisBoundedPointRead.KeyPair(entity, tombstone, false));
                }
                RedisPointRead[] results = RedisBoundedPointRead.read(client, pairs, 16L * 1024 * 1024);
                assertEquals(1024 * 1024, results[0].payload().length());
                for (int i = 1; i < results.length; i++) assertEquals("small", results[i].payload());
            } finally { client.delete(keys.toArray(String[]::new)); }
        }
    }
}
