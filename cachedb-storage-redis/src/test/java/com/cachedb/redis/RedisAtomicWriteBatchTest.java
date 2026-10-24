package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.registry.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.StreamEntryID;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class RedisAtomicWriteBatchTest {
    record Row(Long id, String value) {}

    @Test
    void oneEnvelopeSupportsIdempotentRetryAndAllMemberVersionPreconditions() {
        String prefix = "batch-test-" + UUID.randomUUID();
        var keys = new RedisKeyStrategy(prefix);
        var config =
                WriteBehindConfig.builder()
                        .streamKey(prefix + ":stream")
                        .compactionStreamKey(prefix + ":compaction")
                        .build();
        var registry = new DefaultEntityRegistry(ResourceLimits.defaults());
        registry.register(
                metadata(), codec(), CachePolicy.builder().hotEntityLimit(10).build(), null, null);
        try (var redis = new JedisPooled(RedisTestEndpoint.uri())) {
            try {
                var receipt =
                        batch(redis, registry, keys, config, "first")
                                .put(new Row(1L, "a"))
                                .put(new Row(2L, "b"))
                                .commit();
                assertEquals(List.of(1L, 1L), receipt.versions());
                assertEquals(1L, redis.xlen(config.activeStreamKey()));
                var retried =
                        batch(redis, registry, keys, config, "first")
                                .put(new Row(1L, "a"))
                                .put(new Row(2L, "b"))
                                .commit();
                assertEquals(receipt, retried);
                assertThrows(
                        RuntimeException.class,
                        () ->
                                batch(redis, registry, keys, config, "bad")
                                        .put(new Row(1L, "changed"), 1)
                                        .put(new Row(2L, "changed"), 0)
                                        .commit());
                assertEquals("1|a", redis.get(keys.entityKey("rows", 1)));
                assertEquals("1", redis.get(keys.versionKey("rows", 1)));
                var entry =
                        redis.xrange(
                                        config.activeStreamKey(),
                                        new StreamEntryID("0-0"),
                                        new StreamEntryID("9999999999999-0"))
                                .get(0);
                var operation = new RedisWriteOperationMapper().fromBody(entry.getFields());
                assertTrue(operation.atomicBatch());
                assertEquals(2, operation.batchOperations().size());
                assertEquals("first", operation.batchOperations().get(1).transactionGroupId());
                assertEquals(1L, operation.batchOperations().get(1).version());
                assertTrue(
                        new RedisWriteOperationMapper()
                                .fromBody(new RedisWriteOperationMapper().toBody(operation))
                                .atomicBatch());
            } finally {
                String cursor = "0";
                do {
                    var scan =
                            redis.scan(
                                    cursor,
                                    new redis.clients.jedis.params.ScanParams()
                                            .match(prefix + ":*")
                                            .count(100));
                    cursor = scan.getCursor();
                    for (String key : scan.getResult()) redis.unlink(key);
                } while (!cursor.equals("0"));
            }
        }
    }

    @Test
    void disabledAdmissionStillAcceptsAWriteEnvelopeWithoutCachingPayload() {
        String prefix = "disabled-test-" + UUID.randomUUID();
        var registry = new DefaultEntityRegistry(ResourceLimits.defaults());
        registry.register(
                metadata(), codec(), CachePolicy.builder().hotEntityLimit(0).build(), null, null);
        var keys = new RedisKeyStrategy(prefix);
        var config = WriteBehindConfig.builder().streamKey(prefix + ":stream").build();
        try (var redis = new JedisPooled(RedisTestEndpoint.uri())) {
            try {
                batch(redis, registry, keys, config, "zero").put(new Row(1L, "value")).commit();
                assertNull(redis.get(keys.entityKey("rows", 1)));
                assertEquals(0L, redis.zcard(keys.hotSetKey("rows")));
                assertEquals("1", redis.get(keys.versionKey("rows", 1)));
                assertEquals(1L, redis.xlen(config.activeStreamKey()));
            } finally {
                cleanup(JedisPooledOperations.of(redis), prefix);
            }
        }
    }

    @Test
    void aWorkerRetriesTheWholeEnvelopeAndMarksEveryMemberDurable() throws Exception {
        verifyWorker(false);
    }

    @Test
    @EnabledIfSystemProperty(named = "cachedb.redis.cluster", matches = "true")
    void aClusterWorkerPreservesAtomicEnvelopeRetriesAndDurability() throws Exception {
        verifyWorker(true);
    }

    private void verifyWorker(boolean cluster) throws Exception {
        String prefix =
                cluster
                        ? ClusterHashTagKeyStrategy.scopedPrefix(
                                "batch-test", UUID.randomUUID().toString())
                        : "batch-test-" + UUID.randomUUID();
        var keys = new RedisKeyStrategy(prefix);
        var config =
                WriteBehindConfig.builder()
                        .workerThreads(1)
                        .flushGroupParallelism(1)
                        .compactionShardCount(1)
                        .batchSize(10)
                        .blockTimeoutMillis(100)
                        .idleSleepMillis(10)
                        .maxFlushRetries(1)
                        .retryBackoffMillis(5)
                        .streamKey(prefix + ":stream")
                        .compactionStreamKey(prefix + ":compaction")
                        .deadLetterStreamKey(prefix + ":dlq")
                        .consumerGroup("batch-group")
                        .compactionConsumerGroup("batch-group")
                        .build();
        var registry = new DefaultEntityRegistry(ResourceLimits.defaults());
        registry.register(
                metadata(), codec(), CachePolicy.builder().hotEntityLimit(10).build(), null, null);
        RedisClientOperations redis;
        if (cluster) {
            var clientConfig =
                    DefaultJedisClientConfig.builder()
                            .connectionTimeoutMillis(3000)
                            .socketTimeoutMillis(5000)
                            .hostAndPortMapper(
                                    address ->
                                            System.getProperty("cachedb.redis.cluster.host") != null ? address :
                                            new redis.clients.jedis.HostAndPort(
                                                    "127.0.0.1",
                                                    address.getPort() < 10000
                                                            ? address.getPort() + 10000
                                                            : address.getPort()))
                            .build();
            redis =
                    new JedisClusterOperations(
                            new redis.clients.jedis.JedisCluster(
                                    Set.of(new redis.clients.jedis.HostAndPort(System.getProperty("cachedb.redis.cluster.host", "127.0.0.1"), Integer.getInteger("cachedb.redis.cluster.port", 17001))),
                                    clientConfig));
        } else redis = JedisPooledOperations.of(new JedisPooled(RedisTestEndpoint.uri()));
        try (redis) {
            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            com.reactor.cachedb.core.queue.WriteBehindFlusher flusher =
                    operation -> {
                        assertTrue(operation.atomicBatch());
                        assertEquals(2, operation.batchOperations().size());
                        if (attempts.getAndIncrement() == 0)
                            throw new java.sql.SQLException("retry entire group");
                    };
            try (var worker =
                    new RedisWriteBehindWorker(
                            redis, flusher, config, new RedisWriteOperationMapper(), keys)) {
                var receipt =
                        new RedisAtomicWriteBatch(
                                        redis,
                                        registry,
                                        keys,
                                        config,
                                        RedisGuardrailConfig.defaults(),
                                        null,
                                        "group")
                                .put(new Row(1L, "one"))
                                .put(new Row(2L, "two"))
                                .commit();
                assertEquals(2, receipt.dependencies().size());
                worker.start();
                var durability = new RedisDurabilityTracker(redis, keys);
                long deadline =
                        System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < deadline
                        && !durability.areDurableDependencies(receipt.dependencies()))
                    Thread.sleep(20);
                assertTrue(durability.areDurableDependencies(receipt.dependencies()));
                assertEquals(2, attempts.get());
            } finally {
                cleanup(redis, prefix);
            }
        }
    }

    private void cleanup(RedisClientOperations redis, String prefix) {
        String cursor = "0";
        do {
            var result =
                    redis.scan(
                            cursor,
                            new redis.clients.jedis.params.ScanParams()
                                    .match(prefix + ":*")
                                    .count(100));
            cursor = result.getCursor();
            for (String key : result.getResult()) redis.commands().unlink(key);
        } while (!cursor.equals("0"));
    }

    @Test
    void batchUsesTheSameRecencyScaleAsOrdinaryEntityAccess() {
        String prefix = "recency-test-" + UUID.randomUUID();
        var registry = new DefaultEntityRegistry(ResourceLimits.defaults());
        registry.register(
                metadata(), codec(), CachePolicy.builder().hotEntityLimit(1).build(), null, null);
        var keys = new RedisKeyStrategy(prefix);
        var config = WriteBehindConfig.builder().streamKey(prefix + ":stream").build();
        try (var redis = new JedisPooled(RedisTestEndpoint.uri())) {
            try {
                redis.set(keys.entityKey("rows", 0), "old");
                redis.zadd(keys.hotSetKey("rows"), System.currentTimeMillis() - 1000, "0");
                batch(redis, registry, keys, config, "recent").put(new Row(1L, "new")).commit();
                assertEquals("1|new", redis.get(keys.entityKey("rows", 1)));
                assertNull(redis.get(keys.entityKey("rows", 0)));
            } finally {
                cleanup(JedisPooledOperations.of(redis), prefix);
            }
        }
    }

    @Test void aBatchPreparedOutsideTheRouteStillHonorsTheCommitTimeTenantContract() {
        var registry=new DefaultEntityRegistry(ResourceLimits.defaults());
        registry.register(metadata(),codec(),CachePolicy.defaults(),null,null);
        var configuration=WriteBehindConfig.defaults();
        RedisClientOperations unusedClient = new RedisClientOperations() {
            public redis.clients.jedis.UnifiedJedis commands() {
                throw new AssertionError("A rejected contract must not reach Redis");
            }
        };
        var batch=new RedisAtomicWriteBatch(unusedClient,registry,new RedisKeyStrategy(),configuration,
                RedisGuardrailConfig.defaults(),null,"tenant").put(new Row(1L,"value"));
        var route=com.reactor.cachedb.core.route.RouteCacheContract.builder()
                .tenantQuota(new com.reactor.cachedb.core.route.TenantCacheQuota("tenant_id",10,1000,true)).build();
        assertThrows(IllegalStateException.class,()->com.reactor.cachedb.core.route.RouteCacheContext
                .runWithContract(route,()->batch.commit()));
    }

    private RedisAtomicWriteBatch batch(
            JedisPooled redis,
            EntityRegistry registry,
            RedisKeyStrategy keys,
            WriteBehindConfig config,
            String id) {
        return new RedisAtomicWriteBatch(
                redis, registry, keys, config, RedisGuardrailConfig.defaults(), null, id);
    }

    EntityMetadata<Row, Long> metadata() {
        return new EntityMetadata<>() {
            public String entityName() {
                return "Row";
            }

            public String tableName() {
                return "rows";
            }

            public String redisNamespace() {
                return "rows";
            }

            public String idColumn() {
                return "id";
            }

            public Class<Row> entityType() {
                return Row.class;
            }

            public java.util.function.Function<Row, Long> idAccessor() {
                return Row::id;
            }

            public List<String> columns() {
                return List.of("id", "value");
            }

            public List<RelationDefinition> relations() {
                return List.of();
            }
        };
    }

    EntityCodec<Row> codec() {
        return new EntityCodec<>() {
            public String toRedisValue(Row r) {
                return r.id() + "|" + r.value();
            }

            public Row fromRedisValue(String value) {
                var parts = value.split("\\|", 2);
                return new Row(Long.valueOf(parts[0]), parts[1]);
            }

            public Map<String, Object> toColumns(Row r) {
                return Map.of("id", r.id(), "value", r.value());
            }
        };
    }
}
