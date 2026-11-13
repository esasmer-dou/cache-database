package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;
import com.reactor.cachedb.core.codec.PayloadEntityCodec;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.crypto.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.redis.RedisKeyStrategy;
import com.reactor.cachedb.redis.RedisNonTenantReencryptionRunner;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
@Timeout(60)
class CacheDatabaseReencryptionRedisTest {
    private static final String SECRET = "root-rotation-private-marker";
    record Note(String id, String secret) {}

    @Test void actualRegisteredFramedCodecRuntimeProviderPoolDatabaseAndKeySegmentsAreUsed() throws Exception {
        try (var f = new Fixture(currentKeys()); var admin = CacheTenantContext.administrative()) {
            Note note = new Note("one:with:segments", SECRET);
            String old = f.seed(note);
            f.redis.pexpire(f.entity(note.id()), 60000);
            long ttl = f.redis.pttl(f.entity(note.id()));
            String index = f.oldCodec.toIndexColumns(note).get("secret").toString();
            f.redis.hset(f.keys.indexMetaKey(f.ns, note.id()), Map.of("secret", index));
            f.redis.set(f.keys.durabilityKey(f.ns, note.id()), "21");
            String compact = f.keys.compactionPayloadKey(f.ns, note.id());
            f.redis.hset(compact, Map.of("protected", "~E1:retained-fixture"));
            f.redis.xadd(f.prefix + ":writes", redis.clients.jedis.StreamEntryID.NEW_ENTRY,
                    Map.of("protected", "~E1:retained-fixture"));
            f.redis.xadd(f.prefix + ":dlq", redis.clients.jedis.StreamEntryID.NEW_ENTRY,
                    Map.of("protected", "~E1:retained-fixture"));
            int sourceRequests = f.sourceRequests.get();
            long borrowed = f.redis.getPool().getBorrowedCount();
            assertInstanceOf(PayloadEntityCodec.class, f.database.entityRegistry().find("RootNote").orElseThrow().codec());

            var result = f.finish(f.database.reencryptionRunner(Note.class), null, 1);

            assertEquals(RedisNonTenantReencryptionRunner.Status.COMPLETE, result.status());
            assertEquals(1, result.rewritten());
            String fresh = f.redis.get(f.entity(note.id()));
            assertNotEquals(old, fresh);
            assertTrue(fresh.contains("~E1:k2:"));
            assertFalse(fresh.contains(SECRET));
            assertEquals(note, f.newCodec.fromRedisValue(fresh));
            assertThrows(CacheEncryptionException.class, () -> f.oldCodec.fromRedisValue(fresh));
            assertEquals("21", f.redis.get(f.keys.versionKey(f.ns, note.id())));
            assertEquals("21", f.redis.get(f.keys.durabilityKey(f.ns, note.id())));
            assertTrue(f.redis.pttl(f.entity(note.id())) > 0);
            assertTrue(f.redis.pttl(f.entity(note.id())) <= ttl);
            assertEquals(Map.of("secret", index), f.redis.hgetAll(f.keys.indexMetaKey(f.ns, note.id())));
            assertEquals(Map.of("protected", "~E1:retained-fixture"), f.redis.hgetAll(compact));
            assertEquals(1, f.redis.xlen(f.prefix + ":writes"));
            assertEquals(1, f.redis.xlen(f.prefix + ":dlq"));
            assertTrue(f.redis.getPool().getBorrowedCount() > borrowed);
            assertEquals(0, f.redis.getPool().getNumActive());
            assertEquals(sourceRequests, f.sourceRequests.get());
            f.assertSourceUnchanged();
        }
    }

    @Test void rootEntryIsAdministrativeAndRejectsInvalidScopeAndBudgetsBeforeAnyIo() throws Exception {
        try (var f = new Fixture(currentKeys())) {
            assertCode(CacheReencryptionJob.Code.ADMIN_REQUIRED,
                    () -> f.database.reencryptionRunner(Note.class));
            try (var admin = CacheTenantContext.administrative()) {
                long borrowed = f.redis.getPool().getBorrowedCount();
                int sourceRequests = f.sourceRequests.get();
                for (int rows : new int[] {0, -1, 10001})
                    assertCode(CacheReencryptionJob.Code.INVALID_REQUEST,
                            () -> f.database.reencrypt(Note.class, rows));
                assertCode(CacheReencryptionJob.Code.SOURCE_UNSUPPORTED, () -> f.database.reencrypt(
                        Note.class, 1, RedisNonTenantReencryptionRunner.Scope.SOURCE));
                assertCode(CacheReencryptionJob.Code.UNKNOWN_ENTITY,
                        () -> f.database.reencryptionRunner(String.class));
                assertCode(CacheReencryptionJob.Code.INVALID_REQUEST,
                        () -> f.database.reencryptionRunner(null));
                assertEquals(borrowed, f.redis.getPool().getBorrowedCount());
                assertEquals(sourceRequests, f.sourceRequests.get());
                assertFalse(f.redis.exists(f.cursor()));
            }
        }
    }

    @Test void singletonFactoryIsBoundedAndOldHandlesRejectReplacementRegistrations() throws Exception {
        try (var f = new Fixture(currentKeys()); var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Future<CacheReencryptionJob>>();
            for (int i = 0; i < 12; i++) tasks.add(executor.submit(() -> {
                try (var admin = CacheTenantContext.administrative()) {
                    return f.database.reencryptionRunner(Note.class);
                }
            }));
            var job = tasks.getFirst().get(5, TimeUnit.SECONDS);
            for (var task : tasks) assertSame(job, task.get(5, TimeUnit.SECONDS));
            try (var admin = CacheTenantContext.administrative()) {
                long borrowed = f.redis.getPool().getBorrowedCount();
                f.database.register(f.metadata, f.unbound, CachePolicy.defaults());
                assertCode(CacheReencryptionJob.Code.REGISTRATION_CHANGED, () -> job.reencrypt(1));
                assertCode(CacheReencryptionJob.Code.REGISTRATION_CHANGED,
                        () -> f.database.reencryptionRunner(Note.class));
                assertEquals(borrowed, f.redis.getPool().getBorrowedCount());
            }
        }
    }

    @Test void configuredProviderIsMandatoryEvenIfRegistrationSuppliedAPreboundCodec() throws Exception {
        try (var f = new Fixture(null); var admin = CacheTenantContext.administrative()) {
            long borrowed = f.redis.getPool().getBorrowedCount();
            assertCode(CacheReencryptionJob.Code.UNBOUND_PROVIDER,
                    () -> f.database.reencryptionRunner(Note.class));
            assertEquals(borrowed, f.redis.getPool().getBorrowedCount());
        }
    }

    @Test void providerErrorsAreRedactedAndNeverBecomePlaintextFallback() throws Exception {
        CacheKeyProvider failing = new CacheKeyProvider() {
            public KeyMaterial current(String ring) { throw new IllegalStateException(SECRET); }
            public Optional<KeyMaterial> byId(String ring, String id) { throw new IllegalStateException(SECRET); }
        };
        try (var f = new Fixture(failing); var admin = CacheTenantContext.administrative()) {
            String old = f.seed(new Note("one", SECRET));
            assertCode(CacheReencryptionJob.Code.UNBOUND_PROVIDER,
                    () -> f.database.reencryptionRunner(Note.class));
            assertEquals(old, f.redis.get(f.entity("one")));
            assertFalse(f.redis.exists(f.cursor()));
        }
    }

    @Test void tenantAndSourceCiphertextMappingsAndAliasesFailBeforeBorrowingAConnection() throws Exception {
        try (var f = new Fixture(currentKeys()); var admin = CacheTenantContext.administrative()) {
            long borrowed = f.redis.getPool().getBorrowedCount();
            var source = metadata("SourceNote", "source_notes", EncryptedColumnDescriptor.Scope.REDIS_AND_SOURCE, false);
            f.database.register(source, f.unbound, CachePolicy.defaults());
            assertCode(CacheReencryptionJob.Code.AMBIGUOUS_REGISTRATION,
                    () -> f.database.reencryptionRunner(Note.class));
            assertEquals(borrowed, f.redis.getPool().getBorrowedCount());
        }
        for (boolean tenant : new boolean[] {false, true}) {
            try (var f = new Fixture(currentKeys(), tenant ? EncryptedColumnDescriptor.Scope.REDIS_ONLY
                    : EncryptedColumnDescriptor.Scope.REDIS_AND_SOURCE, tenant);
                    var admin = CacheTenantContext.administrative()) {
                long borrowed = f.redis.getPool().getBorrowedCount();
                assertCode(CacheReencryptionJob.Code.UNSUPPORTED_MAPPING,
                        () -> f.database.reencryptionRunner(Note.class));
                assertEquals(borrowed, f.redis.getPool().getBorrowedCount());
            }
        }
    }

    @Test void durablePassResumesThroughANewRealDatabaseWithoutRestartingItsCursor() throws Exception {
        try (var f = new Fixture(currentKeys()); var admin = CacheTenantContext.administrative()) {
            for (int i = 0; i < 12; i++) f.seed(new Note("row-" + i, SECRET + i));
            var first = f.database.reencrypt(Note.class, 1);
            UUID expectedPass = first.passId();
            var oldHandle = f.database.reencryptionRunner(Note.class);
            // Empty MATCH frames are progress, not evidence that a row was already committed.
            for (int call = 1; call < 256 && first.examinedThisCall() == 0
                    && first.status() == RedisNonTenantReencryptionRunner.Status.MORE; call++) {
                first = oldHandle.resume(expectedPass, 1);
                assertEquals(expectedPass, first.passId());
                assertTrue(first.examinedThisCall() <= 1);
            }
            assertEquals(RedisNonTenantReencryptionRunner.Status.MORE, first.status());
            assertEquals(1, first.examinedThisCall(), "Restart must follow a real committed occurrence");
            var committed = new LinkedHashMap<String, String>();
            for (int i = 0; i < 12; i++) {
                String id = "row-" + i, raw = f.redis.get(f.entity(id));
                if (raw.contains("~E1:k2:")) committed.put(id, raw);
            }
            assertEquals(1, committed.size());
            f.database.close();
            assertCode(CacheReencryptionJob.Code.CLOSED, () -> oldHandle.resume(expectedPass, 1));
            assertEquals("PONG", f.redis.ping()); // The database never owns this supplied client.
            f.openDatabase(currentKeys());
            var result = f.finish(f.database.reencryptionRunner(Note.class), expectedPass, 1);
            assertEquals(expectedPass, result.passId());
            assertEquals(RedisNonTenantReencryptionRunner.Status.COMPLETE, result.status());
            assertTrue(result.rewritten() >= 12);
            for (var accepted : committed.entrySet()) assertEquals(accepted.getValue(), f.redis.get(f.entity(accepted.getKey())));
            for (int i = 0; i < 12; i++) {
                assertEquals(new Note("row-" + i, SECRET + i),
                        f.newCodec.fromRedisValue(f.redis.get(f.entity("row-" + i))));
                assertEquals("21", f.redis.get(f.keys.versionKey(f.ns, "row-" + i)));
            }
        }
    }

    @Test void concurrentBusinessWriteWinsAndOnePhysicalJobKeepsItsAdmission() throws Exception {
        paused(false);
    }

    @Test void databaseCloseWhileCodecRunsRejectsLateCasWithoutReturningItsPhysicalPermitEarly() throws Exception {
        paused(true);
    }

    private void paused(boolean close) throws Exception {
        try (var f = new Fixture(currentKeys())) {
            String old = f.seed(new Note("one", SECRET));
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            f.onEncode.set(() -> {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Fixture interrupted");
                }
            });
            try (var executor = Executors.newSingleThreadExecutor()) {
                Future<RedisNonTenantReencryptionRunner.Step> work = executor.submit(() -> {
                    try (var admin = CacheTenantContext.administrative()) {
                        return f.finish(f.database.reencryptionRunner(Note.class), null, 1);
                    }
                });
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    assertEquals(1, f.redis.getPool().getNumActive());
                    try (var admin = CacheTenantContext.administrative()) {
                        assertCode(CacheReencryptionJob.Code.BUSY, () -> f.database.reencrypt(Note.class, 1));
                        if (close) {
                            f.database.close();
                            assertFalse(work.isDone());
                            assertEquals(1, f.redis.getPool().getNumActive());
                        } else {
                            String changed = f.newCodec.toRedisValue(new Note("one", SECRET + "-new-intent"));
                            f.redis.eval("redis.call('SET',KEYS[1],ARGV[1]);redis.call('SET',KEYS[2],'22');return 1",
                                    List.of(f.entity("one"), f.keys.versionKey(f.ns, "one")), List.of(changed));
                        }
                    }
                    release.countDown();
                    if (close) {
                        var error = assertThrows(ExecutionException.class, () -> work.get(10, TimeUnit.SECONDS));
                        assertInstanceOf(RuntimeException.class, error.getCause());
                        assertEquals(old, f.redis.get(f.entity("one")));
                    } else {
                        var result = work.get(10, TimeUnit.SECONDS);
                        assertEquals(1, result.conflicts());
                        assertEquals("22", f.redis.get(f.keys.versionKey(f.ns, "one")));
                        assertEquals(new Note("one", SECRET + "-new-intent"),
                                f.newCodec.fromRedisValue(f.redis.get(f.entity("one"))));
                    }
                    assertEquals(0, f.redis.getPool().getNumActive());
                    assertEquals("PONG", f.redis.ping());
                } finally { release.countDown(); }
            }
        }
    }

    private static void assertCode(CacheReencryptionJob.Code code, Runnable action) {
        var error = assertThrows(CacheReencryptionJob.Failure.class, action::run);
        assertEquals(code, error.code());
        assertNull(error.getCause());
        assertFalse(error.toString().contains(SECRET));
    }

    private static CacheKeyProvider oldKeys() { return provider("k1", key("k1", 1)); }
    private static CacheKeyProvider currentKeys() { return provider("k2", key("k1", 1), key("k2", 2)); }
    private static CacheKeyProvider provider(String current, KeyMaterial... material) {
        return new StaticKeyProvider(Map.of("notes", List.of(material)), Map.of("notes", current));
    }
    private static KeyMaterial key(String id, int seed) {
        byte[] aes = new byte[32], blind = new byte[32];
        Arrays.fill(aes, (byte) seed); Arrays.fill(blind, (byte) 72);
        return new KeyMaterial(id, aes, blind);
    }

    private static EntityMetadata<Note, String> metadata(String name, String ns,
            EncryptedColumnDescriptor.Scope scope, boolean tenant) {
        return new EntityMetadata<>() {
            public String entityName() { return name; }
            public String tableName() { return "root_notes"; }
            public String redisNamespace() { return ns; }
            public String idColumn() { return "id"; }
            public Class<Note> entityType() { return Note.class; }
            public Function<Note, String> idAccessor() { return Note::id; }
            public List<String> columns() { return List.of("id", "secret"); }
            public List<RelationDefinition> relations() { return List.of(); }
            public String tenantFieldName() { return tenant ? "tenant" : null; }
            public Map<String, EncryptedColumnDescriptor> encryptedColumns() {
                return Map.of("secret", new EncryptedColumnDescriptor("notes", scope, true));
            }
        };
    }

    private static final class Codec implements EntityCodec<Note> {
        final String ns;
        final JcaFieldEncryption crypto;
        final AtomicReference<Runnable> onEncode;
        Codec(String ns, CacheKeyProvider provider, AtomicReference<Runnable> onEncode) {
            this.ns = ns; this.crypto = provider == null ? null : new JcaFieldEncryption(provider); this.onEncode = onEncode;
        }
        public EntityCodec<Note> bindEncryption(CacheKeyProvider provider) { return new Codec(ns, provider, onEncode); }
        public boolean encryptionBound() { return crypto != null; }
        public boolean encryptedMapping() { return true; }
        public String toRedisValue(Note note) {
            onEncode.get().run();
            return LengthPrefixedPayloadCodec.encode(Map.of("id", note.id(),
                    "secret", crypto.encrypt("notes", ns, note.id(), "secret", note.secret())));
        }
        public Note fromRedisValue(String raw) {
            var values = LengthPrefixedPayloadCodec.decode(raw);
            String id = values.get("id");
            return new Note(id, crypto.decrypt("notes", ns, id, "secret", values.get("secret")));
        }
        public Map<String, Object> toColumns(Note note) { return Map.of("id", note.id(), "secret", note.secret()); }
        public Map<String, Object> toIndexColumns(Note note) {
            return Map.of("id", note.id(), "secret", crypto.blindIndex("notes", ns, "secret", note.secret()));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final String prefix = "f3root-" + UUID.randomUUID();
        final String ns = "notes";
        // A non-default database catches accidental creation of a separate default-DB wire.
        final JedisPooled redis = new JedisPooled("redis://"
                + System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1") + ":"
                + Integer.getInteger("cachedb.snapshot.redis.port", 16383) + "/12");
        final AtomicInteger sourceRequests = new AtomicInteger();
        final AtomicReference<Runnable> onEncode = new AtomicReference<>(() -> {});
        final EntityMetadata<Note, String> metadata;
        final EntityCodec<Note> unbound = new Codec(ns, null, onEncode);
        final EntityCodec<Note> oldCodec = new Codec(ns, oldKeys(), new AtomicReference<>(() -> {}));
        final EntityCodec<Note> newCodec = new Codec(ns, currentKeys(), new AtomicReference<>(() -> {}));
        final JdbcDataSource sql = new JdbcDataSource();
        final DataSource observedSql = new DataSource() {
            public Connection getConnection() throws SQLException { sourceRequests.incrementAndGet(); return sql.getConnection(); }
            public Connection getConnection(String u, String p) throws SQLException { sourceRequests.incrementAndGet(); return sql.getConnection(u, p); }
            public PrintWriter getLogWriter() throws SQLException { return sql.getLogWriter(); }
            public void setLogWriter(PrintWriter writer) throws SQLException { sql.setLogWriter(writer); }
            public void setLoginTimeout(int seconds) throws SQLException { sql.setLoginTimeout(seconds); }
            public int getLoginTimeout() throws SQLException { return sql.getLoginTimeout(); }
            public Logger getParentLogger() { return Logger.getLogger("f3-root-test"); }
            public <T> T unwrap(Class<T> type) throws SQLException { return sql.unwrap(type); }
            public boolean isWrapperFor(Class<?> type) throws SQLException { return sql.isWrapperFor(type); }
        };
        final CacheDatabaseConfig config;
        final Connection source;
        RedisKeyStrategy keys;
        CacheDatabase database;

        Fixture(CacheKeyProvider provider) throws Exception {
            this(provider, EncryptedColumnDescriptor.Scope.REDIS_ONLY, false);
        }
        Fixture(CacheKeyProvider provider, EncryptedColumnDescriptor.Scope scope, boolean tenant) throws Exception {
            metadata = metadata("RootNote", ns, scope, tenant);
            sql.setURL("jdbc:h2:mem:" + prefix);
            source = sql.getConnection();
            try (var statement = source.createStatement()) {
                statement.execute("CREATE TABLE root_notes(id VARCHAR(100) PRIMARY KEY,secret VARCHAR(1000))");
            }
            try (var insert = source.prepareStatement("INSERT INTO root_notes VALUES(?,?)")) {
                insert.setString(1, "source-row"); insert.setString(2, SECRET); insert.executeUpdate();
            }
            config = CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                    .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).entitySegment("rows")
                            .versionSegment("revisions").tombstoneSegment("deleted").build())
                    .writeBehind(WriteBehindConfig.builder().streamKey(prefix + ":writes")
                            .compactionStreamKey(prefix + ":compact").deadLetterStreamKey(prefix + ":dlq").build()).build();
            openDatabase(provider);
        }
        void openDatabase(CacheKeyProvider provider) {
            database = new CacheDatabase(redis, observedSql, config, CacheRuntimeOptions.builder().keyProvider(provider).build());
            // Prebound old provider deliberately differs from the actual runtime current provider.
            database.register(metadata, unbound.bindEncryption(oldKeys()), CachePolicy.defaults());
            keys = database.redisKeyStrategy();
        }
        String entity(String id) { return keys.entityKey(ns, id); }
        String cursor() { return prefix + ":" + ns + ":reencryption:resident:n1"; }
        String seed(Note note) {
            String raw = oldCodec.toRedisValue(note);
            redis.set(entity(note.id()), raw); redis.set(keys.versionKey(ns, note.id()), "21"); return raw;
        }
        RedisNonTenantReencryptionRunner.Step finish(CacheReencryptionJob job, UUID pass, int max) {
            UUID expected = pass;
            for (int i = 0; i < 256; i++) {
                var result = expected == null ? job.reencrypt(max) : job.resume(expected, max);
                if (expected == null) expected = result.passId();
                assertEquals(expected, result.passId());
                assertTrue(result.examinedThisCall() <= max);
                if (result.status() != RedisNonTenantReencryptionRunner.Status.MORE) return result;
            }
            throw new AssertionError("Bounded root fixture did not complete");
        }
        void assertSourceUnchanged() throws Exception {
            try (var statement = source.createStatement(); var rows = statement.executeQuery("SELECT secret FROM root_notes")) {
                assertTrue(rows.next()); assertEquals(SECRET, rows.getString(1)); assertFalse(rows.next());
            }
        }
        public void close() throws Exception {
            try { database.close(); }
            finally {
                try {
                    String cursor = "0"; boolean done = false;
                    for (int frame = 0; frame < 256; frame++) {
                        var page = redis.scan(cursor, new ScanParams().match(prefix + ":*").count(512));
                        if (!page.getResult().isEmpty()) redis.del(page.getResult().toArray(String[]::new));
                        cursor = page.getCursor(); if ("0".equals(cursor)) { done = true; break; }
                    }
                    if (!done) throw new AssertionError("Bounded root fixture cleanup did not complete");
                } finally { try { source.close(); } finally { redis.close(); } }
            }
        }
    }
}
