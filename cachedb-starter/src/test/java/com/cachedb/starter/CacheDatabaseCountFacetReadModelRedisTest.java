package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.CacheRuntimeRole;
import com.reactor.cachedb.core.config.KeyspaceConfig;
import com.reactor.cachedb.core.config.RedisFunctionsConfig;
import com.reactor.cachedb.core.config.RedisGuardrailConfig;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.model.RelationDefinition;
import com.reactor.cachedb.core.readmodel.CountFacetDefinition;
import com.reactor.cachedb.core.readmodel.CountFacetRead;
import com.reactor.cachedb.core.readmodel.CountFacetReadModel;
import com.reactor.cachedb.core.readmodel.CountFacetRefresh;
import com.reactor.cachedb.core.readmodel.CountFacetValues;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.redis.RedisClientOperations;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.CommandObject;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.executors.CommandExecutor;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class CacheDatabaseCountFacetReadModelRedisTest {
    private static final String ENTITY = "StarterReadModelRow";
    private static final String NAMESPACE = "starter_read_model_rows";
    private static final CountFacetDefinition DEFINITION = new CountFacetDefinition(
            "status-v1", Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(30));

    private record Row(Long id, String tenant) {}
    private record OtherRow(Long id) {}

    private static final EntityCodec<Row> CODEC = new EntityCodec<>() {
        public String toRedisValue(Row entity) {
            throw new AssertionError("Read models must not serialize source rows");
        }
        public Row fromRedisValue(String encoded) {
            throw new AssertionError("Read models must not hydrate source rows");
        }
        public Map<String, Object> toColumns(Row entity) {
            throw new AssertionError("Read models must not load source columns");
        }
    };

    private static EntityMetadata<Row, Long> metadata(String name, String namespace,
            String tenantField, boolean protectedSource) {
        return metadata(name, namespace, tenantField, protectedSource, Row.class, Row::id);
    }

    private static <T> EntityMetadata<T, Long> metadata(String name, String namespace,
            String tenantField, boolean protectedSource, Class<T> type, Function<T, Long> id) {
        return new EntityMetadata<>() {
            public String entityName() { return name; }
            public String tableName() { return "starter_read_model_rows"; }
            public String redisNamespace() { return namespace; }
            public String idColumn() { return "id"; }
            public Class<T> entityType() { return type; }
            public Function<T, Long> idAccessor() { return id; }
            public List<String> columns() { return List.of("id", "tenant", "other_tenant"); }
            public List<RelationDefinition> relations() { return List.of(); }
            public String tenantFieldName() { return tenantField; }
            public boolean tenantPurgeEnabled() { return protectedSource; }
        };
    }

    private static final class Fixture implements AutoCloseable {
        final String prefix = "starter-readmodel-" + UUID.randomUUID();
        final JedisPooled redis = new JedisPooled("redis://"
                + System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1") + ":"
                + Integer.getInteger("cachedb.snapshot.redis.port", 16383));
        final AtomicInteger commands = new AtomicInteger();
        final Set<String> ownedKeys = new LinkedHashSet<>();
        final CacheDatabase database;

        Fixture() {
            var counted = new UnifiedJedis(new CommandExecutor() {
                @Override public <T> T executeCommand(CommandObject<T> command) {
                    commands.incrementAndGet();
                    return redis.executeCommand(command);
                }
                @Override public void close() {}
            });
            var operations = new RedisClientOperations() {
                @Override public UnifiedJedis commands() { return counted; }
                @Override public void close() {}
            };
            var jdbc = new JdbcDataSource();
            jdbc.setURL("jdbc:h2:mem:" + prefix);
            try {
                database = new CacheDatabase(operations, jdbc, CacheDatabaseConfig.builder()
                        .runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                        .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                        .redisGuardrail(RedisGuardrailConfig.builder().enabled(false).build())
                        .redisFunctions(RedisFunctionsConfig.builder().enabled(false).build())
                        .writeBehind(WriteBehindConfig.builder().enabled(false)
                                .streamKey(prefix + ":writes")
                                .compactionStreamKey(prefix + ":compaction")
                                .deadLetterStreamKey(prefix + ":deadletters").build())
                        .writeBehindFlusherFactory((source, registry, writeBehind, performance) -> {
                            throw new AssertionError("CLIENT_ONLY must not construct a SQL flusher");
                        }).build());
            } catch (RuntimeException | Error failure) {
                try { redis.close(); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        EntityMetadata<Row, Long> register(String tenantField, boolean protectedSource) {
            var source = metadata(ENTITY, NAMESPACE, tenantField, protectedSource);
            database.register(source, CODEC, CachePolicy.defaults());
            return source;
        }

        String own(String tenant) {
            String base = database.redisKeyStrategy().countFacetReadModelKey(NAMESPACE,
                    DEFINITION.name(), tenant == null ? "global" : "tenant:" + tenant);
            ownedKeys.add(base);
            ownedKeys.add(base + ":lease");
            ownedKeys.add(base + ":sequence");
            return base;
        }

        <T extends Throwable> T rejectedBeforeRedis(Class<T> type, Runnable action) {
            int before = commands.get();
            T failure = assertThrows(type, action::run);
            assertEquals(before, commands.get(), "Invalid source/scope must fail before Redis I/O");
            return failure;
        }

        @Override public void close() {
            try { database.close(); }
            finally {
                try {
                    // Delete only exact keys tracked before each fixture-owned publication attempt.
                    if (!ownedKeys.isEmpty()) redis.del(ownedKeys.toArray(String[]::new));
                } finally { redis.close(); }
            }
        }
    }

    @Test
    void matchingRegisteredMetadataCreatesAnEntryWithoutStartOrRedisIo() {
        try (var f = new Fixture()) {
            var registered = f.register(null, false);
            var matching = metadata(ENTITY, NAMESPACE, null, false);
            assertNotSame(registered, matching);
            int before = f.commands.get();
            var model = f.database.countFacetReadModel(matching, DEFINITION);
            assertSame(DEFINITION, model.definition());
            assertEquals(before, f.commands.get());
        }
    }

    @Test
    void unknownSourceFailsBeforeRedisIo() {
        try (var f = new Fixture()) {
            f.register(null, false);
            var unknown = metadata("UnknownReadModelRow", NAMESPACE, null, false);
            f.rejectedBeforeRedis(IllegalStateException.class,
                    () -> f.database.countFacetReadModel(unknown, DEFINITION));
        }
    }

    @Test
    void mismatchedNamespaceFailsBeforeRedisIo() {
        try (var f = new Fixture()) {
            f.register(null, false);
            var mismatch = metadata(ENTITY, "other_namespace", null, false);
            f.rejectedBeforeRedis(IllegalArgumentException.class,
                    () -> f.database.countFacetReadModel(mismatch, DEFINITION));
        }
    }

    @Test
    void mismatchedEntityTypeFailsBeforeRedisIo() {
        try (var f = new Fixture()) {
            f.register(null, false);
            var mismatch = metadata(ENTITY, NAMESPACE, null, false, OtherRow.class, OtherRow::id);
            f.rejectedBeforeRedis(IllegalArgumentException.class,
                    () -> f.database.countFacetReadModel(mismatch, DEFINITION));
        }
    }

    @Test
    void mismatchedTenantColumnFailsBeforeRedisIo() {
        try (var f = new Fixture()) {
            f.register("tenant", false);
            var mismatch = metadata(ENTITY, NAMESPACE, "other_tenant", false);
            f.rejectedBeforeRedis(IllegalArgumentException.class,
                    () -> f.database.countFacetReadModel(mismatch, DEFINITION));
        }
    }

    @Test
    void tenantScopeCannotBeAddedOrRemovedByEntryMetadata() {
        for (String registeredTenant : new String[] {null, "tenant"}) {
            try (var f = new Fixture()) {
                f.register(registeredTenant, false);
                var mismatch = metadata(ENTITY, NAMESPACE,
                        registeredTenant == null ? "tenant" : null, false);
                f.rejectedBeforeRedis(IllegalArgumentException.class,
                        () -> f.database.countFacetReadModel(mismatch, DEFINITION));
            }
        }
    }

    @Test
    void protectionFlagCannotBeAddedOrHiddenByEntryMetadata() {
        for (boolean registeredProtection : new boolean[] {false, true}) {
            try (var f = new Fixture()) {
                f.register("tenant", registeredProtection);
                var mismatch = metadata(ENTITY, NAMESPACE, "tenant", !registeredProtection);
                f.rejectedBeforeRedis(IllegalArgumentException.class,
                        () -> f.database.countFacetReadModel(mismatch, DEFINITION));
            }
        }
    }

    @Test
    void registeredProtectedSourceIsRejectedBeforeRedisIo() {
        try (var f = new Fixture()) {
            var source = f.register("tenant", true);
            f.rejectedBeforeRedis(UnsupportedOperationException.class,
                    () -> f.database.countFacetReadModel(source, DEFINITION));
        }
    }

    @Test
    void nullSourceAndDefinitionFailBeforeRedisIo() {
        try (var f = new Fixture()) {
            var source = f.register(null, false);
            f.rejectedBeforeRedis(NullPointerException.class,
                    () -> f.database.countFacetReadModel(null, DEFINITION));
            f.rejectedBeforeRedis(NullPointerException.class,
                    () -> f.database.countFacetReadModel(source, null));
        }
    }

    @Test
    void publicEntryRefreshesAndReadsAnExactSnapshotWithoutStartingDatabase() {
        try (var f = new Fixture()) {
            var source = f.register(null, false);
            CountFacetReadModel model = f.database.countFacetReadModel(source, DEFINITION);
            String base = f.own(null);
            assertEquals(CountFacetRead.Status.MISSING, model.inspect().status());
            assertThrows(IllegalStateException.class, model::requireFresh);
            var groups = new LinkedHashMap<String, Long>();
            groups.put("OPEN", 3L);
            groups.put("", 1L);
            groups.put("__NULL__", 1L);
            groups.put("\u0130stanbul \uD83D\uDE00", 2L);
            var values = new CountFacetValues(7, groups);
            var calls = new AtomicInteger();
            int before = f.commands.get();
            var refreshed = model.refresh(() -> { calls.incrementAndGet(); return values; });
            assertTrue(f.commands.get() > before, "Refresh must use the counted real Redis client");
            assertEquals(1, calls.get());
            assertEquals(CountFacetRefresh.Status.PUBLISHED, refreshed.status());
            var published = refreshed.snapshot().orElseThrow();
            assertEquals(1L, published.generation());
            assertFalse(published.observedFrom().isAfter(published.observedThrough()));
            assertEquals(published.observedThrough(), published.publishedAt());
            assertEquals(CountFacetRead.Status.FRESH, model.inspect().status());
            assertEquals(published, model.requireFresh());
            assertEquals(values, model.requireFresh().values());
            long ttl = f.redis.pttl(base);
            assertTrue(ttl > 0 && ttl <= DEFINITION.retention().toMillis());
            assertEquals(-1L, f.redis.pttl(base + ":sequence"));
            assertFalse(f.redis.exists(base + ":lease"));
            var next = model.refresh(() -> new CountFacetValues(0, Map.of()));
            assertEquals(CountFacetRefresh.Status.PUBLISHED, next.status());
            assertEquals(2L, next.snapshot().orElseThrow().generation());
            assertEquals(new CountFacetValues(0, Map.of()), model.requireFresh().values());
        }
    }

    @Test
    void tenantEntryRequiresContextAndIsolatesWellFormedUnicodeTenants() {
        try (var f = new Fixture()) {
            var source = f.register("tenant", false);
            var model = f.database.countFacetReadModel(source, DEFINITION);
            var calls = new AtomicInteger();
            f.rejectedBeforeRedis(IllegalStateException.class, model::inspect);
            f.rejectedBeforeRedis(IllegalStateException.class,
                    () -> model.refresh(() -> { calls.incrementAndGet(); return new CountFacetValues(0, Map.of()); }));
            try (var ignored = CacheTenantContext.administrative()) {
                f.rejectedBeforeRedis(IllegalStateException.class, model::inspect);
            }
            String firstTenant = "\u0130stanbul:\uD83D\uDE00";
            String firstKey = f.own(firstTenant);
            String secondKey = f.own("beta");
            assertNotEquals(firstKey, secondKey);
            try (var ignored = CacheTenantContext.withTenant(firstTenant)) {
                var result = model.refresh(() -> new CountFacetValues(3, Map.of("OPEN", 3L)));
                assertEquals(CountFacetRefresh.Status.PUBLISHED, result.status());
                assertEquals(3L, model.requireFresh().values().count());
            }
            try (var ignored = CacheTenantContext.withTenant("beta")) {
                assertEquals(CountFacetRead.Status.MISSING, model.inspect().status());
                var result = model.refresh(() -> new CountFacetValues(1, Map.of("CLOSED", 1L)));
                assertEquals(CountFacetRefresh.Status.PUBLISHED, result.status());
                assertEquals(Map.of("CLOSED", 1L), model.requireFresh().values().groups());
            }
            try (var ignored = CacheTenantContext.withTenant(firstTenant)) {
                assertEquals(Map.of("OPEN", 3L), model.requireFresh().values().groups());
            }
            assertEquals(0, calls.get(), "Rejected scopes must never invoke the supplier");
        }
    }

    @Test
    void globalEntryRejectsAmbientTenantBeforeRedisOrSupplierInvocation() {
        try (var f = new Fixture()) {
            var source = f.register(null, false);
            var model = f.database.countFacetReadModel(source, DEFINITION);
            var calls = new AtomicInteger();
            try (var ignored = CacheTenantContext.withTenant("alpha")) {
                f.rejectedBeforeRedis(IllegalStateException.class, model::inspect);
                f.rejectedBeforeRedis(IllegalStateException.class,
                        () -> model.refresh(() -> { calls.incrementAndGet(); return new CountFacetValues(0, Map.of()); }));
            }
            assertEquals(0, calls.get());
        }
    }

    @Test
    void supplierFailureThroughEntryPreservesIdentityPriorSnapshotAndLeaseOwnership() {
        try (var f = new Fixture()) {
            var source = f.register(null, false);
            var model = f.database.countFacetReadModel(source, DEFINITION);
            String base = f.own(null);
            var published = model.refresh(() -> new CountFacetValues(1, Map.of("OPEN", 1L)))
                    .snapshot().orElseThrow();
            var failure = new IllegalStateException("source observation failed");
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> model.refresh(() -> { throw failure; })));
            assertEquals(published, model.requireFresh());
            assertFalse(f.redis.exists(base + ":lease"));
            assertEquals(CountFacetRefresh.Status.PUBLISHED,
                    model.refresh(() -> new CountFacetValues(2, Map.of("OPEN", 2L))).status());
        }
    }
}
