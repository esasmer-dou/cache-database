package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
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
import redis.clients.jedis.exceptions.*;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class RedisStandaloneCountRoutingRedisTest {
    @Test void tenantOwnerReadThresholdIsThirtyTwoAndGlobalCountRemainsNative() {
        for (int size : List.of(32, 33, 512)) {
            try (Fixture f = new Fixture(size)) {
                assertEquals(size, f.count(true).count());
                assertEquals(size == 32 ? 0 : 1, f.frequency("EVAL"));
                assertEquals(size == 32 ? size : 0, f.frequency("GETRANGE"));
                assertFalse(f.commands.contains("WATCH"));
                f.commands.clear();
                assertEquals(size, f.count(false).count());
                assertEquals(List.of("MULTI", "EXISTS", "EXISTS", "EXEC"), f.commands);
            }
        }
    }

    @Test void evalReadOnlyAclDoesNotNeedNativeCommandsForLargeTenantCount() {
        try (Fixture f = new Fixture(512, "-watch", "-multi", "-exec", "-unwatch", "-getrange")) {
            f.admin.set(f.owners.get(0), f.tenant + "x".repeat(1024 * 1024));
            f.admin.set(f.owners.get(1), "another");
            f.admin.set(f.tombs.get(2), "deleted"); f.admin.del(f.entities.get(3));
            f.admin.del(f.meta.get(4)); f.admin.set(f.meta.get(4), "COUNT-star-does-not-read-metadata");
            assertEquals(508, f.count(true).count());
            assertEquals(List.of("EVAL"), f.commands);
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void deniedEvalRetainsHealthyLargeNativeCountWithoutWatch() {
        try (Fixture f = new Fixture(512, "-eval", "-watch")) {
            assertEquals(512, f.count(true).count());
            assertEquals(1, f.frequency("EVAL"));
            assertEquals(1, f.frequency("MULTI"));
            assertEquals(1, f.frequency("EXEC"));
            assertEquals(512, f.frequency("GETRANGE"));
            assertFalse(f.commands.contains("WATCH"));
            assertEquals(1, f.client.getPool().getDestroyedCount());
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void deniedEvalRetainsOneAndTwoFieldLargeFacetsWithinNativeFieldBudget() {
        for (boolean twoFields : List.of(false, true)) {
            int size = twoFields ? 256 : 512;
            try (Fixture f = new Fixture(size, "-eval")) {
                var rows = f.facets(twoFields).rows();
                assertEquals(size, rows.size());
                assertEquals(new RedisClientOperations.FacetIndexRow(0, "g0", twoFields ? "0.125" : null), rows.getFirst());
                assertEquals(1, f.frequency("EVAL"));
                assertEquals(1, f.frequency("WATCH"));
                assertEquals(2 * size * (twoFields ? 2 : 1), f.frequency("HSTRLEN"));
                assertEquals(size * (twoFields ? 2 : 1), f.frequency("HGET"));
                assertEquals(1, f.client.getPool().getDestroyedCount());
                assertEquals(0, f.client.getPool().getNumActive());
            }
        }
    }

    @Test void largeNumericAggregateWithEvalOnlyAclPreservesExactDecimalBytes() {
        try (Fixture f = new Fixture(128, "-watch", "-multi", "-exec", "-unwatch")) {
            f.admin.hset(f.meta.get(0), "amount", "0.00000000000000000001");
            var result = f.operations.aggregateIndexBatch(f.entities, f.tombs, f.meta, f.owners,
                    f.tenant, "amount", false).orElseThrow();
            assertEquals(128, result.count());
            assertEquals("0.00000000000000000001", result.values().getFirst());
            assertEquals(List.of("EVAL"), f.commands);
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void evalDeniedLargeNumericAndCountFieldAggregatesRetainNativeSemantics() {
        try (Fixture f = new Fixture(128, "-eval")) {
            f.admin.hset(f.meta.get(0), "amount", "0.00000000000000000001");
            var numeric = f.operations.aggregateIndexBatch(f.entities, f.tombs, f.meta, f.owners,
                    f.tenant, "amount", false).orElseThrow();
            assertEquals(128, numeric.count());
            assertEquals("0.00000000000000000001", numeric.values().getFirst());
            assertEquals(1, f.frequency("EVAL")); assertEquals(1, f.frequency("WATCH"));
            f.commands.clear(); f.admin.hset(f.meta.get(0), "amount", "__NULL__");
            var count = f.operations.aggregateIndexBatch(f.entities, f.tombs, f.meta, f.owners,
                    f.tenant, "amount", true).orElseThrow();
            assertEquals(127, count.count());
            assertEquals(List.of(0), count.nullCandidates());
            assertEquals(1, f.frequency("EVAL")); assertEquals(1, f.frequency("WATCH"));
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void bothAlgorithmsDeniedPropagateOriginalEvalAclWithoutRecursion() {
        try (Fixture f = new Fixture(33, "-eval", "-multi", "-exec")) {
            var failure = assertThrows(JedisAccessControlException.class, () -> f.count(true));
            assertTrue(failure.getMessage().contains("'eval'"));
            assertEquals(1, f.frequency("EVAL"));
            assertEquals(1, f.frequency("MULTI"));
            assertEquals(2, f.client.getPool().getDestroyedCount());
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void wrongtypeAndMissingMetadataAfterEvalDenialAreNotHidden() {
        try (Fixture f = new Fixture(40, "-eval")) {
            f.admin.del(f.owners.get(0)); f.admin.hset(f.owners.get(0), "wrong", "type");
            assertTrue(assertThrows(JedisDataException.class, () -> f.count(true)).getMessage().contains("WRONGTYPE"));
            assertEquals(1, f.frequency("EVAL"));
            f.admin.del(f.owners.get(0)); f.admin.set(f.owners.get(0), f.tenant);
            f.admin.hdel(f.meta.get(0), "group"); f.commands.clear();
            assertTrue(assertThrows(JedisDataException.class, () -> f.facets(false)).getMessage().contains("metadata"));
            assertEquals(1, f.frequency("EVAL"));
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void evalKeyPermissionDenialIsNotCommandCapabilityFallback() {
        try (Fixture f = new Fixture(40)) {
            f.admin.sendCommand(Command.ACL, "SETUSER", f.user, "resetkeys", "~" + f.prefix + "entity:*");
            var failure = assertThrows(JedisAccessControlException.class, () -> f.count(true));
            assertTrue(failure.getMessage().toLowerCase(Locale.ROOT).contains("key"));
            assertEquals(List.of("EVAL"), f.commands);
            assertEquals(0, f.client.getPool().getDestroyedCount());
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void oversizedMetadataAfterEvalDenialIsRejectedBeforeAnyHget() {
        try (Fixture f = new Fixture(40, "-eval")) {
            f.admin.hset(f.meta.get(0), "group", "x".repeat(16 * 1024 * 1024 + 1));
            var failure = assertThrows(JedisDataException.class, () -> f.facets(false));
            assertTrue(failure.getMessage().contains("byte budget"));
            assertEquals(1, f.frequency("EVAL"));
            assertEquals(40, f.frequency("HSTRLEN"));
            assertFalse(f.commands.contains("HGET"));
            assertFalse(f.commands.contains("EXEC"));
            assertEquals(2, f.client.getPool().getDestroyedCount());
        }
    }

    @Test void nativeFallbackPreservesExactUtf8OwnerChecksAndDoesNotReadPayloads() {
        try (Fixture f = new Fixture(33, "-eval")) {
            f.admin.set(f.owners.get(0), f.tenant + "x".repeat(2 * 1024 * 1024));
            f.admin.set(f.owners.get(1), "foreign"); f.admin.set(f.owners.get(2), f.tenant + "\0");
            f.admin.del(f.owners.get(3));
            assertEquals(29, f.count(true).count());
            assertEquals(1, f.frequency("EVAL"));
            assertEquals(33, f.frequency("GETRANGE"));
            assertFalse(f.commands.contains("GET"));
            assertFalse(f.commands.contains("HGET"));
            assertFalse(f.commands.contains("HSTRLEN"));
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void evalDeniedTwoColumnRequestAboveNativeFieldBudgetFailsWithoutUnboundedReads() {
        try (Fixture f = new Fixture(512, "-eval")) {
            assertTrue(assertThrows(JedisAccessControlException.class, () -> f.facets(true)).getMessage().contains("'eval'"));
            assertEquals(1, f.frequency("EVAL"));
            assertFalse(f.commands.contains("WATCH"));
            assertFalse(f.commands.contains("HSTRLEN"));
            assertFalse(f.commands.contains("HGET"));
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final JedisPooled admin = new JedisPooled(URI.create(RedisTestEndpoint.uri()), 2000);
        final String prefix = "t4-count-routing-" + UUID.randomUUID() + ":";
        final String user = "t4_count_" + UUID.randomUUID().toString().replace("-", "");
        final String tenant = "\u015firket";
        final List<String> entities = new ArrayList<>(), tombs = new ArrayList<>(),
                meta = new ArrayList<>(), owners = new ArrayList<>(), commands = new ArrayList<>();
        TraceClient client;
        JedisPooledOperations operations;
        boolean userCreated;
        Fixture(int size, String... denied) {
            try {
                String password = UUID.randomUUID().toString();
                var rules = new ArrayList<String>(List.of("SETUSER", user, "reset", "on", ">" + password,
                        "~" + prefix + "*", "+@read", "+eval", "+ping", "+hello", "+client|setinfo",
                        "+watch", "+multi", "+exec", "+unwatch"));
                rules.addAll(Arrays.asList(denied));
                admin.sendCommand(Command.ACL, rules.toArray(String[]::new)); userCreated = true;
                try (var pipeline = admin.pipelined()) {
                    for (int i = 0; i < size; i++) {
                        entities.add(prefix + "entity:" + i); tombs.add(prefix + "tomb:" + i);
                        meta.add(prefix + "meta:" + i); owners.add(prefix + "owner:" + i);
                        pipeline.set(entities.get(i), "payload-not-loaded");
                        pipeline.set(owners.get(i), tenant);
                        pipeline.hset(meta.get(i), Map.of("group", "g" + i % 20, "amount", "0.125"));
                    }
                    pipeline.sync();
                }
                URI endpoint = URI.create(RedisTestEndpoint.uri());
                var config = DefaultJedisClientConfig.builder().user(user).password(password)
                        .connectionTimeoutMillis(2000).socketTimeoutMillis(2000).build();
                client = new TraceClient(new HostAndPort(endpoint.getHost(), endpoint.getPort()), config, commands);
                operations = new JedisPooledOperations(client);
                client.ping(); commands.clear();
            } catch (RuntimeException | Error failure) {
                try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        RedisClientOperations.IndexAggregateBatch count(boolean owned) {
            return operations.aggregateIndexBatch(entities, tombs, meta, owned ? owners : null,
                    owned ? tenant : null, null, true).orElseThrow();
        }
        RedisClientOperations.FacetIndexBatch facets(boolean twoFields) {
            return operations.facetIndexBatch(entities, tombs, meta, owners, tenant, "group",
                    twoFields ? "amount" : null).orElseThrow();
        }
        int frequency(String command) { return Collections.frequency(commands, command); }
        @Override public void close() {
            try { if (client != null) client.close(); }
            finally {
                try {
                    var keys = new ArrayList<String>(); keys.addAll(entities); keys.addAll(tombs);
                    keys.addAll(meta); keys.addAll(owners);
                    if (!keys.isEmpty()) admin.unlink(keys.toArray(String[]::new));
                } finally {
                    try { if (userCreated) admin.sendCommand(Command.ACL, "DELUSER", user); }
                    finally { admin.close(); }
                }
            }
        }
    }

    private static final class TraceConnection extends Connection {
        private List<String> trace;
        TraceConnection(HostAndPort endpoint, JedisClientConfig config, List<String> trace) {
            super(endpoint, config); this.trace = trace;
        }
        @Override public void sendCommand(CommandArguments args) {
            if (trace != null) trace.add(args.getCommand().toString());
            super.sendCommand(args);
        }
    }
    private static final class TraceClient extends JedisPooled {
        TraceClient(HostAndPort endpoint, JedisClientConfig clientConfig, List<String> trace) {
            super(new BasePooledObjectFactory<Connection>() {
                public Connection create() { return new TraceConnection(endpoint, clientConfig, trace); }
                public PooledObject<Connection> wrap(Connection value) { return new DefaultPooledObject<>(value); }
                @Override public void destroyObject(PooledObject<Connection> value) {
                    value.getObject().setHandlingPool(null); value.getObject().close();
                }
                @Override public boolean validateObject(PooledObject<Connection> value) {
                    return value.getObject().isConnected() && !value.getObject().isBroken();
                }
            }, poolConfig());
        }
        private static GenericObjectPoolConfig<Connection> poolConfig() {
            var config = new GenericObjectPoolConfig<Connection>(); config.setJmxEnabled(false);
            config.setMaxTotal(1); config.setMaxIdle(1); config.setMaxWait(Duration.ofSeconds(2)); return config;
        }
    }
}
