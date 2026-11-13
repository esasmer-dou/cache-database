package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;
import redis.clients.jedis.Protocol.Command;
import redis.clients.jedis.exceptions.JedisDataException;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class RedisStandaloneCountFacetPipelineRedisTest {
    @Test void repositoryCountAndCountFacetsUsePipelineFor10000_1024_545() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri())) {
            for (int size : List.of(10_000, 1024, 545)) {
                try (var fixture = new RedisT4AggregationRedisTest.Fixture(redis, true);
                        var tenant = CacheTenantContext.withTenant("a")) {
                    var rows = rows(size, "a");
                    fixture.seed(rows);
                    var operations = new ObservedOperations(redis);
                    var repository = repository(operations, fixture);
                    var count = AggregateSpec.count(null);
                    assertEquals(size, repository.aggregate(count).count());
                    assertEquals(1, operations.countRequests);
                    assertTrue(operations.countResult.isPresent());
                    assertEquals(windows(size, 512), sizes(operations.countWindows));
                    assertTrue(operations.legacyCounts.isEmpty());

                    operations.reset();
                    var facets = repository.aggregateBy("category", count);
                    assertTwentyGroups(size, facets);
                    assertEquals(1, operations.facetRequests);
                    assertTrue(operations.facetResult.isPresent());
                    assertEquals(windows(size, 512), sizes(operations.facetWindows));
                    assertTrue(operations.legacyFacets.isEmpty());
                    var reference = fixture.repository(new RedisT4AggregationRedisTest.Client(redis, true), CachePolicy.defaults());
                    assertFacets(reference.aggregateBy("category", count), facets);

                    if (size == 545) {
                        for (AggregateFunction function : AggregateFunction.values()) {
                            var spec = new AggregateSpec(function, "amount", null);
                            operations.reset();
                            RedisT4AggregationRedisTest.assertResult(reference.aggregate(spec), repository.aggregate(spec));
                            assertEquals(0, operations.countRequests, "Only COUNT(*) uses the new COUNT phase");
                            operations.reset();
                            assertFacets(reference.aggregateBy("category", spec), repository.aggregateBy("category", spec));
                            assertTrue(operations.facetResult.isPresent());
                            assertEquals(List.of(256, 256, 33), sizes(operations.facetWindows));
                            assertTrue(operations.legacyFacets.isEmpty());
                        }
                    }
                    if (size == 10_000) {
                        // Select the actual first window, not a guessed Redis SET iteration order.
                        var byKey = rows.stream().collect(Collectors.toMap(row -> fixture.entity(row.id()), row -> row));
                        String heavyGroup = "h".repeat(4096);
                        operations.reset();
                        operations.beforeFacet = (start, window) -> {
                            if (start != 0) return;
                            operations.beforeFacet = null;
                            fixture.seed(window.entities().stream().map(key -> {
                                var row = byKey.get(key);
                                return new RedisT4AggregationRedisTest.Row(row.id(), row.tenant(), heavyGroup, row.amount(), row.label());
                            }).toList());
                        };
                        var uneven = repository.aggregateBy("category", count);
                        assertTrue(operations.facetResult.isEmpty(), "A valid uneven phase must request legacy fallback");
                        assertEquals(20, operations.facetWindows.size());
                        assertEquals(windows(size, 512), operations.legacyFacets);
                        assertEquals(512, uneven.get(heavyGroup).count());
                        assertEquals(size, total(uneven));
                        assertFacets(reference.aggregateBy("category", count), uneven);
                        // A later live metadata failure must outrank the first window's quota marker.
                        var last = operations.facetWindows.getLast();
                        String metadata = last.indexMetadata().getLast();
                        redis.hdel(metadata, "category");
                        operations.reset();
                        assertTrue(assertThrows(JedisDataException.class,
                                () -> repository.aggregateBy("category", count)).getMessage().contains("Incomplete facet index metadata"));
                        assertTrue(operations.legacyFacets.isEmpty());
                    }
                }
            }
        }
    }

    @Test void unownedCountPipelinesEveryTailWithoutReadingPayloadsOrMetadata() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var scope = CacheTenantContext.administrative()) {
            for (int size : List.of(1, 512, 513, 545, 1024, 1025, 10_000)) {
                try (var fixture = new RedisT4AggregationRedisTest.Fixture(redis, false)) {
                    var seeded = rows(size, "a");
                    for (int start = 0; start < size; start += 512)
                        fixture.seed(seeded.subList(start, Math.min(size, start + 512)));
                    redis.set(fixture.entity(0), size == 545 ? "x".repeat(16 * 1024 * 1024 + 1) : "not-a-row");
                    String meta = fixture.keys.indexMetaKey("t4_rows", Math.min(3, size - 1));
                    redis.del(meta); redis.set(meta, "COUNT-star-must-not-read-metadata");
                    var operations = new ObservedOperations(redis);
                    var repository = repository(operations, fixture);
                    assertEquals(size, repository.aggregate(AggregateSpec.count(null)).count());
                    assertEquals(size > 512 ? 1 : 0, operations.countRequests);
                    assertEquals(size > 512, operations.countResult.isPresent());
                    assertEquals(size > 512 ? windows(size, 512) : List.of(), sizes(operations.countWindows));
                    assertTrue(operations.countWindows.stream().allMatch(window -> window.owners() == null));
                    assertEquals(size > 512 ? List.of() : List.of(size), operations.legacyCounts);
                    assertEquals(0, operations.payloadReads);
                    if (size <= 512) continue;
                    operations.reset();
                    operations.beforeCount = (start, window) -> {
                        if (start == 0) {
                            redis.del(fixture.entity(0));
                            redis.set(fixture.tombstone(1), "deleted");
                            redis.expireAt(fixture.entity(2), 1);
                        }
                    };
                    assertEquals(size - 3, repository.aggregate(AggregateSpec.count(null)).count());
                    assertTrue(operations.countResult.isPresent());
                    assertTrue(operations.legacyCounts.isEmpty());
                    assertEquals(0, operations.payloadReads);
                    assertEquals(0, redis.getPool().getNumActive());
                }
            }
        }
    }

    @Test void unownedEvalDeniedCountUsesEveryNativeWindowWithoutPayloadReads() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var fixture = new RedisT4AggregationRedisTest.Fixture(redis, false);
                var scope = CacheTenantContext.administrative()) {
            fixture.seed(rows(1025, "a"));
            redis.set(fixture.entity(0), "not-a-row");
            String user = "t4_unowned_" + UUID.randomUUID().toString().replace("-", "");
            String password = UUID.randomUUID().toString();
            try {
                redis.sendCommand(Command.ACL, "SETUSER", user, "reset", "on", ">" + password,
                        "~" + fixture.keys.namespacePrefix("t4_rows") + ":*", "+@read", "+ping", "+hello",
                        "+client|setinfo", "+multi", "+exec", "-eval");
                var control = new TraceControl();
                var config = DefaultJedisClientConfig.builder().user(user).password(password)
                        .connectionTimeoutMillis(2000).socketTimeoutMillis(2000).build();
                try (var client = new TraceClient(config, control)) {
                    assertEquals("PONG", client.ping());
                    control.commands.clear();
                    var operations = new ObservedOperations(client);
                    var repository = repository(operations, fixture);
                    assertEquals(1025, repository.aggregate(AggregateSpec.count(null)).count());
                    assertEquals(1, operations.countRequests);
                    assertTrue(operations.countResult.isPresent());
                    assertEquals(List.of(512, 512, 1), sizes(operations.countWindows));
                    assertTrue(operations.legacyCounts.isEmpty());
                    assertEquals(3, Collections.frequency(control.commands, "EXEC"));
                    assertFalse(control.commands.contains("EVAL"), "Healthy unowned COUNT must not attempt Lua");
                    assertFalse(control.commands.contains("MGET"));
                    assertFalse(control.commands.contains("GETRANGE"));
                    assertFalse(control.commands.contains("HGET"));
                    assertFalse(control.commands.contains("HSTRLEN"));
                    assertEquals(0, operations.payloadReads);
                    assertEquals(0, client.getPool().getNumActive());
                }
            } finally { redis.sendCommand(Command.ACL, "DELUSER", user); }
        }
    }

    @Test void countPhaseRoutingRemainsBoundedAcrossTenThousandCandidateWindowBoundaries() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri())) {
            for (int size : List.of(10_239, 10_240, 10_241)) {
                try (var fixture = new RedisT4AggregationRedisTest.Fixture(redis, true);
                        var tenant = CacheTenantContext.withTenant("a")) {
                    var rows = rows(size, "a");
                    for (int start = 0; start < size; start += 512)
                        fixture.seed(rows.subList(start, Math.min(size, start + 512)));
                    assertFalse(redis.exists(fixture.keys.indexDegradedKey("t4_rows")));
                    var operations = new ObservedOperations(redis);
                    var repository = repository(operations, fixture);
                    assertEquals(size, repository.aggregate(AggregateSpec.count(null)).count());
                    assertEquals(1, operations.countRequests, "COUNT phase not requested for " + size);
                    if (size == 10_241) {
                        assertTrue(operations.countResult.isEmpty());
                        assertTrue(operations.countWindows.isEmpty());
                        assertEquals(windows(size, 512), operations.legacyCounts);
                    } else {
                        assertTrue(operations.countResult.isPresent());
                        assertEquals(windows(size, 512), sizes(operations.countWindows));
                        assertTrue(operations.legacyCounts.isEmpty());
                    }
                    assertEquals(0, redis.getPool().getNumActive());
                }
            }
        }
    }

    @Test void repository513And1025PreserveNativeTailRoutingForCountAndBothFacetShapes() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri())) {
            for (int size : List.of(513, 1025)) {
                try (var fixture = new RedisT4AggregationRedisTest.Fixture(redis, true);
                        var tenant = CacheTenantContext.withTenant("a")) {
                    fixture.seed(rows(size, "a"));
                    var operations = new ObservedOperations(redis);
                    var repository = repository(operations, fixture);
                    assertEquals(size, repository.aggregate(AggregateSpec.count(null)).count());
                    assertEquals(1, operations.countRequests);
                    assertTrue(operations.countResult.isEmpty());
                    assertTrue(operations.countWindows.isEmpty(), "Decline before evaluating any window factory");
                    assertEquals(windows(size, 512), operations.legacyCounts);
                    for (String field : Arrays.asList(null, "label")) {
                        operations.reset();
                        var facets = repository.aggregateBy("category", new AggregateSpec(AggregateFunction.COUNT, field, null));
                        assertTwentyGroups(size, facets);
                        assertEquals(1, operations.facetRequests);
                        assertTrue(operations.facetResult.isEmpty());
                        assertTrue(operations.facetWindows.isEmpty());
                        assertEquals(windows(size, field == null ? 512 : 256), operations.legacyFacets);
                    }
                    assertEquals(0, redis.getPool().getNumActive());
                }
            }
        }
    }

    @Test void repositoryEmptyAndForeignOnlyResultsAreNotUnsupportedCapabilities() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var fixture = new RedisT4AggregationRedisTest.Fixture(redis, true);
                var tenant = CacheTenantContext.withTenant("a")) {
            var operations = new ObservedOperations(redis);
            var repository = repository(operations, fixture);
            var count = AggregateSpec.count(null);
            assertEquals(0, repository.aggregate(count).count());
            assertTrue(repository.aggregateBy("category", count).isEmpty());
            assertEquals(0, operations.countRequests);
            assertEquals(0, operations.facetRequests);
            var rows = rows(1024, "a");
            fixture.seed(rows);
            try (var pipeline = redis.pipelined()) {
                rows.forEach(row -> pipeline.set(fixture.owner(row.id()), "b"));
                pipeline.sync();
            }
            assertEquals(0, repository.aggregate(count).count());
            assertTrue(operations.countResult.isPresent());
            assertTrue(operations.legacyCounts.isEmpty());
            assertTrue(repository.aggregateBy("category", count).isEmpty());
            assertEquals(2, operations.facetResult.orElseThrow().size());
            assertTrue(operations.facetResult.orElseThrow().stream().allMatch(batch -> batch.rows().isEmpty()));
            assertTrue(operations.legacyFacets.isEmpty());
            operations.reset();
            operations.declinePhases = true;
            assertEquals(0, repository.aggregate(count).count());
            assertTrue(repository.aggregateBy("category", count).isEmpty());
            assertTrue(operations.countResult.isEmpty());
            assertTrue(operations.facetResult.isEmpty());
            assertEquals(List.of(512, 512), operations.legacyCounts);
            assertEquals(List.of(512, 512), operations.legacyFacets);
        }
    }

    @Test void unicodeOwnershipLivenessAndTombstonesRemainAuthoritativeInEveryWindow() {
        String owner = "\u015firket";
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var fixture = new RedisT4AggregationRedisTest.Fixture(redis, true);
                var tenant = CacheTenantContext.withTenant(owner)) {
            fixture.seed(rows(1536, owner));
            var operations = new ObservedOperations(redis);
            operations.beforeCount = (start, window) -> {
                redis.set(window.owners().get(0), "foreign");
                redis.del(window.owners().get(1));
                redis.set(window.owners().get(2), "");
                redis.set(window.owners().get(3), owner + "x".repeat(2 * 1024 * 1024));
                redis.set(window.tombstones().get(4), "deleted");
                redis.del(window.entities().get(5));
                for (int dead : List.of(4, 5)) {
                    redis.del(window.owners().get(dead));
                    redis.hset(window.owners().get(dead), "not", "a string");
                }
                redis.del(window.indexMetadata().subList(0, 6).toArray(String[]::new));
            };
            var repository = repository(operations, fixture);
            var count = AggregateSpec.count(null);
            assertEquals(1536 - 18, repository.aggregate(count).count());
            assertEquals(List.of(512, 512, 512), sizes(operations.countWindows));
            assertTrue(operations.countResult.isPresent());
            assertTrue(operations.legacyCounts.isEmpty());
            operations.beforeCount = null;
            var facets = repository.aggregateBy("category", count);
            assertEquals(1536 - 18, total(facets));
            assertTrue(operations.facetResult.isPresent());
            assertTrue(operations.legacyFacets.isEmpty());
            var reference = fixture.repository(new RedisT4AggregationRedisTest.Client(redis, true), CachePolicy.defaults());
            RedisT4AggregationRedisTest.assertResult(reference.aggregate(count), repository.aggregate(count));
            assertFacets(reference.aggregateBy("category", count), facets);
        }
    }

    @Test void liveWrongtypeOwnerAndMalformedMetadataAtFirstMiddleAndFinalWindowsNeverFallback() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var fixture = new RedisT4AggregationRedisTest.Fixture(redis, true);
                var tenant = CacheTenantContext.withTenant("a")) {
            var rows = rows(1536, "a");
            fixture.seed(rows);
            var byKey = rows.stream().collect(Collectors.toMap(row -> fixture.entity(row.id()), row -> row));
            var operations = new ObservedOperations(redis);
            var repository = repository(operations, fixture);
            var count = AggregateSpec.count(null);
            assertEquals(1536, repository.aggregate(count).count());
            var windows = List.copyOf(operations.countWindows);
            for (var window : windows) {
                String owner = window.owners().getFirst();
                redis.del(owner); redis.hset(owner, "bad", "type");
                operations.reset();
                assertTrue(assertThrows(JedisDataException.class, () -> repository.aggregate(count)).getMessage().contains("WRONGTYPE"));
                assertTrue(operations.legacyCounts.isEmpty());
                operations.reset();
                assertTrue(assertThrows(JedisDataException.class,
                        () -> repository.aggregateBy("category", count)).getMessage().contains("WRONGTYPE"));
                assertTrue(operations.legacyFacets.isEmpty());
                redis.del(owner); redis.set(owner, "a");

                String metadata = window.indexMetadata().getFirst();
                redis.hdel(metadata, "category");
                operations.reset();
                assertEquals(1536, repository.aggregate(count).count(), "COUNT(*) must not read index fields");
                assertTrue(assertThrows(JedisDataException.class,
                        () -> repository.aggregateBy("category", count)).getMessage().contains("Incomplete facet index metadata"));
                assertTrue(operations.legacyFacets.isEmpty());
                redis.hset(metadata, "category", byKey.get(window.entities().getFirst()).category());
            }
            assertEquals(3, windows.size());
            assertEquals(1536, repository.aggregate(count).count());
            assertTwentyGroups(1536, repository.aggregateBy("category", count));
        }
    }

    @Test void ambiguousNullPositionsStillRereadValidateAndRequalifyPayloadsAfterReturningThePhaseLease() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var fixture = new RedisT4AggregationRedisTest.Fixture(redis, true);
                var tenant = CacheTenantContext.withTenant("a")) {
            var rows = new ArrayList<RedisT4AggregationRedisTest.Row>();
            for (long id = 0; id < 1536; id++) {
                String group = switch ((int) (id % 4)) { case 0 -> "__NULL__"; case 1 -> null; case 2 -> ""; default -> "g"; };
                String label = switch ((int) (id % 3)) { case 0 -> null; case 1 -> "__NULL__"; default -> ""; };
                rows.add(new RedisT4AggregationRedisTest.Row(id, "a", group, BigDecimal.ONE, label));
            }
            fixture.seed(rows);
            var operations = new ObservedOperations(redis);
            var repository = repository(operations, fixture);
            var reference = fixture.repository(new RedisT4AggregationRedisTest.Client(redis, true), CachePolicy.defaults());
            for (String field : Arrays.asList(null, "label")) {
                operations.reset();
                var spec = new AggregateSpec(AggregateFunction.COUNT, field, null);
                var result = repository.aggregateBy("category", spec);
                assertTrue(operations.facetResult.isPresent());
                assertTrue(operations.legacyFacets.isEmpty());
                assertTrue(operations.payloadReads > 0);
                assertEquals(field == null ? 1152 : 768, total(result));
                assertTrue(result.containsKey("__NULL__"));
                assertTrue(result.containsKey(""));
                assertFacets(reference.aggregateBy("category", spec), result);
            }
            operations.reset();
            redis.set(fixture.entity(0), "malformed");
            assertThrows(NumberFormatException.class, () -> repository.aggregateBy("category", AggregateSpec.count(null)));
            assertTrue(operations.facetResult.isPresent());
            assertTrue(operations.legacyFacets.isEmpty());
            redis.set(fixture.entity(0), RedisT4AggregationRedisTest.CODEC.toRedisValue(rows.getFirst()));
            operations.reset();
            operations.beforePayload = () -> {
                assertEquals(0, redis.getPool().getNumActive(), "No phase lease may be held during payload rereads");
                redis.set(fixture.owner(0), "foreign");
                redis.set(fixture.tombstone(4), "deleted");
                redis.del(fixture.entity(8));
            };
            assertEquals(1149, total(repository.aggregateBy("category", AggregateSpec.count(null))));
            assertTrue(operations.facetResult.isPresent());
            assertTrue(operations.legacyFacets.isEmpty());
        }
    }

    @Test void validSixteenMiBFacetFallsBackInFullAndLegacyOperationLimitStillRejectsOverflow() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var fixture = new RedisT4AggregationRedisTest.Fixture(redis, true);
                var tenant = CacheTenantContext.withTenant("a")) {
            String label = "x".repeat(4096);
            var rows = new ArrayList<RedisT4AggregationRedisTest.Row>();
            for (long id = 0; id < 1024; id++) {
                String group = "g" + id % 20;
                group += "h".repeat(4096 - group.length());
                rows.add(new RedisT4AggregationRedisTest.Row(id, "a", group, BigDecimal.ONE, label));
            }
            fixture.seed(rows);
            assertEquals(16L * 1024 * 1024, rows.stream().mapToLong(row -> 2L * (row.category().length() + row.label().length())).sum());
            var operations = new ObservedOperations(redis);
            var repository = repository(operations, fixture);
            var spec = new AggregateSpec(AggregateFunction.COUNT, "label", null);
            var result = repository.aggregateBy("category", spec);
            assertEquals(1, operations.facetRequests);
            assertTrue(operations.facetResult.isEmpty());
            assertEquals(List.of(256, 256, 256, 256), sizes(operations.facetWindows));
            assertEquals(List.of(256, 256, 256, 256), operations.legacyFacets);
            assertEquals(20, result.size()); assertEquals(1024, total(result));
            var reference = fixture.repository(new RedisT4AggregationRedisTest.Client(redis, true), CachePolicy.defaults());
            assertFacets(reference.aggregateBy("category", spec), result);
            assertThrows(UnsupportedOperationException.class, () -> result.clear());
            redis.hset(fixture.keys.indexMetaKey("t4_rows", 0), "label", label + "x");
            operations.reset();
            assertTrue(assertThrows(IllegalStateException.class,
                    () -> repository.aggregateBy("category", spec)).getMessage().contains("Facet byte budget"));
            assertTrue(operations.facetResult.isEmpty());
            assertEquals(4, operations.legacyFacets.size());
        }
    }

    @Test void quotaExhaustionCannotHideMalformedMetadataLaterInTheSameRealLuaWindow() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var fixture = new RedisT4AggregationRedisTest.Fixture(redis, true);
                var tenant = CacheTenantContext.withTenant("a")) {
            var rows = rows(1536, "a");
            fixture.seed(rows);
            var byKey = rows.stream().collect(Collectors.toMap(row -> fixture.entity(row.id()), row -> row));
            var operations = new ObservedOperations(redis);
            String heavy = "h".repeat(4096);
            BiConsumer<Integer, RedisClientOperations.IndexReadKeys> fillQuota = (start, window) -> {
                if (start == 0) fixture.seed(window.entities().stream().map(key -> {
                    var row = byKey.get(key);
                    return new RedisT4AggregationRedisTest.Row(row.id(), row.tenant(), heavy, row.amount(), row.label());
                }).toList());
            };
            operations.beforeFacet = fillQuota;
            var repository = repository(operations, fixture);
            assertEquals(1536, total(repository.aggregateBy("category", AggregateSpec.count(null))));
            assertTrue(operations.facetResult.isEmpty());
            assertEquals(List.of(512, 512, 512), operations.legacyFacets);
            var window = operations.facetWindows.getFirst();
            long quota = (16L * 1024 * 1024 - 64L * (1536 + 3)) / 9;
            assertTrue(511L * heavy.length() > quota, "Quota is exhausted before the last row");
            var request = RedisClientOperations.facetIndexCommand(window.entities(), window.tombstones(),
                    window.indexMetadata(), window.owners(), "a", "category", null, quota);
            assertEquals(List.of("cachedb-facet-phase-budget-v1", 512L, quota),
                    redis.eval(request.script(), request.keys(), request.arguments()));
            for (boolean wrongtype : List.of(false, true)) {
                operations.reset();
                operations.beforeFacet = (start, current) -> {
                    if (start != 0) return;
                    fillQuota.accept(start, current);
                    String malformed = current.indexMetadata().getLast();
                    if (wrongtype) { redis.del(malformed); redis.set(malformed, "not-a-hash"); }
                    else redis.hdel(malformed, "category");
                };
                var failure = assertThrows(JedisDataException.class,
                        () -> repository.aggregateBy("category", AggregateSpec.count(null)));
                assertTrue(failure.getMessage().contains(wrongtype ? "WRONGTYPE" : "Incomplete facet index metadata"));
                assertEquals(1, operations.facetRequests);
                assertEquals(3, operations.facetWindows.size());
                assertTrue(operations.legacyFacets.isEmpty(), "Malformed rows cannot become quota fallback");
                assertEquals(0, redis.getPool().getNumActive());
            }
        }
    }

    @Test void realEvalDeniedMultiwindowRepositoryCountAndFacetsRetainNativeFallback() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var fixture = new RedisT4AggregationRedisTest.Fixture(redis, true);
                var tenant = CacheTenantContext.withTenant("a")) {
            fixture.seed(rows(1024, "a"));
            redis.set(fixture.owner(0), "b");
            String user = "t4_phase_" + UUID.randomUUID().toString().replace("-", "");
            String password = UUID.randomUUID().toString();
            try {
                redis.sendCommand(Command.ACL, "SETUSER", user, "reset", "on", ">" + password,
                        "~" + fixture.keys.namespacePrefix("t4_rows") + ":*", "+@read", "+ping", "+hello",
                        "+client|setinfo", "+watch", "+multi", "+exec", "+unwatch", "-eval");
                var control = new TraceControl();
                var config = DefaultJedisClientConfig.builder().user(user).password(password)
                        .connectionTimeoutMillis(2000).socketTimeoutMillis(2000).build();
                try (var client = new TraceClient(config, control)) {
                    assertEquals("PONG", client.ping());
                    control.commands.clear();
                    var operations = new ObservedOperations(client);
                    var repository = repository(operations, fixture);
                    assertEquals(1023, repository.aggregate(AggregateSpec.count(null)).count());
                    assertTrue(operations.countResult.isPresent());
                    assertEquals(List.of(512, 512), sizes(operations.countWindows));
                    assertTrue(operations.legacyCounts.isEmpty());
                    assertEquals(0, Collections.frequency(control.commands, "EVAL"), "Healthy owned COUNT must be native-primary");
                    assertEquals(2, Collections.frequency(control.commands, "EXEC"));
                    assertEquals(1024, Collections.frequency(control.commands, "GETRANGE"));
                    assertFalse(control.commands.contains("STRLEN"));
                    assertFalse(control.commands.contains("GET"));
                    assertFalse(control.commands.contains("MGET"));
                    assertEquals(0, client.getPool().getDestroyedCount(), "No EVAL lease is needed for healthy native COUNT");
                    assertEquals(0, operations.payloadReads);
                    var reference = fixture.repository(new RedisT4AggregationRedisTest.Client(redis, true), CachePolicy.defaults());
                    for (String field : Arrays.asList(null, "label")) {
                        operations.reset(); control.commands.clear();
                        long destroyed = client.getPool().getDestroyedCount();
                        var spec = new AggregateSpec(AggregateFunction.COUNT, field, null);
                        var result = repository.aggregateBy("category", spec);
                        int windowRows = field == null ? 512 : 256;
                        int count = 1024 / windowRows;
                        assertEquals(1023, total(result));
                        assertFacets(reference.aggregateBy("category", spec), result);
                        assertTrue(operations.facetResult.isEmpty());
                        assertEquals(windows(1024, windowRows), sizes(operations.facetWindows));
                        assertEquals(windows(1024, windowRows), operations.legacyFacets);
                        assertEquals(2 * count, Collections.frequency(control.commands, "EVAL"));
                        assertEquals(count, Collections.frequency(control.commands, "EXEC"));
                        assertTrue(control.commands.contains("HGET"));
                        assertTrue(client.getPool().getDestroyedCount() >= destroyed + count + 1);
                        assertEquals(0, operations.payloadReads);
                        assertEquals(0, client.getPool().getNumActive());
                    }
                }
            } finally { redis.sendCommand(Command.ACL, "DELUSER", user); }
        }
    }

    @Test void acceptedGlobalFacetPhaseAndOwnedEightNineGlobalTenElevenTailsRetainRouting() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri())) {
            for (boolean owned : List.of(true, false)) {
                for (int tail : owned ? List.of(8, 9) : List.of(10, 11)) {
                    int size = 512 + tail;
                    try (var fixture = new RedisT4AggregationRedisTest.Fixture(redis, owned);
                            var tenant = owned ? CacheTenantContext.withTenant("a") : CacheTenantContext.administrative()) {
                        fixture.seed(rows(size, "a"));
                        var operations = new ObservedOperations(redis);
                        var repository = repository(operations, fixture);
                        for (String field : Arrays.asList(null, "label")) {
                            operations.reset();
                            assertTwentyGroups(size, repository.aggregateBy("category",
                                    new AggregateSpec(AggregateFunction.COUNT, field, null)));
                            assertEquals(1, operations.facetRequests);
                            int windowRows = field == null ? 512 : 256;
                            boolean accepted = tail > (owned ? 8 : 10);
                            assertEquals(accepted, operations.facetResult.isPresent());
                            if (accepted) {
                                assertEquals(windows(size, windowRows), sizes(operations.facetWindows));
                                assertTrue(operations.legacyFacets.isEmpty());
                                assertTrue(operations.facetWindows.stream().allMatch(window -> owned
                                        ? window.owners() != null : window.owners() == null));
                            } else {
                                assertTrue(operations.facetWindows.isEmpty(), "Native tails decline before factory dispatch");
                                assertEquals(windows(size, windowRows), operations.legacyFacets);
                            }
                        }
                        assertEquals(0, redis.getPool().getNumActive());
                    }
                }
            }
        }
    }

    @Test void sameRepositorySwitchesTenantAToBAndBackWithoutReusingScopeOrPhaseResults() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var fixture = new RedisT4AggregationRedisTest.Fixture(redis, true)) {
            var rows = rows(1024, "a");
            rows.addAll(rows(545, "b").stream().map(row -> new RedisT4AggregationRedisTest.Row(
                    row.id() + 10_000, row.tenant(), "b" + row.category(), row.amount(), row.label())).toList());
            fixture.seed(rows);
            String[] ids = rows.stream().map(row -> row.id().toString()).toArray(String[]::new);
            for (String tenant : List.of("a", "b")) redis.sadd(fixture.keys.indexExactKey("t4_rows", "tenant",
                    RedisIndexKeySpace.encodeKeyPart(tenant)), ids);
            var operations = new ObservedOperations(redis);
            var repository = repository(operations, fixture);
            for (String scope : List.of("a", "b", "a")) {
                try (var tenant = CacheTenantContext.withTenant(scope)) {
                    operations.reset();
                    int expected = scope.equals("a") ? 1024 : 545;
                    String prefix = scope.equals("a") ? "g" : "bg";
                    assertEquals(expected, repository.aggregate(AggregateSpec.count(null)).count());
                    assertTrue(operations.countResult.isPresent());
                    assertTrue(operations.legacyCounts.isEmpty());
                    var facets = repository.aggregateBy("category", AggregateSpec.count(null));
                    assertEquals(20, facets.size()); assertEquals(expected, total(facets));
                    for (int group = 0; group < 20; group++)
                        assertEquals((expected + 19 - group) / 20, facets.get(prefix + group).count());
                    assertIterableEquals(new TreeSet<>(facets.keySet()), facets.keySet());
                    assertTrue(operations.facetResult.isPresent());
                    assertTrue(operations.legacyFacets.isEmpty());
                    assertEquals(1, operations.countRequests); assertEquals(1, operations.facetRequests);
                }
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "cachedb.redis.cluster", matches = "true")
    void realClusterAbove512DeclinesCountAndFacetPhasesWithoutInvokingAnyWindowFactory() {
        var config = DefaultJedisClientConfig.builder().connectionTimeoutMillis(3000).socketTimeoutMillis(5000)
                .hostAndPortMapper(address -> System.getProperty("cachedb.redis.cluster.host") != null ? address
                        : new HostAndPort("127.0.0.1", address.getPort() < 10000 ? address.getPort() + 10000 : address.getPort()))
                .build();
        try (var client = new JedisClusterOperations(new JedisCluster(Set.of(new HostAndPort(
                System.getProperty("cachedb.redis.cluster.host", "127.0.0.1"),
                Integer.getInteger("cachedb.redis.cluster.port", 17001))), config))) {
            String key = "t4-phase-{" + UUID.randomUUID() + "}:owned";
            try {
                client.commands().set(key, "reachable");
                assertEquals("reachable", client.commands().get(key));
                IntFunction<RedisClientOperations.IndexReadKeys> forbidden = start -> {
                    throw new AssertionError("Cluster cannot dispatch a standalone phase factory: " + start);
                };
                for (int size : List.of(545, 1024)) {
                    for (String tenant : Arrays.asList(null, "a"))
                        assertTrue(client.aggregateIndexCounts(size, forbidden, tenant).isEmpty());
                    for (String tenant : Arrays.asList(null, "a")) for (String field : Arrays.asList(null, "label"))
                        assertTrue(client.facetIndexBatches(size, forbidden, tenant, "category", field).isEmpty());
                }
            } finally { client.delete(key); }
        }
    }

    @Test void inFlightCountAndFacetCancellationDuringRealSendAndReadDiscardsThePhysicalLease() throws Exception {
        assertInFlightCancellation(true, false);
        assertInFlightCancellation(true, true);
    }

    @Test void unownedNativeCountCancellationDuringRealSendAndReadDiscardsThePhysicalLease() throws Exception {
        assertInFlightCancellation(false, false);
    }

    private void assertInFlightCancellation(boolean owned, boolean facet) throws Exception {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var fixture = new RedisT4AggregationRedisTest.Fixture(redis, owned)) {
            fixture.seed(rows(1024, "a"));
            for (boolean duringRead : List.of(false, true)) {
                var control = new TraceControl();
                var config = DefaultJedisClientConfig.builder().connectionTimeoutMillis(2000).socketTimeoutMillis(2000).build();
                try (var client = new TraceClient(config, control)) {
                    assertEquals("PONG", client.ping());
                    var operations = new ObservedOperations(client);
                    var repository = repository(operations, fixture);
                    var entities = List.of(fixture.entity(0)); var tombs = List.of(fixture.tombstone(0));
                    var metadata = List.of(fixture.keys.indexMetaKey("t4_rows", 0)); var owners = List.of(fixture.owner(0));
                    control.script = facet ? RedisClientOperations.facetIndexCommand(entities, tombs, metadata,
                            owners, "a", "category", null, 1).script()
                            : RedisClientOperations.indexAggregateCommand(entities, tombs, metadata, owners, "a", null, true).script();
                    control.nativeCount = !facet;
                    control.unownedCount = !owned;
                    var gate = new InFlightGate(duringRead); control.gate = gate;
                    var delivered = new AtomicReference<Object>();
                    var failure = new AtomicReference<Throwable>();
                    var interrupted = new AtomicBoolean();
                    var worker = new Thread(() -> {
                        try (var tenant = owned ? CacheTenantContext.withTenant("a") : CacheTenantContext.administrative()) {
                            delivered.set(facet ? repository.aggregateBy("category", AggregateSpec.count(null))
                                    : repository.aggregate(AggregateSpec.count(null)));
                        } catch (Throwable thrown) { failure.set(thrown); }
                        finally { interrupted.set(Thread.currentThread().isInterrupted()); }
                    }, "t4-phase-cancellation");
                    worker.start();
                    try {
                        assertTrue(gate.entered.await(5, TimeUnit.SECONDS), "Real phase did not reach its I/O barrier");
                        assertTrue(gate.connection.isConnected());
                        assertEquals(duringRead ? 2 : 1, control.phaseEvals);
                        assertEquals(duringRead ? 1 : 0, control.repliesRead);
                        worker.interrupt(); gate.resume.countDown(); worker.join(5000);
                        assertFalse(worker.isAlive(), "Cancellation cannot leave a running test worker");
                        assertInstanceOf(CancellationException.class, failure.get());
                        assertNull(delivered.get(), "No partial aggregate or facet result may escape");
                        assertTrue(interrupted.get());
                        assertTrue(gate.connection.isBroken());
                        assertFalse(gate.connection.isConnected(), "Returning broken must physically close the socket");
                        assertEquals(0, client.getPool().getNumActive());
                        assertEquals(0, client.getPool().getNumIdle());
                        assertEquals(1, client.getPool().getDestroyedCount());
                        assertTrue(operations.legacyCounts.isEmpty()); assertTrue(operations.legacyFacets.isEmpty());
                        assertEquals(2, facet ? operations.facetWindows.size() : operations.countWindows.size());
                        assertEquals(1, control.created.size());
                        control.gate = null;
                        assertEquals("PONG", client.ping());
                        assertEquals(2, control.created.size());
                        assertNotSame(gate.connection, control.created.getLast());
                    } finally {
                        gate.resume.countDown();
                        if (worker.isAlive()) { worker.interrupt(); worker.join(5000); }
                        assertFalse(worker.isAlive(), "Cancellation worker must terminate before fixture cleanup");
                    }
                }
            }
        }
    }

    private static final class InFlightGate {
        final boolean duringRead;
        final CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
        volatile Connection connection;
        InFlightGate(boolean duringRead) { this.duringRead = duringRead; }
        void pause(Connection connection) {
            this.connection = connection; entered.countDown();
            boolean interrupted = false;
            try {
                for (;;) {
                    try {
                        if (!resume.await(5, TimeUnit.SECONDS)) throw new AssertionError("Unreleased I/O barrier");
                        return;
                    } catch (InterruptedException cancelled) { interrupted = true; }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    private static final class TraceControl {
        final List<String> commands = new ArrayList<>();
        final List<Connection> created = new ArrayList<>();
        String script;
        boolean nativeCount;
        boolean unownedCount;
        int phaseEvals, repliesRead;
        InFlightGate gate;
    }

    private static final class TraceConnection extends Connection {
        private TraceControl control;
        private int pendingEvals;
        TraceConnection(HostAndPort endpoint, JedisClientConfig config, TraceControl control) {
            super(endpoint, config); this.control = control;
        }
        @Override public void sendCommand(CommandArguments arguments) {
            super.sendCommand(arguments);
            if (control == null) return;
            String command = arguments.getCommand().toString(); control.commands.add(command);
            if (control.nativeCount) {
                if (command.equals("MULTI")) control.phaseEvals++;
                var gate = control.gate;
                if (gate != null && !gate.duringRead && gate.connection == null && control.phaseEvals == 1
                        && command.equals(control.unownedCount ? "EXISTS" : "GETRANGE"))
                    gate.pause(this);
                return;
            }
            if (!command.equals("EVAL") || control.script == null) return;
            var iterator = arguments.iterator(); iterator.next();
            if (!iterator.hasNext() || !control.script.equals(new String(iterator.next().getRaw(), StandardCharsets.UTF_8))) return;
            pendingEvals++; control.phaseEvals++;
            var gate = control.gate;
            if (gate != null && !gate.duringRead && control.phaseEvals == 1) gate.pause(this);
        }
        @Override public List<Object> getMany(int count) {
            try {
                var gate = control == null ? null : control.gate;
                if (gate != null && gate.duringRead && gate.connection == null && count > 1
                        && ((control.nativeCount && control.phaseEvals > 0) || pendingEvals == count)) {
                    // Consume one real reply, then cancel while later replies are still pending.
                    var replies = new ArrayList<>(super.getMany(1)); control.repliesRead++;
                    gate.pause(this);
                    replies.addAll(super.getMany(count - 1)); control.repliesRead += count - 1;
                    return replies;
                }
                return super.getMany(count);
            } finally { pendingEvals = 0; }
        }
    }

    private static final class TraceClient extends JedisPooled {
        TraceClient(JedisClientConfig config, TraceControl control) {
            super(new BasePooledObjectFactory<Connection>() {
                public Connection create() {
                    URI endpoint = URI.create(RedisTestEndpoint.uri());
                    var connection = new TraceConnection(new HostAndPort(endpoint.getHost(), endpoint.getPort()), config, control);
                    control.created.add(connection); return connection;
                }
                public PooledObject<Connection> wrap(Connection value) { return new DefaultPooledObject<>(value); }
                @Override public void destroyObject(PooledObject<Connection> value) {
                    value.getObject().setHandlingPool(null); value.getObject().close();
                }
                @Override public boolean validateObject(PooledObject<Connection> value) {
                    return value.getObject().isConnected() && !value.getObject().isBroken();
                }
            }, config());
        }
        private static GenericObjectPoolConfig<Connection> config() {
            var config = new GenericObjectPoolConfig<Connection>(); config.setJmxEnabled(false);
            config.setMaxTotal(1); config.setMaxIdle(1); config.setMaxWait(Duration.ofSeconds(2)); return config;
        }
    }

    private static List<RedisT4AggregationRedisTest.Row> rows(int size, String tenant) {
        var rows = new ArrayList<RedisT4AggregationRedisTest.Row>(size);
        for (long id = 0; id < size; id++) rows.add(new RedisT4AggregationRedisTest.Row(id, tenant, "g" + id % 20,
                id % 11 == 0 ? null : new BigDecimal("0.125"), "ok"));
        return rows;
    }

    private static void assertTwentyGroups(int size, Map<String, AggregateResult> result) {
        assertEquals(20, result.size());
        assertEquals(size, total(result));
        assertIterableEquals(new TreeSet<>(result.keySet()), result.keySet());
        for (int group = 0; group < 20; group++) assertEquals((size + 19 - group) / 20, result.get("g" + group).count());
    }

    private static long total(Map<String, AggregateResult> result) {
        return result.values().stream().mapToLong(AggregateResult::count).sum();
    }

    private static void assertFacets(Map<String, AggregateResult> expected, Map<String, AggregateResult> actual) {
        RedisT4AggregationRedisTest.assertFacets(expected, actual);
        assertIterableEquals(expected.keySet(), actual.keySet());
    }

    private static List<Integer> windows(int candidates, int rows) {
        var sizes = new ArrayList<Integer>();
        for (int start = 0; start < candidates; start += rows) sizes.add(Math.min(rows, candidates - start));
        return sizes;
    }

    private static List<Integer> sizes(List<RedisClientOperations.IndexReadKeys> windows) {
        return windows.stream().map(window -> window.entities().size()).toList();
    }

    static RedisEntityRepository<RedisT4AggregationRedisTest.Row, Long> repository(
            RedisClientOperations client, RedisT4AggregationRedisTest.Fixture fixture) {
        var policy = CachePolicy.defaults();
        var indexes = new RedisQueryIndexManager<>(client, fixture.metadata, RedisT4AggregationRedisTest.CODEC,
                fixture.keys, null, QueryIndexConfig.builder().maxMaterializedCandidateIds(100_001).build(),
                RelationConfig.defaults(), new QueryEvaluator(), null);
        var pages = new RedisPageCacheManager<>(client, fixture.metadata, RedisT4AggregationRedisTest.CODEC,
                policy, fixture.keys, null, indexes, null, ReadShapeGuardrailConfig.defaults());
        return new RedisEntityRepository<>(client, fixture.metadata, RedisT4AggregationRedisTest.CODEC, null,
                fixture.keys, FetchPlan.empty(), policy, null, null, RedisGuardrailConfig.defaults(),
                "unused:stream", "unused:compaction", 1, null, null, RelationConfig.defaults(),
                QueryIndexConfig.defaults(), null, PageCacheConfig.defaults(), ReadShapeGuardrailConfig.defaults(),
                pages, new QueryEvaluator(), indexes, null, client, null, null);
    }

    private static final class ObservedOperations implements RedisClientOperations {
        final JedisPooled redis;
        final RedisClientOperations delegate;
        final List<IndexReadKeys> countWindows = new ArrayList<>(), facetWindows = new ArrayList<>();
        final List<Integer> legacyCounts = new ArrayList<>(), legacyFacets = new ArrayList<>();
        int countRequests, facetRequests, payloadReads;
        boolean declinePhases;
        OptionalLong countResult = OptionalLong.empty();
        Optional<List<FacetIndexBatch>> facetResult = Optional.empty();
        BiConsumer<Integer, IndexReadKeys> beforeCount, beforeFacet;
        Runnable beforePayload;

        ObservedOperations(JedisPooled redis) { this.redis = redis; delegate = new JedisPooledOperations(redis); }
        public UnifiedJedis commands() { return redis; }
        @Override public boolean supportsUnownedIndexCounts() { return true; }
        @Override public OptionalLong aggregateIndexCounts(int candidates, IntFunction<IndexReadKeys> windows, String tenant) {
            countRequests++;
            countResult = declinePhases ? OptionalLong.empty() : delegate.aggregateIndexCounts(candidates, start -> {
                var window = windows.apply(start); countWindows.add(window);
                if (beforeCount != null) beforeCount.accept(start, window);
                return window;
            }, tenant);
            return countResult;
        }
        @Override public Optional<List<FacetIndexBatch>> facetIndexBatches(int candidates, IntFunction<IndexReadKeys> windows,
                String tenant, String group, String field) {
            facetRequests++;
            facetResult = declinePhases ? Optional.empty() : delegate.facetIndexBatches(candidates, start -> {
                var window = windows.apply(start); facetWindows.add(window);
                if (beforeFacet != null) beforeFacet.accept(start, window);
                return window;
            }, tenant, group, field);
            return facetResult;
        }
        @Override public Optional<IndexAggregateBatch> aggregateIndexBatch(List<String> entities, List<String> tombstones,
                List<String> metadata, List<String> owners, String tenant, String field, boolean count) {
            legacyCounts.add(entities.size());
            return delegate.aggregateIndexBatch(entities, tombstones, metadata, owners, tenant, field, count);
        }
        @Override public Optional<FacetIndexBatch> facetIndexBatch(List<String> entities, List<String> tombstones,
                List<String> metadata, List<String> owners, String tenant, String group, String field) {
            legacyFacets.add(entities.size());
            return delegate.facetIndexBatch(entities, tombstones, metadata, owners, tenant, group, field);
        }
        @Override public Optional<List<String>> liveIndexPayloadBatch(List<String> entities, List<String> tombstones,
                List<String> owners, String tenant) {
            payloadReads++;
            var hook = beforePayload; beforePayload = null;
            if (hook != null) hook.run();
            return RedisClientOperations.super.liveIndexPayloadBatch(entities, tombstones, owners, tenant);
        }
        void reset() {
            countRequests = 0; facetRequests = 0; payloadReads = 0;
            countWindows.clear(); facetWindows.clear(); legacyCounts.clear(); legacyFacets.clear();
            countResult = OptionalLong.empty(); facetResult = Optional.empty();
            declinePhases = false; beforeCount = null; beforeFacet = null; beforePayload = null;
        }
    }
}
