package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;
import com.reactor.cachedb.core.crypto.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis", matches="true")
@Timeout(60)
class RedisNonTenantReencryptionRunnerTest {
    private static final int REDIS_DATABASE = 15;
    static final String SECRET = "GLOBAL_PRIVATE_NOTE_MARKER";
    record Row(String id, String bucket, String secret) {}

    @Test void pooledAndNativeWireSelectTheSameDedicatedFixtureDatabase() {
        try (var f = new Fixture(); var session = f.wire.connect(); var connection = f.db.getPool().getResource()) {
            connection.sendCommand(new redis.clients.jedis.CommandArguments(redis.clients.jedis.Protocol.Command.CLIENT).add("INFO"));
            String pooled = new String((byte[]) connection.getOne(), java.nio.charset.StandardCharsets.UTF_8);
            String nativeInfo = (String) session.call(BoundedRedisWire.Limits.entries(), "CLIENT", "INFO").value();
            assertTrue(Arrays.asList(pooled.strip().split("\\s+")).contains("db=" + REDIS_DATABASE));
            assertTrue(Arrays.asList(nativeInfo.strip().split("\\s+")).contains("db=" + REDIS_DATABASE));
            String seeded = f.seed(new Row("database-contract", "all", SECRET), "21");
            assertEquals(seeded, session.call(BoundedRedisWire.Limits.entries(), "GET", f.entity("database-contract")).value());
        }
    }

    @Test void globalEntityRotatesOldReadCurrentWriteWithoutTenantFenceOrGeneration() {
        try (var f = new Fixture(); var admin = CacheTenantContext.administrative()) {
            Row row = new Row("id:with:colon", "all", SECRET);
            String old = f.seed(row, "21");
            f.db.pexpire(f.entity(row.id()), 60000);
            long ttl = f.db.pttl(f.entity(row.id()));
            f.db.set(f.keys.durabilityKey(f.ns, row.id()), "21");
            f.db.hset(f.keys.indexMetaKey(f.ns, row.id()), Map.of("secret", f.oldCodec.toIndexColumns(row).get("secret").toString()));
            var index = f.db.hgetAll(f.keys.indexMetaKey(f.ns, row.id()));
            f.db.hset(f.keys.compactionPayloadKey(f.ns, row.id()), Map.of("body", old));
            assertEquals(row, f.newCodec.fromRedisValue(old));
            var result = f.finish(f.runner(), 10);
            assertEquals(RedisNonTenantReencryptionRunner.Status.COMPLETE, result.status());
            String after = f.db.get(f.entity(row.id()));
            assertTrue(after.contains("~E1:k2:")); assertFalse(after.contains(SECRET));
            assertNotEquals(old, after); assertEquals(row, f.newCodec.fromRedisValue(after));
            assertThrows(RuntimeException.class, () -> f.oldCodec.fromRedisValue(after));
            assertEquals("21", f.db.get(f.keys.versionKey(f.ns, row.id())));
            assertEquals("21", f.db.get(f.keys.durabilityKey(f.ns, row.id())));
            assertTrue(f.db.pttl(f.entity(row.id())) > 0); assertTrue(f.db.pttl(f.entity(row.id())) <= ttl);
            assertEquals(index, f.db.hgetAll(f.keys.indexMetaKey(f.ns, row.id())));
            assertEquals(old, f.db.hget(f.keys.compactionPayloadKey(f.ns, row.id()), "body"));
            f.assertNoKeys(f.keys.namespacePrefix(f.ns) + ":generation:*");
            f.assertNoKeys(f.keys.namespacePrefix(f.ns) + ":security:version:*");
        }
    }

    @Test void newRunnerResumePreservesWholeFramePassReceiptAndMaxRows() {
        try (var f = new Fixture(); var admin = CacheTenantContext.administrative()) {
            for (int i = 0; i < 9; i++) f.seed(new Row("row-" + i, "all", SECRET + i), "21");
            var first = f.runner().reencrypt(Row.class, 1);
            var receipt = first.passId();
            var result = first;
            for (int call = 0; call < 200 && result.status() == RedisNonTenantReencryptionRunner.Status.MORE; call++) {
                result = f.runner().resume(Row.class, receipt, 1);
                assertEquals(receipt, result.passId()); assertTrue(result.examinedThisCall() <= 1);
            }
            assertEquals(RedisNonTenantReencryptionRunner.Status.COMPLETE, result.status());
            assertTrue(result.rewritten() >= 9);
            assertFalse(f.db.hgetAll(f.runner().cursorKey()).toString().contains(SECRET));
            for (int i = 0; i < 9; i++) assertTrue(f.db.get(f.entity("row-" + i)).contains("~E1:k2:"));
        }
    }

    @Test void missingExpiredOrReplacedPassIsNeverSilentlyResumed() {
        try (var f = new Fixture(); var admin = CacheTenantContext.administrative()) {
            f.seed(new Row("one", "all", SECRET), "21");
            var runner = f.runner();
            var prior = f.finish(runner, 1);
            f.db.del(runner.cursorKey()); // Deterministic stand-in for seven-day inactivity expiry.
            var expired = assertThrows(RedisNonTenantReencryptionRunner.Failure.class,
                    () -> f.runner().resume(Row.class, prior.passId(), 1));
            assertEquals(RedisNonTenantReencryptionRunner.Code.CURSOR_EXPIRED, expired.code());
            assertFalse(f.db.exists(runner.cursorKey()));
            var next = f.runner().reencrypt(Row.class, 1);
            assertNotEquals(prior.passId(), next.passId());
            assertEquals(RedisNonTenantReencryptionRunner.Code.CURSOR_MISMATCH,
                    assertThrows(RedisNonTenantReencryptionRunner.Failure.class,
                            () -> f.runner().resume(Row.class, prior.passId(), 1)).code());
        }
    }

    @Test void crashAfterCommittedOccurrenceKeepsCursorAndCiphertextStableOnResume() {
        try (var f = new Fixture(); var admin = CacheTenantContext.administrative()) {
            f.seed(new Row("one", "all", SECRET), "21");
            AtomicBoolean crash = new AtomicBoolean();
            var runner = f.runner(f.unbound, f.newKeys, RedisNonTenantReencryptionRunner.QuotaPolicy.withoutRouteQuotas(), () -> {
                if (crash.compareAndSet(false, true)) throw new IllegalStateException(SECRET);
            });
            var failure = assertThrows(RedisNonTenantReencryptionRunner.Failure.class, () -> f.finish(runner, 1));
            assertTrue(crash.get(), "Failure must follow the actual committed-occurrence hook");
            assertNull(failure.getCause()); assertFalse(failure.toString().contains(SECRET));
            String committed = f.db.get(f.entity("one"));
            assertTrue(committed.contains("~E1:k2:"));
            var checkpoint = f.db.hgetAll(runner.cursorKey());
            UUID pass = UUID.fromString(checkpoint.get("pass"));
            assertEquals("1", checkpoint.get("total"));
            assertEquals("1", checkpoint.get("updated"));
            assertEquals("21", f.db.get(f.keys.versionKey(f.ns, "one")));
            var result = f.finish(f.runner(), pass, 1);
            assertEquals(pass, result.passId());
            assertEquals(RedisNonTenantReencryptionRunner.Status.COMPLETE, result.status());
            assertEquals(1, result.rewritten()); assertEquals(committed, f.db.get(f.entity("one")));
            assertEquals("21", f.db.get(f.keys.versionKey(f.ns, "one")));
            var resumed = f.db.hgetAll(runner.cursorKey());
            for (String field : List.of("fmt", "pass", "target", "topo"))
                assertEquals(checkpoint.get(field), resumed.get(field));
        }
    }

    @Test void newVersionBusinessWriteWins() throws Exception { race(false, false); }
    @Test void sameVersionDifferentPayloadWins() throws Exception { race(true, false); }
    @Test void concurrentTombstonePreventsResurrection() throws Exception { race(false, true); }

    private void race(boolean sameVersion, boolean delete) throws Exception {
        try (var f = new Fixture()) {
            f.seed(new Row("one", "all", SECRET), "21");
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var once = new AtomicBoolean();
            var codec = new RowCodec(f.ns, null, () -> {
                if (!once.compareAndSet(false, true)) return;
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Test interrupted"); }
            });
            var runner = f.runner(codec, f.newKeys, RedisNonTenantReencryptionRunner.QuotaPolicy.withoutRouteQuotas(), () -> {});
            try (var executor = Executors.newSingleThreadExecutor()) {
                var work = executor.submit(() -> {
                    try (var admin = CacheTenantContext.administrative()) { return f.finish(runner, 10); }
                });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                String accepted = f.newCodec.toRedisValue(new Row("one", "all", SECRET + "-changed"));
                if (delete) f.db.eval("redis.call('DEL',KEYS[1]);redis.call('SET',KEYS[2],'22');redis.call('SET',KEYS[3],'22');return 1",
                        List.of(f.entity("one"), f.keys.versionKey(f.ns, "one"), f.keys.tombstoneKey(f.ns, "one")), List.of());
                else f.db.eval("redis.call('SET',KEYS[1],ARGV[1]);redis.call('SET',KEYS[2],ARGV[2]);return 1",
                        List.of(f.entity("one"), f.keys.versionKey(f.ns, "one")), List.of(accepted, sameVersion ? "21" : "22"));
                release.countDown();
                var result = work.get(10, TimeUnit.SECONDS);
                assertEquals(RedisNonTenantReencryptionRunner.Status.COMPLETE_WITH_GAPS, result.status());
                assertEquals(1, result.conflicts());
                if (delete) assertNull(f.db.get(f.entity("one"))); else assertEquals(accepted, f.db.get(f.entity("one")));
            } finally { release.countDown(); }
        }
    }

    @Test void conservativeQuotaModeDefersGrowthAndUnknownOrUnexpectedOwnerIsBlocked() {
        try (var f = new Fixture(); var admin = CacheTenantContext.administrative()) {
            Row row = new Row("one", "all", SECRET);
            String old = f.seed(row, "21");
            var longer = provider("longer-current", key("k1", 1), key("longer-current", 2));
            var runner = f.runner(f.unbound, longer, RedisNonTenantReencryptionRunner.QuotaPolicy.nonGrowing(Set.of("bucket")), () -> {});
            String set = f.keys.tenantHotSetKey(f.ns, "bucket", "all"), bytes = f.keys.tenantHotPayloadBytesKey(f.ns, "bucket", "all");
            String hot = set + "\n" + bytes + "\n" + old.length();
            f.db.set(f.keys.tenantHotOwnerKey(f.ns, "one"), hot); f.db.set(bytes, Integer.toString(old.length()));
            var result = f.finish(runner, 10);
            assertEquals(1, result.growthDeferred()); assertEquals(old, f.db.get(f.entity("one")));
            assertEquals(hot, f.db.get(f.keys.tenantHotOwnerKey(f.ns, "one")));
            assertEquals(Integer.toString(old.length()), f.db.get(bytes));
            runner.startNewPass(Row.class);
            f.db.set(f.keys.tenantHotOwnerKey(f.ns, "one"), "unknown-schema");
            assertEquals(1, f.finish(runner, 10).blocked());
            runner.startNewPass(Row.class);
            var unaccounted = f.runner();
            assertEquals(1, f.finish(unaccounted, 10).blocked());
        }
    }

    @Test void approvedQuotaFreeModeAllowsBoundedGrowthWithoutForgingAReservation() {
        try (var f = new Fixture(); var admin = CacheTenantContext.administrative()) {
            String old = f.seed(new Row("one", "all", SECRET), "21");
            var longer = provider("longer-current", key("k1", 1), key("longer-current", 2));
            var runner = f.runner(f.unbound, longer, RedisNonTenantReencryptionRunner.QuotaPolicy.withoutRouteQuotas(), () -> {});
            assertEquals(RedisNonTenantReencryptionRunner.Status.COMPLETE, f.finish(runner, 10).status());
            assertTrue(f.db.get(f.entity("one")).length() > old.length());
            assertFalse(f.db.exists(f.keys.tenantHotOwnerKey(f.ns, "one")));
        }
    }

    @Test void unknownKeyLegacyWrongIdentityUnexpectedTenantAndOversizeNeverOverwrite() {
        try (var f = new Fixture(); var admin = CacheTenantContext.administrative()) {
            String unknown = f.seed(new Row("unknown", "all", SECRET), "21").replace("~E1:k1:", "~E1:zz:");
            f.db.set(f.entity("unknown"), unknown);
            f.seed(new Row("legacy", "all", SECRET), "21"); f.db.set(f.entity("legacy"), SECRET);
            f.seed(new Row("wrong", "all", SECRET), "21");
            String wrong = f.oldCodec.toRedisValue(new Row("other", "all", SECRET)); f.db.set(f.entity("wrong"), wrong);
            String marked = f.seed(new Row("marked", "all", SECRET), "21");
            f.db.set(f.keys.versionKey(f.ns, "marked") + ":tenant", "unexpected");
            f.seed(new Row("big", "all", SECRET), "21"); f.db.set(f.entity("big"), "x".repeat(262145));
            var result = f.finish(f.runner(), 10);
            assertEquals(RedisNonTenantReencryptionRunner.Status.COMPLETE_WITH_GAPS, result.status());
            assertTrue(result.blocked() >= 5);
            assertEquals(unknown, f.db.get(f.entity("unknown")));
            assertEquals(SECRET, f.db.get(f.entity("legacy")));
            assertEquals(wrong, f.db.get(f.entity("wrong")));
            assertEquals(marked, f.db.get(f.entity("marked")));
        }
    }

    @Test void nullEncryptedValueSurvivesRotation() {
        try (var f = new Fixture(); var admin = CacheTenantContext.administrative()) {
            Row row = new Row("null", "all", null); f.seed(row, "21");
            assertEquals(RedisNonTenantReencryptionRunner.Status.COMPLETE, f.finish(f.runner(), 10).status());
            assertEquals(row, f.newCodec.fromRedisValue(f.db.get(f.entity("null"))));
        }
    }

    @Test void sourceAndBadBudgetsRejectBeforeNativeIoOrProgressCreation() {
        try (var f = new Fixture(); var admin = CacheTenantContext.administrative()) {
            var runner = f.runner(); f.wire.close();
            for (int rows : new int[] {0, -1, 10001})
                assertEquals(RedisNonTenantReencryptionRunner.Code.INVALID_REQUEST,
                        assertThrows(RedisNonTenantReencryptionRunner.Failure.class, () -> runner.reencrypt(Row.class, rows)).code());
            assertEquals(RedisNonTenantReencryptionRunner.Code.SOURCE_REQUIRES_FENCED_MAINTENANCE,
                    assertThrows(RedisNonTenantReencryptionRunner.Failure.class,
                            () -> runner.reencrypt(Row.class, 10, RedisNonTenantReencryptionRunner.Scope.SOURCE)).code());
            assertFalse(f.db.exists(runner.cursorKey()));
        }
    }

    @Test void leaseExclusionAndCurrentKeyChangeDoNotSilentlyReuseAnotherPass() {
        try (var f = new Fixture(); var admin = CacheTenantContext.administrative()) {
            f.seed(new Row("one", "all", SECRET), "21");
            var prior = f.finish(f.runner(), 10);
            long now = (Long) f.db.eval("local t=redis.call('TIME'); return tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)");
            f.db.hset(f.runner().cursorKey(), Map.of("token", UUID.randomUUID().toString(), "until", Long.toString(now + 30000)));
            assertEquals(RedisNonTenantReencryptionRunner.Code.BUSY,
                    assertThrows(RedisNonTenantReencryptionRunner.Failure.class, () -> f.runner().resume(Row.class, prior.passId(), 10)).code());
            f.db.hset(f.runner().cursorKey(), "until", "0");
            var third = provider("k3", key("k1", 1), key("k2", 2), key("k3", 3));
            var next = f.runner(f.unbound, third, RedisNonTenantReencryptionRunner.QuotaPolicy.withoutRouteQuotas(), () -> {});
            assertEquals(RedisNonTenantReencryptionRunner.Code.CURSOR_MISMATCH,
                    assertThrows(RedisNonTenantReencryptionRunner.Failure.class, () -> next.reencrypt(Row.class, 10)).code());
            next.startNewPass(Row.class); var fresh = f.finish(next, 10);
            assertNotEquals(prior.passId(), fresh.passId()); assertTrue(f.db.get(f.entity("one")).contains("~E1:k3:"));
        }
    }

    static KeyMaterial key(String id, int seed) {
        byte[] aes = new byte[32], blind = new byte[32]; Arrays.fill(aes, (byte) seed); Arrays.fill(blind, (byte) 72);
        return new KeyMaterial(id, aes, blind);
    }
    static CacheKeyProvider provider(String current, KeyMaterial... materials) {
        return new StaticKeyProvider(Map.of("default", List.of(materials)), Map.of("default", current));
    }
    static final class RowCodec implements EntityCodec<Row> {
        final String ns; final JcaFieldEncryption crypto; final Runnable beforeEncode;
        RowCodec(String ns, CacheKeyProvider provider, Runnable beforeEncode) {
            this.ns = ns; this.crypto = provider == null ? null : new JcaFieldEncryption(provider); this.beforeEncode = beforeEncode;
        }
        public EntityCodec<Row> bindEncryption(CacheKeyProvider provider) { return new RowCodec(ns, provider, beforeEncode); }
        public boolean encryptionBound() { return crypto != null; }
        public boolean encryptedMapping() { return true; }
        public String toRedisValue(Row row) {
            beforeEncode.run();
            var map = new LinkedHashMap<String, String>(); map.put("id", row.id()); map.put("bucket", row.bucket());
            map.put("secret", crypto.encrypt("default", ns, row.id(), "secret", row.secret()));
            return LengthPrefixedPayloadCodec.encode(map);
        }
        public Row fromRedisValue(String raw) {
            var map = LengthPrefixedPayloadCodec.decode(raw); String id = map.get("id");
            return new Row(id, map.get("bucket"), crypto.decrypt("default", ns, id, "secret", map.get("secret")));
        }
        public Map<String, Object> toColumns(Row row) {
            var map = new LinkedHashMap<String, Object>(); map.put("id", row.id()); map.put("bucket", row.bucket()); map.put("secret", row.secret());
            return map;
        }
        public Map<String, Object> toIndexColumns(Row row) {
            var map = new LinkedHashMap<>(toColumns(row));
            map.put("secret", crypto.blindIndex("default", ns, "secret", row.secret())); return map;
        }
    }
    static final class Fixture implements AutoCloseable {
        final String ns = "global_rotation_" + UUID.randomUUID().toString().replace("-", "");
        final RedisKeyStrategy keys = new RedisKeyStrategy("f3global");
        final JedisPooled db = new JedisPooled(RedisTestEndpoint.uri() + "/" + REDIS_DATABASE);
        final RedisClientOperations ops = JedisPooledOperations.of(db);
        final BoundedRedisWire wire = new BoundedRedisWire(new InetSocketAddress(
                System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1"),
                Integer.getInteger("cachedb.snapshot.redis.port", 16383)), null, null, null, null, REDIS_DATABASE, 3000, 5000, 1);
        final CacheKeyProvider oldKeys = provider("k1", key("k1", 1)), newKeys = provider("k2", key("k1", 1), key("k2", 2));
        final EntityCodec<Row> unbound = new RowCodec(ns, null, () -> {});
        final EntityCodec<Row> oldCodec = unbound.bindEncryption(oldKeys), newCodec = unbound.bindEncryption(newKeys);
        final EntityMetadata<Row, String> metadata = new EntityMetadata<>() {
            public String entityName() { return "GlobalRow"; }
            public String tableName() { return "global_rows"; }
            public String redisNamespace() { return ns; }
            public String idColumn() { return "id"; }
            public Class<Row> entityType() { return Row.class; }
            public Function<Row, String> idAccessor() { return Row::id; }
            public List<String> columns() { return List.of("id", "bucket", "secret"); }
            public List<RelationDefinition> relations() { return List.of(); }
            public Map<String, EncryptedColumnDescriptor> encryptedColumns() {
                return Map.of("secret", new EncryptedColumnDescriptor("default", EncryptedColumnDescriptor.Scope.REDIS_ONLY, true));
            }
        };
        String entity(String id) { return keys.entityKey(ns, id); }
        String seed(Row row, String version) {
            String raw = oldCodec.toRedisValue(row); db.set(entity(row.id()), raw); db.set(keys.versionKey(ns, row.id()), version); return raw;
        }
        RedisNonTenantReencryptionRunner<Row, String> runner() {
            return runner(unbound, newKeys, RedisNonTenantReencryptionRunner.QuotaPolicy.withoutRouteQuotas(), () -> {});
        }
        RedisNonTenantReencryptionRunner<Row, String> runner(EntityCodec<Row> codec, CacheKeyProvider provider,
                RedisNonTenantReencryptionRunner.QuotaPolicy quota, Runnable hook) {
            return new RedisNonTenantReencryptionRunner<>(metadata, codec, provider, ops, keys, wire, quota, hook);
        }
        RedisNonTenantReencryptionRunner.Step finish(RedisNonTenantReencryptionRunner<Row, String> runner, int max) {
            return finish(runner, null, max);
        }
        RedisNonTenantReencryptionRunner.Step finish(RedisNonTenantReencryptionRunner<Row, String> runner, UUID pass, int max) {
            UUID expected = pass;
            for (int i = 0; i < 300; i++) {
                var result = expected == null ? runner.reencrypt(Row.class, max) : runner.resume(Row.class, expected, max);
                if (expected == null) expected = result.passId();
                assertEquals(expected, result.passId());
                assertTrue(result.examinedThisCall() <= max);
                if (result.status() != RedisNonTenantReencryptionRunner.Status.MORE) return result;
            }
            throw new AssertionError("Bounded test did not finish");
        }
        void assertNoKeys(String pattern) {
            String cursor = "0";
            for (int frame = 0; frame < 1000; frame++) {
                var page = db.scan(cursor, new ScanParams().match(pattern).count(128));
                assertTrue(page.getResult().isEmpty(), "Unexpected protection keys in non-tenant fixture");
                cursor = page.getCursor();
                if ("0".equals(cursor)) return;
            }
            throw new AssertionError("Bounded absence scan did not complete");
        }
        public void close() {
            wire.close();
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
