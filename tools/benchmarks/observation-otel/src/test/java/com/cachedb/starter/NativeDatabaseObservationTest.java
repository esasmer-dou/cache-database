package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.cache.*;
import com.reactor.cachedb.core.change.*;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.observe.*;
import com.reactor.cachedb.core.page.*;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.route.*;
import com.reactor.cachedb.redis.*;
import com.reactor.cachedb.spring.boot.*;
import com.reactor.cachedb.spring.boot.observe.*;
import io.micrometer.observation.*;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import redis.clients.jedis.JedisPooled;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis", matches="true")
class NativeDatabaseObservationTest {
    record Order(String id, String tenant, String status, Integer score) {}
    static final EntityMetadata<Order, String> METADATA = new EntityMetadata<>() {
        public String entityName() { return "Order"; }
        public String tableName() { return "orders"; }
        public String redisNamespace() { return "orders"; }
        public String idColumn() { return "id"; }
        public Class<Order> entityType() { return Order.class; }
        public Function<Order, String> idAccessor() { return Order::id; }
        public List<String> columns() { return List.of("id", "tenant", "status", "score"); }
        public Map<String, String> columnTypes() { return Map.of("id", "java.lang.String",
                "tenant", "java.lang.String", "status", "java.lang.String", "score", "java.lang.Integer"); }
        public List<RelationDefinition> relations() { return List.of(); }
        public Map<String, List<String>> compositeExactIndexes() { return Map.of("tenant_status", List.of("tenant", "status")); }
        public Map<String, String> compositeSortColumns() { return Map.of("tenant_status", "score"); }
    };
    static final EntityCodec<Order> CODEC = new EntityCodec<>() {
        public String toRedisValue(Order row) { return row.id() + "|" + row.tenant() + "|" + row.status() + "|" + row.score(); }
        public Order fromRedisValue(String value) {
            var fields = value.split("\\|"); return new Order(fields[0], fields[1], fields[2], Integer.valueOf(fields[3]));
        }
        public Map<String, Object> toColumns(Order row) {
            return Map.of("id", row.id(), "tenant", row.tenant(), "status", row.status(), "score", row.score());
        }
        public Order fromColumns(Map<String, Object> columns) {
            return new Order(String.valueOf(columns.get("id")), String.valueOf(columns.getOrDefault("tenant", "a")),
                    String.valueOf(columns.getOrDefault("status", "OPEN")), ((Number) columns.getOrDefault("score", 0)).intValue());
        }
    };
    static final class Source implements VersionedEntitySourceLoader<Order, String> {
        volatile RuntimeException failure;
        public Optional<VersionedEntity<Order>> loadVersionedById(String id) {
            if (failure != null) throw failure;
            return Optional.of(new VersionedEntity<>(new Order(id, "a", "OPEN", 7), 100));
        }
        public List<VersionedEntity<Order>> loadVersionedPage(PageWindow page) { return loadVersionedQuery(QuerySpec.allOf()); }
        public List<VersionedEntity<Order>> loadVersionedQuery(QuerySpec query) {
            if (failure != null) throw failure;
            return List.of(new VersionedEntity<>(new Order("warm", "a", "OPEN", 5), 20));
        }
    }
    static final class Sink implements ObservationHandler<Observation.Context> {
        final List<Map<String, String>> stopped = new CopyOnWriteArrayList<>();
        volatile CountDownLatch stoppedSignal = new CountDownLatch(1);
        public boolean supportsContext(Observation.Context context) { return context instanceof CacheMicrometerObservationContext; }
        public void onStop(Observation.Context context) {
            var tags = new LinkedHashMap<String, String>();
            context.getLowCardinalityKeyValues().forEach(value -> tags.put(value.getKey(), value.getValue()));
            stopped.add(Map.copyOf(tags));
            stoppedSignal.countDown();
        }
    }
    static final class Fixture implements AutoCloseable {
        final TestObservationRegistry registry = TestObservationRegistry.create();
        final Sink sink = new Sink();
        final AnnotationConfigApplicationContext spring = new AnnotationConfigApplicationContext();
        final Source source = new Source();
        final String prefix = "native-observe-" + UUID.randomUUID();
        final RedisKeyStrategy keys = new RedisKeyStrategy(prefix);
        final NearCachePolicy nearPolicy = new NearCachePolicy(32, 10, 65536);
        final JedisPooled redis = new JedisPooled("redis://" + System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1")
                + ":" + Integer.getInteger("cachedb.snapshot.redis.port", 16383));
        final CacheDatabase database;
        final EntityRepository<Order, String> repository;
        Fixture(boolean enabled) {
            registry.observationConfig().observationHandler(sink);
            if (enabled) spring.registerBean(ObservationRegistry.class, () -> registry);
            spring.registerBean(CacheObservationTagPolicy.class, () -> new CacheObservationTagPolicy(Set.of("Order"), Set.of("native_orders")));
            spring.register(CacheObservationAutoConfiguration.class); spring.refresh();
            var builder = CacheRuntimeOptions.builder().asyncMaxConcurrent(4)
                    .nearCacheInvalidation(NearCacheInvalidationConfig.defaults()
                            .withMode(NearCacheInvalidationMode.SERVER_TRACKING));
            var customizer = spring.getBeanProvider(CacheRuntimeOptionsCustomizer.class).getIfAvailable();
            if (customizer != null) customizer.customize(builder, new CacheDbSpringProperties());
            var sourceDatabase = new JdbcDataSource(); sourceDatabase.setURL("jdbc:h2:mem:" + prefix);
            var policy = CachePolicy.builder().hotEntityLimit(CachePolicy.UNBOUNDED).lruEvictionEnabled(false)
                    .stateWindow("status", List.of("OPEN")).nearCachePolicy(nearPolicy).build();
            var config = CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                    .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                    .redisGuardrail(RedisGuardrailConfig.builder().enabled(false).build())
                    .readThrough(ReadThroughConfig.builder().mode(ReadThroughMode.READ_THROUGH_BY_ID).build())
                    .writeBehind(WriteBehindConfig.builder().streamKey(prefix + ":writes")
                            .deadLetterStreamKey(prefix + ":dead").consumerGroup(prefix).build()).build();
            database = new CacheDatabase(redis, sourceDatabase, config, builder.build());
            database.register(METADATA, CODEC, policy, null, source, source, source);
            repository = database.repository(METADATA, CODEC, policy);
            database.start();
        }
        void reset() { registry.clear(); sink.stopped.clear(); sink.stoppedSignal = new CountDownLatch(1); }
        void awaitStopped() throws InterruptedException {
            assertTrue(sink.stoppedSignal.await(5, TimeUnit.SECONDS), "Observation onStop did not arrive");
        }
        RedisNearCacheCoordinator.Store nearStore() {
            return database.nearCacheCoordinator().register("orders", nearPolicy);
        }
        String state(String id) {
            var near = database.nearCacheCoordinator();
            return "id=" + id + ", connected=" + near.connected() + ", mode=" + near.invalidationMode()
                    + ", failure=" + near.invalidationFailure() + ", invalidations=" + near.invalidationsReceived()
                    + ", flushes=" + near.flushAllEvents() + ", l1=" + nearStore().get(id)
                    + ", redis=" + redis.mget(keys.entityKey("orders", id), keys.versionKey("orders", id),
                            keys.tombstoneKey("orders", id));
        }
        void one(String operation, String tier, String outcome) {
            TestObservationRegistryAssert.assertThat(registry).hasNumberOfObservationsEqualTo(1);
            assertEquals(1, sink.stopped.size());
            var tags = sink.stopped.getFirst();
            assertEquals(operation, tags.get("cachedb.operation"));
            if (tier != null) assertEquals(tier, tags.get("cachedb.tier"));
            assertEquals(outcome, tags.get("cachedb.outcome"));
            assertEquals("Order", tags.get("cachedb.entity"));
            assertTrue(Set.of("db.system", "cachedb.operation", "cachedb.entity", "cachedb.tier",
                    "cachedb.outcome", "cachedb.route").containsAll(tags.keySet()));
            assertNull(registry.getCurrentObservation());
            assertSame(NoOpCacheObservationRecorder.SCOPE, CacheObservationContext.current());
        }
        void awaitNear() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!database.nearCacheCoordinator().trackingReady() && System.nanoTime() < deadline) Thread.sleep(2);
            assertTrue(database.nearCacheCoordinator().trackingReady(), () -> state("redis"));
            assertEquals(NearCacheInvalidationMode.SERVER_TRACKING, database.nearCacheCoordinator().invalidationMode());
        }
        void awaitInvalidation(String id, RedisNearCacheCoordinator.Store.Stamp before) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (nearStore().capture(id).equals(before) && System.nanoTime() < deadline) Thread.sleep(2);
            var after = nearStore().capture(id);
            assertTrue(database.nearCacheCoordinator().trackingReady(), () -> state(id));
            assertEquals(before.clearEpoch(), after.clearEpoch(), "An unrelated reconnect must not satisfy invalidation");
            assertNotEquals(before.segmentEpoch(), after.segmentEpoch(), () -> state(id));
            assertNull(nearStore().get(id), () -> state(id));
        }
        void seed(Order row) throws InterruptedException {
            var before = nearStore().capture(row.id());
            redis.set(keys.entityKey("orders", row.id()), CODEC.toRedisValue(row));
            // SET completion does not order delivery on the separate tracking connection.
            awaitInvalidation(row.id(), before);
        }
        public void close() {
            try { database.close(); }
            finally {
                try {
                    String cursor = "0"; int rounds = 0;
                    do {
                        var scan = redis.scan(cursor, new redis.clients.jedis.params.ScanParams().match(prefix + ":*").count(256));
                        for (String key : scan.getResult()) redis.unlink(key);
                        cursor = scan.getCursor();
                        if (++rounds > 10000) throw new IllegalStateException("Fixture cleanup bound exceeded");
                    } while (!cursor.equals("0"));
                } finally { redis.close(); spring.close(); }
            }
        }
    }

    @Test void nativePublicDatabaseSpringRegistrySeesRedisL1SourceErrorAndNativeAsync() throws Exception {
        try (var f = new Fixture(true)) {
            f.awaitNear();
            f.seed(new Order("redis", "a", "OPEN", 3));
            f.reset(); assertTrue(f.repository.findById("redis").isPresent()); f.one("findById", "REDIS", "hit");
            f.reset(); assertTrue(f.repository.withFetchPlan(FetchPlan.empty()).findById("redis").isPresent());
            f.one("findById", "L1", "hit");
            f.reset(); assertTrue(f.repository.findById("source").isPresent()); f.one("findById", "SOURCE", "hit");
            f.source.failure = new IllegalStateException("sensitive");
            f.reset(); assertSame(f.source.failure, assertThrows(IllegalStateException.class, () -> f.repository.findById("error")));
            f.one("findById", "SOURCE", "error"); f.source.failure = null;
            f.seed(new Order("async", "a", "OPEN", 4));
            f.reset(); assertTrue(f.repository.async().findByIdAsync("async").get(5, TimeUnit.SECONDS).isPresent());
            assertTrue(f.database.asyncExecutor().awaitIdle(Duration.ofSeconds(5)));
            // The original future is preserved; get()/executor idle do not join its observer callbacks.
            f.awaitStopped(); f.one("findById", "REDIS", "hit");
            var route = RouteCacheContract.builder().routeName("native_orders").entityName("Order").build();
            f.reset();
            RouteCacheContext.runWithContract(route, () -> f.repository.findById("redis"));
            f.one("findById", "L1", "hit");
            assertEquals("native_orders", f.sink.stopped.getFirst().get("cachedb.route"));
        }
    }

    @Test void realWarmMigrationHydratorReconciliationAndExternalApplyUseThePublicRepository() throws Exception {
        try (var f = new Fixture(true)) {
            f.awaitNear();
            var warm = new CacheWarmRunner(f.database).execute(CacheWarmPlan.builder("Order")
                    .querySpec(QuerySpec.allOf().limitTo(10)).maxRows(10).reindexQueryIndexes(true).build());
            assertEquals(1, warm.submittedRows()); assertNotNull(f.redis.get(f.keys.entityKey("orders", "warm")));
            var hydrator = MigrationWarmRunner.using(f.database.entityRegistry(), f.database).resolve("Order").orElseThrow();
            hydrator.hydrateBatch(List.of(CODEC.toColumns(new Order("migration", "a", "OPEN", 6))), List.of(30L), false, true);
            assertNotNull(f.redis.get(f.keys.entityKey("orders", "migration")));

            f.redis.set(f.keys.entityKey("orders", "rejected"), CODEC.toRedisValue(new Order("rejected", "a", "CLOSED", 9)));
            f.redis.zadd(f.keys.hotSetKey("orders"), System.currentTimeMillis(), "rejected");
            var reconciliation = f.database.reconcileHotSet("Order", "0", 100, 100);
            assertTrue(reconciliation.evictedRows() >= 1);
            assertNull(f.redis.get(f.keys.entityKey("orders", "rejected")));
            assertNull(f.redis.get(f.keys.tombstoneKey("orders", "rejected")));

            var external = ExternalChangeApplyRunner.builder(f.database, f.database.entityRegistry()).build();
            var upsert = new ExternalChangeEvent("Order", "external", ExternalChangeType.UPSERT,
                    CODEC.toColumns(new Order("external", "a", "OPEN", 8)), 40, Instant.EPOCH, "test");
            var upsertStamp = f.nearStore().capture("external");
            assertEquals(ExternalChangeApplyStatus.APPLIED, external.apply(upsert).status());
            f.awaitInvalidation("external", upsertStamp);
            assertTrue(f.repository.findHotById("external").isHit());
            assertNotNull(f.nearStore().get("external"));
            var deleted = new ExternalChangeEvent("Order", "external", ExternalChangeType.DELETE, Map.of(),
                    41, Instant.EPOCH, "test");
            var deleteStamp = f.nearStore().capture("external");
            assertEquals(ExternalChangeApplyStatus.APPLIED, external.apply(deleted).status());
            assertNull(f.redis.get(f.keys.entityKey("orders", "external")));
            assertEquals("41", f.redis.get(f.keys.versionKey("orders", "external")));
            assertEquals("41", f.redis.get(f.keys.tombstoneKey("orders", "external")));
            f.awaitInvalidation("external", deleteStamp);
            assertTrue(f.repository.findHotById("external").isTombstoned(), () -> f.state("external"));
            assertEquals(0, f.redis.xlen(f.prefix + ":writes"));

            @SuppressWarnings("unchecked") var nativeRepository = (RedisEntityRepository<Order, String>) f.repository;
            assertTrue(nativeRepository.rebuildCompositeIndexesFenced(false, "acceptance").degradedCleared());
            var query = QuerySpec.allOf(QueryFilter.eq("tenant", "a"), QueryFilter.eq("status", "OPEN"))
                    .orderBy(QuerySort.asc("score")).limitTo(1);
            assertEquals(1, nativeRepository.queryRedisLexicalWindow(query,
                    com.reactor.cachedb.core.repository.WindowRequest.first(1), "tenant_status",
                    "native_orders", "a", null, null).size());
        }
    }

    @Test void absentSpringRegistryKeepsTheNativePublicDatabaseUnobserved() {
        try (var f = new Fixture(false)) {
            assertSame(NoOpCacheObservationRecorder.INSTANCE, f.database.runtimeOptions().observationRecorder());
            f.redis.set(f.keys.entityKey("orders", "plain"), CODEC.toRedisValue(new Order("plain", "a", "OPEN", 2)));
            assertTrue(f.repository.findById("plain").isPresent());
            TestObservationRegistryAssert.assertThat(f.registry).hasNumberOfObservationsEqualTo(0);
            assertTrue(f.sink.stopped.isEmpty());
        }
    }
}
