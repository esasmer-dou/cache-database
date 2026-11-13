package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.model.RelationDefinition;
import com.reactor.cachedb.core.readmodel.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.util.JedisClusterCRC16;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class RedisCountFacetReadModelRedisTest {
    private static final CountFacetDefinition DEFINITION = new CountFacetDefinition(
            "ticket-status-v1", Duration.ofSeconds(15), Duration.ofMinutes(5), Duration.ofSeconds(30));

    record Row(Long id) {}

    static EntityMetadata<Row, Long> metadata(boolean tenant, boolean protectedSource) {
        return metadata(tenant, protectedSource, "read_model_rows");
    }

    static EntityMetadata<Row, Long> metadata(boolean tenant, boolean protectedSource, String namespace) {
        return new EntityMetadata<>() {
            public String entityName() { return "ReadModelRow"; }
            public String tableName() { return "read_model_rows"; }
            public String redisNamespace() { return namespace; }
            public String idColumn() { return "id"; }
            public Class<Row> entityType() { return Row.class; }
            public Function<Row, Long> idAccessor() { return Row::id; }
            public List<String> columns() { return List.of("id", "tenant"); }
            public List<RelationDefinition> relations() { return List.of(); }
            public String tenantFieldName() { return tenant ? "tenant" : null; }
            public boolean tenantPurgeEnabled() { return protectedSource; }
        };
    }

    static final class Fixture implements AutoCloseable {
        final JedisPooled redis = new JedisPooled(RedisTestEndpoint.uri());
        final String prefix = "readmodel-" + UUID.randomUUID();
        final RedisKeyStrategy keys = new RedisKeyStrategy(prefix);
        final RedisCountFacetReadModel model;
        final boolean tenant;
        Fixture(boolean tenant) {
            this.tenant = tenant;
            model = new RedisCountFacetReadModel(JedisPooledOperations.of(redis), keys,
                    metadata(tenant, false), DEFINITION);
        }
        String base(String tenantId) {
            return keys.countFacetReadModelKey("read_model_rows", DEFINITION.name(),
                    tenantId == null ? "global" : "tenant:" + tenantId);
        }
        public void close() {
            // Only exact fixture-owned keys; never SCAN/FLUSH the shared Redis endpoint.
            for (String tenantId : new String[] {null, "alpha", "beta", "alpha:beta"}) {
                String base = base(tenantId);
                redis.del(base, base + ":lease", base + ":sequence");
            }
            redis.close();
        }
    }

    @Test
    void missingDoesNotRunSourceAndPublishedSnapshotRetainsExactValues() {
        try (var f = new Fixture(false)) {
            assertEquals(CountFacetRead.Status.MISSING, f.model.inspect().status());
            assertThrows(IllegalStateException.class, f.model::requireFresh);
            var values = new CountFacetValues(Long.MAX_VALUE,
                    Map.of("", 1L, "__NULL__", 2L, "\u0130stanbul \uD83D\uDE00", 3L));
            var refreshed = f.model.refresh(() -> values);
            assertEquals(CountFacetRefresh.Status.PUBLISHED, refreshed.status());
            var read = f.model.inspect();
            assertEquals(CountFacetRead.Status.FRESH, read.status());
            assertEquals(values, read.snapshot().orElseThrow().values());
            assertEquals(refreshed.snapshot().orElseThrow(), f.model.requireFresh());
            assertTrue(f.redis.pttl(f.base(null)) > 0);
            assertTrue(f.redis.pttl(f.base(null)) <= DEFINITION.retention().toMillis());
        }
    }

    @Test
    void sourceObservationStartDeterminesFreshnessNotPublicationTime() {
        try (var f = new Fixture(false)) {
            f.model.refresh(() -> new CountFacetValues(2, Map.of("OPEN", 2L)));
            long published = Long.parseLong(f.redis.hget(f.base(null), "published"));
            f.redis.hset(f.base(null), "from", Long.toString(published - 30_000));
            assertEquals(CountFacetRead.Status.STALE, f.model.inspect().status());
            assertThrows(IllegalStateException.class, f.model::requireFresh);
            f.redis.pexpire(f.base(null), 0);
            assertEquals(CountFacetRead.Status.MISSING, f.model.inspect().status());
        }
    }

    @Test
    void sourceFailurePreservesPriorSnapshotAndReleasesOnlyItsLease() {
        try (var f = new Fixture(false)) {
            var prior = f.model.refresh(() -> new CountFacetValues(1, Map.of())).snapshot().orElseThrow();
            var sourceFailure = new IllegalArgumentException("source unavailable");
            assertSame(sourceFailure, assertThrows(IllegalArgumentException.class,
                    () -> f.model.refresh(() -> { throw sourceFailure; })));
            assertEquals(prior, f.model.requireFresh());
            assertFalse(f.redis.exists(f.base(null) + ":lease"));
            assertEquals(CountFacetRefresh.Status.PUBLISHED,
                    f.model.refresh(() -> new CountFacetValues(2, Map.of())).status());
        }
    }

    @Test
    void multipleInstancesShareLeaseAndBusyDoesNotInvokeSource() throws Exception {
        try (var f = new Fixture(false); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var other = new RedisCountFacetReadModel(JedisPooledOperations.of(f.redis), f.keys,
                    metadata(false, false), DEFINITION);
            var entered = new CountDownLatch(1);
            var finish = new CountDownLatch(1);
            try {
                var first = executor.submit(() -> f.model.refresh(() -> {
                    entered.countDown();
                    try {
                        if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test deadline");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    return new CountFacetValues(3, Map.of());
                }));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var calls = new AtomicInteger();
                assertEquals(CountFacetRefresh.Status.BUSY,
                        other.refresh(() -> { calls.incrementAndGet(); return new CountFacetValues(1, Map.of()); }).status());
                assertEquals(0, calls.get());
                finish.countDown();
                assertEquals(CountFacetRefresh.Status.PUBLISHED, first.get(5, TimeUnit.SECONDS).status());
                assertEquals(3, other.requireFresh().values().count());
            } finally {
                finish.countDown();
            }
        }
    }

    @Test
    void expiredOwnerCannotOverwriteNewerSnapshot() {
        try (var f = new Fixture(false)) {
            var old = f.model.refresh(() -> {
                // Deterministic lease-expiry/reacquisition race without waiting for wall time.
                f.redis.del(f.base(null) + ":lease");
                assertEquals(CountFacetRefresh.Status.PUBLISHED,
                        f.model.refresh(() -> new CountFacetValues(9, Map.of())).status());
                return new CountFacetValues(1, Map.of());
            });
            assertEquals(CountFacetRefresh.Status.LEASE_LOST, old.status());
            assertEquals(9, f.model.requireFresh().values().count());
        }
    }

    @Test
    void oldOwnerFailureDoesNotRemoveReplacementLease() {
        try (var f = new Fixture(false)) {
            assertThrows(IllegalStateException.class, () -> f.model.refresh(() -> {
                f.redis.set(f.base(null) + ":lease", "new-owner");
                throw new IllegalStateException("old source failed");
            }));
            assertEquals("new-owner", f.redis.get(f.base(null) + ":lease"));
        }
    }

    @Test
    void sequenceRetainsPrecisionBeyondLuaDoubleIntegerRange() {
        try (var f = new Fixture(false)) {
            f.redis.set(f.base(null) + ":sequence", "9007199254740992");
            assertEquals(9_007_199_254_740_993L,
                    f.model.refresh(() -> new CountFacetValues(0, Map.of())).snapshot().orElseThrow().generation());
            f.redis.del(f.base(null));
            assertEquals(9_007_199_254_740_994L,
                    f.model.refresh(() -> new CountFacetValues(0, Map.of())).snapshot().orElseThrow().generation());
        }
    }

    @Test
    void tenantScopeIsMandatoryEvenForAdministratorAndNeverMixesTenants() {
        try (var f = new Fixture(true)) {
            assertThrows(IllegalStateException.class, f.model::inspect);
            try (var ignored = CacheTenantContext.administrative()) {
                assertThrows(IllegalStateException.class, f.model::inspect);
            }
            try (var ignored = CacheTenantContext.withTenant("alpha")) {
                f.model.refresh(() -> new CountFacetValues(7, Map.of("OPEN", 7L)));
            }
            try (var ignored = CacheTenantContext.withTenant("beta")) {
                assertEquals(CountFacetRead.Status.MISSING, f.model.inspect().status());
                f.model.refresh(() -> new CountFacetValues(2, Map.of()));
            }
            try (var ignored = CacheTenantContext.withTenant("alpha")) {
                assertEquals(7, f.model.requireFresh().values().count());
            }
        }
    }

    @Test
    void globalScopeRejectsAmbientTenantAndMalformedTenantCannotCollide() {
        try (var global = new Fixture(false); var tenant = new Fixture(true)) {
            try (var ignored = CacheTenantContext.withTenant("alpha")) {
                assertThrows(IllegalStateException.class, global.model::inspect);
            }
            try (var ignored = CacheTenantContext.withTenant("bad\uD800")) {
                assertThrows(IllegalArgumentException.class, tenant.model::inspect);
            }
        }
    }

    @Test
    void changedContextCannotPublishSourceResultUnderOriginalTenant() {
        try (var f = new Fixture(true); var original = CacheTenantContext.withTenant("alpha")) {
            CacheTenantContext.Scope[] leaked = new CacheTenantContext.Scope[1];
            try {
                assertThrows(IllegalStateException.class, () -> f.model.refresh(() -> {
                    leaked[0] = CacheTenantContext.withTenant("beta");
                    return new CountFacetValues(1, Map.of());
                }));
            } finally {
                if (leaked[0] != null) leaked[0].close();
            }
            assertEquals(CountFacetRead.Status.MISSING, f.model.inspect().status());
            assertFalse(f.redis.exists(f.base("alpha") + ":lease"));
        }
    }

    @Test
    void malformedOrOversizedSnapshotFailsRatherThanFetchingUnboundedPayload() {
        try (var f = new Fixture(false)) {
            f.model.refresh(() -> new CountFacetValues(1, Map.of()));
            f.redis.hset(f.base(null), "values", "x".repeat(RedisCountFacetReadModel.MAX_VALUES_BYTES + 1));
            assertThrows(JedisDataException.class, f.model::inspect);
            f.redis.hset(f.base(null), "values", "5:count1:1");
            f.redis.hset(f.base(null), "extra", "unexpected");
            assertThrows(JedisDataException.class, f.model::inspect);
        }
    }

    @Test
    void malformedSequenceFailsBeforeChangingSnapshot() {
        try (var f = new Fixture(false)) {
            var original = f.model.refresh(() -> new CountFacetValues(1, Map.of())).snapshot().orElseThrow();
            f.redis.set(f.base(null) + ":sequence", "0");
            assertThrows(JedisDataException.class, () -> f.model.refresh(() -> new CountFacetValues(2, Map.of())));
            // Restore the independently corrupted allocator before inspecting the intact snapshot.
            f.redis.set(f.base(null) + ":sequence", Long.toString(original.generation()));
            assertEquals(original, f.model.requireFresh());
        }
    }

    @Test
    void missingOrExpiringAllocatorFailsClosedInsteadOfResettingGeneration() {
        try (var f = new Fixture(false)) {
            f.model.refresh(() -> new CountFacetValues(1, Map.of()));
            f.redis.del(f.base(null) + ":sequence");
            assertThrows(JedisDataException.class, f.model::inspect);
            assertThrows(JedisDataException.class, () -> f.model.refresh(() -> new CountFacetValues(2, Map.of())));
            f.redis.set(f.base(null) + ":sequence", "1");
            f.redis.pexpire(f.base(null) + ":sequence", 60_000);
            assertThrows(JedisDataException.class, f.model::inspect);
            assertThrows(JedisDataException.class, () -> f.model.refresh(() -> new CountFacetValues(2, Map.of())));
        }
    }

    @Test
    void deniedPublicationDoesNotModifyPreviousSnapshotOrSequence() {
        try (var f = new Fixture(false)) {
            var previous = f.model.refresh(() -> new CountFacetValues(1, Map.of())).snapshot().orElseThrow();
            String user = "readmodel-" + UUID.randomUUID();
            String password = UUID.randomUUID().toString();
            var endpoint = java.net.URI.create(RedisTestEndpoint.uri());
            try (var admin = new Jedis(endpoint)) {
                admin.aclSetUser(user, "on", ">" + password, "~" + f.prefix + ":*", "+@all", "-hset");
                try (var restricted = new JedisPooled(new HostAndPort(endpoint.getHost(), endpoint.getPort()),
                        DefaultJedisClientConfig.builder().user(user).password(password).build())) {
                    var model = new RedisCountFacetReadModel(JedisPooledOperations.of(restricted), f.keys,
                            metadata(false, false), DEFINITION);
                    assertThrows(JedisDataException.class, () -> model.refresh(() -> new CountFacetValues(2, Map.of())));
                    assertEquals(previous, model.requireFresh());
                    assertEquals(Long.toString(previous.generation()), f.redis.get(f.base(null) + ":sequence"));
                } finally {
                    admin.aclDelUser(user);
                }
            }
        }
    }

    @Test
    void namespaceAndAllModelKeysShareClusterSlot() {
        var keys = new ClusterHashTagKeyStrategy("app");
        String base = keys.countFacetReadModelKey("orders", DEFINITION.name(), "tenant:alpha:beta");
        assertEquals(JedisClusterCRC16.getSlot(keys.entityKey("orders", 1)), JedisClusterCRC16.getSlot(base));
        assertEquals(JedisClusterCRC16.getSlot(base), JedisClusterCRC16.getSlot(base + ":lease"));
        assertEquals(JedisClusterCRC16.getSlot(base), JedisClusterCRC16.getSlot(base + ":sequence"));
        assertNotEquals(base, keys.countFacetReadModelKey("orders", DEFINITION.name(), "global"));
        assertNotEquals(base, keys.countFacetReadModelKey("orders", DEFINITION.name(), "tenant:alpha"));
    }

    @Test
    void purgeProtectedSourcesAreRejectedBeforeAnyRedisCall() {
        try (var f = new Fixture(true)) {
            assertThrows(UnsupportedOperationException.class, () -> new RedisCountFacetReadModel(
                    JedisPooledOperations.of(f.redis), f.keys, metadata(true, true), DEFINITION));
        }
    }

    @Test
    void invalidNamespaceIsRejectedBeforeAnyRedisCall() {
        try (var f = new Fixture(false)) {
            long borrowed = f.redis.getPool().getBorrowedCount();
            for (String namespace : new String[] {"", " ", "x".repeat(129), "a\uD800", "a\uDC00"}) {
                assertThrows(IllegalArgumentException.class, () -> new RedisCountFacetReadModel(
                        JedisPooledOperations.of(f.redis), f.keys, metadata(false, false, namespace), DEFINITION));
            }
            assertEquals(borrowed, f.redis.getPool().getBorrowedCount());
        }
    }
}
