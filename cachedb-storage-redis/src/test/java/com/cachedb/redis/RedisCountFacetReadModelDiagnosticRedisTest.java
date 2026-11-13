package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.query.AggregateSpec;
import com.reactor.cachedb.core.readmodel.CountFacetDefinition;
import com.reactor.cachedb.core.readmodel.CountFacetRead;
import com.reactor.cachedb.core.readmodel.CountFacetRefresh;
import com.reactor.cachedb.core.readmodel.CountFacetValues;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPooled;

/** Snapshot-read diagnostics, not an equivalent-contract replacement for the raw T4 JMH gate. */
@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
@EnabledIfSystemProperty(named = "cachedb.readmodel.diagnostic", matches = "true")
class RedisCountFacetReadModelDiagnosticRedisTest {
    private static final int ROWS = 10_000;
    private static final int GROUPS = 20;
    private static final int BATCH = 256;
    private static final int WARMUPS = 2;
    private static final int MEASURED = 5;

    @Test
    void explicitlyRefreshedSnapshotsMatchRawCountAndEveryFacetForOwnedAndUnownedSources() throws Exception {
        var report = new StringBuilder("scope\toperation\tmeasured\tservice_time_derived_ops_per_second\tp50_ns\tp95_ns"
                + "\tredis_input_bytes_delta\tredis_output_bytes_delta"
                + "\tseries_wall_ns\tvalidated_wall_ops_per_second\tvalidation_ns\traw_durations_ns\n");
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var stats = new Jedis(RedisTestEndpoint.uri())) {
            for (boolean owned : new boolean[] {false, true}) {
                try (var fixture = new RedisT4AggregationRedisTest.Fixture(redis, owned);
                        var scope = owned ? CacheTenantContext.withTenant("a") : CacheTenantContext.administrative()) {
                    var definition = new CountFacetDefinition("count-facet-diagnostic", Duration.ofMinutes(2),
                            Duration.ofMinutes(5), Duration.ofSeconds(30));
                    String snapshotKey = fixture.keys.countFacetReadModelKey(fixture.metadata.redisNamespace(),
                            definition.name(), owned ? "tenant:a" : "global");
                    try {
                        seedBounded(fixture);
                        var repository = fixture.repository(new RedisT4AggregationRedisTest.Client(redis, true),
                                CachePolicy.defaults());
                        var count = AggregateSpec.count(null);
                        var expectedGroups = new LinkedHashMap<String, Long>();
                        for (int i = 0; i < GROUPS; i++) expectedGroups.put("g" + i, (long) ROWS / GROUPS);
                        var expected = new CountFacetValues(ROWS, expectedGroups);
                        Supplier<CountFacetValues> raw = () -> {
                            long total = repository.aggregate(count).count();
                            var groups = new LinkedHashMap<String, Long>();
                            repository.aggregateBy("category", count).forEach((key, value) -> groups.put(key, value.count()));
                            return new CountFacetValues(total, groups);
                        };
                        assertValues(expected, raw.get());
                        var sourceCalls = new AtomicInteger();
                        var model = new RedisCountFacetReadModel(JedisPooledOperations.of(redis), fixture.keys,
                                fixture.metadata, definition);
                        assertEquals(CountFacetRead.Status.MISSING, model.inspect().status());
                        assertThrows(IllegalStateException.class, model::requireFresh);
                        assertEquals(0, sourceCalls.get());
                        long refreshStart = System.nanoTime();
                        var published = model.refresh(() -> { sourceCalls.incrementAndGet(); return raw.get(); });
                        long refreshNanos = System.nanoTime() - refreshStart;
                        assertEquals(CountFacetRefresh.Status.PUBLISHED, published.status());
                        assertValues(expected, published.snapshot().orElseThrow().values());
                        String name = owned ? "owned" : "unowned";
                        measure(stats, report, name, "RAW_COUNT_LIVE", () -> repository.aggregate(count).count(),
                                actual -> assertEquals((long) ROWS, actual.longValue()));
                        measure(stats, report, name, "RAW_FACET_LIVE", () -> {
                            var groups = new LinkedHashMap<String, Long>();
                            repository.aggregateBy("category", count).forEach((key, value) -> groups.put(key, value.count()));
                            return groups;
                        }, actual -> assertGroups(expected.groups(), actual));
                        measure(stats, report, name, "SNAPSHOT_INSPECT", model::inspect, actual -> {
                            assertEquals(CountFacetRead.Status.FRESH, actual.status());
                            assertValues(expected, actual.snapshot().orElseThrow().values());
                        });
                        measure(stats, report, name, "SNAPSHOT_REQUIRE_FRESH", model::requireFresh,
                                actual -> assertValues(expected, actual.values()));
                        assertEquals(1, sourceCalls.get(), "Reading a snapshot must not invoke source aggregation");
                        report.append("# ").append(name).append(" refresh_ns=").append(refreshNanos)
                                .append(" source_calls=").append(sourceCalls.get())
                                .append(" snapshot_redis_memory_bytes=").append(redis.memoryUsage(snapshotKey)).append('\n');
                    } finally {
                        fixture.cleanup.addAll(List.of(snapshotKey, snapshotKey + ":lease", snapshotKey + ":sequence"));
                        cleanupBounded(fixture);
                    }
                }
            }
        }
        report.append("# DIAGNOSTIC_ONLY_NOT_RAW_T4_ACCEPTANCE; rows=10000; groups=20; warmups=2; measured=5\n")
                .append("# service_time_derived_ops_per_second is inverse mean serial read time, not concurrent capacity; p95 is max of five samples\n")
                .append("# validated_wall_ops_per_second includes result verification; network deltas are Redis-wide and include observer traffic\n")
                .append("# Source is quiescent. Raw reads observe live indexes; snapshots retain explicit-refresh values.\n")
                .append("# Seed, refresh, validation and cleanup are outside read timers. Refresh cost is separate.\n")
                .append("# Network deltas are Redis INFO stats counters and include observer-command overhead.\n")
                .append("# Five samples are exploratory; p95 is nearest-rank maximum, not a production percentile.\n")
                .append("# Standard Redis only; no Redis module, speedup assertion, or raw T4 waiver.\n");
        Path output = Path.of(System.getProperty("cachedb.readmodel.diagnostic.output",
                "target/readmodel-diagnostic.tsv"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, report, StandardCharsets.UTF_8);
        System.out.print(report);
    }

    private static void seedBounded(RedisT4AggregationRedisTest.Fixture fixture) {
        fixture.cleanup.add(fixture.keys.indexAllKey("t4_rows"));
        for (int start = 0; start < ROWS; start += BATCH) {
            var rows = new ArrayList<RedisT4AggregationRedisTest.Row>(BATCH);
            for (int i = start; i < Math.min(ROWS, start + BATCH); i++) {
                rows.add(new RedisT4AggregationRedisTest.Row((long) i + 1, "a", "g" + i % GROUPS,
                        BigDecimal.valueOf(i), "row"));
            }
            // Use the real index manager and codec, but avoid the fixture's per-row synchronous SETs.
            fixture.indexes.reindexBatch(rows);
            try (var pipeline = fixture.redis.pipelined()) {
                for (var row : rows) {
                    pipeline.set(fixture.entity(row.id()), RedisT4AggregationRedisTest.CODEC.toRedisValue(row));
                    pipeline.set(fixture.owner(row.id()), row.tenant());
                    fixture.cleanup.add(fixture.entity(row.id()));
                    fixture.cleanup.add(fixture.owner(row.id()));
                    fixture.cleanup.add(fixture.tombstone(row.id()));
                    fixture.cleanup.add(fixture.keys.indexMetaKey("t4_rows", row.id()));
                    RedisT4AggregationRedisTest.CODEC.toColumns(row).forEach((column, value) -> {
                        String raw = value == null ? "__NULL__" : value.toString();
                        fixture.cleanup.add(fixture.keys.indexExactKey("t4_rows", column,
                                RedisIndexKeySpace.encodeKeyPart(raw)));
                        fixture.cleanup.add(fixture.keys.indexSortKey("t4_rows", column));
                    });
                }
                pipeline.sync();
            }
        }
    }

    private static void cleanupBounded(RedisT4AggregationRedisTest.Fixture fixture) {
        var keys = new ArrayList<>(fixture.cleanup);
        for (int start = 0; start < keys.size(); start += BATCH) {
            try (var pipeline = fixture.redis.pipelined()) {
                for (String key : keys.subList(start, Math.min(keys.size(), start + BATCH))) pipeline.unlink(key);
                pipeline.sync();
            }
        }
        fixture.cleanup.clear();
    }

    private static void assertValues(CountFacetValues expected, CountFacetValues actual) {
        assertEquals(expected.count(), actual.count());
        assertGroups(expected.groups(), actual.groups());
    }

    private static void assertGroups(Map<String, Long> expected, Map<String, Long> actual) {
        assertEquals(GROUPS, actual.size());
        assertEquals(expected, actual, "Every group's identity and weight must match");
        assertEquals((long) ROWS, actual.values().stream().mapToLong(Long::longValue).sum());
    }

    private static <T> void measure(Jedis redis, StringBuilder report, String scope, String operation,
            Supplier<T> read, Consumer<T> verify) {
        for (int i = 0; i < WARMUPS; i++) verify.accept(read.get());
        var before = network(redis);
        var durations = new long[MEASURED];
        long validationNanos = 0L;
        long seriesStart = System.nanoTime();
        for (int i = 0; i < MEASURED; i++) {
            long start = System.nanoTime();
            T result = read.get();
            durations[i] = System.nanoTime() - start;
            long validationStart = System.nanoTime();
            verify.accept(result);
            validationNanos += System.nanoTime() - validationStart;
        }
        long seriesNanos = System.nanoTime() - seriesStart;
        var after = network(redis);
        long total = Arrays.stream(durations).sum();
        String rawDurations = Arrays.stream(durations).mapToObj(Long::toString)
                .collect(java.util.stream.Collectors.joining(","));
        Arrays.sort(durations);
        report.append(scope).append('\t').append(operation).append('\t').append(MEASURED).append('\t')
                .append(MEASURED * 1_000_000_000.0 / total).append('\t')
                .append(durations[(MEASURED - 1) / 2]).append('\t').append(durations[MEASURED - 1]).append('\t')
                .append(after[0] - before[0]).append('\t').append(after[1] - before[1]).append('\t')
                .append(seriesNanos).append('\t').append(MEASURED * 1_000_000_000.0 / seriesNanos).append('\t')
                .append(validationNanos).append('\t').append(rawDurations).append('\n');
    }

    private static long[] network(Jedis redis) {
        var result = new long[] {-1, -1};
        for (String line : redis.info("stats").lines().toList()) {
            if (line.startsWith("total_net_input_bytes:")) result[0] = Long.parseLong(line.substring(22));
            if (line.startsWith("total_net_output_bytes:")) result[1] = Long.parseLong(line.substring(23));
        }
        assertTrue(result[0] >= 0 && result[1] >= 0, "Redis must expose both network counters");
        return result;
    }
}
