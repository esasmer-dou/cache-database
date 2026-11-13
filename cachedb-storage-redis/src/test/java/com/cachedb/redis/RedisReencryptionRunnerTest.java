package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.crypto.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis", matches="true")
@Timeout(60)
class RedisReencryptionRunnerTest {
    private static final int REDIS_DATABASE = 13;
    static final String SECRET = "reencrypt-private-marker";
    record Row(String id, String tenant, String secret) {}

    @Test void pooledAndNativeWireSelectTheSameDedicatedFixtureDatabase() {
        try (var f = new Fixture(); var session = f.wire.connect(); var connection = f.db.getPool().getResource()) {
            connection.sendCommand(new redis.clients.jedis.CommandArguments(redis.clients.jedis.Protocol.Command.CLIENT).add("INFO"));
            String pooled = new String((byte[]) connection.getOne(), java.nio.charset.StandardCharsets.UTF_8);
            String nativeInfo = (String) session.call(BoundedRedisWire.Limits.entries(), "CLIENT", "INFO").value();
            assertTrue(Arrays.asList(pooled.strip().split("\\s+")).contains("db=" + REDIS_DATABASE));
            assertTrue(Arrays.asList(nativeInfo.strip().split("\\s+")).contains("db=" + REDIS_DATABASE));
            String seeded = f.seed(new Row("database-contract", "a", SECRET), "41", false);
            assertEquals(seeded, session.call(BoundedRedisWire.Limits.entries(), "GET", f.entity("database-contract")).value());
        }
    }

    @Test void oldKeyReadCurrentWritePreservesVersionTtlIndexesAndNeverTouchesQueueOrSql() {
        try (var f = new Fixture(); var admin = CacheTenantContext.administrative()) {
            Row row = new Row("id:with:separators", "a", SECRET);
            String old = f.seed(row, "41", true);
            long ttl = f.db.pttl(f.entity(row.id()));
            f.db.set(f.keys.durabilityKey(f.ns, row.id()), "41");
            f.db.hset(f.keys.indexMetaKey(f.ns, row.id()), Map.of("secret", f.oldCodec.toIndexColumns(row).get("secret").toString()));
            var index = f.db.hgetAll(f.keys.indexMetaKey(f.ns, row.id()));
            f.db.set(f.keys.compactionPendingKey(f.ns, row.id()), "unchanged");
            f.db.hset(f.keys.compactionPayloadKey(f.ns, row.id()), Map.of("body", "unchanged"));
            var result = f.finish(f.runner(), 1);
            assertEquals(RedisReencryptionRunner.Status.COMPLETE, result.status());
            String fresh = f.db.get(f.entity(row.id()));
            assertNotEquals(old, fresh);
            assertTrue(fresh.contains("~E1:k2:"));
            assertFalse(fresh.contains(SECRET));
            assertEquals(row, f.newCodec.fromRedisValue(fresh));
            assertEquals(f.oldCodec.toIndexColumns(row), f.newCodec.toIndexColumns(row));
            assertEquals(index, f.db.hgetAll(f.keys.indexMetaKey(f.ns, row.id())));
            assertEquals("41", f.db.get(f.keys.versionKey(f.ns, row.id())));
            assertEquals("41", f.db.get(f.keys.durabilityKey(f.ns, row.id())));
            assertTrue(f.db.pttl(f.entity(row.id())) > 0);
            assertTrue(f.db.pttl(f.entity(row.id())) <= ttl);
            assertEquals("unchanged", f.db.get(f.keys.compactionPendingKey(f.ns, row.id())));
            assertEquals(Map.of("body", "unchanged"), f.db.hgetAll(f.keys.compactionPayloadKey(f.ns, row.id())));
            assertFalse(f.db.exists(f.keys.namespacePrefix(f.ns) + ":write-stream"));
            // No source/queue API is accepted by the runner. This test asserts the native boundary.
        }
    }

    @Test void maxRowsOneRetainsTheWholePendingScanFrameAcrossNewRunnerInstances() {
        try (var f = new Fixture(); var admin = CacheTenantContext.administrative()) {
            for (int i = 0; i < 12; i++) f.seed(new Row("row-" + i, "a", SECRET + i), "41", false);
            RedisReencryptionRunner.Step last = null;
            for (int call = 0; call < 200; call++) {
                var runner = f.runner(); // Simulate process restart: no cursor in Java fields.
                last = runner.reencrypt(Row.class, "a", 1);
                assertTrue(last.examinedThisCall() <= 1);
                String state = f.db.hgetAll(runner.cursorKey(f.key)).toString();
                assertFalse(state.contains(SECRET));
                if (last.status() != RedisReencryptionRunner.Status.MORE) break;
            }
            assertNotNull(last);
            assertEquals(RedisReencryptionRunner.Status.COMPLETE, last.status());
            assertTrue(last.rewritten() >= 12);
            for (int i = 0; i < 12; i++) assertTrue(f.db.get(f.entity("row-" + i)).contains("~E1:k2:"));
        }
    }

    @Test void crashAfterCommitResumesFromDurableCursorWithoutReplayingAcceptedOccurrence() {
        try (var f = new Fixture(); var admin = CacheTenantContext.administrative()) {
            f.seed(new Row("one", "a", SECRET), "41", false);
            var once = new AtomicBoolean();
            var crashing = f.runner(f.unbound, () -> {
                if (once.compareAndSet(false, true)) throw new IllegalStateException(SECRET);
            });
            var failure = assertThrows(RedisReencryptionRunner.Failure.class,
                    () -> f.finish(crashing, 1));
            assertTrue(once.get(), "Failure must follow the actual committed-occurrence hook");
            assertNull(failure.getCause());
            assertFalse(failure.toString().contains(SECRET));
            String committed = f.db.get(f.entity("one"));
            assertTrue(committed.contains("~E1:k2:"));
            var checkpoint = f.db.hgetAll(crashing.cursorKey(f.key));
            assertEquals("1", checkpoint.get("total"));
            assertEquals("1", checkpoint.get("updated"));
            assertEquals("1", checkpoint.get("gen"));
            assertEquals("41", f.db.get(f.keys.versionKey(f.ns, "one")));
            var complete = f.finish(f.runner(), 1);
            assertEquals(RedisReencryptionRunner.Status.COMPLETE, complete.status());
            assertEquals(1L, complete.rewritten());
            assertEquals(committed, f.db.get(f.entity("one")));
            assertEquals("41", f.db.get(f.keys.versionKey(f.ns, "one")));
            var resumed = f.db.hgetAll(crashing.cursorKey(f.key));
            for (String field : List.of("fmt", "target", "gen", "topo"))
                assertEquals(checkpoint.get(field), resumed.get(field));
        }
    }

    @Test void newBusinessVersionWinsAgainstPausedRotation() throws Exception {
        concurrent(false);
    }

    @Test void sameVersionButDifferentPayloadAlsoWinsAgainstPausedRotation() throws Exception {
        concurrent(true);
    }

    private void concurrent(boolean preserveVersion) throws Exception {
        try (var f = new Fixture()) {
            f.seed(new Row("one", "a", SECRET), "41", false);
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            EntityCodec<Row> pausing = f.pausing(entered, release);
            var runner = f.runner(pausing, () -> {});
            try (var executor = Executors.newSingleThreadExecutor()) {
                Future<RedisReencryptionRunner.Step> work = executor.submit(() -> {
                    try (var admin = CacheTenantContext.administrative()) {
                        return f.finish(runner, 10);
                    }
                });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                Row changed = new Row("one", "a", SECRET + "-business-change");
                String accepted = f.newCodec.toRedisValue(changed);
                f.db.eval("redis.call('SET',KEYS[1],ARGV[1]);redis.call('SET',KEYS[2],ARGV[2]);return 1",
                        List.of(f.entity("one"), f.keys.versionKey(f.ns, "one")),
                        List.of(accepted, preserveVersion ? "41" : "42"));
                release.countDown();
                var result = work.get(10, TimeUnit.SECONDS);
                assertEquals(RedisReencryptionRunner.Status.COMPLETE_WITH_GAPS, result.status());
                assertEquals(1L, result.conflicts());
                assertEquals(accepted, f.db.get(f.entity("one")));
                assertEquals(preserveVersion ? "41" : "42", f.db.get(f.keys.versionKey(f.ns, "one")));
            } finally { release.countDown(); }
        }
    }

    @Test void closeWhileCodecRunsRejectsCasAndReopenCannotRelabelOldCursor() throws Exception {
        try (var f = new Fixture()) {
            String old = f.seed(new Row("one", "a", SECRET), "41", false);
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var runner = f.runner(f.pausing(entered, release), () -> {});
            try (var executor = Executors.newSingleThreadExecutor()) {
                Future<?> work = executor.submit(() -> {
                    try (var admin = CacheTenantContext.administrative()) { f.finish(runner, 10); }
                });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                try (var admin = CacheTenantContext.administrative()) {
                    assertThrows(RuntimeException.class, () -> f.barrier.close(f.lease, 1));
                    var purge = new Lease(f.key, UUID.randomUUID(), UUID.randomUUID(), 2, 120);
                    f.barrier.close(purge, 1);
                    assertFalse(f.fence.closeAndDrain(purge, 1).drained());
                    release.countDown();
                    assertThrows(ExecutionException.class, () -> work.get(10, TimeUnit.SECONDS));
                    assertTrue(f.fence.closeAndDrain(purge, 1).drained());
                    assertEquals(old, f.db.get(f.entity("one")));
                    f.openNext(purge);
                    assertThrows(RedisReencryptionRunner.Failure.class, () -> f.runner().reencrypt(Row.class, "a", 10));
                    assertEquals(old, f.db.get(f.entity("one")));
                    // Fixture advances the expired maintenance lease, not the generation authority.
                    f.db.hset(runner.cursorKey(f.key), "until", "0");
                    var fresh = f.runner();
                    fresh.startNewPass(Row.class, "a");
                    assertEquals(RedisReencryptionRunner.Status.COMPLETE, f.finish(fresh, 10).status());
                }
            } finally { release.countDown(); }
        }
    }

    @Test void ownerChangeTombstoneAndMissingVersionNeverProduceAnOverwrite() {
        try (var f = new Fixture(); var admin = CacheTenantContext.administrative()) {
            String foreign = f.seed(new Row("foreign", "b", SECRET), "41", false);
            String deleted = f.seed(new Row("deleted", "a", SECRET), "41", false);
            f.db.set(f.keys.tombstoneKey(f.ns, "deleted"), "42");
            String orphan = f.seed(new Row("orphan", "a", SECRET), "41", false);
            f.db.del(f.keys.versionKey(f.ns, "orphan"));
            var result = f.finish(f.runner(), 10);
            assertEquals(RedisReencryptionRunner.Status.COMPLETE_WITH_GAPS, result.status());
            assertTrue(result.foreign() >= 1); assertTrue(result.missing() >= 1); assertTrue(result.blocked() >= 1);
            assertEquals(foreign, f.db.get(f.entity("foreign")));
            assertEquals(deleted, f.db.get(f.entity("deleted")));
            assertEquals(orphan, f.db.get(f.entity("orphan")));
        }
    }

    @Test void unknownOldKeyAndPlaintextLegacyAreReportedAsGapsNotFallback() {
        try (var f = new Fixture(); var admin = CacheTenantContext.administrative()) {
            String unknown = f.seed(new Row("unknown", "a", SECRET), "41", false).replace("~E1:k1:", "~E1:zz:");
            f.db.set(f.entity("unknown"), unknown);
            f.seed(new Row("legacy", "a", SECRET), "41", false);
            String plaintext = "legacy|a|" + SECRET;
            f.db.set(f.entity("legacy"), plaintext);
            var result = f.finish(f.runner(), 10);
            assertEquals(RedisReencryptionRunner.Status.COMPLETE_WITH_GAPS, result.status());
            assertTrue(result.blocked() >= 2);
            assertEquals(unknown, f.db.get(f.entity("unknown")));
            assertEquals(plaintext, f.db.get(f.entity("legacy")));
        }
    }

    @Test void trackedQuotaChargeNeverGrowsAndLargerEnvelopeIsExplicitlyIncomplete() {
        try (var f = new Fixture(); var admin = CacheTenantContext.administrative()) {
            Row row = new Row("one", "a", SECRET);
            String old = f.seed(row, "41", false);
            String set = f.keys.tenantHotSetKey(f.ns, "tenant", "a");
            String bytes = f.keys.tenantHotPayloadBytesKey(f.ns, "tenant", "a");
            String owner = set + "\n" + bytes + "\n" + old.length();
            f.db.set(f.keys.tenantHotOwnerKey(f.ns, "one"), owner);
            f.db.set(bytes, Integer.toString(old.length()));
            CacheKeyProvider longer = provider("longerCurrent", key("k1", 1), key("longerCurrent", 2));
            var runner = new RedisReencryptionRunner<>(f.metadata, f.unbound, longer, f.ops, f.keys, f.barrier, f.fence, f.wire);
            assertEquals(RedisReencryptionRunner.Status.COMPLETE_WITH_GAPS, f.finish(runner, 10).status());
            assertEquals(old, f.db.get(f.entity("one")));
            assertEquals(owner, f.db.get(f.keys.tenantHotOwnerKey(f.ns, "one")));
            assertEquals(Integer.toString(old.length()), f.db.get(bytes));
        }
    }

    @Test void sourceModeBudgetsAndTenantMismatchRejectBeforeAnyCursorOrEntityMutation() {
        try (var f = new Fixture(); var admin = CacheTenantContext.administrative()) {
            var runner = f.runner();
            assertEquals(RedisReencryptionRunner.Code.UNSUPPORTED_SOURCE,
                    assertThrows(RedisReencryptionRunner.Failure.class,
                            () -> runner.reencrypt(Row.class, "a", 1, RedisReencryptionRunner.Scope.SOURCE)).code());
            for (int rows : new int[] {0, -1, 10001}) assertThrows(RedisReencryptionRunner.Failure.class,
                    () -> runner.reencrypt(Row.class, "a", rows));
            assertFalse(f.db.exists(runner.cursorKey(f.key)));
            try (var tenant = CacheTenantContext.withTenant("b")) {
                assertThrows(RedisReencryptionRunner.Failure.class, () -> runner.reencrypt(Row.class, "a", 1));
            }
            assertFalse(f.db.exists(runner.cursorKey(f.key)));
        }
    }

    @Test void independentRuntimeLeaseExcludesDuplicateWorkerAndTakeoverAfterExpiryResumes() {
        try (var f = new Fixture(); var admin = CacheTenantContext.administrative()) {
            f.seed(new Row("one", "a", SECRET), "41", false);
            var first = f.runner();
            f.finish(first, 10);
            String key = first.cursorKey(f.key), accepted = f.db.get(f.entity("one"));
            long redisMillis = (Long) f.db.eval("local t=redis.call('TIME'); return tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)");
            f.db.hset(key, Map.of("token", UUID.randomUUID().toString(), "until", Long.toString(redisMillis + 30000)));
            assertEquals(RedisReencryptionRunner.Code.BUSY,
                    assertThrows(RedisReencryptionRunner.Failure.class,
                            () -> f.runner().reencrypt(Row.class, "a", 10)).code());
            assertEquals(accepted, f.db.get(f.entity("one")));
            f.db.hset(key, "until", "0");
            assertEquals(1L, f.finish(f.runner(), 10).rewritten());
        }
    }

    @Test void currentKeyTargetChangeRequiresAnExplicitNewPass() {
        try (var f = new Fixture(); var admin = CacheTenantContext.administrative()) {
            f.seed(new Row("one", "a", SECRET), "41", false);
            f.finish(f.runner(), 10);
            CacheKeyProvider third = provider("k3", key("k1", 1), key("k2", 2), key("k3", 3));
            var next = new RedisReencryptionRunner<>(f.metadata, f.unbound, third, f.ops, f.keys, f.barrier, f.fence, f.wire);
            assertEquals(RedisReencryptionRunner.Code.CURSOR_MISMATCH,
                    assertThrows(RedisReencryptionRunner.Failure.class,
                            () -> next.reencrypt(Row.class, "a", 10)).code());
            next.startNewPass(Row.class, "a");
            assertEquals(RedisReencryptionRunner.Status.COMPLETE, f.finish(next, 10).status());
            assertTrue(f.db.get(f.entity("one")).contains("~E1:k3:"));
        }
    }

    @Test void overBudgetPayloadStaysUnchangedAndIsCountedAsBlocked() {
        try (var f = new Fixture(); var admin = CacheTenantContext.administrative()) {
            f.seed(new Row("one", "a", SECRET), "41", false);
            String tooLarge = "x".repeat(262145);
            f.db.set(f.entity("one"), tooLarge);
            var result = f.finish(f.runner(), 10);
            assertEquals(RedisReencryptionRunner.Status.COMPLETE_WITH_GAPS, result.status());
            assertEquals(1L, result.blocked());
            assertEquals(tooLarge, f.db.get(f.entity("one")));
        }
    }

    @Test void exactClosedProgressCleanupIsIdempotentAndNeverErasesAnotherTenantOrUnknownSchema() {
        try (var f = new Fixture(); var admin = CacheTenantContext.administrative()) {
            f.seed(new Row("one", "a", SECRET), "41", false);
            var runner = f.runner(); f.finish(runner, 10);
            String own = runner.cursorKey(f.key), foreign = runner.cursorKey(f.barrier.key("b"));
            f.db.hset(foreign, Map.of("foreign", "retained"));
            assertThrows(RedisReencryptionRunner.Failure.class, () -> runner.discardCursorAfterClose(f.lease, 1));
            assertTrue(f.db.exists(own));
            assertThrows(RuntimeException.class, () -> f.barrier.close(f.lease, 1));
            var purge = new Lease(f.key, UUID.randomUUID(), UUID.randomUUID(), 2, 120);
            f.barrier.close(purge, 1);
            assertEquals(RedisReencryptionRunner.Cleanup.CLEARED, runner.discardCursorAfterClose(purge, 1));
            assertEquals(RedisReencryptionRunner.Cleanup.CLEARED, runner.discardCursorAfterClose(purge, 1));
            assertFalse(f.db.exists(own));
            assertEquals(Map.of("foreign", "retained"), f.db.hgetAll(foreign));
            f.db.hset(own, Map.of("fmt", "2"));
            assertEquals(RedisReencryptionRunner.Cleanup.INCOMPLETE, runner.discardCursorAfterClose(purge, 1));
            assertEquals(Map.of("fmt", "2"), f.db.hgetAll(own));
        }
    }

    static KeyMaterial key(String id, int seed) {
        byte[] aes = new byte[32], blind = new byte[32];
        Arrays.fill(aes, (byte) seed); Arrays.fill(blind, (byte) 72);
        return new KeyMaterial(id, aes, blind);
    }
    static CacheKeyProvider provider(String id, KeyMaterial... keys) {
        return new StaticKeyProvider(Map.of("default", List.of(keys)), Map.of("default", id));
    }
    static final class RowCodec implements EntityCodec<Row> {
        final String ns;
        final JcaFieldEncryption crypto;
        final Runnable onEncode;
        RowCodec(String ns, CacheKeyProvider provider, Runnable onEncode) {
            this.ns = ns; this.crypto = provider == null ? null : new JcaFieldEncryption(provider); this.onEncode = onEncode;
        }
        public EntityCodec<Row> bindEncryption(CacheKeyProvider provider) { return new RowCodec(ns, provider, onEncode); }
        public boolean encryptionBound() { return crypto != null; }
        public boolean encryptedMapping() { return true; }
        public String toRedisValue(Row r) {
            onEncode.run();
            return r.id() + "|" + r.tenant() + "|" + crypto.encrypt("default",
                    EncryptedCodecSupport.tenantNamespace(ns, r.tenant()), r.id(), "secret", r.secret());
        }
        public Row fromRedisValue(String encoded) {
            String[] parts = encoded.split("\\|", 3);
            return new Row(parts[0], parts[1], crypto.decrypt("default",
                    EncryptedCodecSupport.tenantNamespace(ns, parts[1]), parts[0], "secret", parts[2]));
        }
        public Map<String, Object> toColumns(Row r) { return Map.of("id", r.id(), "tenant", r.tenant(), "secret", r.secret()); }
        public Map<String, Object> toIndexColumns(Row r) {
            return Map.of("id", r.id(), "tenant", r.tenant(), "secret", crypto.blindIndex("default",
                    EncryptedCodecSupport.tenantNamespace(ns, r.tenant()), "secret", r.secret()));
        }
    }
    static final class Fixture implements AutoCloseable {
        final String ns = "reencryption_" + UUID.randomUUID().toString().replace("-", "");
        final RedisKeyStrategy keys = new RedisKeyStrategy("f3reencryption");
        final JedisPooled db = new JedisPooled(RedisTestEndpoint.uri() + "/" + REDIS_DATABASE);
        final RedisClientOperations ops = JedisPooledOperations.of(db);
        final RedisGenerationBarrier barrier = new RedisGenerationBarrier(ops, keys, ns);
        final ProtectionKey key = barrier.key("a");
        final Lease lease = new Lease(key, UUID.randomUUID(), UUID.randomUUID(), 1, 120);
        final RedisTenantVersionAllocator allocator = new RedisTenantVersionAllocator(ops, keys, barrier, ns);
        final RedisTenantReadFence fence = new RedisTenantReadFence(barrier, 8, List.of());
        final BoundedRedisWire wire = new BoundedRedisWire(new InetSocketAddress(
                System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1"),
                Integer.getInteger("cachedb.snapshot.redis.port", 16383)), null, null, null, null, REDIS_DATABASE, 3000, 5000, 1);
        final CacheKeyProvider oldKeys = provider("k1", key("k1", 1));
        final CacheKeyProvider newKeys = provider("k2", key("k1", 1), key("k2", 2));
        final EntityCodec<Row> unbound = new RowCodec(ns, null, () -> {});
        final EntityCodec<Row> oldCodec = unbound.bindEncryption(oldKeys), newCodec = unbound.bindEncryption(newKeys);
        final EntityMetadata<Row, String> metadata = new EntityMetadata<>() {
            public String entityName() { return "RotationRow"; }
            public String tableName() { return "rotation_rows"; }
            public String redisNamespace() { return ns; }
            public String idColumn() { return "id"; }
            public Class<Row> entityType() { return Row.class; }
            public Function<Row, String> idAccessor() { return Row::id; }
            public List<String> columns() { return List.of("id", "tenant", "secret"); }
            public List<RelationDefinition> relations() { return List.of(); }
            public String tenantFieldName() { return "tenant"; }
            public String extractTenantId(Row row) { return row.tenant(); }
            public boolean tenantPurgeEnabled() { return true; }
            public Map<String, EncryptedColumnDescriptor> encryptedColumns() {
                return Map.of("secret", new EncryptedColumnDescriptor("default", EncryptedColumnDescriptor.Scope.REDIS_ONLY, true));
            }
        };
        Fixture() {
            try (var admin = CacheTenantContext.administrative()) {
                barrier.close(lease, 1);
                allocator.install(new TenantVersionRange(key, 1, 1, 1, 100, 200, UUID.randomUUID(),
                        allocator.runId(key), ProtectionKey.hash("reencryption-fixture")));
                barrier.openPreparedGeneration(lease, 1);
            }
        }
        String entity(String id) { return keys.entityKey(ns, id); }
        String seed(Row row, String version, boolean ttl) {
            String body = oldCodec.toRedisValue(row);
            db.set(entity(row.id()), body);
            if (ttl) db.pexpire(entity(row.id()), 60_000);
            db.set(keys.versionKey(ns, row.id()), version);
            db.set(keys.versionKey(ns, row.id()) + ":tenant", row.tenant());
            return body;
        }
        RedisReencryptionRunner<Row, String> runner() { return runner(unbound, () -> {}); }
        RedisReencryptionRunner<Row, String> runner(EntityCodec<Row> codec, Runnable after) {
            return new RedisReencryptionRunner<>(metadata, codec, newKeys, ops, keys, barrier, fence, wire, after);
        }
        EntityCodec<Row> pausing(CountDownLatch entered, CountDownLatch release) {
            AtomicBoolean first = new AtomicBoolean();
            return new RowCodec(ns, null, () -> {
                if (!first.compareAndSet(false, true)) return;
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test barrier timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Test interrupted"); }
            });
        }
        RedisReencryptionRunner.Step finish(RedisReencryptionRunner<Row, String> runner, int max) {
            for (int i = 0; i < 300; i++) {
                var result = runner.reencrypt(Row.class, "a", max);
                assertTrue(result.examinedThisCall() <= max);
                if (result.status() != RedisReencryptionRunner.Status.MORE) return result;
            }
            throw new AssertionError("Bounded fixture did not finish");
        }
        void openNext(Lease purge) {
            barrier.installClosedGeneration(purge, 2);
            allocator.install(new TenantVersionRange(key, 2, purge.epoch(), 2, 201, 300, UUID.randomUUID(),
                    allocator.runId(key), ProtectionKey.hash("reencryption-fixture")));
            barrier.openPreparedGeneration(purge, 2);
        }
        public void close() {
            fence.close(); wire.close();
            try {
                String cursor = "0"; int frames = 0;
                do {
                    if (++frames > 1000) throw new AssertionError("Fixture cleanup bound");
                    var page = db.scan(cursor, new ScanParams().match(keys.namespacePrefix(ns) + ":*").count(128));
                    cursor = page.getCursor();
                    if (!page.getResult().isEmpty()) db.del(page.getResult().toArray(String[]::new));
                } while (!cursor.equals("0"));
            } finally { db.close(); }
        }
    }
}
