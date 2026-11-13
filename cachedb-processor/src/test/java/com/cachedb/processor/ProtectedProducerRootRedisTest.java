package com.reactor.cachedb.processor;

import static com.reactor.cachedb.processor.EncryptionCompilerFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.*;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Binding;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.SourcePlan;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.jdbc.*;
import com.reactor.cachedb.redis.*;
import com.reactor.cachedb.starter.CacheDatabase;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.JedisPooled;

/** Real root/session + generated encryption codec. This is not a SQL purge/provider certification test. */
@EnabledIfSystemProperty(named="cachedb.snapshot.redis", matches="true")
@Timeout(60)
class ProtectedProducerRootRedisTest {
    @TempDir Path temp;

    @Test void encryptedNativeRootAcceptsStampedWritesAndL1PointReadsButClosedTenantCannotReadOrWrite() throws Exception {
        var sources = new HashMap<>(recordSources(true));
        sources.compute("sample/ProtectedRow.java", (path, source) ->
                source.replace("@CacheEntity(", "@CacheEntity(tenantPurgeEnabled=true, "));
        String prefix = "producer_root_" + UUID.randomUUID().toString().replace("-", "");
        String library = prefix + "_functions";
        var functions = RedisFunctionsConfig.builder().libraryName(library)
                .upsertFunctionName(library + "_upsert").deleteFunctionName(library + "_delete")
                .compactionCompleteFunctionName(library + "_complete").build();
        var write = WriteBehindConfig.builder().enabled(false).streamKey(prefix + ":stream")
                .deadLetterStreamKey(prefix + ":dlq").compactionStreamKey(prefix + ":compaction").build();
        var config = CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build()).redisFunctions(functions)
                .redisGuardrail(RedisGuardrailConfig.builder().enabled(false).build()).writeBehind(write).build();
        var options = CacheRuntimeOptions.builder().keyProvider(keys("k1", key("k1", 1))).build();
        var source = new NoSql();
        try (var fixture = load(compile(temp, sources));
                var initial = NativeProducerInitialFixture.create(fixture.metadata(), source);
                var redis = new JedisPooled(
                System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1"),
                Integer.getInteger("cachedb.snapshot.redis.port", 16383))) {
            assertTrue(fixture.metadata().tenantPurgeEnabled(), "Requires annotation-to-metadata validation");
            assertProtectedL1Unsupported(fixture, initial, redis, config, options, source);
            try (var database = new CacheDatabase(redis, initial, config, options)) {
                database.start();
                var metadata = fixture.metadata();
                var policy = CachePolicy.builder().hotEntityLimit(16).build();
                database.register(metadata, fixture.unbound(), policy);
                var activated = initial.bootstrap(database, metadata, "tenant-a", 8);
                var producer = activated.producer();
                var repository = database.repository(metadata, fixture.unbound(), policy);
                var codec = database.runtimeCodec(metadata, fixture.unbound());
                var keys = database.redisKeyStrategy();
                var barrier = new RedisGenerationBarrier(database.redisOperations(), keys, metadata.redisNamespace());
                var pair = barrier.key("tenant-a");
                try (var admin = CacheTenantContext.administrative()) {
                    // Only source-owner fixtures are component-only; use INITIAL's real 1..64 reservation.
                    for (long id : List.of(1L, 2L)) redis.set(keys.versionKey(metadata.redisNamespace(), id) + ":tenant", "tenant-a");
                }
                try (var tenant = CacheTenantContext.withTenant("tenant-a")) {
                    var accepted = repository.saveWithReceipt(fixture.entity());
                    assertEquals(1, accepted.version());
                    assertEquals(fixture.entity(), accepted.entity());
                    assertEquals(fixture.entity(), repository.findById(1L).orElseThrow());
                    assertEquals(fixture.entity(), repository.findById(1L).orElseThrow()); // protected point read; INITIAL excludes L1
                    var columns = new LinkedHashMap<>(codec.toColumns(fixture.entity()));
                    columns.put("id", 2L);
                    Object second = codec.fromLogicalColumns(columns);
                    var batch = repository.saveAll(List.of(fixture.entity(), second));
                    assertEquals(List.of(2L, 3L), batch.stream().map(WriteReceipt::version).toList());
                    var cas = repository.save(fixture.entity(), 2);
                    assertEquals(4, cas.version());
                    var patch = repository.patchWithReceipt(1L, Map.of("contact", "changed"));
                    assertEquals(5, patch.version());
                    assertEquals("changed", codec.toColumns(repository.findById(1L).orElseThrow()).get("contact"));
                    var deleted = repository.deleteWithReceipt(1L);
                    assertEquals(6, deleted.version());
                    assertTrue(repository.findById(1L).isEmpty());
                    var bulk = repository.bulkDelete(List.of(2L));
                    assertEquals(1, bulk.size()); assertEquals(7, bulk.getFirst().version());
                    var unknownColumns = new LinkedHashMap<>(columns); unknownColumns.put("id", 3L);
                    assertEquals(ProtectionException.Code.MIGRATION_REQUIRED, assertThrows(ProtectionException.class,
                            () -> repository.saveWithReceipt(codec.fromLogicalColumns(unknownColumns))).code());
                    assertEquals(0, source.calls.get());
                    assertThrows(IllegalStateException.class, () -> database.configureTenantProducer(metadata, 8));
                }
                var entries = redis.xrange(write.streamKey(), new redis.clients.jedis.StreamEntryID("0-0"),
                        new redis.clients.jedis.StreamEntryID("9999999999999-0"));
                assertEquals(7, entries.size());
                for (var entry : entries) {
                    assertEquals("1", entry.getFields().get("tenantGeneration"));
                    assertEquals(pair.tenantToken(), entry.getFields().get("tenantToken"));
                    assertFalse(entry.getFields().toString().contains(SECRET));
                    assertFalse(entry.getFields().toString().contains(HIDDEN));
                }
                try (var admin = CacheTenantContext.administrative()) {
                    var guard = activated.controlGuard();
                    var optionsForClose = new PurgeOptions(UUID.randomUUID(), "tenant-a",
                            ProtectionKey.hash("producer-root-close"), false, 128, 120, 2000,
                            PurgeScope.ACTIVE_STORES_RETAINING_SECURITY_METADATA);
                    var closing = guard.acquireForTenant(activated.binding(), "tenant-a", optionsForClose, false);
                    try (AutoCloseable sqlLeaseCleanup = () -> guard.release(closing);
                            AutoCloseable redisLeaseCleanup = () -> barrier.release(closing)) {
                        var progress = guard.load(closing);
                        assertEquals(TenantPurgeProtocol.Stage.FENCING, progress.stage());
                        assertEquals(1, progress.closedGeneration());
                        barrier.close(closing, progress.closedGeneration());
                        assertTrue(producer.drain(pair).drained());
                        assertTrue(producer.readFence().closeAndDrain(closing, progress.closedGeneration()).drained());
                    }
                }
                try (var tenant = CacheTenantContext.withTenant("tenant-a")) {
                    assertThrows(RuntimeException.class, () -> repository.findById(2L));
                    assertThrows(RuntimeException.class, () -> repository.saveWithReceipt(fixture.entity()));
                    assertThrows(RuntimeException.class, () -> database.sourceRepository(metadata, fixture.unbound()));
                }
                assertEquals(0, source.calls.get());
            } finally {
                String cursor = "0"; int pages = 0;
                do {
                    if (++pages > 1000) throw new AssertionError("Fixture cleanup bound");
                    var page = redis.scan(cursor, new redis.clients.jedis.params.ScanParams().match(prefix + ":*").count(128));
                    cursor = page.getCursor();
                    if (!page.getResult().isEmpty()) redis.del(page.getResult().toArray(String[]::new));
                } while (!cursor.equals("0"));
                redis.functionDelete(library);
                redis.del("cachedb:function-library:" + library + ":marker");
            }
        }
        ordinaryEncryptedL1Companion();
    }

    @SuppressWarnings("unchecked")
    private static void assertProtectedL1Unsupported(Loaded fixture, NativeProducerInitialFixture initial,
            JedisPooled redis, CacheDatabaseConfig config, CacheRuntimeOptions options, NoSql source) {
        try (var database = new CacheDatabase(redis, initial, config, options)) {
            database.start();
            var metadata = (EntityMetadata<Object, Long>) (EntityMetadata<?, ?>) fixture.metadata();
            database.register(metadata, fixture.unbound(), CachePolicy.builder().hotEntityLimit(16)
                    .nearCachePolicy(new NearCachePolicy(16, 10, 65536)).build());
            var binding = new Binding(metadata.entityType(), metadata.redisNamespace(),
                    new SourcePlan(metadata.tableName(), metadata.tenantFieldName(), metadata.idColumn()),
                    RedisInitialSourceLayout.layoutToken(metadata, database.redisKeyStrategy(), config.queryIndex()),
                    10000, 128, 1048576, true);
            var guard = new JdbcTenantPurgeGuard(initial, JdbcTenantPurgeDialect.POSTGRES, 2, 1, Set.of(binding.fingerprint()));
            try (var admin = CacheTenantContext.administrative()) {
                assertEquals(ProtectionException.Code.UNWIRED_PROTOCOL, assertThrows(ProtectionException.class,
                        () -> database.prepareInitialNamespace(metadata, binding, guard, JdbcSourceOwnerOnboarding.longIds(),
                                1L, 1L, 1, 65536, 4096, 1048576, 8)).code());
            }
            assertEquals(0, source.calls.get(), "Unsupported INITIAL must reject before SQL");
            assertFalse(redis.exists(RedisTenantRuntimeControl.rosterKey(database.redisKeyStrategy(), metadata.redisNamespace())),
                    "Unsupported INITIAL must not create a roster");
        }
    }

    private void ordinaryEncryptedL1Companion() throws Exception {
        String prefix = "producer_l1_" + UUID.randomUUID().toString().replace("-", "");
        String library = prefix + "_functions";
        var config = CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                .redisFunctions(RedisFunctionsConfig.builder().libraryName(library).upsertFunctionName(library + "_upsert")
                        .deleteFunctionName(library + "_delete").compactionCompleteFunctionName(library + "_complete").build())
                .redisGuardrail(RedisGuardrailConfig.builder().enabled(false).build())
                .writeBehind(WriteBehindConfig.builder().enabled(false).streamKey(prefix + ":stream")
                        .deadLetterStreamKey(prefix + ":dlq").compactionStreamKey(prefix + ":compaction").build()).build();
        var options = CacheRuntimeOptions.builder().keyProvider(keys("k1", key("k1", 1))).build();
        var source = new NoSql();
        try (var fixture = load(compile(temp, recordSources(true)));
                var redis = new JedisPooled(System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1"),
                        Integer.getInteger("cachedb.snapshot.redis.port", 16383))) {
            try (var database = new CacheDatabase(redis, source, config, options)) {
                database.start();
                var metadata = fixture.metadata();
                assertFalse(metadata.tenantPurgeEnabled(), "Ordinary encrypted L1 companion, not protected INITIAL coverage");
                var policy = CachePolicy.builder().hotEntityLimit(16)
                        .nearCachePolicy(new NearCachePolicy(16, 10, 65536)).build();
                database.register(metadata, fixture.unbound(), policy);
                var repository = database.repository(metadata, fixture.unbound(), policy);
                var nativeRepository = assertInstanceOf(RedisEntityRepository.class, repository);
                var local = nativeRepository.tenantNearCache();
                assertNotNull(local, "Ordinary encrypted companion must have a real L1 store");
                long readyUntil = System.nanoTime() + 5_000_000_000L;
                for (int poll = 0; poll < 500 && !database.nearCacheCoordinator().connected()
                        && System.nanoTime() < readyUntil; poll++) Thread.sleep(10);
                assertTrue(database.nearCacheCoordinator().connected(), "Ordinary L1 invalidation must be ready, not bypassed");
                redis.set(database.redisKeyStrategy().versionKey(metadata.redisNamespace(), 1L) + ":tenant", "tenant-a");
                try (var tenant = CacheTenantContext.withTenant("tenant-a")) {
                    assertEquals(fixture.entity(), repository.saveWithReceipt(fixture.entity()).entity());
                    for (int read = 0; read < 32 && local.lookup("1", 0) == null; read++) {
                        assertEquals(fixture.entity(), repository.findById(1L).orElseThrow());
                        Thread.sleep(10);
                    }
                    assertNotNull(local.lookup("1", 0), "Real encrypted L1 population required");
                    assertEquals(fixture.entity(), repository.findById(1L).orElseThrow());
                    assertEquals(fixture.entity(), repository.findById(1L).orElseThrow()); // actual ordinary encrypted L1
                }
                assertEquals(0, source.calls.get());
            } finally {
                String cursor = "0"; int pages = 0;
                do {
                    if (++pages > 1000) throw new AssertionError("Fixture cleanup bound");
                    var page = redis.scan(cursor, new redis.clients.jedis.params.ScanParams().match(prefix + ":*").count(128));
                    cursor = page.getCursor();
                    if (!page.getResult().isEmpty()) redis.del(page.getResult().toArray(String[]::new));
                } while (!cursor.equals("0"));
                redis.functionDelete(library);
                redis.del("cachedb:function-library:" + library + ":marker");
            }
        }
    }

    static final class NoSql implements DataSource {
        final AtomicInteger calls = new AtomicInteger();
        public Connection getConnection() { calls.incrementAndGet(); throw new AssertionError("No foreground SQL"); }
        public Connection getConnection(String username, String password) { return getConnection(); }
        public java.io.PrintWriter getLogWriter() { return null; }
        public void setLogWriter(java.io.PrintWriter writer) {}
        public void setLoginTimeout(int seconds) {}
        public int getLoginTimeout() { return 1; }
        public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getLogger("producer-test"); }
        public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLException("No wrapper"); }
        public boolean isWrapperFor(Class<?> type) { return false; }
    }
}
