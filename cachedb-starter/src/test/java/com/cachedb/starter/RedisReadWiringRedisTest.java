package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.api.CacheUnitOfWork;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.cache.NearCachePolicy;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.redis.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class RedisReadWiringRedisTest {
    private static final String NS = "evolution";
    private static void await(BooleanSupplier test, long millis) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!test.getAsBoolean() && until - System.nanoTime() > 0) Thread.sleep(2);
        assertTrue(test.getAsBoolean(), "Bounded integration deadline exceeded");
    }
    private record Gate(CountDownLatch entered, CountDownLatch release) {}
    private static final class Fixture implements AutoCloseable {
        final String prefix = "t1-wiring-" + UUID.randomUUID();
        final JedisPooled redis = new JedisPooled("redis://" +
                System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1") + ":" +
                Integer.getInteger("cachedb.snapshot.redis.port", 16383));
        final RedisKeyStrategy keys = new RedisKeyStrategy(prefix);
        final Set<Long> seeded = new LinkedHashSet<>();
        final AtomicInteger mgets = new AtomicInteger(), pipelines = new AtomicInteger(), binaryReads = new AtomicInteger();
        final AtomicReference<Gate> readGate = new AtomicReference<>();
        final AtomicReference<Runnable> decodeHook = new AtomicReference<>();
        final HotPathEvolutionRedisTest.Codec delegate = new HotPathEvolutionRedisTest.Codec();
        final EntityCodec<HotPathEvolutionRedisTest.Row> codec = new EntityCodec<>() {
            public String toRedisValue(HotPathEvolutionRedisTest.Row row) { return delegate.toRedisValue(row); }
            public HotPathEvolutionRedisTest.Row fromRedisValue(String payload) {
                Runnable hook = decodeHook.getAndSet(null); if (hook != null) hook.run();
                return delegate.fromRedisValue(payload);
            }
            public Map<String, Object> toColumns(HotPathEvolutionRedisTest.Row row) { return delegate.toColumns(row); }
        };
        private final class TrackingOperations implements RedisClientOperations, RedisTrackingConnectionProvider {
            private final UnifiedJedis commands = new UnifiedJedis(new redis.clients.jedis.executors.CommandExecutor() {
                @Override public <T> T executeCommand(CommandObject<T> command) { return redis.executeCommand(command); }
                @Override public void close() {}
            }) {
                // Observe the primary dispatch, including a cold-cache EVALSHA before its EVAL fallback.
                @Override public Object evalsha(byte[] sha, List<byte[]> names, List<byte[]> args) {
                    binaryReads.incrementAndGet();
                    Gate gate = readGate.getAndSet(null);
                    if (gate != null) {
                        gate.entered.countDown();
                        try {
                            if (!gate.release.await(5, TimeUnit.SECONDS))
                                throw new IllegalStateException("Read integration test gate timed out");
                        } catch (InterruptedException stopped) {
                            Thread.currentThread().interrupt(); throw new IllegalStateException(stopped);
                        }
                    }
                    return redis.evalsha(sha, names, args);
                }
            };
            public List<RedisTrackingNode> trackingNodes(int maximumNodes) {
                return new JedisPooledOperations(redis).trackingNodes(maximumNodes);
            }
            public UnifiedJedis commands() { return commands; }
            public int physicalPoolCapacity() { return new JedisPooledOperations(redis).physicalPoolCapacity(); }
            public List<String> mget(String... names) { mgets.incrementAndGet(); return redis.mget(names); }
            public AbstractPipeline pipeline() {
                pipelines.incrementAndGet();
                return redis.pipelined();
            }
            public void close() { }
        }
        final RedisClientOperations operations = new TrackingOperations();
        final CacheDatabase database;
        final RedisEntityRepository<HotPathEvolutionRedisTest.Row, Long> repository;
        Fixture(boolean enabled, CachePolicy policy) {
            var jdbc = new JdbcDataSource(); jdbc.setURL("jdbc:h2:mem:" + prefix);
            var reads = RedisReadCoalescingConfig.builder().enabled(enabled).maxInFlightBatches(1).timeoutMillis(5000).build();
            CacheDatabase created = null;
            try {
                created = new CacheDatabase(operations, jdbc, CacheDatabaseConfig.builder()
                        .runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                        .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                        .writeBehind(WriteBehindConfig.builder().streamKey(prefix + ":writes")
                                .deadLetterStreamKey(prefix + ":deadletters").consumerGroup(prefix).build())
                        .build(), CacheRuntimeOptions.builder().redisReadCoalescing(reads)
                                .nearCacheInvalidation(NearCacheInvalidationConfig.builder()
                                        .mode(NearCacheInvalidationMode.PUBSUB).build()).build());
                created.register(HotPathEvolutionRedisTest.METADATA, codec, policy);
                repository = (RedisEntityRepository<HotPathEvolutionRedisTest.Row, Long>)
                        created.repository(HotPathEvolutionRedisTest.METADATA, codec, policy);
                database = created;
            } catch (RuntimeException | Error failure) {
                if (created != null) try { created.close(); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                try { redis.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        void seed(long id, long ttl) {
            if (!seeded.contains(id) && seeded.size() == 16) throw new IllegalStateException("Fixture seed budget exceeded");
            seeded.add(id);
            redis.set(keys.entityKey(NS, id), codec.toRedisValue(new HotPathEvolutionRedisTest.Row(id, "row-" + id, id)));
            redis.set(keys.versionKey(NS, id), "3");
            if (ttl > 0) redis.pexpire(keys.entityKey(NS, id), ttl);
        }
        @Override public void close() {
            try { database.close(); }
            finally {
                try {
                    var names = new ArrayList<String>();
                    for (long id : seeded) {
                        names.add(keys.entityKey(NS, id)); names.add(keys.versionKey(NS, id)); names.add(keys.tombstoneKey(NS, id));
                    }
                    names.add(keys.hotSetKey(NS));
                    redis.del(names.toArray(String[]::new));
                } finally { redis.close(); }
            }
        }
    }

    @Test void standaloneReportsPhysicalPoolCapacityInsteadOfTheCustomDefault() {
        try (var f = new Fixture(true, CachePolicy.defaults())) {
            int original = f.redis.getPool().getMaxTotal();
            try {
                f.redis.getPool().setMaxTotal(13);
                assertEquals(13, new JedisPooledOperations(f.redis).physicalPoolCapacity());
                f.redis.getPool().setMaxTotal(-1);
                assertThrows(IllegalStateException.class, () -> new JedisPooledOperations(f.redis).physicalPoolCapacity());
            } finally { f.redis.getPool().setMaxTotal(original); }
        }
    }
    @Test void oneRuntimeIsSharedAcrossPoliciesAndFetchPlanCopies() {
        try (var f = new Fixture(true, CachePolicy.defaults())) {
            f.seed(1, 0);
            var second = (RedisEntityRepository<HotPathEvolutionRedisTest.Row, Long>) f.database.repository(
                    HotPathEvolutionRedisTest.METADATA, f.codec, CachePolicy.builder().hotEntityLimit(6001).build());
            var copy = (RedisEntityRepository<HotPathEvolutionRedisTest.Row, Long>) f.repository.withFetchPlan(FetchPlan.empty());
            assertTrue(f.database.runtimeOptions().redisReadCoalescing().enabled());
            assertEquals(21, f.database.config().getClass().getRecordComponents().length);
            assertSame(f.database.redisReadRuntime(), f.repository.redisReadRuntime());
            assertSame(f.database.redisReadRuntime(), second.redisReadRuntime());
            assertSame(f.database.redisReadRuntime(), copy.redisReadRuntime());
            long before = f.database.redisReadMetrics().transport().batches();
            assertTrue(f.repository.findById(1L).isPresent());
            assertTrue(second.findHotById(1L).isHit());
            assertTrue(copy.findById(1L).isPresent());
            assertEquals(before + 3, f.database.redisReadMetrics().transport().batches());
            assertEquals(3, f.binaryReads.get());
            assertEquals(0, f.mgets.get(), "Eligible point reads must enter the shared bounded transport");
        }
    }
    @Test void causalAndUnitOfWorkReadsBypassTransportAndKeepDistinctCounters() {
        try (var f = new Fixture(true, CachePolicy.defaults())) {
            f.seed(1, 0);
            var before = f.database.redisReadMetrics();
            try (var scope = CacheCausalContext.withMinVersion(NS, 1L, 3)) {
                assertTrue(f.repository.findById(1L).isPresent());
                assertTrue(f.repository.findHotById(1L).isHit());
            }
            try (var work = CacheUnitOfWork.begin(f.database, f.database.writeBatch())) {
                assertTrue(f.repository.findById(1L).isPresent());
                assertTrue(f.repository.findHotById(1L).isHit());
            }
            try (var scope = CacheCausalContext.withMinVersion(NS, 1L, 4)) {
                assertThrows(IllegalStateException.class, () -> f.repository.findById(1L));
            }
            var after = f.database.redisReadMetrics();
            assertEquals(before.transport().batches(), after.transport().batches());
            assertEquals(before.causal() + 3, after.causal());
            assertEquals(before.unitOfWork() + 2, after.unitOfWork());
            assertEquals(before.bypassed() + 5, after.bypassed());
            assertEquals(5, f.mgets.get());
            assertFalse(CacheUnitOfWork.active());
            assertEquals(0, CacheCausalContext.requiredVersion(NS, 1L));
        }
    }
    @Test void disabledCoalescingUsesLegacySyncTransportWithoutPipelineOrFutureAdmission() {
        try (var f = new Fixture(false, CachePolicy.defaults())) {
            f.seed(1, 0);
            int pipelines = f.pipelines.get(), reads = f.mgets.get(), binaryReads = f.binaryReads.get();
            assertTrue(f.repository.findById(1L).isPresent());
            assertTrue(f.repository.findHotById(1L).isHit());
            assertEquals(reads + 2, f.mgets.get());
            assertEquals(pipelines, f.pipelines.get());
            assertEquals(binaryReads, f.binaryReads.get());
            assertEquals(0, f.database.redisReadMetrics().transport().batches());
            assertEquals(2, f.database.redisReadMetrics().disabled());
        }
    }
    @Test void closingOwnerForeclosesQueuedAndLegacyBypassReadsButNotTheBorrowedPool() throws Exception {
        try (var f = new Fixture(true, CachePolicy.defaults())) {
            f.seed(1, 0);
            var gate = new Gate(new CountDownLatch(1), new CountDownLatch(1));
            f.readGate.set(gate);
            var first = new CompletableFuture<Optional<HotPathEvolutionRedisTest.Row>>();
            Thread reader = Thread.ofVirtual().inheritInheritableThreadLocals(false).start(() -> {
                try { first.complete(f.repository.findById(1L)); }
                catch (Throwable failure) { first.completeExceptionally(failure); }
            });
            var closeDone = new CompletableFuture<Void>();
            Thread closer = null;
            try {
                assertTrue(gate.entered.await(2, TimeUnit.SECONDS));
                var queued = f.database.redisReadRuntime().readAsync(
                        f.keys.entityKey(NS, 1L), f.keys.tombstoneKey(NS, 1L), false);
                closer = Thread.ofVirtual().inheritInheritableThreadLocals(false).start(() -> {
                    try { f.database.close(); closeDone.complete(null); }
                    catch (Throwable failure) { closeDone.completeExceptionally(failure); }
                });
                var rejected = assertThrows(ExecutionException.class, () -> queued.get(2, TimeUnit.SECONDS));
                assertInstanceOf(RejectedExecutionException.class, rejected.getCause());
                assertThrows(RejectedExecutionException.class, () -> f.repository.findById(1L));
                try (var scope = CacheCausalContext.withMinVersion(NS, 1L, 3)) {
                    assertThrows(RejectedExecutionException.class, () -> f.repository.findHotById(1L));
                }
                try (var work = CacheUnitOfWork.begin(f.database, f.database.writeBatch())) {
                    assertThrows(RejectedExecutionException.class, () -> f.repository.findById(1L));
                }
                gate.release.countDown();
                first.get(5, TimeUnit.SECONDS); closeDone.get(5, TimeUnit.SECONDS);
                assertEquals("PONG", f.redis.ping());
            } finally {
                gate.release.countDown(); reader.interrupt();
                assertTrue(reader.join(Duration.ofSeconds(5)));
                if (closer != null) { closer.interrupt(); assertTrue(closer.join(Duration.ofSeconds(5))); }
            }
        }
    }
    @Test void warmL1HitsCannotEscapeForeclosureWhileTheSharedRawRuntimeIsDraining() throws Exception {
        var policy = CachePolicy.builder().nearCachePolicy(new NearCachePolicy(32, 10, 1048576)).build();
        try (var f = new Fixture(true, policy)) {
            var near = f.database.nearCacheCoordinator();
            var store = near.register(NS, policy.nearCachePolicy());
            await(near::connected, 5000);
            f.seed(1, 0); f.seed(2, 0);
            assertTrue(f.repository.findById(1L).isPresent());
            assertNotNull(store.get("1"), "Must begin with a real root-owned L1 hit");
            var copy = f.repository.withFetchPlan(FetchPlan.empty());
            var gate = new Gate(new CountDownLatch(1), new CountDownLatch(1));
            f.readGate.set(gate);
            var first = new CompletableFuture<Optional<HotPathEvolutionRedisTest.Row>>();
            var closeDone = new CompletableFuture<Void>();
            Thread reader = Thread.ofVirtual().inheritInheritableThreadLocals(false).start(() -> {
                try { first.complete(f.repository.findById(2L)); }
                catch (Throwable failure) { first.completeExceptionally(failure); }
            });
            Thread closer = null;
            try {
                assertTrue(gate.entered.await(2, TimeUnit.SECONDS));
                var queued = f.database.redisReadRuntime().readAsync(
                        f.keys.entityKey(NS, 2L), f.keys.tombstoneKey(NS, 2L), false);
                closer = Thread.ofVirtual().inheritInheritableThreadLocals(false).start(() -> {
                    try { f.database.close(); closeDone.complete(null); }
                    catch (Throwable failure) { closeDone.completeExceptionally(failure); }
                });
                var failure = assertThrows(ExecutionException.class, () -> queued.get(1, TimeUnit.SECONDS));
                assertInstanceOf(RejectedExecutionException.class, failure.getCause());
                assertFalse(closeDone.isDone(), "Must exercise foreclosure before raw drain/near-cache close");
                assertTrue(near.connected(), "The cached fast path must still be physically available");
                assertNotNull(store.get("1"));
                int pipelines = f.pipelines.get(), mgets = f.mgets.get(), binaryReads = f.binaryReads.get();
                assertThrows(RejectedExecutionException.class, () -> f.repository.findById(1L));
                assertThrows(RejectedExecutionException.class, () -> f.repository.findHotById(1L));
                assertThrows(RejectedExecutionException.class, () -> copy.findById(1L));
                assertEquals(pipelines, f.pipelines.get()); assertEquals(mgets, f.mgets.get());
                assertEquals(binaryReads, f.binaryReads.get());
                gate.release.countDown();
                first.get(5, TimeUnit.SECONDS); closeDone.get(5, TimeUnit.SECONDS);
                assertEquals("PONG", f.redis.ping(), "Root cleanup must preserve the borrowed pool");
            } finally {
                gate.release.countDown(); reader.interrupt();
                assertTrue(reader.join(Duration.ofSeconds(5)));
                if (closer != null) { closer.interrupt(); assertTrue(closer.join(Duration.ofSeconds(5))); }
            }
        }
    }
    @Test void disabledLegacyPathIsAlsoForeclosedOnClose() {
        try (var f = new Fixture(false, CachePolicy.defaults())) {
            f.database.close();
            assertThrows(RejectedExecutionException.class, () -> f.repository.findById(1L));
            assertThrows(RejectedExecutionException.class, () -> f.repository.findHotById(1L));
            assertEquals("PONG", f.redis.ping());
        }
    }
    @Test void nearFillRejectsThePreReadStampAfterDeterministicInvalidation() throws Exception {
        var policy = CachePolicy.builder().nearCachePolicy(new NearCachePolicy(32, 10, 1048576)).build();
        try (var f = new Fixture(true, policy)) {
            var near = f.database.nearCacheCoordinator();
            var store = near.register(NS, policy.nearCachePolicy());
            await(near::connected, 5000);
            f.seed(1, 0);
            f.decodeHook.set(() -> store.invalidate("1"));
            assertTrue(f.repository.findById(1L).isPresent());
            assertNull(store.get("1"), "The integrated point path must retain its pre-read stamp late-fill guard");
            assertTrue(f.repository.findById(1L).isPresent());
            assertNotNull(store.get("1"));
        }
    }
    @Test void coalescedTtlIsAgedAcrossCallerDecodeAndCannotExtendAnExpiredRedisEntry() throws Exception {
        var policy = CachePolicy.builder().nearCachePolicy(new NearCachePolicy(32, 10, 1048576)).build();
        try (var f = new Fixture(true, policy)) {
            var near = f.database.nearCacheCoordinator();
            var store = near.register(NS, policy.nearCachePolicy());
            await(near::connected, 5000);
            f.seed(1, 2000);
            f.decodeHook.set(() -> {
                try { Thread.sleep(2250); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IllegalStateException(stopped); }
            });
            assertTrue(f.repository.findById(1L).isPresent());
            assertFalse(f.redis.exists(f.keys.entityKey(NS, 1L)));
            assertNull(store.get("1"), "A response's transport TTL must be aged before L1 admission");
        }
    }

    @Test void oldAndNewDatabaseConstructorsShareTheStableBaseConfigSurface() {
        var jdbc = new JdbcDataSource(); jdbc.setURL("jdbc:h2:mem:runtime_options_constructor");
        try (var redis = new JedisPooled("redis://" + System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1")
                + ":" + Integer.getInteger("cachedb.snapshot.redis.port", 16383))) {
            var base = CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY).build();
            var operations = new JedisPooledOperations(redis);
            var options = CacheRuntimeOptions.builder()
                    .redisReadCoalescing(RedisReadCoalescingConfig.builder().enabled(false).build()).build();
            try (var oldPooled = new CacheDatabase(redis, jdbc, base);
                    var oldOperations = new CacheDatabase(operations, jdbc, base);
                    var oldPair = new CacheDatabase(redis, redis, jdbc, base);
                    var oldOperationsPair = new CacheDatabase(operations, operations, jdbc, base);
                    var configured = new CacheDatabase(operations, jdbc, base, options);
                    var configuredPooled = new CacheDatabase(redis, jdbc, base, options);
                    var configuredPair = new CacheDatabase(redis, redis, jdbc, base, options);
                    var configuredOperationsPair = new CacheDatabase(operations, operations, jdbc, base, options)) {
                assertFalse(oldPooled.runtimeOptions().redisReadCoalescing().enabled());
                assertFalse(oldOperations.runtimeOptions().redisReadCoalescing().enabled());
                assertFalse(oldPair.runtimeOptions().redisReadCoalescing().enabled());
                assertFalse(oldOperationsPair.runtimeOptions().redisReadCoalescing().enabled());
                assertSame(options, configured.runtimeOptions()); assertSame(options, configuredPooled.runtimeOptions());
                assertSame(options, configuredPair.runtimeOptions()); assertSame(options, configuredOperationsPair.runtimeOptions());
                assertEquals(base.mutations(), configured.config().mutations());
            }
            assertEquals("PONG", redis.ping(), "Every direct constructor borrows the supplied client");
        }
    }
}
