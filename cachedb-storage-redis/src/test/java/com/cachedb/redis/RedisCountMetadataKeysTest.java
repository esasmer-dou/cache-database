package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import redis.clients.jedis.CommandObject;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.executors.CommandExecutor;

@Timeout(30)
class RedisCountMetadataKeysTest {
    @Test void ownedCountStarLeavesMetadataUnresolvedUntilCustomPhaseSpiReadsIt() {
        for (boolean inspect : List.of(false, true)) {
            var client = new Client(10_000, true, inspect);
            try (var tenant = CacheTenantContext.withTenant("a")) {
                assertEquals(10_000, repository(client, true).aggregate(AggregateSpec.count(null)).count());
            }
            assertEquals(1, client.phaseCalls);
            assertEquals(0, client.batchCalls);
            assertEquals(inspect ? 10_000 : 0, client.keys.metadataCalls);
        }
    }

    @Test void sequentialCountStarLeavesMetadataUnresolvedUntilCustomSpiReadsIt() {
        for (boolean scoped : List.of(false, true)) {
            for (int size : List.of(3, 1025)) {
                for (boolean inspect : List.of(false, true)) {
                    for (var spec : List.of(AggregateSpec.count(null),
                            new AggregateSpec(AggregateFunction.COUNT, "*", null),
                            new AggregateSpec(AggregateFunction.COUNT, " ", null))) {
                        var client = new Client(size, false, inspect);
                        try (var tenant = CacheTenantContext.withTenant("a")) {
                            assertEquals(size, repository(client, scoped).aggregate(spec).count());
                        }
                        assertEquals(scoped && size > 512 ? 1 : 0, client.phaseCalls);
                        assertEquals((size + 511) / 512, client.batchCalls);
                        assertEquals(inspect ? size : 0, client.keys.metadataCalls);
                    }
                }
            }
        }
    }

    @Test void countFieldMetadataKeysRemainEagerAndActual() {
        for (boolean scoped : List.of(false, true)) {
            var client = new Client(13, false, true);
            try (var tenant = CacheTenantContext.withTenant("a")) {
                assertEquals(13, repository(client, scoped)
                        .aggregate(new AggregateSpec(AggregateFunction.COUNT, "amount", null)).count());
            }
            assertEquals(0, client.phaseCalls);
            assertEquals(1, client.batchCalls);
            assertEquals(13, client.keys.metadataCalls);
        }
    }

    @Test void customMetadataKeyFailureIsDeferredButNotSuppressed() {
        var failure = new IllegalStateException("custom metadata key failure");
        for (boolean inspect : List.of(false, true)) {
            var client = new Client(545, true, inspect);
            client.keys.failure = failure;
            try (var tenant = CacheTenantContext.withTenant("a")) {
                var repository = repository(client, true);
                if (inspect) {
                    assertSame(failure, assertThrows(IllegalStateException.class,
                            () -> repository.aggregate(AggregateSpec.count(null))));
                } else {
                    assertEquals(545, repository.aggregate(AggregateSpec.count(null)).count());
                    assertEquals(0, client.keys.metadataCalls);
                }
            }
        }
    }

    @Test void optedInUnownedUnsupportedPhasePreservesLazyFallbackAcrossWindowBoundaries() {
        for (int size : List.of(512, 513, 545, 1024, 1025, 10_000)) {
            for (boolean inspect : List.of(false, true)) {
                for (var spec : List.of(AggregateSpec.count(null),
                        new AggregateSpec(AggregateFunction.COUNT, "*", null),
                        new AggregateSpec(AggregateFunction.COUNT, " ", null))) {
                    var client = new UnownedUnsupportedClient(size, inspect);
                    try (var tenant = CacheTenantContext.withTenant("a")) {
                        assertEquals(size, repository(client, false).aggregate(spec).count());
                    }
                    assertEquals(size > 512 ? 1 : 0, client.phaseCalls);
                    assertEquals(size > 512 ? (size + 511) / 512 : 0, client.phaseWindows);
                    assertEquals((size + 511) / 512, client.batchCalls);
                    assertEquals(inspect ? size : 0, client.keys.metadataCalls);
                }
            }
        }
    }

    private static RedisEntityRepository<RedisT4AggregationRedisTest.Row, Long> repository(
            Client client, boolean tenantScoped) {
        var metadata = RedisT4AggregationRedisTest.metadata(tenantScoped);
        var codec = RedisT4AggregationRedisTest.CODEC;
        var policy = CachePolicy.defaults();
        var indexes = new RedisQueryIndexManager<>(client, metadata, codec, client.keys, null,
                QueryIndexConfig.builder().maxMaterializedCandidateIds(100_001).build(),
                RelationConfig.defaults(), new QueryEvaluator(), null);
        var pages = new RedisPageCacheManager<>(client, metadata, codec, policy, client.keys, null,
                indexes, null, ReadShapeGuardrailConfig.defaults());
        return new RedisEntityRepository<>(client, metadata, codec, null, client.keys, FetchPlan.empty(),
                policy, null, null, RedisGuardrailConfig.defaults(), "unused:stream", "unused:compaction", 1,
                null, null, RelationConfig.defaults(), QueryIndexConfig.defaults(), null,
                PageCacheConfig.defaults(), ReadShapeGuardrailConfig.defaults(), pages,
                new QueryEvaluator(), indexes, null, client, null, null);
    }

    private static String metadataKey(Object id) { return "custom:metadata:t4_rows|" + id; }

    private static final class Keys extends RedisKeyStrategy {
        int metadataCalls;
        RuntimeException failure;
        Keys() { super("count-metadata"); }
        @Override public String indexMetaKey(String namespace, Object id) {
            assertEquals("t4_rows", namespace);
            metadataCalls++;
            if (failure != null) throw failure;
            return metadataKey(id);
        }
    }

    private static final class UnownedUnsupportedClient extends Client {
        int phaseWindows;
        UnownedUnsupportedClient(int size, boolean inspect) { super(size, false, inspect); }
        @Override public boolean supportsUnownedIndexCounts() { return true; }
        @Override public OptionalLong aggregateIndexCounts(int candidates, IntFunction<IndexReadKeys> windows,
                String tenant) {
            phaseCalls++;
            assertNull(tenant);
            assertEquals(ids.size(), candidates);
            assertTrue(candidates > 512);
            assertEquals(0, batchCalls);
            for (int start = 0; start < candidates; start += 512) {
                var window = windows.apply(start);
                int size = Math.min(512, candidates - start);
                assertEquals(size, window.entities().size());
                assertEquals(size, window.tombstones().size());
                assertEquals(size, window.indexMetadata().size());
                assertNull(window.owners());
                assertEquals(0, keys.metadataCalls);
                phaseWindows++;
            }
            return OptionalLong.empty();
        }
    }

    private static class Client implements RedisClientOperations {
        final Keys keys = new Keys();
        final List<String> ids;
        final boolean phaseSupported, inspect;
        int phaseCalls, batchCalls;
        final UnifiedJedis commands = new UnifiedJedis(new CommandExecutor() {
            public <T> T executeCommand(CommandObject<T> command) {
                throw new AssertionError("Unexpected Redis command");
            }
            public void close() {}
        });

        Client(int size, boolean phaseSupported, boolean inspect) {
            ids = IntStream.rangeClosed(1, size).mapToObj(Integer::toString).toList();
            this.phaseSupported = phaseSupported;
            this.inspect = inspect;
        }
        public UnifiedJedis commands() { return commands; }
        public Optional<GuardedSetMembers> guardedSetMembers(String degraded, String membership, int maximum) {
            assertTrue(ids.size() <= maximum);
            return Optional.of(new GuardedSetMembers(false, ids));
        }
        public OptionalLong aggregateIndexCounts(int candidates, IntFunction<IndexReadKeys> windows, String tenant) {
            phaseCalls++;
            if (!phaseSupported) return OptionalLong.empty();
            assertEquals(ids.size(), candidates);
            assertEquals("a", tenant);
            for (int start = 0; start < candidates; start += 512) {
                var window = windows.apply(start);
                inspect(window.entities(), window.tombstones(), window.indexMetadata(), window.owners(), start, null);
            }
            return OptionalLong.of(candidates);
        }
        public Optional<IndexAggregateBatch> aggregateIndexBatch(List<String> entities, List<String> tombstones,
                List<String> metadata, List<String> owners, String tenant, String field, boolean countOnly) {
            int start = batchCalls++ * 512;
            assertTrue(countOnly);
            if (owners == null) assertNull(tenant); else assertEquals("a", tenant);
            inspect(entities, tombstones, metadata, owners, start, field);
            return Optional.of(new IndexAggregateBatch(entities.size(), List.of(), List.of()));
        }
        private void inspect(List<String> entities, List<String> tombstones, List<String> metadata,
                List<String> owners, int start, String field) {
            int size = Math.min(512, ids.size() - start);
            int resolved = field == null ? (inspect ? start : 0) : start + size;
            assertEquals(resolved, keys.metadataCalls, "Metadata must not resolve before COUNT(*) SPI access");
            assertEquals(size, entities.size());
            assertEquals(size, tombstones.size());
            assertEquals(size, metadata.size());
            if (owners != null) assertEquals(size, owners.size());
            assertThrows(UnsupportedOperationException.class, () -> metadata.set(0, "alias"));
            assertThrows(UnsupportedOperationException.class, () -> metadata.add("placeholder"));
            assertThrows(UnsupportedOperationException.class, metadata::clear);
            assertThrows(IndexOutOfBoundsException.class, () -> metadata.get(-1));
            assertThrows(IndexOutOfBoundsException.class, () -> metadata.get(size));
            assertEquals(resolved, keys.metadataCalls);
            if (inspect) {
                var actual = List.copyOf(metadata);
                for (int i = 0; i < size; i++) {
                    String id = ids.get(start + i);
                    assertEquals(keys.entityKey("t4_rows", id), entities.get(i));
                    assertEquals(keys.tombstoneKey("t4_rows", id), tombstones.get(i));
                    assertEquals(metadataKey(id), actual.get(i));
                    if (owners != null) assertEquals(keys.versionKey("t4_rows", id) + ":tenant", owners.get(i));
                }
                assertEquals(field == null ? start + size : resolved, keys.metadataCalls);
            }
        }
    }
}
