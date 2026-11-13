package com.reactor.cachedb.processor;

import static com.reactor.cachedb.processor.EncryptionCompilerFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.codec.PayloadEntityCodec;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.redis.*;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis", matches="true")
@Timeout(90)
class GeneratedReencryptionRedisTest {
    @TempDir Path temp;

    @Test void generatedRecordBothScopesReencryptUsingBoundCodecWithoutSqlOrQueuedColumnConversion() throws Exception {
        verify(0);
    }

    @Test void generatedCompressedPayloadRetainsActualFramingAndBothSqlScopeContracts() throws Exception {
        verify(128);
    }

    private void verify(int threshold) throws Exception {
        String namespace = "f3_generated_rotation_" + UUID.randomUUID().toString().replace("-", "");
        var sources = new HashMap<>(recordSources(true));
        sources.replaceAll((name, code) -> code.replace("@CacheEntity(table=", "@CacheEntity(tenantPurgeEnabled=true, table=")
                .replace("redisNamespace=\"protected_rows\"", "redisNamespace=\"" + namespace + "\""));
        String host = System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1");
        int port = Integer.getInteger("cachedb.snapshot.redis.port", 16383);
        String keyPrefix = "f3generated_" + namespace;
        var keys = new RedisKeyStrategy(keyPrefix);
        try (var fixture = load(compile(temp, sources)); var db = new JedisPooled(host, port);
                var wire = new BoundedRedisWire(new InetSocketAddress(host, port), null, null, null, null, 0, 3000, 5000, 1);
                var admin = CacheTenantContext.administrative();
                var source = DriverManager.getConnection("jdbc:h2:mem:" + namespace)) {
            var ops = JedisPooledOperations.of(db);
            var barrier = new RedisGenerationBarrier(ops, keys, namespace);
            var pair = barrier.key("tenant-a");
            var lease = new Lease(pair, UUID.randomUUID(), UUID.randomUUID(), 1, 120);
            var allocator = new RedisTenantVersionAllocator(ops, keys, barrier, namespace);
            barrier.close(lease, 1);
            allocator.install(new TenantVersionRange(pair, 1, 1, 1, 100, 200, UUID.randomUUID(),
                    allocator.runId(pair), ProtectionKey.hash("generated-rotation-fixture")));
            barrier.openPreparedGeneration(lease, 1);
            var oldProvider = keys("k1", key("k1", 1));
            var newProvider = keys("k2", key("k1", 1), key("k2", 2));
            var oldCodec = PayloadEntityCodec.wrap(fixture.unbound().bindEncryption(oldProvider), fixture.metadata(), threshold);
            var newCodec = PayloadEntityCodec.wrap(fixture.unbound().bindEncryption(newProvider), fixture.metadata(), threshold);
            var logical = oldCodec.toColumns(fixture.entity());
            if (threshold > 0) logical.put("hidden", null);
            Object row = oldCodec.fromLogicalColumns(logical);
            var sourceBefore = oldCodec.toSourceColumns(row);
            var queuedBefore = oldCodec.toQueuedColumns(row);
            try (var schema = source.createStatement()) {
                schema.execute("CREATE TABLE protected_rows(id BIGINT PRIMARY KEY, contact VARCHAR(1000), amount VARCHAR(1000))");
            }
            try (var insert = source.prepareStatement("INSERT INTO protected_rows(id,contact,amount) VALUES(?,?,?)")) {
                insert.setLong(1, 1L);
                insert.setString(2, sourceBefore.get("contact").toString());
                insert.setString(3, sourceBefore.get("amount").toString());
                assertEquals(1, insert.executeUpdate());
            }
            String before = oldCodec.toRedisValue(row);
            String entityKey = keys.entityKey(namespace, 1L), versionKey = keys.versionKey(namespace, 1L);
            try (var fence = new RedisTenantReadFence(barrier, 4, List.of())) {
                db.set(entityKey, before); db.set(versionKey, "41"); db.set(versionKey + ":tenant", "tenant-a");
                String queueSentinel = keys.compactionStatsKey(namespace) + ":fixture-queue";
                db.hset(queueSentinel, Map.of("contact", queuedBefore.get("contact").toString(),
                        "amount", queuedBefore.get("amount").toString()));
                var runner = new RedisReencryptionRunner<>(fixture.metadata(), oldCodec, newProvider, ops, keys, barrier, fence, wire);
                RedisReencryptionRunner.Step result = null;
                for (int call = 0; call < 100; call++) {
                    result = runner.reencrypt(fixture.metadata().entityType(), "tenant-a", 1);
                    if (result.status() != RedisReencryptionRunner.Status.MORE) break;
                }
                assertNotNull(result);
                assertEquals(RedisReencryptionRunner.Status.COMPLETE, result.status());
                String after = db.get(entityKey);
                absent(after);
                assertNotEquals(before, after);
                assertEquals(row, newCodec.fromRedisValue(after));
                assertEquals(oldCodec.toIndexColumns(row), newCodec.toIndexColumns(row));
                assertThrows(RuntimeException.class, () -> oldCodec.fromRedisValue(after));
                assertEquals("41", db.get(versionKey));
                try (var query = source.createStatement();
                        var rows = query.executeQuery("SELECT contact,amount FROM protected_rows WHERE id=1")) {
                    assertTrue(rows.next());
                    assertEquals(sourceBefore.get("contact"), rows.getString(1));
                    assertEquals(sourceBefore.get("amount"), rows.getString(2));
                    assertFalse(rows.next());
                }
                assertEquals(queuedBefore.get("contact"), db.hget(queueSentinel, "contact"));
                assertEquals(queuedBefore.get("amount"), db.hget(queueSentinel, "amount"));
                // REDIS_ONLY remains logical SQL; REDIS_AND_SOURCE old SQL ciphertext is NOT migrated by this job.
                assertEquals(SECRET, sourceBefore.get("contact"));
                assertTrue(sourceBefore.get("amount").toString().startsWith("~E1:k1:"));
            } finally {
                String cursor = "0"; int pages = 0;
                do {
                    if (++pages > 1000) throw new AssertionError("Fixture cleanup bound");
                    var page = db.scan(cursor, new ScanParams().match(keyPrefix + ":*").count(128));
                    cursor = page.getCursor();
                    if (!page.getResult().isEmpty()) db.del(page.getResult().toArray(String[]::new));
                } while (!cursor.equals("0"));
            }
        }
    }
    private static void absent(Object value) {
        assertFalse(String.valueOf(value).contains(SECRET));
        assertFalse(String.valueOf(value).contains(HIDDEN));
    }
}
