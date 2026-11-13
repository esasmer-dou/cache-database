package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.*;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.redis.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;
import redis.clients.jedis.Protocol.Command;
import redis.clients.jedis.params.ScanParams;

/** Run serially against an isolated Redis server: INFO commandstats is server-wide, not keyspace-local. */
@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class NearCacheTrackingWiringRedisTest {
    static final String NS = "evolution";
    static final CachePolicy POLICY = CachePolicy.builder().hotEntityLimit(5000)
            .nearCachePolicy(new NearCachePolicy(64, 10, 1048576)).build();
    static URI endpoint() {
        return URI.create(System.getProperty("cachedb.tracking.redis.uri", "redis://" +
                System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1") + ":" +
                Integer.getInteger("cachedb.snapshot.redis.port", 16383)));
    }
    static void await(BooleanSupplier condition, long millis) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean() && until - System.nanoTime() > 0) Thread.sleep(2);
        assertTrue(condition.getAsBoolean(), "Tracking integration bounded deadline exceeded");
    }
    static long publishes(JedisPooled redis) {
        Object raw = redis.sendCommand(Command.INFO, "commandstats");
        String info = raw instanceof byte[] bytes
                ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8) : raw.toString();
        for (String line : info.split("\\r?\\n")) {
            if (!line.startsWith("cmdstat_publish:")) continue;
            for (String field : line.substring(line.indexOf(':') + 1).split(","))
                if (field.startsWith("calls=")) return Long.parseLong(field.substring(6));
            throw new AssertionError("Malformed INFO PUBLISH statistics");
        }
        return 0;
    }
    static NearCacheInvalidationConfig invalidation(NearCacheInvalidationMode mode, boolean legacy) {
        return NearCacheInvalidationConfig.builder().mode(mode).publishLegacyInvalidations(legacy).build();
    }
    static final class Fixture implements AutoCloseable {
        final String prefix;
        final JedisPooled redis;
        final RedisKeyStrategy keys;
        final AtomicReference<Runnable> decodeHook = new AtomicReference<>();
        final AtomicInteger decodes = new AtomicInteger();
        final HotPathEvolutionRedisTest.Codec delegate = new HotPathEvolutionRedisTest.Codec();
        final EntityCodec<HotPathEvolutionRedisTest.Row> codec = new EntityCodec<>() {
            public String toRedisValue(HotPathEvolutionRedisTest.Row row) { return delegate.toRedisValue(row); }
            public Map<String, Object> toColumns(HotPathEvolutionRedisTest.Row row) { return delegate.toColumns(row); }
            public HotPathEvolutionRedisTest.Row fromColumns(Map<String, Object> columns) { return delegate.fromColumns(columns); }
            public HotPathEvolutionRedisTest.Row fromRedisValue(String payload) {
                decodes.incrementAndGet(); Runnable hook = decodeHook.getAndSet(null); if (hook != null) hook.run();
                return delegate.fromRedisValue(payload);
            }
        };
        final CacheDatabase database;
        final RedisEntityRepository<HotPathEvolutionRedisTest.Row, Long> repository;
        final RedisNearCacheCoordinator.Store store;
        Fixture(NearCacheInvalidationConfig config) throws Exception {
            this("t2-wiring-" + UUID.randomUUID(), endpoint(), config);
        }
        Fixture(String prefix, URI uri, NearCacheInvalidationConfig config) throws Exception {
            this.prefix = prefix; this.redis = new JedisPooled(uri); keys = new RedisKeyStrategy(prefix);
            var jdbc = new JdbcDataSource(); jdbc.setURL("jdbc:h2:mem:" + UUID.randomUUID());
            database = new CacheDatabase(new JedisPooledOperations(redis), jdbc, CacheDatabaseConfig.builder()
                    .runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                    .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                    .writeBehind(WriteBehindConfig.builder().streamKey(prefix + ":writes")
                            .deadLetterStreamKey(prefix + ":deadletters").consumerGroup(prefix).build())
                    .queryIndex(QueryIndexConfig.builder().exactIndexEnabled(false).rangeIndexEnabled(false)
                            .prefixIndexEnabled(false).textIndexEnabled(false).plannerStatisticsEnabled(false)
                            .cacheWarmingEnabled(false).build())
                    .build(), CacheRuntimeOptions.builder().nearCacheInvalidation(config).build());
            try {
                database.register(HotPathEvolutionRedisTest.METADATA, codec, POLICY);
                repository = (RedisEntityRepository<HotPathEvolutionRedisTest.Row, Long>)
                        database.repository(HotPathEvolutionRedisTest.METADATA, codec, POLICY);
                store = database.nearCacheCoordinator().register(NS, POLICY.nearCachePolicy());
                database.start(); await(database.nearCacheCoordinator()::connected, 10000);
            } catch (RuntimeException | Error | InterruptedException failure) { database.close(); redis.close(); throw failure; }
        }
        void save(long id, String value) { repository.save(new HotPathEvolutionRedisTest.Row(id, value, id)); }
        void seed(long id, String value) throws Exception {
            long before = database.nearCacheCoordinator().invalidationsReceived();
            redis.set(keys.entityKey(NS, id), codec.toRedisValue(new HotPathEvolutionRedisTest.Row(id, value, id)));
            redis.set(keys.versionKey(NS, id), "1");
            if (database.nearCacheTrackingReadySupplier().getAsBoolean())
                await(() -> database.nearCacheCoordinator().invalidationsReceived() > before, 1000);
        }
        void awaitFilled(long id) throws Exception {
            await(() -> {
                repository.findById(id).orElseThrow();
                return store.get(Long.toString(id)) != null;
            }, 2000);
        }
        @Override public void close() {
            try { database.close(); }
            finally {
                try {
                    String cursor = "0";
                    int pages = 0;
                    do {
                        var result = redis.scan(cursor, new ScanParams().match(prefix + ":*").count(256));
                        var names = result.getResult();
                        if (!names.isEmpty()) redis.del(names.toArray(String[]::new));
                        cursor = result.getCursor();
                        if (++pages > 128) throw new IllegalStateException("Isolated fixture cleanup scan budget exceeded");
                    } while (!"0".equals(cursor));
                } finally { redis.close(); }
            }
        }
    }
    @Test void oneThousandCacheDbWritesHaveZeroPublishDeltaWithRealPositiveControl() throws Exception {
        try (var control = new Fixture(invalidation(NearCacheInvalidationMode.PUBSUB, true))) {
            long before = publishes(control.redis); control.save(1, "positive-control");
            assertTrue(publishes(control.redis) > before, "A real CacheDB write must exercise the INFO PUBLISH counter");
        }
        try (var f = new Fixture(invalidation(NearCacheInvalidationMode.SERVER_TRACKING, false))) {
            assertEquals(NearCacheInvalidationMode.SERVER_TRACKING, f.database.nearCacheInvalidationMode());
            assertTrue(f.database.nearCacheTrackingReadySupplier().getAsBoolean());
            assertFalse(f.database.publishLegacyNearInvalidations());
            long before = publishes(f.redis);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            for (int i = 1; i <= 1000; i++) {
                assertTrue(deadline - System.nanoTime() > 0, "1000-write test exceeded its hard budget");
                assertTrue(f.database.nearCacheTrackingReadySupplier().getAsBoolean(), "Do not count a degraded run as an opt-out proof");
                f.save(i, "write-" + i);
            }
            assertTrue(f.database.nearCacheTrackingReadySupplier().getAsBoolean());
            assertEquals(0, publishes(f.redis) - before, "No CacheDB foreground write may publish under healthy strict opt-out");
            for (int start = 1; start <= 1000; start += 256) {
                var versions = new ArrayList<String>();
                for (long id = start; id <= Math.min(1000, start + 255); id++) versions.add(f.keys.versionKey(NS, id));
                assertTrue(f.redis.mget(versions.toArray(String[]::new)).stream().allMatch("1"::equals),
                        "Every one of the 1000 distinct CacheDB writes must really increment its version");
            }
            assertEquals("write-1000", f.repository.findById(1000L).orElseThrow().name);
        }
    }
    @Test void atomicBatchAndPointDeleteUseTheSameDynamicPublicationPolicy() throws Exception {
        try (var f = new Fixture(invalidation(NearCacheInvalidationMode.SERVER_TRACKING, false))) {
            f.save(1, "old"); f.awaitFilled(1);
            await(() -> f.database.nearCacheTrackingReadySupplier().getAsBoolean(), 1000);
            long before = publishes(f.redis), invalidations = f.database.nearCacheCoordinator().invalidationsReceived();
            f.database.writeBatch("strict_batch").put(new HotPathEvolutionRedisTest.Row(1L, "batch", 1)).commit();
            await(() -> f.database.nearCacheCoordinator().invalidationsReceived() > invalidations, 1000);
            assertEquals("batch", f.repository.findById(1L).orElseThrow().name);
            f.repository.patchWithReceipt(1L, Map.of("name", "patched"));
            assertEquals("patched", f.repository.findById(1L).orElseThrow().name);
            f.repository.deleteById(1L);
            assertTrue(f.repository.findById(1L).isEmpty());
            assertEquals(0, publishes(f.redis) - before);
        }
    }
    @Test void preIoStampRejectsSameIdPointAndBulkLateFillsButNotOtherSegments() throws Exception {
        try (var f = new Fixture(invalidation(NearCacheInvalidationMode.SERVER_TRACKING, false))) {
            f.seed(1, "old"); f.seed(2, "two");
            f.decodeHook.set(() -> f.store.invalidate("1"));
            assertTrue(f.repository.findById(1L).isPresent());
            assertNull(f.store.get("1"), "Point stamp must precede read and codec callbacks");
            f.decodeHook.set(() -> f.store.invalidate("1"));
            assertEquals(2, f.repository.findAll(List.of(1L, 2L)).size());
            assertNull(f.store.get("1"), "Bulk stamps must precede payload/TTL I/O");
            f.store.invalidate("1");
            f.decodeHook.set(() -> f.store.invalidate("2"));
            assertTrue(f.repository.findHotById(1L).isHit());
            assertNotNull(f.store.get("1"), "Unrelated segments must no longer invalidate a known-ID fill");
        }
    }
    @Test void knownTopologyChangeFlushesAndSuppressesAdmissionBeforeReturningToCaller() throws Exception {
        try (var f = new Fixture(invalidation(NearCacheInvalidationMode.SERVER_TRACKING, false))) {
            f.seed(1, "old"); f.awaitFilled(1);
            var ids = f.database.nearCacheCoordinator().trackingClientIds();
            f.database.nearCacheCoordinator().topologyChanged();
            assertNull(f.store.get("1")); assertFalse(f.database.nearCacheTrackingReadySupplier().getAsBoolean());
            assertTrue(f.database.publishLegacyNearInvalidations());
            await(() -> f.database.nearCacheTrackingReadySupplier().getAsBoolean()
                    && !ids.equals(f.database.nearCacheCoordinator().trackingClientIds()), 10000);
        }
    }
    @Test void autoFallbackPeerRemainsCoherentWhenAnotherPeerUsesServerTracking() throws Exception {
        String prefix = "t2-mixed-" + UUID.randomUUID(), user = "cachedb_t2_" + UUID.randomUUID().toString().replace("-", "");
        String password = UUID.randomUUID().toString().replace("-", "");
        URI base = endpoint();
        try (var admin = new JedisPooled(base)) {
            admin.sendCommand(Command.ACL, "SETUSER", user, "on", ">" + password, "~*", "&*", "+@all", "-client|tracking");
            URI limited = new URI(base.getScheme(), user + ":" + password, base.getHost(), base.getPort(), base.getPath(), null, null);
            try (var writer = new Fixture(prefix, base, invalidation(NearCacheInvalidationMode.SERVER_TRACKING, true));
                    var fallback = new Fixture(prefix, limited, invalidation(NearCacheInvalidationMode.AUTO, true))) {
                assertEquals(NearCacheInvalidationMode.PUBSUB, fallback.database.nearCacheInvalidationMode());
                assertFalse(fallback.database.nearCacheTrackingReadySupplier().getAsBoolean());
                assertTrue(writer.database.publishLegacyNearInvalidations());
                writer.save(1, "old"); fallback.awaitFilled(1);
                assertNotNull(fallback.store.get("1"));
                long before = publishes(admin), invalidations = fallback.database.nearCacheCoordinator().invalidationsReceived();
                writer.save(1, "new");
                await(() -> fallback.database.nearCacheCoordinator().invalidationsReceived() > invalidations, 1000);
                assertEquals("new", fallback.repository.findById(1L).orElseThrow().name);
                assertTrue(publishes(admin) > before, "Default legacy=true must protect the fallback peer");
            } finally { admin.sendCommand(Command.ACL, "DELUSER", user); }
        }
    }

    @Test void strictOptOutNeverFallsBackAfterTrackingBecomesUnavailable() throws Exception {
        String user = "cachedb_t2_" + UUID.randomUUID().toString().replace("-", "");
        String password = UUID.randomUUID().toString().replace("-", "");
        URI base = endpoint();
        try (var admin = new JedisPooled(base)) {
            admin.sendCommand(Command.ACL, "SETUSER", user, "on", ">" + password, "~*", "&*", "+@all");
            URI uri = new URI(base.getScheme(), user + ":" + password, base.getHost(), base.getPort(), base.getPath(), null, null);
            try (var f = new Fixture("t2-strict-" + UUID.randomUUID(), uri,
                    invalidation(NearCacheInvalidationMode.SERVER_TRACKING, false))) {
                f.seed(1, "old"); f.awaitFilled(1);
                var pendingBatch = f.database.writeBatch("degraded_batch")
                        .put(new HotPathEvolutionRedisTest.Row(3L, "queued-before-loss", 3));
                var subscription = f.database.nearCacheCoordinator().trackingClientIds().get("standalone").getFirst();
                admin.sendCommand(Command.ACL, "SETUSER", user, "-client|tracking");
                admin.sendCommand(Command.CLIENT, "KILL", "ID", subscription.toString());
                await(() -> !f.database.nearCacheCoordinator().connected(), 1500);
                assertNull(f.store.get("1"));
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500);
                do {
                    assertFalse(f.database.nearCacheCoordinator().connected(), "Strict mode must not become a PUBSUB fallback");
                    assertFalse(f.database.nearCacheTrackingReadySupplier().getAsBoolean());
                    assertEquals(NearCacheInvalidationMode.SERVER_TRACKING, f.database.nearCacheInvalidationMode());
                    Thread.sleep(5);
                } while (deadline - System.nanoTime() > 0);
                long before = publishes(admin);
                f.save(2, "degraded-write");
                assertTrue(publishes(admin) > before, "The dynamic mapper must resume legacy publication on local tracking loss");
                before = publishes(admin); pendingBatch.commit();
                assertTrue(publishes(admin) > before, "A batch created while healthy must evaluate publication at commit, not creation");
                assertNull(f.store.get("2"), "Tracking failure must leave L1 disabled");
                assertNull(f.store.get("3"));
            } finally { admin.sendCommand(Command.ACL, "DELUSER", user); }
        }
    }
}
