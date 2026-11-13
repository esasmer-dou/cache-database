package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Redis component tests. SQL migration/administrative approval is intentionally not simulated as end-to-end certification. */
@EnabledIfSystemProperty(named="cachedb.snapshot.redis", matches="true")
@Timeout(40)
class RedisTenantProducerTest {
    record Row(String id, String tenant, String name) {}

    @Test void canonicalUpsertPatchDeleteUseOneRetainedVersionDomainAndOriginalWireGeneration() {
        try (var f = new Fixture(); var tenant = CacheTenantContext.withTenant("a")) {
            long first = f.producer.execute("a", () -> f.upsert("first", -1));
            long second = f.producer.execute("a", () -> f.upsert("second", -1));
            assertEquals(101, first);
            assertEquals(102, second);
            var failed = assertThrows(OptimisticWriteConflictException.class,
                    () -> f.producer.execute("a", () -> f.upsert("first", 0)));
            assertEquals("102", f.redis.get(f.barrier.versionAllocatorKey(f.key) + ":next"));
            assertTrue(f.producer.drain(f.barrier.key("b")).drained());
            long patch = f.producer.execute("a", () -> f.patch("first", first));
            long deleted = f.producer.execute("a", () -> f.delete("first", patch));
            assertEquals(103, patch);
            assertEquals(104, deleted);
            var entries = f.redis.xrange(f.stream, new redis.clients.jedis.StreamEntryID("0-0"),
                    new redis.clients.jedis.StreamEntryID("9999999999999-0"));
            assertEquals(4, entries.size());
            for (var entry : entries) {
                assertEquals("1", entry.getFields().get("tenantGeneration"));
                assertEquals(f.key.tenantToken(), entry.getFields().get("tenantToken"));
                assertEquals(f.key.scopeToken(), entry.getFields().get("generationScope"));
            }
        }
    }

    @Test void delayedOriginalFrameCannotResurrectAfterAnExplicitNewGenerationAndVersionsStayMonotonic() {
        try (var f = new Fixture(); var tenant = CacheTenantContext.withTenant("a")) {
            var delayed = new AtomicReference<RedisProtectedFunctionCall.Frame>();
            long first = f.producer.execute("a", () -> {
                delayed.set(RedisProtectedFunctionCall.prepare(f.operations, f.functions, f.keys, f.write, f.keysFor("first"),
                        f.mapper.upsertArgs(f.operation("first", OperationType.UPSERT), CachePolicy.defaults(),
                                RedisGuardrailConfig.defaults(), f.write, true, -1),
                        f.operation("first", OperationType.UPSERT), false));
                return f.upsert("first", -1);
            });
            try (var admin = CacheTenantContext.administrative()) {
                Lease lease = f.lease(2);
                f.barrier.close(lease, 1);
                assertTrue(f.producer.drain(f.key).drained());
                f.barrier.installClosedGeneration(lease, 2);
                f.versions.install(new TenantVersionRange(f.key, 2, 2, 2, 2001, 3000,
                        UUID.randomUUID(), f.versions.runId(f.key), ProtectionKey.hash("component-inventory")));
                // Redis component hook only. Production requires a separately verified SQL OPEN commit.
                f.barrier.openPreparedGeneration(lease, 2);
            }
            var frame = delayed.get();
            assertThrows(RuntimeException.class,
                    () -> f.operations.fcall(f.functions.upsertFunctionName(), frame.keys(), frame.args()));
            long reopened = f.producer.execute("a", () -> f.upsert("first", -1));
            assertEquals(2001, reopened);
            assertTrue(reopened > first);
        }
    }

    @Test void framingRejectsForeignClientStrategyAndEveryForeignCanonicalRouteBeforeIo() {
        try (var f = new Fixture(); var tenant = CacheTenantContext.withTenant("a")) {
            f.producer.execute("a", () -> {
                var operation = f.operation("first", OperationType.UPSERT);
                var args = f.mapper.upsertArgs(operation, CachePolicy.defaults(),
                        RedisGuardrailConfig.defaults(), f.write, true, -1);
                assertDoesNotThrow(() -> RedisProtectedFunctionCall.prepare(
                        f.operations, f.functions, f.keys, f.write, f.keysFor("first"), args, operation, false));
                // Equal physical server/prefix is not the same installed runtime capability.
                assertThrows(ProtectionException.class, () -> RedisProtectedFunctionCall.prepare(
                        JedisPooledOperations.of(f.redis), f.functions, f.keys, f.write,
                        f.keysFor("first"), args, operation, false));
                assertThrows(ProtectionException.class, () -> RedisProtectedFunctionCall.prepare(
                        f.operations, f.functions, new RedisKeyStrategy("f3"), f.write,
                        f.keysFor("first"), args, operation, false));
                for (int index = 0; index < 8; index++) {
                    var foreign = new ArrayList<>(f.keysFor("first"));
                    foreign.set(index, "other-runtime:" + index);
                    assertThrows(ProtectionException.class, () -> RedisProtectedFunctionCall.prepare(
                            f.operations, f.functions, f.keys, f.write, foreign, args, operation, false));
                }
                var patch = f.operation("first", OperationType.PATCH);
                var patchArgs = f.mapper.upsertArgs(patch, CachePolicy.defaults(),
                        RedisGuardrailConfig.defaults(), f.write, true, -1);
                var foreignPatch = new ArrayList<>(f.keysFor("first"));
                if (f.write.compactionActive()) foreignPatch.set(3, foreignPatch.get(6));
                foreignPatch.add("other-runtime:index-degraded");
                assertThrows(ProtectionException.class, () -> RedisProtectedFunctionCall.prepare(
                        f.operations, f.functions, f.keys, f.write, foreignPatch, patchArgs, patch, false));
                assertEquals("100", f.redis.get(f.barrier.versionAllocatorKey(f.key) + ":next"));
                assertFalse(f.redis.exists(f.keys.entityKey(f.ns, "first")));
                assertEquals(0, f.redis.xlen(f.stream));
                return null;
            });
            assertEquals(101, f.producer.execute("a", () -> f.upsert("first", -1)));
        }
    }

    @Test void cancellationCannotAcknowledgeDrainWhilePhysicalWorkIsBlocked() throws Exception {
        try (var f = new Fixture(); var executor = Executors.newSingleThreadExecutor()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var exited = new CountDownLatch(1);
            var pending = CompletableFuture.supplyAsync(() -> {
                try {
                    return f.producer.execute("a", () -> {
                        entered.countDown();
                        try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                        catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                        return 1;
                    });
                } finally { exited.countDown(); }
            }, executor);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                pending.cancel(true);
                try (var admin = CacheTenantContext.administrative()) { f.barrier.close(f.lease(2), 1); }
                assertEquals(1, f.producer.drain(f.key).active());
                assertFalse(f.producer.drain(f.key).drained());
            } finally { release.countDown(); }
            assertTrue(exited.await(5, TimeUnit.SECONDS));
            assertTrue(f.producer.drain(f.key).drained());
        }
    }

    @Test void uncertainTransportPoisonsBoundedSlotAndCannotBeClaimedAsClosure() {
        try (var f = new Fixture()) {
            assertThrows(redis.clients.jedis.exceptions.JedisConnectionException.class, () ->
                    f.producer.execute("a", () -> { throw new redis.clients.jedis.exceptions.JedisConnectionException("simulated"); }));
            var drain = f.producer.drain(f.key);
            assertEquals(1, drain.uncertain());
            assertFalse(drain.drained());
            assertEquals(ProtectionException.Code.BUSY,
                    assertThrows(ProtectionException.class, () -> f.producer.execute("a", () -> 1)).code());
        }
    }

    static final class Fixture implements AutoCloseable {
        final String ns = "producer_" + UUID.randomUUID().toString().replace("-", "");
        final RedisKeyStrategy keys = new RedisKeyStrategy("f3");
        final redis.clients.jedis.JedisPooled redis = new redis.clients.jedis.JedisPooled(RedisTestEndpoint.uri());
        final RedisClientOperations operations = JedisPooledOperations.of(redis);
        final RedisGenerationBarrier barrier = new RedisGenerationBarrier(operations, keys, ns);
        final ProtectionKey key = barrier.key("a");
        final UUID operationId = UUID.randomUUID();
        Lease lease(long epoch) { return new Lease(key, operationId, UUID.randomUUID(), epoch, 120); }
        final String stream = keys.namespacePrefix(ns) + ":stream";
        final String library = "producer_" + UUID.randomUUID().toString().replace("-", "");
        final RedisFunctionsConfig functions = RedisFunctionsConfig.builder().libraryName(library)
                .upsertFunctionName(library + "_upsert").deleteFunctionName(library + "_delete")
                .compactionCompleteFunctionName(library + "_complete").build();
        final WriteBehindConfig write = WriteBehindConfig.builder().streamKey(stream)
                .compactionStreamKey(stream + ":compaction").build();
        final RedisFunctionArgsMapper mapper = new RedisFunctionArgsMapper();
        final RedisFunctionExecutor executor = new RedisFunctionExecutor(operations, functions,
                RedisGuardrailConfig.defaults(), write, mapper).withProtectedRuntime(keys);
        final RedisTenantVersionAllocator versions = new RedisTenantVersionAllocator(operations, keys, barrier, ns);
        final RedisTenantProducer producer = new RedisTenantProducer(barrier, ns, 1);
        final EntityMetadata<Row, String> metadata = new EntityMetadata<>() {
            public String entityName() { return "ProducerRow"; }
            public String tableName() { return "producer_rows"; }
            public String redisNamespace() { return ns; }
            public String idColumn() { return "id"; }
            public Class<Row> entityType() { return Row.class; }
            public Function<Row, String> idAccessor() { return Row::id; }
            public List<String> columns() { return List.of("id", "tenant", "name"); }
            public List<RelationDefinition> relations() { return List.of(); }
            public String tenantFieldName() { return "tenant"; }
            public String extractTenantId(Row row) { return row.tenant(); }
            public boolean tenantPurgeEnabled() { return true; }
        };
        Fixture() {
            redis.functionLoad(new RedisFunctionLibrarySource().render(functions));
            try (var admin = CacheTenantContext.administrative()) {
                var lease = lease(1);
                barrier.close(lease, 1);
                versions.install(new TenantVersionRange(key, 1, 1, 1, 101, 1000,
                        UUID.randomUUID(), versions.runId(key), ProtectionKey.hash("component-inventory")));
                barrier.openPreparedGeneration(lease, 1);
                for (String id : List.of("first", "second")) redis.set(keys.versionKey(ns, id) + ":tenant", "a");
            }
        }
        List<String> keysFor(String id) {
            return List.of(keys.entityKey(ns, id), keys.versionKey(ns, id), keys.tombstoneKey(ns, id), stream,
                    keys.compactionPayloadKey(ns, id), keys.compactionPendingKey(ns, id),
                    keys.compactionStreamKey(write.compactionStreamKey(), ns, id, write.compactionShardCount()),
                    keys.compactionStatsKey(ns));
        }
        WriteOperation<Row, String> operation(String id, OperationType type) {
            return new WriteOperation<>(type, metadata, id, Map.of("id", id, "tenant", "a", "name", "value"),
                    "payload", 0, Instant.EPOCH);
        }
        long upsert(String id, long expected) {
            var k = keysFor(id);
            return executor.upsert(k.get(0), k.get(1), k.get(2), k.get(3), k.get(4), k.get(5), k.get(6), k.get(7),
                    operation(id, OperationType.UPSERT), CachePolicy.defaults(), true, expected);
        }
        long patch(String id, long expected) {
            var k = new ArrayList<>(keysFor(id));
            k.add(keys.indexDegradedKey(ns));
            return executor.patch(k, operation(id, OperationType.PATCH), CachePolicy.defaults(), true, expected);
        }
        long delete(String id, long expected) {
            var k = keysFor(id);
            return executor.delete(k.get(0), k.get(1), k.get(2), k.get(3), k.get(4), k.get(5), k.get(6), k.get(7),
                    operation(id, OperationType.DELETE), CachePolicy.defaults(), expected);
        }
        @Override public void close() {
            producer.close();
            try {
                redis.functionDelete(library);
                var cleanup = new HashSet<String>();
                cleanup.addAll(keysFor("first")); cleanup.addAll(keysFor("second"));
                cleanup.add(keys.versionKey(ns, "first") + ":tenant");
                cleanup.add(keys.versionKey(ns, "second") + ":tenant");
                cleanup.add(keys.indexDegradedKey(ns));
                cleanup.add(barrier.generationKey(key)); cleanup.add(barrier.leaseKey(key));
                cleanup.add(barrier.versionAllocatorKey(key)); cleanup.add(barrier.versionAllocatorKey(key) + ":next");
                redis.del(cleanup.toArray(String[]::new));
            } finally { redis.close(); }
        }
    }
}
