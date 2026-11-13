package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Isolated;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.util.SafeEncoder;

@Isolated("Temporarily changes the global Jedis charset")
class RedisBinaryCountCharsetTest {
    @Test void genericOwnedNonUtf8CountBypassesNativeAndRetainsLuaWindows() {
        var original = SafeEncoder.DEFAULT_CHARSET;
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            SafeEncoder.DEFAULT_CHARSET = StandardCharsets.ISO_8859_1;
            pool.connection.reply(List.of(List.of(512L, List.of(), List.of()), List.of(512L, List.of(), List.of())));
            assertEquals(1024, RedisStandaloneIndexSnapshot.indexCounts(pool, 1024, start ->
                    new RedisClientOperations.IndexReadKeys(Collections.nCopies(512, "entity:caf\u00e9"),
                            Collections.nCopies(512, "tomb:caf\u00e9"), Collections.nCopies(512, null),
                            Collections.nCopies(512, "owner:caf\u00e9")), "t\u00e9").orElseThrow());
            assertEquals(List.of("EVAL", "EVAL"), pool.connection.names());
            assertEquals(1, pool.borrows);
            assertEquals(1, pool.healthyReturns);
            assertEquals(0, pool.brokenReturns);
        } finally {
            SafeEncoder.DEFAULT_CHARSET = original;
        }
        assertSame(original, SafeEncoder.DEFAULT_CHARSET);
    }

    @Test
    @EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
    void genericOwnedMultiwindowCountRetainsNonUtf8TenantAndKeyEncodingOnRealRedis() {
        var original = SafeEncoder.DEFAULT_CHARSET;
        String prefix = "count-charset-" + java.util.UUID.randomUUID() + ":caf\u00e9:";
        var entities = java.util.stream.IntStream.range(0, 1024).mapToObj(i -> prefix + "entity:" + i).toList();
        var tombstones = java.util.stream.IntStream.range(0, 1024).mapToObj(i -> prefix + "tomb:" + i).toList();
        var owners = java.util.stream.IntStream.range(0, 1024).mapToObj(i -> prefix + "owner:" + i).toList();
        try (var client = new JedisPooled(URI.create(RedisTestEndpoint.uri()), 2000)) {
            SafeEncoder.DEFAULT_CHARSET = StandardCharsets.ISO_8859_1;
            try {
                try (var pipeline = client.pipelined()) {
                    for (int i = 0; i < entities.size(); i++) {
                        pipeline.set(entities.get(i), "payload");
                        pipeline.set(owners.get(i), "t\u00e9");
                    }
                    pipeline.sync();
                }
                var operations = new JedisPooledOperations(client);
                assertFalse(RedisBinaryCountKeys.supports(operations, new RedisKeyStrategy()));
                java.util.function.IntFunction<RedisClientOperations.IndexReadKeys> windows = start ->
                        new RedisClientOperations.IndexReadKeys(entities.subList(start, start + 512),
                                tombstones.subList(start, start + 512), Collections.nCopies(512, null),
                                owners.subList(start, start + 512));
                assertEquals(1024, operations.aggregateIndexCounts(1024, windows, "t\u00e9").orElseThrow());
                client.set(owners.get(0), "t\u00e9-extra");
                client.set(tombstones.get(1), "deleted");
                assertEquals(1022, operations.aggregateIndexCounts(1024, windows, "t\u00e9").orElseThrow());
            } finally {
                client.del(entities.toArray(String[]::new));
                client.del(tombstones.toArray(String[]::new));
                client.del(owners.toArray(String[]::new));
            }
        } finally {
            SafeEncoder.DEFAULT_CHARSET = original;
        }
        assertSame(original, SafeEncoder.DEFAULT_CHARSET);
    }

    @Test void binaryCountEligibilityAndStringFallbackKeysRespectCharsetAndRestoreIt() {
        var original = SafeEncoder.DEFAULT_CHARSET;
        var strategies = List.of(new RedisKeyStrategy("p"),
                new ClusterHashTagKeyStrategy("p", "scope"), new ClusterHashTagKeyStrategy("p"));
        var roots = List.of("p:ns", "p:{scope}:ns", "p:{ns}");
        String id = "caf\u00e9";
        try {
            for (var charset : List.of(StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1, StandardCharsets.UTF_8)) {
                SafeEncoder.DEFAULT_CHARSET = charset;
                for (int i = 0; i < strategies.size(); i++) {
                    var strategy = strategies.get(i);
                    assertEquals(StandardCharsets.UTF_8.equals(charset), RedisBinaryCountKeys.supports(strategy));
                    for (String tenant : Arrays.asList(null, "t\u00e9")) {
                        if (StandardCharsets.UTF_8.equals(charset)) {
                            assertDoesNotThrow(() -> new RedisBinaryCountKeys(strategy, "ns", List.of(id), tenant));
                        } else {
                            assertThrows(IllegalArgumentException.class,
                                    () -> new RedisBinaryCountKeys(strategy, "ns", List.of(id), tenant));
                        }
                        var expected = new ArrayList<>(List.of(roots.get(i) + ":entity:" + id,
                                roots.get(i) + ":tombstone:" + id));
                        List<String> owners = tenant == null ? null
                                : List.of(strategy.versionKey("ns", id) + ":tenant");
                        if (tenant != null) expected.add(roots.get(i) + ":version:" + id + ":tenant");
                        var fallback = RedisClientOperations.indexAggregateCommand(
                                List.of(strategy.entityKey("ns", id)), List.of(strategy.tombstoneKey("ns", id)),
                                Collections.singletonList(null), owners, tenant, null, true);
                        assertEquals(expected, fallback.keys());
                        for (int key = 0; key < expected.size(); key++) {
                            byte[] encoded = SafeEncoder.encode(fallback.keys().get(key));
                            assertArrayEquals(expected.get(key).getBytes(charset), encoded);
                            assertEquals(expected.get(key), SafeEncoder.encode(encoded));
                        }
                        assertEquals(tenant == null ? "" : tenant, fallback.arguments().get(4));
                    }
                }
            }
        } finally {
            SafeEncoder.DEFAULT_CHARSET = original;
        }
        assertSame(original, SafeEncoder.DEFAULT_CHARSET);
    }
}
