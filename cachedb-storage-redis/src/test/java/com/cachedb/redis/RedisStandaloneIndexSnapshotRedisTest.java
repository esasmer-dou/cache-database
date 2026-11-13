package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;
import redis.clients.jedis.Protocol.Command;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class RedisStandaloneIndexSnapshotRedisTest {
    @Test void publicAdapterCountUsesBoundedUtf8OwnerComparisonWithoutLua() {
        try (Fixture f = new Fixture(2, "\u015firket")) {
            f.other.set(f.owners.get(1), f.tenant + "x".repeat(2 * 1024 * 1024));
            assertEquals(1, f.count().count());
            assertEquals(0, f.control.evaluations);
            assertEquals(1, f.control.phases.size());
            Phase phase = f.control.phases.get(0);
            assertEquals(List.of("MULTI", "EXISTS", "EXISTS", "GETRANGE", "GETRANGE", "EXEC"), phase.names);
            assertEquals(1, f.control.created);
            assertEquals(0, f.client.getPool().getNumActive());
            assertEquals(1, f.client.getPool().getNumIdle());
        }
    }

    @Test void exactDecimalStringsAndCountFieldMatchUnchangedLuaAdapter() {
        try (Fixture f = new Fixture(2, "a")) {
            f.other.hset(f.metadata.get(0), "amount", "12345678901234567890.000000001");
            f.other.hset(f.metadata.get(1), "amount", "__NULL__");
            var expectedNumeric = f.lua().aggregateIndexBatch(f.entities, f.tombs, f.metadata,
                    f.owners, f.tenant, "amount", false).orElseThrow();
            var expectedCount = f.lua().aggregateIndexBatch(f.entities, f.tombs, f.metadata,
                    f.owners, f.tenant, "amount", true).orElseThrow();
            f.reset();
            var numeric = f.numeric();
            assertEquals(expectedNumeric, numeric);
            assertEquals(new BigDecimal("12345678901234567890.000000001"), new BigDecimal(numeric.values().get(0)));
            assertEquals(expectedCount, f.ops.aggregateIndexBatch(f.entities, f.tombs, f.metadata,
                    f.owners, f.tenant, "amount", true).orElseThrow());
            assertEquals(0, f.control.evaluations);
            assertEquals(1, f.control.phases.stream().map(p -> p.connection).distinct().count());
            assertTrue(f.control.phases.stream().flatMap(p -> p.names.stream())
                    .allMatch(Set.of("WATCH", "EXISTS", "GETRANGE", "HSTRLEN", "MULTI", "HGET", "EXEC")::contains));
        }
    }

    @Test void facetPreservesPositionsLiteralSentinelEmptyGroupAndNullValueField() {
        try (Fixture f = new Fixture(2, "a")) {
            f.other.hset(f.metadata.get(0), Map.of("group", "__NULL__", "amount", "__NULL__"));
            f.other.hset(f.metadata.get(1), Map.of("group", "", "amount", "0.00000000001"));
            var expected = f.lua().facetIndexBatch(f.entities, f.tombs, f.metadata,
                    f.owners, f.tenant, "group", "amount").orElseThrow();
            f.reset();
            assertEquals(expected, f.ops.facetIndexBatch(f.entities, f.tombs, f.metadata,
                    f.owners, f.tenant, "group", "amount").orElseThrow());
            assertEquals(0, f.control.evaluations);
            assertEquals(List.of(new RedisClientOperations.FacetIndexRow(0, "__NULL__", null),
                    new RedisClientOperations.FacetIndexRow(1, "", null)),
                    f.ops.facetIndexBatch(f.entities, f.tombs, f.metadata, f.owners,
                            f.tenant, "group", null).orElseThrow().rows());
        }
    }

    @Test void mixedCountProofFallsBackOnceAndIgnoresBadOwnerOnDeadRow() {
        try (Fixture f = new Fixture(2, "a")) {
            f.other.del(f.entities.get(1), f.owners.get(1));
            f.other.hset(f.owners.get(1), "not", "a-string");
            assertEquals(1, f.count().count());
            assertEquals(1, f.control.evaluations);
            assertEquals(0, f.client.getPool().getNumActive());
            assertEquals("PONG", f.client.ping());
        }
    }

    @Test void mixedFacetProofUnwatchesThenUsesSingleLuaFallback() {
        try (Fixture f = new Fixture(2, "a")) {
            f.other.set(f.tombs.get(1), "deleted");
            var result = f.ops.facetIndexBatch(f.entities, f.tombs, f.metadata, f.owners,
                    f.tenant, "group", "amount").orElseThrow();
            assertEquals(List.of(new RedisClientOperations.FacetIndexRow(0, "g0", "1")), result.rows());
            assertEquals(1, f.control.evaluations);
            assertTrue(f.control.phases.stream().anyMatch(p -> p.names.equals(List.of("UNWATCH"))));
            assertFalse(f.control.phases.stream().anyMatch(p -> p.names.contains("HSTRLEN")));
            assertEquals("PONG", f.client.ping());
        }
    }

    @Test void ownerMutationBetweenPreflightAndExecAbortsAndFallsBackOnce() {
        try (Fixture f = new Fixture(2, "a")) {
            f.control.afterLengths = () -> f.other.set(f.owners.get(0), "b");
            assertEquals(List.of("2"), f.numeric().values());
            assertEquals(1, f.control.aborts);
            assertEquals(1, f.control.evaluations);
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void metadataGrowthCannotExecuteQueuedHgetBeforeLuaBudgetRejection() {
        try (Fixture f = new Fixture(2, "a")) {
            f.control.afterLengths = () -> f.other.hset(f.metadata.get(0), "amount",
                    "9".repeat(16 * 1024 * 1024 + 1));
            assertThrows(JedisDataException.class, f::numeric);
            assertEquals(1, f.control.aborts);
            assertEquals(1, f.control.evaluations);
            assertEquals("PONG", f.client.ping());
        }
    }

    @Test void payloadDeletionAndTombstoneInsertionInvalidateWatch() {
        for (boolean tombstone : List.of(false, true)) {
            try (Fixture f = new Fixture(2, "a")) {
                f.control.afterLengths = () -> {
                    if (tombstone) f.other.set(f.tombs.get(0), "deleted");
                    else f.other.del(f.entities.get(0));
                };
                assertEquals(List.of("2"), f.numeric().values());
                assertEquals(1, f.control.aborts);
                assertEquals(1, f.control.evaluations);
                assertEquals("PONG", f.client.ping());
            }
        }
    }

    @Test void facetGroupChangeBetweenPreflightAndExecUsesCurrentLuaValues() {
        try (Fixture f = new Fixture(2, "a")) {
            f.control.afterLengths = () -> f.other.hset(f.metadata.get(0), "group", "changed");
            var result = f.ops.facetIndexBatch(f.entities, f.tombs, f.metadata, f.owners,
                    f.tenant, "group", "amount").orElseThrow();
            assertEquals(List.of(new RedisClientOperations.FacetIndexRow(0, "changed", "1"),
                    new RedisClientOperations.FacetIndexRow(1, "g1", "2")), result.rows());
            assertEquals(1, f.control.aborts);
            assertEquals(1, f.control.evaluations);
        }
    }

    @Test void missingMetadataAfterPreflightAbortsAndFailsWithRebuildError() {
        try (Fixture f = new Fixture(2, "a")) {
            f.control.afterLengths = () -> f.other.hdel(f.metadata.get(0), "amount");
            assertThrows(JedisDataException.class, f::numeric);
            assertEquals(1, f.control.aborts);
            assertEquals(1, f.control.evaluations);
        }
    }

    @Test void allForeignProofCannotPublishFalseZeroWhenOwnerChanges() {
        try (Fixture f = new Fixture(2, "a")) {
            for (String owner : f.owners) f.other.set(owner, "b");
            f.control.afterProof = () -> f.other.set(f.owners.get(0), "a");
            assertEquals(List.of("1"), f.numeric().values());
            assertEquals(1, f.control.aborts);
            assertEquals(1, f.control.evaluations);
            assertTrue(f.control.phases.stream().anyMatch(p -> p.names.equals(
                    List.of("EXISTS", "EXISTS", "GETRANGE", "GETRANGE"))));
            assertFalse(f.control.phases.stream().anyMatch(p -> p.names.contains("HGET")));
        }
    }

    @Test void metadataLengthAclFlipBeforeQueueCannotPublishNumericOrFacetValues() {
        for (boolean facet : List.of(false, true)) for (boolean beforeLengths : List.of(false, true)) {
            try (Fixture f = new Fixture(2, "a", "-hget")) {
                Runnable flip = () -> f.acl("-hstrlen", "+hget");
                if (beforeLengths) f.control.afterProof = flip;
                else f.control.afterLengths = flip;
                assertCanonicalMetadataRejection(f, facet);
                if (beforeLengths)
                    assertFalse(f.control.phases.stream().anyMatch(p -> p.names.contains("HGET")));
            }
        }
    }

    @Test void metadataLengthAclRevokedAfterQueueCannotPublishNumericOrFacetValues() {
        for (boolean facet : List.of(false, true)) {
            try (Fixture f = new Fixture(2, "a", "+hget")) {
                f.control.beforeExec = () -> f.acl("-hstrlen");
                assertCanonicalMetadataRejection(f, facet);
                assertEquals(1, f.control.queuedProofs);
            }
        }
    }

    @Test void ownerAclFlipReplaysProofForAdmittedAndAllForeignRowsBeforeSingleFallback() {
        for (boolean afterQueue : List.of(false, true)) for (boolean foreign : List.of(false, true)) {
            try (Fixture f = new Fixture(2, "a", "+getrange")) {
                if (foreign) for (String owner : f.owners) f.other.set(owner, "b");
                Runnable revoke = () -> f.acl("-getrange");
                if (afterQueue) f.control.beforeExec = revoke;
                else if (foreign) f.control.afterProof = revoke;
                else f.control.afterLengths = revoke;
                assertEquals(foreign ? List.of() : List.of("1", "2"), f.numeric().values());
                assertEquals(1, f.control.evaluations);
                assertEquals(1, f.client.getPool().getDestroyedCount());
                assertEquals(0, f.control.aborts);
                assertEquals(afterQueue ? 1 : 0, f.control.queuedProofs);
                assertEquals(0, f.client.getPool().getNumActive());
                assertEquals("PONG", f.client.ping());
            }
        }
    }

    @Test void selectorRevocationAfterQueueCannotPublishNumericOrFacetValues() {
        for (boolean facet : List.of(false, true)) {
            try (Fixture f = new Fixture(2, "a", "+hget")) {
                f.control.beforeExec = () -> f.acl("resetkeys", "~" + f.metadata.getFirst(),
                        "~" + f.metadata.get(1));
                assertCanonicalMetadataRejection(f, facet);
                assertEquals(1, f.control.queuedProofs);
            }
        }
    }

    @Test void nativeOnlyAclRetainsSmallAndMaximumOneAndTwoFieldWindows() {
        for (boolean twoFields : List.of(false, true)) for (boolean large : List.of(false, true)) {
            int size = large ? (twoFields ? 256 : 512) : 2;
            try (Fixture f = new Fixture(size, "a", "-eval", "-get", "-mget", "-strlen")) {
                assertEquals(size, f.numeric().values().size());
                assertEquals(size, f.ops.aggregateIndexBatch(f.entities, f.tombs, f.metadata, f.owners,
                        f.tenant, "amount", true).orElseThrow().count());
                var rows = f.ops.facetIndexBatch(f.entities, f.tombs, f.metadata, f.owners,
                        f.tenant, "group", twoFields ? "amount" : null).orElseThrow().rows();
                assertEquals(size, rows.size());
                assertEquals(new RedisClientOperations.FacetIndexRow(0, "g0", twoFields ? "1" : null), rows.getFirst());
                assertEquals(large ? 3 : 0, f.control.evaluations);
                assertEquals(3, f.control.phases.stream().filter(p -> p.names.contains("EXEC")).count());
                assertEquals(0, f.client.getPool().getNumActive());
                assertEquals("PONG", f.client.ping());
            }
        }
    }

    private static void assertCanonicalMetadataRejection(Fixture f, boolean facet) {
        var actual = assertThrows(JedisDataException.class, () -> f.metadataRead(facet, false));
        assertEquals(1, f.control.evaluations);
        assertEquals(1, f.client.getPool().getDestroyedCount());
        assertEquals(0, f.client.getPool().getNumActive());
        assertEquals(0, f.control.aborts);
        var expected = assertThrows(JedisDataException.class, () -> f.metadataRead(facet, true));
        assertEquals(expected.getClass(), actual.getClass());
        assertEquals(expected.getMessage(), actual.getMessage());
        assertEquals("PONG", f.client.ping());
    }

    @Test void twoColumnFieldBudgetUsesLuaBeforeNativeBorrow() {
        try (Fixture f = new Fixture(257, "a")) {
            var result = f.ops.facetIndexBatch(f.entities, f.tombs, f.metadata,
                    f.owners, f.tenant, "group", "amount").orElseThrow();
            assertEquals(257, result.rows().size());
            assertEquals(1, f.control.evaluations);
            assertFalse(f.control.phases.stream().anyMatch(p -> p.names.contains("WATCH")));
        }
    }

    @Test void ioFailureDropsReservedConnectionAndNeverRetriesOrEvaluatesLua() {
        try (Fixture f = new Fixture(2, "a")) {
            f.control.failAfterLengths = true;
            assertThrows(JedisConnectionException.class, f::numeric);
            assertEquals(0, f.control.evaluations);
            assertEquals(1, f.control.created);
            assertEquals(0, f.client.getPool().getNumActive());
            assertEquals(0, f.client.getPool().getNumIdle());
            assertEquals("PONG", f.client.ping());
            assertEquals(2, f.control.created);
        }
    }

    @Test void nativePublicRepositoryPathPreservesAllFunctionsAndSentinelPayloadResolution() {
        URI uri = URI.create(RedisTestEndpoint.uri());
        for (boolean tenantScoped : List.of(false, true)) {
            Control control = new Control();
            try (var nativeClient = new SnapshotClient(new HostAndPort(uri.getHost(), uri.getPort()), control);
                    var redis = new JedisPooled(uri, 2000);
                    var fixture = new RedisT4AggregationRedisTest.Fixture(redis, tenantScoped);
                    var tenant = tenantScoped ? CacheTenantContext.withTenant("a") : CacheTenantContext.administrative()) {
                fixture.seed(List.of(
                        new RedisT4AggregationRedisTest.Row(1L, "a", "__NULL__", new BigDecimal("0.1"), "__NULL__"),
                        new RedisT4AggregationRedisTest.Row(2L, "a", "", null, null),
                        new RedisT4AggregationRedisTest.Row(3L, "a", null, new BigDecimal("0.2"), "ok"),
                        new RedisT4AggregationRedisTest.Row(4L, "b", "foreign", new BigDecimal("1000"), "foreign")));
                if (tenantScoped) redis.sadd(fixture.keys.indexExactKey("t4_rows", "tenant",
                        RedisIndexKeySpace.encodeKeyPart("a")), "4");
                var nativeOps = new JedisPooledOperations(nativeClient);
                var indexes = new RedisQueryIndexManager<>(nativeOps, fixture.metadata, RedisT4AggregationRedisTest.CODEC,
                        fixture.keys, null, QueryIndexConfig.defaults(), RelationConfig.defaults(), new QueryEvaluator(), null);
                var pages = new RedisPageCacheManager<>(nativeOps, fixture.metadata, RedisT4AggregationRedisTest.CODEC,
                        CachePolicy.defaults(), fixture.keys, null, indexes, null, ReadShapeGuardrailConfig.defaults());
                var repository = new RedisEntityRepository<>(nativeOps, fixture.metadata, RedisT4AggregationRedisTest.CODEC,
                        null, fixture.keys, FetchPlan.empty(), CachePolicy.defaults(), null, null,
                        RedisGuardrailConfig.defaults(), "unused:stream", "unused:compaction", 1, null, null,
                        RelationConfig.defaults(), QueryIndexConfig.defaults(), null, PageCacheConfig.defaults(),
                        ReadShapeGuardrailConfig.defaults(), pages, new QueryEvaluator(), indexes, null, nativeOps, null, null);
                var reference = fixture.repository(new RedisT4AggregationRedisTest.Client(redis, false), CachePolicy.defaults());
                for (AggregateFunction function : AggregateFunction.values()) {
                    var spec = new AggregateSpec(function, function == AggregateFunction.COUNT ? null : "amount", null);
                    RedisT4AggregationRedisTest.assertResult(reference.aggregate(spec), repository.aggregate(spec));
                    RedisT4AggregationRedisTest.assertFacets(reference.aggregateBy("category", spec),
                            repository.aggregateBy("category", spec));
                }
                var countLabel = new AggregateSpec(AggregateFunction.COUNT, "label", null);
                RedisT4AggregationRedisTest.assertResult(reference.aggregate(countLabel), repository.aggregate(countLabel));
                RedisT4AggregationRedisTest.assertFacets(reference.aggregateBy("category", countLabel),
                        repository.aggregateBy("category", countLabel));
                assertTrue(control.phases.stream().anyMatch(p -> p.names.contains("WATCH")));
                assertTrue(control.phases.stream().anyMatch(p -> p.names.contains("HSTRLEN")));
                // Sentinel payload revalidation remains the existing read-only script, not a native snapshot claim.
                assertTrue(control.evaluations > 0);
            }
        }
    }

    private record Phase(Connection connection, List<String> names) {}
    private static final class Control {
        final List<Phase> phases = new ArrayList<>();
        Runnable afterLengths, afterProof, beforeExec;
        boolean failAfterLengths;
        int created, aborts, evaluations, queuedProofs;
    }
    private static final class HookedConnection extends Connection {
        private Control control;
        private List<String> pending;
        private final Deque<Object> queuedReplies = new ArrayDeque<>();
        HookedConnection(HostAndPort address, JedisClientConfig config, Control control) {
            super(address, config);
            this.control = control; this.pending = new ArrayList<>();
        }
        @Override public void sendCommand(CommandArguments args) {
            if (control != null) {
                String name = new String(args.getCommand().getRaw(), StandardCharsets.US_ASCII);
                if (name.equals("EXEC") && control.beforeExec != null) {
                    // Drain only QUEUED acknowledgements, then change real ACLs before Redis receives EXEC.
                    List<Object> acknowledged = super.getMany(pending.size());
                    assertEquals(pending.size(), acknowledged.size());
                    for (Object reply : acknowledged)
                        assertArrayEquals("QUEUED".getBytes(StandardCharsets.US_ASCII), assertInstanceOf(byte[].class, reply));
                    queuedReplies.addAll(acknowledged);
                    control.queuedProofs++;
                    Runnable hook = control.beforeExec; control.beforeExec = null;
                    hook.run();
                }
                pending.add(name);
                if (name.equals("EVAL")) control.evaluations++;
            }
            super.sendCommand(args);
        }
        @Override public Object getOne() {
            try { return super.getOne(); }
            finally { if (control != null) pending.clear(); }
        }
        @Override public List<Object> getMany(int count) {
            // Initialization can invoke this override before subclass fields exist.
            if (control == null) return super.getMany(count);
            var replies = new ArrayList<Object>(count);
            while (!queuedReplies.isEmpty() && replies.size() < count) replies.add(queuedReplies.removeFirst());
            if (replies.size() < count) replies.addAll(super.getMany(count - replies.size()));
            List<String> names = List.copyOf(pending.subList(0, count));
            pending.subList(0, count).clear();
            control.phases.add(new Phase(this, names));
            if (names.contains("EXEC") && replies.get(replies.size() - 1) == null) control.aborts++;
            if (names.contains("WATCH")) {
                Runnable hook = control.afterProof; control.afterProof = null;
                if (hook != null) hook.run();
            }
            if (names.contains("HSTRLEN")) {
                Runnable hook = control.afterLengths; control.afterLengths = null;
                if (hook != null) hook.run();
                if (control.failAfterLengths) {
                    control.failAfterLengths = false;
                    throw new JedisConnectionException("Injected lost socket after preflight");
                }
            }
            return replies;
        }
    }
    private static final class SnapshotClient extends JedisPooled {
        SnapshotClient(HostAndPort address, Control control) {
            this(address, control, DefaultJedisClientConfig.builder()
                    .connectionTimeoutMillis(2000).socketTimeoutMillis(2000).build());
        }
        SnapshotClient(HostAndPort address, Control control, JedisClientConfig clientConfig) {
            super(new BasePooledObjectFactory<Connection>() {
                public Connection create() {
                    control.created++;
                    return new HookedConnection(address, clientConfig, control);
                }
                public PooledObject<Connection> wrap(Connection connection) { return new DefaultPooledObject<>(connection); }
                @Override public void destroyObject(PooledObject<Connection> object) {
                    object.getObject().setHandlingPool(null); object.getObject().close();
                }
                @Override public boolean validateObject(PooledObject<Connection> object) {
                    return object.getObject().isConnected() && !object.getObject().isBroken();
                }
            }, config());
        }
        private static GenericObjectPoolConfig<Connection> config() {
            var config = new GenericObjectPoolConfig<Connection>();
            config.setMaxTotal(1); config.setMaxIdle(1); config.setJmxEnabled(false);
            config.setMaxWait(Duration.ofSeconds(2));
            return config;
        }
    }
    private static final class Fixture implements AutoCloseable {
        final Control control = new Control();
        final SnapshotClient client;
        final JedisPooled other;
        final JedisPooledOperations ops;
        final List<String> entities = new ArrayList<>(), tombs = new ArrayList<>(),
                metadata = new ArrayList<>(), owners = new ArrayList<>();
        final String tenant;
        final String user;
        boolean userCreated;
        Fixture(int rows, String tenant) {
            this(rows, tenant, (String[]) null);
        }
        Fixture(int rows, String tenant, String... aclRules) {
            URI uri = URI.create(RedisTestEndpoint.uri()); this.tenant = tenant;
            other = new JedisPooled(uri, 2000);
            String prefix = "native-snapshot-" + UUID.randomUUID() + ":";
            user = aclRules == null ? null : "native_snapshot_" + UUID.randomUUID().toString().replace("-", "");
            String password = aclRules == null ? null : UUID.randomUUID().toString();
            var config = DefaultJedisClientConfig.builder().connectionTimeoutMillis(2000).socketTimeoutMillis(2000);
            if (user != null) config.user(user).password(password);
            client = new SnapshotClient(new HostAndPort(uri.getHost(), uri.getPort()), control, config.build());
            ops = new JedisPooledOperations(client);
            try {
                if (user != null) {
                    var rules = new ArrayList<String>(List.of("SETUSER", user, "reset", "on", ">" + password,
                            "~" + prefix + "*", "+@read", "+eval", "+ping", "+hello", "+client|setinfo",
                            "+watch", "+multi", "+exec", "+unwatch"));
                    rules.addAll(Arrays.asList(aclRules));
                    other.sendCommand(Command.ACL, rules.toArray(String[]::new));
                    userCreated = true;
                }
                try (var pipeline = other.pipelined()) {
                    for (int i = 0; i < rows; i++) {
                        entities.add(prefix + "entity:" + i); tombs.add(prefix + "tomb:" + i);
                        metadata.add(prefix + "meta:" + i); owners.add(prefix + "owner:" + i);
                        pipeline.set(entities.get(i), "payload");
                        pipeline.set(owners.get(i), tenant);
                        pipeline.hset(metadata.get(i), Map.of("group", "g" + i, "amount", Integer.toString(i + 1)));
                    }
                    pipeline.sync();
                }
            } catch (RuntimeException | Error failure) {
                try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        void acl(String... rules) {
            var args = new ArrayList<String>(List.of("SETUSER", user));
            args.addAll(Arrays.asList(rules));
            other.sendCommand(Command.ACL, args.toArray(String[]::new));
        }
        RedisClientOperations lua() { return new RedisClientOperations() {
            public UnifiedJedis commands() { return client; }
        }; }
        RedisClientOperations.IndexAggregateBatch count() {
            return ops.aggregateIndexBatch(entities, tombs, metadata, owners, tenant, null, true).orElseThrow();
        }
        RedisClientOperations.IndexAggregateBatch numeric() {
            return ops.aggregateIndexBatch(entities, tombs, metadata, owners, tenant, "amount", false).orElseThrow();
        }
        Object metadataRead(boolean facet, boolean lua) {
            RedisClientOperations operations = lua ? lua() : ops;
            return facet ? operations.facetIndexBatch(entities, tombs, metadata, owners,
                    tenant, "group", "amount").orElseThrow()
                    : operations.aggregateIndexBatch(entities, tombs, metadata, owners,
                            tenant, "amount", false).orElseThrow();
        }
        void reset() { control.evaluations = 0; control.phases.clear(); control.aborts = 0; }
        @Override public void close() {
            try {
                var keys = new ArrayList<String>(); keys.addAll(entities); keys.addAll(tombs);
                keys.addAll(metadata); keys.addAll(owners);
                if (!keys.isEmpty()) other.del(keys.toArray(String[]::new));
            } finally {
                try { client.close(); }
                finally {
                    try { if (userCreated) other.sendCommand(Command.ACL, "DELUSER", user); }
                    finally { other.close(); }
                }
            }
        }
    }
}
