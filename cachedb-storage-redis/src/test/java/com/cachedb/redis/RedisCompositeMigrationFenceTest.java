package com.reactor.cachedb.redis;

import static com.reactor.cachedb.redis.RedisCompositeRangeTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.registry.DefaultEntityRegistry;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
class RedisCompositeMigrationFenceTest {
    @Test void activeWriterAndMigrationAreMutuallyExclusiveAcrossIndependentManagers() throws Exception {
        try (var f = new RedisCompositeRangeRedisTest.Fixture()) {
            f.put(new Row("1", "a", "OPEN", 1, true));
            var fence = new RedisCompositeMigrationFence(JedisPooledOperations.of(f.redis), f.keys, "rows");
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var writer = executor.submit(() -> f.manager.withCompositeMutation(() -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Writer release timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
                    }
                    return null;
                }));
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    assertThrows(RuntimeException.class, fence::exclusive);
                } finally { release.countDown(); }
                writer.get(5, TimeUnit.SECONDS);
            }
            var independent = manager(JedisPooledOperations.of(f.redis), f.keys, false, RedisCompositeRangeRedisTest.SORTS);
            try (var migration = fence.exclusive()) {
                assertThrows(RuntimeException.class, () -> independent.reindex(new Row("1", "a", "OPEN", 9, true)));
                assertEquals(1d, f.redis.zscore(exact(f.keys) + ":sort", "1"));
                migration.publish(f.keys.compositeIndexSignatureKey("rows"), RedisCompositeIndexes.signature(metadata(false,
                        RedisCompositeRangeRedisTest.SORTS)));
            }
            assertEquals(RedisQueryIndexManager.CompositeIndexState.VERIFIED, f.manager.compositeIndexState());
        }
    }

    @Test void existingAtomicBatchInvalidatesPublicationEvenWithoutANewMutationLua() {
        try (var f = new RedisCompositeRangeRedisTest.Fixture()) {
            f.put(new Row("1", "a", "OPEN", 1, true));
            var registry = new DefaultEntityRegistry(ResourceLimits.defaults());
            registry.register(metadata(false, RedisCompositeRangeRedisTest.SORTS), CODEC, CachePolicy.defaults(), null, null);
            var config = WriteBehindConfig.builder().streamKey("f1-fence-stream-" + UUID.randomUUID()).build();
            var fence = new RedisCompositeMigrationFence(JedisPooledOperations.of(f.redis), f.keys, "rows");
            try {
                try (var migration = fence.exclusive()) {
                    assertFalse(f.manager.rebuildFromEntityStore(false, "ordinary").degradedCleared());
                    f.redis.del(f.keys.indexAllKey("rows"));
                    new RedisAtomicWriteBatch(f.redis, registry, f.keys, config, RedisGuardrailConfig.defaults(), null, "fence-batch")
                            .withQueryIndexConfig(QueryIndexConfig.builder().exactIndexEnabled(false).rangeIndexEnabled(false)
                                    .prefixIndexEnabled(false).textIndexEnabled(false).build())
                            .put(new Row("1", "a", "OPEN", 9, true)).commit();
                    assertThrows(RuntimeException.class, () -> migration.publish(f.keys.compositeIndexSignatureKey("rows"),
                            RedisCompositeIndexes.signature(metadata(false, RedisCompositeRangeRedisTest.SORTS))));
                    assertTrue(f.redis.exists(f.keys.indexDegradedKey("rows")));
                    assertEquals(RedisQueryIndexManager.CompositeIndexState.PENDING_REBUILD, f.manager.compositeIndexState());
                }
                assertTrue(f.manager.rebuildCompositeIndexesFenced(false, "after-batch").degradedCleared());
                assertEquals(9d, f.redis.zscore(exact(f.keys) + ":sort", "1"));
            } finally {
                String stream = f.keys.writeStreamKey(config.activeStreamKey(), "rows");
                f.redis.del(stream, stream + ":batch:fence-batch");
            }
        }
    }

    @Test void failedRebuildAndAbandonedPersistentWriterCannotPublishVerified() {
        try (var f = new RedisCompositeRangeRedisTest.Fixture()) {
            f.put(new Row("1", "a", "OPEN", 1, true));
            f.redis.set(f.keys.entityKey("rows", "1"), "invalid-payload");
            assertThrows(RuntimeException.class, () -> f.manager.rebuildCompositeIndexesFenced(false, "bad-payload"));
            assertTrue(f.redis.exists(f.keys.indexDegradedKey("rows")));
            assertEquals(RedisQueryIndexManager.CompositeIndexState.PENDING_REBUILD, f.manager.compositeIndexState());
            var fence = new RedisCompositeMigrationFence(JedisPooledOperations.of(f.redis), f.keys, "rows");
            String writers = fence.identityKeys().get(1);
            f.redis.hset(writers, "abandoned", "1");
            assertEquals(-1L, f.redis.pttl(writers));
            assertThrows(RuntimeException.class, fence::exclusive);
            assertEquals(RedisQueryIndexManager.CompositeIndexState.PENDING_REBUILD, f.manager.compositeIndexState());
        }
    }

    @Test void atomicBatchPreflightsDegradationBeforePayloadChanges() {
        try (var f = new RedisCompositeRangeRedisTest.Fixture()) {
            f.put(new Row("1", "a", "OPEN", 1, true));
            String before = f.redis.get(f.keys.entityKey("rows", "1"));
            f.redis.del(f.keys.indexDegradedKey("rows"));
            f.redis.hset(f.keys.indexDegradedKey("rows"), "wrong", "type");
            var registry = new DefaultEntityRegistry(ResourceLimits.defaults());
            registry.register(metadata(false, RedisCompositeRangeRedisTest.SORTS), CODEC, CachePolicy.defaults(), null, null);
            var config = WriteBehindConfig.builder().streamKey("f1-preflight-" + UUID.randomUUID()).build();
            assertThrows(RuntimeException.class, () -> new RedisAtomicWriteBatch(f.redis, registry, f.keys, config,
                    RedisGuardrailConfig.defaults(), null, "preflight-batch").put(new Row("1", "a", "OPEN", 9, true)).commit());
            assertEquals(before, f.redis.get(f.keys.entityKey("rows", "1")));
        }
    }

    @Test void persistentWriterRegistryHasAHardCardinalityLimit() {
        try (var f = new RedisCompositeRangeRedisTest.Fixture()) {
            var fence = new RedisCompositeMigrationFence(JedisPooledOperations.of(f.redis), f.keys, "rows");
            var entries = new LinkedHashMap<String, String>();
            for (int i = 0; i < RedisCompositeMigrationFence.MAX_ACTIVE_WRITERS; i++) entries.put("orphan-" + i, "1");
            String writers = fence.identityKeys().get(1);
            f.redis.hset(writers, entries);
            var invoked = new java.util.concurrent.atomic.AtomicBoolean();
            assertThrows(RuntimeException.class, () -> fence.write(() -> { invoked.set(true); return null; }));
            assertFalse(invoked.get());
            assertEquals(RedisCompositeMigrationFence.MAX_ACTIVE_WRITERS, f.redis.hlen(writers));
            assertEquals(-1L, f.redis.pttl(writers));
            assertThrows(RuntimeException.class, fence::exclusive);
        }
    }

    @Test void lostRegistrationReplyLeavesABoundedPersistentBlockerWithoutExecutingAction() {
        try (var f = new RedisCompositeRangeRedisTest.Fixture()) {
            RedisClientOperations lostReply = new RedisClientOperations() {
                public redis.clients.jedis.UnifiedJedis commands() { return f.redis; }
                public Object eval(String script, List<String> keys, List<String> args) {
                    Object result = RedisClientOperations.super.eval(script, keys, args);
                    if (script.contains("Composite active-writer bound exceeded"))
                        throw new IllegalStateException("Registration reply lost");
                    return result;
                }
            };
            var fence = new RedisCompositeMigrationFence(lostReply, f.keys, "rows");
            var invoked = new java.util.concurrent.atomic.AtomicBoolean();
            assertThrows(IllegalStateException.class, () -> fence.write(() -> { invoked.set(true); return null; }));
            assertFalse(invoked.get());
            assertEquals(1L, f.redis.hlen(fence.identityKeys().get(1)));
            assertEquals(-1L, f.redis.pttl(fence.identityKeys().get(1)));
            assertTrue(f.manager.resolveCompositeWindow(query(), null, row -> true).isEmpty());
            assertThrows(RuntimeException.class, fence::exclusive);
        }
    }

    @Test void unknownReleaseOutcomeKeepsPersistentWriterBlocker() {
        try (var f = new RedisCompositeRangeRedisTest.Fixture()) {
            RedisClientOperations lostRelease = new RedisClientOperations() {
                public redis.clients.jedis.UnifiedJedis commands() { return f.redis; }
                public Object eval(String script, List<String> keys, List<String> args) {
                    if (script.equals("return redis.call('HDEL',KEYS[1],ARGV[1])"))
                        throw new IllegalStateException("Release outcome unknown");
                    return RedisClientOperations.super.eval(script, keys, args);
                }
            };
            var fence = new RedisCompositeMigrationFence(lostRelease, f.keys, "rows");
            var invoked = new java.util.concurrent.atomic.AtomicBoolean();
            assertThrows(IllegalStateException.class, () -> fence.write(() -> { invoked.set(true); return null; }));
            assertTrue(invoked.get());
            assertEquals(1L, f.redis.hlen(fence.identityKeys().get(1)));
            assertTrue(f.manager.resolveCompositeWindow(query(), null, row -> true).isEmpty());
            assertThrows(RuntimeException.class, fence::exclusive);
        }
    }

    @Test void writerEnteringAfterJavaEligibilityIsRejectedByTheAtomicWindowScript() {
        try (var f = new RedisCompositeRangeRedisTest.Fixture()) {
            f.put(new Row("1", "a", "OPEN", 1, true));
            var fence = new RedisCompositeMigrationFence(JedisPooledOperations.of(f.redis), f.keys, "rows");
            var rangeCalls = new java.util.concurrent.atomic.AtomicInteger();
            RedisClientOperations racing = new RedisClientOperations() {
                public redis.clients.jedis.UnifiedJedis commands() { return f.redis; }
                public Object eval(String script, List<String> keys, List<String> args) {
                    if (script.contains("local probes=0")) {
                        rangeCalls.incrementAndGet();
                        assertEquals(6, keys.size());
                        f.redis.hset(fence.identityKeys().get(1), "interleaved-writer", "1");
                    }
                    return RedisClientOperations.super.eval(script, keys, args);
                }
            };
            var reader = manager(racing, f.keys, false, RedisCompositeRangeRedisTest.SORTS);
            assertTrue(reader.resolveCompositeWindow(query(), null, row -> true).isEmpty());
            assertEquals(1, rangeCalls.get());
            assertEquals(1, reader.compositeBoundedFallbacks());
        }
    }

    @Test void fenceKeyTypesArePreflightedBeforeAnyMutation() {
        try (var f = new RedisCompositeRangeRedisTest.Fixture()) {
            var fence = new RedisCompositeMigrationFence(JedisPooledOperations.of(f.redis), f.keys, "rows");
            f.redis.set(fence.identityKeys().get(1), "wrong-type");
            assertThrows(RuntimeException.class, fence::exclusive);
            assertFalse(f.redis.exists(fence.identityKeys().get(0)));
            assertThrows(RuntimeException.class, () -> fence.write(() -> {
                fail("Writer action must not execute"); return null;
            }));
        }
    }
}
