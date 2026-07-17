package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.KeyspaceConfig;
import com.reactor.cachedb.core.config.RedisFunctionsConfig;
import com.reactor.cachedb.core.config.ResourceLimits;
import com.reactor.cachedb.core.config.RuntimeCoordinationConfig;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.model.RelationDefinition;
import com.reactor.cachedb.core.page.VersionedEntity;
import com.reactor.cachedb.core.page.VersionedEntitySourceLoader;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.relation.NoOpRelationBatchLoader;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.CacheWarmPlan;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheScheduledWarmCoordinatorTest {

    @Test
    void leaseHeartbeatMustPreventASecondPodFromRunningTheSameWarmCycle() throws Exception {
        String redisUri = resolveRedisUri();
        try (JedisPooled jedis = new JedisPooled(URI.create(redisUri))) {
            boolean available = redisAvailable(jedis);
            if (Boolean.getBoolean("cachedb.test.redis.required")) {
                assertTrue(available, "Required Redis is unavailable at " + redisUri);
            } else {
                Assumptions.assumeTrue(available, "Redis is required for the multi-pod scheduled warm test");
            }
            String keyPrefix = "cachedb-scheduled-warm-test-" + UUID.randomUUID();
            BlockingLoader firstLoader = new BlockingLoader(true);
            BlockingLoader secondLoader = new BlockingLoader(false);
            CacheDatabase firstDatabase = cacheDatabase(jedis, keyPrefix, "pod-a", firstLoader);
            CacheDatabase secondDatabase = cacheDatabase(jedis, keyPrefix, "pod-b", secondLoader);
            CacheScheduledWarmRegistry firstRegistry = new CacheScheduledWarmRegistry();
            CacheScheduledWarmRegistry secondRegistry = new CacheScheduledWarmRegistry();
            CacheScheduledWarmCoordinator firstCoordinator = new CacheScheduledWarmCoordinator(
                    firstDatabase, jedis, firstRegistry, "coordination:scheduled-warm", 1, 2_000L
            );
            CacheScheduledWarmCoordinator secondCoordinator = new CacheScheduledWarmCoordinator(
                    secondDatabase, jedis, secondRegistry, "coordination:scheduled-warm", 1, 2_000L
            );
            CacheScheduledWarmDefinition definition = definition();
            CacheWarmPlan plan = CacheWarmPlan.builder("ScheduledEntity")
                    .name("scheduled-entity-window")
                    .querySpec(QuerySpec.builder().limit(1).build())
                    .maxRows(1)
                    .build();
            var executor = Executors.newSingleThreadExecutor();
            try {
                firstCoordinator.register(definition.jobName());
                secondCoordinator.register(definition.jobName());
                var first = executor.submit(() -> firstCoordinator.execute(definition, () -> plan));
                assertTrue(firstLoader.entered.await(5, TimeUnit.SECONDS));

                secondCoordinator.execute(definition, () -> plan);

                assertEquals(
                        CacheScheduledWarmState.SKIPPED_LOCK_TIMEOUT,
                        secondRegistry.snapshot(definition.jobName()).state()
                );
                assertEquals(0, secondLoader.queryCalls.get());

                firstLoader.release.countDown();
                first.get(5, TimeUnit.SECONDS);
                assertEquals(CacheScheduledWarmState.COMPLETED, firstRegistry.snapshot(definition.jobName()).state());

                secondCoordinator.execute(definition, () -> plan);
                assertEquals(CacheScheduledWarmState.SKIPPED_NOT_DUE, secondRegistry.snapshot(definition.jobName()).state());
                assertEquals(0, secondLoader.queryCalls.get());
            } finally {
                firstLoader.release.countDown();
                executor.shutdownNow();
                firstCoordinator.close();
                secondCoordinator.close();
                firstDatabase.close();
                secondDatabase.close();
                for (String key : jedis.keys(keyPrefix + "*")) {
                    jedis.del(key);
                }
            }
        }
    }

    @Test
    void failedOwnerMustReleaseTheLeaseWithoutCommittingAndAllowAnotherPodToRetry() {
        String redisUri = resolveRedisUri();
        try (JedisPooled jedis = new JedisPooled(URI.create(redisUri))) {
            boolean available = redisAvailable(jedis);
            if (Boolean.getBoolean("cachedb.test.redis.required")) {
                assertTrue(available, "Required Redis is unavailable at " + redisUri);
            } else {
                Assumptions.assumeTrue(available, "Redis is required for the multi-pod scheduled warm test");
            }
            String keyPrefix = "cachedb-scheduled-warm-failover-test-" + UUID.randomUUID();
            BlockingLoader failingLoader = new BlockingLoader(false, true);
            BlockingLoader takeoverLoader = new BlockingLoader(false);
            CacheDatabase failingDatabase = cacheDatabase(jedis, keyPrefix, "pod-failing", failingLoader);
            CacheDatabase takeoverDatabase = cacheDatabase(jedis, keyPrefix, "pod-takeover", takeoverLoader);
            CacheScheduledWarmRegistry failingRegistry = new CacheScheduledWarmRegistry();
            CacheScheduledWarmRegistry takeoverRegistry = new CacheScheduledWarmRegistry();
            CacheScheduledWarmCoordinator failingCoordinator = new CacheScheduledWarmCoordinator(
                    failingDatabase, jedis, failingRegistry, "coordination:scheduled-warm", 1, 2_000L
            );
            CacheScheduledWarmCoordinator takeoverCoordinator = new CacheScheduledWarmCoordinator(
                    takeoverDatabase, jedis, takeoverRegistry, "coordination:scheduled-warm", 1, 2_000L
            );
            CacheScheduledWarmDefinition definition = definition();
            CacheWarmPlan plan = CacheWarmPlan.builder("ScheduledEntity")
                    .name("scheduled-entity-window")
                    .querySpec(QuerySpec.builder().limit(1).build())
                    .maxRows(1)
                    .build();
            try {
                failingCoordinator.register(definition.jobName());
                takeoverCoordinator.register(definition.jobName());

                failingCoordinator.execute(definition, () -> plan);
                assertEquals(CacheScheduledWarmState.FAILED, failingRegistry.snapshot(definition.jobName()).state());
                assertEquals(1, failingLoader.queryCalls.get());

                takeoverCoordinator.execute(definition, () -> plan);
                assertEquals(CacheScheduledWarmState.COMPLETED, takeoverRegistry.snapshot(definition.jobName()).state());
                assertEquals(1, takeoverLoader.queryCalls.get());
            } finally {
                failingCoordinator.close();
                takeoverCoordinator.close();
                failingDatabase.close();
                takeoverDatabase.close();
                for (String key : jedis.keys(keyPrefix + "*")) {
                    jedis.del(key);
                }
            }
        }
    }

    private CacheScheduledWarmDefinition definition() {
        return new CacheScheduledWarmDefinition(
                "scheduled-entity-refresh",
                CacheScheduledWarmDefinition.ScheduleKind.FIXED_DELAY,
                "",
                "",
                Duration.ofMinutes(10),
                Duration.ZERO,
                CacheScheduledWarmMode.ENTITY_AND_PROJECTIONS,
                Duration.ofMillis(900),
                Duration.ofMillis(1_400),
                Duration.ofMillis(50),
                Duration.ofMinutes(10),
                true,
                100,
                10
        );
    }

    private CacheDatabase cacheDatabase(
            JedisPooled jedis,
            String keyPrefix,
            String instanceId,
            BlockingLoader loader
    ) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + instanceId + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        CachePolicy policy = CachePolicy.builder()
                .hotEntityLimit(100)
                .pageSize(10)
                .stateWindow("status", List.of("ACTIVE"))
                .build();
        CacheDatabase database = new CacheDatabase(jedis, dataSource, CacheDatabaseConfig.builder()
                .writeBehind(WriteBehindConfig.builder().enabled(false).build())
                .redisFunctions(RedisFunctionsConfig.builder().enabled(false).build())
                .resourceLimits(ResourceLimits.builder().defaultCachePolicy(policy).build())
                .keyspace(KeyspaceConfig.builder().keyPrefix(keyPrefix).build())
                .runtimeCoordination(RuntimeCoordinationConfig.builder().instanceId(instanceId).build())
                .build());
        database.register(METADATA, CODEC, policy, new NoOpRelationBatchLoader<>(), loader, loader, loader);
        return database;
    }

    private boolean redisAvailable(JedisPooled jedis) {
        try {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String resolveRedisUri() {
        String configured = System.getProperty("cachedb.test.redis.uri");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("TEST_REDIS_URI");
        }
        return configured == null || configured.isBlank() ? "redis://127.0.0.1:56379" : configured;
    }

    private static final EntityMetadata<ScheduledEntity, Long> METADATA = new EntityMetadata<>() {
        @Override
        public String entityName() {
            return "ScheduledEntity";
        }

        @Override
        public String tableName() {
            return "scheduled_entity";
        }

        @Override
        public String redisNamespace() {
            return "scheduled-entities";
        }

        @Override
        public String idColumn() {
            return "id";
        }

        @Override
        public Class<ScheduledEntity> entityType() {
            return ScheduledEntity.class;
        }

        @Override
        public java.util.function.Function<ScheduledEntity, Long> idAccessor() {
            return ScheduledEntity::id;
        }

        @Override
        public List<String> columns() {
            return List.of("id", "status");
        }

        @Override
        public List<RelationDefinition> relations() {
            return List.of();
        }
    };

    private static final EntityCodec<ScheduledEntity> CODEC = new EntityCodec<>() {
        @Override
        public String toRedisValue(ScheduledEntity entity) {
            return entity.id() + "|" + entity.status();
        }

        @Override
        public ScheduledEntity fromRedisValue(String encoded) {
            String[] values = encoded.split("\\|", -1);
            return new ScheduledEntity(Long.parseLong(values[0]), values[1]);
        }

        @Override
        public Map<String, Object> toColumns(ScheduledEntity entity) {
            return Map.of("id", entity.id(), "status", entity.status());
        }
    };

    private record ScheduledEntity(long id, String status) {
    }

    private static final class BlockingLoader implements VersionedEntitySourceLoader<ScheduledEntity, Long> {
        private final boolean blocking;
        private final boolean failing;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger queryCalls = new AtomicInteger();

        private BlockingLoader(boolean blocking) {
            this(blocking, false);
        }

        private BlockingLoader(boolean blocking, boolean failing) {
            this.blocking = blocking;
            this.failing = failing;
        }

        @Override
        public Optional<VersionedEntity<ScheduledEntity>> loadVersionedById(Long id) {
            return Optional.empty();
        }

        @Override
        public List<VersionedEntity<ScheduledEntity>> loadVersionedPage(PageWindow pageWindow) {
            return List.of();
        }

        @Override
        public List<VersionedEntity<ScheduledEntity>> loadVersionedQuery(QuerySpec querySpec) {
            queryCalls.incrementAndGet();
            entered.countDown();
            if (blocking) {
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release scheduled warm test loader");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Scheduled warm test loader was interrupted", interrupted);
                }
            }
            if (failing) {
                throw new IllegalStateException("Deliberate scheduled warm failure");
            }
            return List.of(new VersionedEntity<>(new ScheduledEntity(1L, "ACTIVE"), 1L));
        }
    }
}
