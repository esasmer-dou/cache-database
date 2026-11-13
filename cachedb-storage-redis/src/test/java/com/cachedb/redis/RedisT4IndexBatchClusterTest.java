package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
@EnabledIfSystemProperty(named = "cachedb.redis.cluster", matches = "true")
class RedisT4IndexBatchClusterTest {
    @Test void colocatedTenantScriptsAndSplitOwnerFallbackUseTheRealCluster() {
        var config = DefaultJedisClientConfig.builder().connectionTimeoutMillis(3000).socketTimeoutMillis(5000)
                .hostAndPortMapper(address -> System.getProperty("cachedb.redis.cluster.host") != null ? address
                        : new HostAndPort("127.0.0.1", address.getPort() < 10000 ? address.getPort() + 10000 : address.getPort()))
                .build();
        try (var client = new JedisClusterOperations(new JedisCluster(Set.of(new HostAndPort(
                System.getProperty("cachedb.redis.cluster.host", "127.0.0.1"),
                Integer.getInteger("cachedb.redis.cluster.port", 17001))), config))) {
            String prefix = "t4-{" + UUID.randomUUID() + "}:";
            var entities = List.of(prefix + "entity:1", prefix + "entity:2", prefix + "entity:3");
            var tombstones = List.of(prefix + "tomb:1", prefix + "tomb:2", prefix + "tomb:3");
            var metadata = List.of(prefix + "meta:1", prefix + "meta:2", prefix + "meta:3");
            var owners = List.of(prefix + "owner:1", prefix + "owner:2", prefix + "owner:3");
            var cleanup = new ArrayList<String>();
            cleanup.addAll(entities); cleanup.addAll(tombstones); cleanup.addAll(metadata); cleanup.addAll(owners);
            try {
                for (int i = 0; i < 3; i++) {
                    client.commands().set(entities.get(i), "payload-" + i);
                    client.commands().hset(metadata.get(i), Map.of("category", "x", "amount", "0.125"));
                    client.commands().set(owners.get(i), i == 1 ? "b" : "a");
                }
                client.commands().set(tombstones.get(2), "1");
                var result = client.aggregateIndexBatch(entities, tombstones, metadata, owners, "a", "amount", false).orElseThrow();
                assertEquals(1, result.count()); assertEquals(List.of("0.125"), result.values());
                assertEquals(List.of(new RedisClientOperations.FacetIndexRow(0, "x", "0.125")),
                        client.facetIndexBatch(entities, tombstones, metadata, owners, "a", "category", "amount").orElseThrow().rows());
                assertEquals(Arrays.asList("payload-0", null, null),
                        client.liveIndexPayloadBatch(entities, tombstones, owners, "a").orElseThrow());
                client.commands().del(tombstones.get(2));
                assertEquals(2, client.aggregateIndexBatch(entities, tombstones, metadata, owners, "a", null, true)
                        .orElseThrow().count());
                assertEquals(List.of(new RedisClientOperations.FacetIndexRow(0, "x", null),
                        new RedisClientOperations.FacetIndexRow(2, "x", null)),
                        client.facetIndexBatch(entities, tombstones, metadata, owners, "a", "category", null)
                                .orElseThrow().rows());
                String split = "t4-split-" + UUID.randomUUID();
                for (int attempt = 0; attempt < 16 && redis.clients.jedis.util.JedisClusterCRC16.getSlot(split)
                        == redis.clients.jedis.util.JedisClusterCRC16.getSlot(entities.get(0)); attempt++) split += "x";
                assertNotEquals(redis.clients.jedis.util.JedisClusterCRC16.getSlot(entities.get(0)),
                        redis.clients.jedis.util.JedisClusterCRC16.getSlot(split));
                var splitOwners = List.of(split, owners.get(1), owners.get(2));
                assertTrue(client.aggregateIndexBatch(entities, tombstones, metadata, splitOwners, "a", null, true).isEmpty());
                assertTrue(client.facetIndexBatch(entities, tombstones, metadata, splitOwners, "a", "category", null).isEmpty());
                assertTrue(client.liveIndexPayloadBatch(entities, tombstones, splitOwners, "a").isEmpty());
                client.commands().del(owners.get(1));
                client.commands().hset(owners.get(1), "wrong", "type");
                assertTrue(assertThrows(redis.clients.jedis.exceptions.JedisDataException.class,
                        () -> client.aggregateIndexBatch(entities, tombstones, metadata, owners, "a", null, true))
                        .getMessage().contains("WRONGTYPE"));
                assertTrue(assertThrows(redis.clients.jedis.exceptions.JedisDataException.class,
                        () -> client.facetIndexBatch(entities, tombstones, metadata, owners, "a", "category", null))
                        .getMessage().contains("WRONGTYPE"));
            } finally {
                client.delete(cleanup.toArray(String[]::new));
            }
        }
    }
}
