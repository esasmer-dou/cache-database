package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.crypto.CacheEncryptionException;
import com.reactor.cachedb.core.crypto.EncryptedColumnDescriptor;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.route.RouteCacheContext;
import com.reactor.cachedb.core.route.RouteCacheContract;
import com.reactor.cachedb.core.route.RouteCacheStrictMode;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.*;
import redis.clients.jedis.executors.CommandExecutor;

@Timeout(30)
class RedisGuardedCandidateFetchTest {
    private static final RedisKeyStrategy KEYS = new RedisKeyStrategy("guarded-test");
    private static final String NAMESPACE = "t4_rows";

    @Test void defaultCapabilityDeclinesWithoutTouchingClusterOrCustomTransport() {
        for (boolean cluster : List.of(false, true)) {
            var client = new RedisClientOperations() {
                public boolean clustered() { return cluster; }
                public UnifiedJedis commands() { throw new AssertionError("No transport expected"); }
                public Object eval(String script, List<String> keys, List<String> args) {
                    throw new AssertionError("No EVAL expected");
                }
            };
            assertTrue(client.guardedSetMembers("health", "members", 3).isEmpty());
        }
    }

    @Test void fusedFetchDeclaresBothCustomKeysAndPreservesHealthyEmpty() {
        var client = new Evaluator();
        var empty = fetch(client, 3).orElseThrow();
        assertFalse(empty.degraded());
        assertTrue(empty.members().isEmpty());
        assertEquals(List.of("custom:health:\u00e9", "other:membership:\u96ea"), client.keys);
        assertEquals(List.of("3"), client.arguments);
        assertEquals(1, client.evaluations);
        assertTrue(client.script.indexOf("'EXISTS'") < client.script.indexOf("'SCARD'"));
        assertTrue(client.script.indexOf("'SCARD'") < client.script.indexOf("'SMEMBERS'"));
        assertTrue(client.script.contains("n>tonumber(ARGV[1])"));
        assertTrue(client.script.contains("redis.error_reply"));
        client.reply = List.of(1L, List.of("1", "2", "3"));
        assertEquals(List.of("1", "2", "3"), fetch(client, 3).orElseThrow().members());
    }

    @Test void degradedAndUnsupportedRemainDistinctFromHealthyEmpty() {
        var client = new Evaluator();
        client.reply = List.of(0L, List.of());
        var degraded = fetch(client, 1).orElseThrow();
        assertTrue(degraded.degraded());
        assertTrue(degraded.members().isEmpty());
        assertTrue(client.guardedSetMembers("health", "members", 1).isEmpty());
        var source = new ArrayList<>(List.of("1"));
        var healthy = new RedisClientOperations.GuardedSetMembers(false, source);
        source.clear();
        assertEquals(List.of("1"), healthy.members());
        assertThrows(UnsupportedOperationException.class, () -> healthy.members().add("2"));
        assertThrows(IllegalArgumentException.class,
                () -> new RedisClientOperations.GuardedSetMembers(true, List.of("1")));
    }

    @Test void malformedRepliesAndOverBudgetMembersFailClosed() {
        var client = new Evaluator();
        var invalid = new ArrayList<Object>();
        invalid.add(null);
        invalid.add("reply");
        invalid.add(List.of());
        invalid.add(List.of(1L));
        invalid.add(List.of(1L, List.of(), "extra"));
        invalid.add(List.of(1, List.of()));
        invalid.add(List.of(-1L, List.of()));
        invalid.add(List.of(2L, List.of()));
        invalid.add(List.of(0L, List.of("1")));
        invalid.add(List.of(1L, "members"));
        invalid.add(List.of(1L, Arrays.asList((Object) null)));
        invalid.add(List.of(1L, List.of(1L)));
        invalid.add(List.of(1L, List.of("1", "1")));
        invalid.add(List.of(1L, List.of("1", "2", "3")));
        for (Object reply : invalid) {
            client.reply = reply;
            assertThrows(IllegalStateException.class, () -> fetch(client, 2));
        }
    }

    @Test void unverifiedAclTypeNetworkAndCleanupFailuresNeverDecline() {
        var client = new Evaluator();
        for (RuntimeException failure : List.of(denied("eval"),
                new JedisAccessControlException("NOPERM No permissions to access a key"),
                new JedisDataException("WRONGTYPE Operation against a key holding the wrong kind of value"),
                new JedisDataException("NOAUTH Authentication required"),
                new JedisDataException("CacheDB materialized membership budget exceeded"),
                new JedisConnectionException("Injected connection failure"),
                new IllegalStateException("Injected pool return failure"))) {
            client.failure = failure;
            assertSame(failure, assertThrows(RuntimeException.class, () -> fetch(client, 2)));
        }
    }

    @Test void invalidBudgetsFailBeforeEvaluation() {
        var client = new Evaluator();
        for (int maximum : List.of(0, -1))
            assertThrows(IllegalArgumentException.class, () -> fetch(client, maximum));
        assertEquals(0, client.evaluations);
    }

    @Test void emptyRootAndSingletonEqUseOneGuardedFetchWithCustomKeys() {
        var keys = new RedisKeyStrategy("unused") {
            public String indexDegradedKey(String namespace) { return "custom:health"; }
            public String indexAllKey(String namespace) { return "custom:all"; }
            public String indexExactKey(String namespace, String column, String value) {
                return "custom:exact:" + column + ":" + value;
            }
        };
        var client = new Client();
        var manager = manager(client, keys, QueryIndexConfig.builder().maxMaterializedCandidateIds(3).build(), null);
        assertEquals(List.of("1", "2"), manager.indexOnlyCandidates(QuerySpec.allOf()).orElseThrow());
        assertEquals(List.of("custom:health", "custom:all"), client.keys);
        assertEquals(3, client.maximum);
        assertEquals(List.of("1", "2"), manager.indexOnlyCandidates(tenantQuery()).orElseThrow());
        assertEquals(List.of("custom:health", "custom:exact:tenant:" + RedisIndexKeySpace.encodeKeyPart("a")), client.keys);
        assertEquals(2, client.fetches);
        assertEquals(0, client.commandAccesses);
    }

    @Test void healthyEmptyAndDegradedNeverUseTheLegacyTransport() {
        var client = new Client();
        var manager = manager(client, KEYS, QueryIndexConfig.defaults(), null);
        client.result = Optional.of(new RedisClientOperations.GuardedSetMembers(false, List.of()));
        assertEquals(List.of(), manager.indexOnlyCandidates(tenantQuery()).orElseThrow());
        client.result = Optional.of(new RedisClientOperations.GuardedSetMembers(true, List.of()));
        assertTrue(manager.indexOnlyCandidates(tenantQuery()).isEmpty());
        assertEquals(2, client.fetches);
        assertEquals(0, client.commandAccesses);
    }

    @Test void singletonAndOrWrappersUseTheSameGuardedKeys() {
        var client = new Client();
        var manager = manager(client, KEYS, QueryIndexConfig.defaults(), null);
        var filter = QueryFilter.eq("tenant", "a");
        var evaluator = new QueryEvaluator();
        for (QuerySpec query : List.of(QuerySpec.allOf(filter), QuerySpec.anyOf(filter),
                QuerySpec.where(QueryGroup.and(QueryGroup.or(QueryGroup.and(filter)))))) {
            assertTrue(evaluator.matches(Map.of("tenant", "a"), query.rootGroup()));
            assertFalse(evaluator.matches(Map.of("tenant", "b"), query.rootGroup()));
            int before = client.fetches;
            assertEquals(List.of("1", "2"), manager.indexOnlyCandidates(query).orElseThrow());
            assertEquals(before + 1, client.fetches);
            assertEquals(List.of(KEYS.indexDegradedKey(NAMESPACE),
                    KEYS.indexExactKey(NAMESPACE, "tenant", RedisIndexKeySpace.encodeKeyPart("a"))), client.keys);
        }
        assertEquals(0, client.commandAccesses);
    }

    @Test void emptyAndWrappersPreserveBooleanSemanticsWithoutAdmittingEmptyOr() {
        var client = new Client();
        var manager = manager(client, KEYS, QueryIndexConfig.defaults(), null);
        var evaluator = new QueryEvaluator();
        for (QuerySpec query : List.of(QuerySpec.builder().build(), QuerySpec.allOf(),
                QuerySpec.where(QueryGroup.or(QueryGroup.and(QueryGroup.and()))))) {
            assertTrue(evaluator.matches(Map.of(), query.rootGroup()));
            client.result = Optional.of(new RedisClientOperations.GuardedSetMembers(false, List.of()));
            int before = client.fetches;
            assertEquals(List.of(), manager.indexOnlyCandidates(query).orElseThrow());
            assertEquals(List.of(KEYS.indexDegradedKey(NAMESPACE), KEYS.indexAllKey(NAMESPACE)), client.keys);
            client.result = Optional.of(new RedisClientOperations.GuardedSetMembers(true, List.of()));
            assertTrue(manager.indexOnlyCandidates(query).isEmpty());
            assertEquals(before + 2, client.fetches);
        }
        assertEquals(0, client.commandAccesses);
        int fetched = client.fetches;
        var failure = new IllegalStateException("Legacy path reached");
        client.commandFailure = failure;
        for (QuerySpec query : List.of(QuerySpec.anyOf(), QuerySpec.where(QueryGroup.and(QueryGroup.or())),
                QuerySpec.where(QueryGroup.or(QueryGroup.or())))) {
            assertFalse(evaluator.matches(Map.of(), query.rootGroup()));
            assertSame(failure, assertThrows(IllegalStateException.class, () -> manager.indexOnlyCandidates(query)));
        }
        assertEquals(fetched, client.fetches);
    }

    @Test void degradedEmptyAndRootCannotBypassStrictRouteFullScanRejection() {
        var client = new Client();
        client.degraded = true;
        client.result = Optional.of(new RedisClientOperations.GuardedSetMembers(true, List.of()));
        var manager = manager(client, KEYS, QueryIndexConfig.defaults(), null);
        var query = QuerySpec.allOf();
        assertTrue(manager.indexOnlyCandidates(query).isEmpty());
        assertEquals(1, client.fetches);
        assertEquals(0, client.commandAccesses);
        var contract = new RouteCacheContract("guarded-root", NAMESPACE, "", 1, 1, false,
                0, 0, RouteCacheStrictMode.FAIL_FAST, null, false);
        RouteCacheContext.runWithContract(contract, () -> {
            var failure = assertThrows(IllegalStateException.class, () -> manager.resolveCandidateIds(query));
            assertTrue(failure.getMessage().contains("cannot fall back to a Redis full-scan"));
        });
        assertEquals(0, client.cardinalities);
        assertTrue(client.setKeys.isEmpty());
        assertEquals(Collections.nCopies(2, KEYS.indexDegradedKey(NAMESPACE)), client.healthKeys);
    }

    @Test void unsupportedCapabilityKeepsLegacyEightHealthReads() {
        var client = new Client();
        client.result = Optional.empty();
        var manager = manager(client, KEYS, QueryIndexConfig.defaults(), null);
        assertEquals(Set.of("1", "2"), Set.copyOf(manager.indexOnlyCandidates(tenantQuery()).orElseThrow()));
        assertEquals(Collections.nCopies(8, KEYS.indexDegradedKey(NAMESPACE)), client.healthKeys);
        assertEquals(List.of(KEYS.indexExactKey(NAMESPACE, "tenant", RedisIndexKeySpace.encodeKeyPart("a"))), client.setKeys);
        assertEquals(1, client.cardinalities);
        client.healthKeys.clear(); client.setKeys.clear(); client.cardinalities = 0;
        assertEquals(Set.of("1", "2"), Set.copyOf(manager.indexOnlyCandidates(QuerySpec.builder().build()).orElseThrow()));
        assertEquals(List.of(KEYS.indexDegradedKey(NAMESPACE)), client.healthKeys);
        assertEquals(List.of(KEYS.indexAllKey(NAMESPACE)), client.setKeys);
        assertEquals(1, client.cardinalities);
        client.healthKeys.clear(); client.setKeys.clear(); client.cardinalities = 0;
        assertEquals(Set.of("1", "2"), Set.copyOf(manager.indexOnlyCandidates(QuerySpec.allOf()).orElseThrow()));
        assertEquals(Collections.nCopies(5, KEYS.indexDegradedKey(NAMESPACE)), client.healthKeys);
        assertEquals(List.of(KEYS.indexAllKey(NAMESPACE)), client.setKeys);
        assertEquals(1, client.cardinalities);
    }

    @Test void ineligibleShapesAndDisabledExactIndexesNeverUseFusion() {
        var client = new Client();
        var failure = new IllegalStateException("Legacy path reached");
        client.commandFailure = failure;
        var manager = manager(client, KEYS, QueryIndexConfig.defaults(), null);
        for (QuerySpec query : List.of(QuerySpec.allOf(QueryFilter.eq("tenant", "a"), QueryFilter.eq("category", "g")),
                QuerySpec.anyOf(QueryFilter.eq("tenant", "a"), QueryFilter.eq("tenant", "b")),
                QuerySpec.allOf(QueryGroup.and(), QueryFilter.eq("tenant", "a")),
                QuerySpec.where(QueryFilter.gt("amount", 1)), QuerySpec.where(QueryFilter.eq("relation.tenant", "a")),
                QuerySpec.where(QueryFilter.eq("unknown", "a"))))
            assertSame(failure, assertThrows(IllegalStateException.class, () -> manager.indexOnlyCandidates(query)));
        var disabled = manager(client, KEYS, QueryIndexConfig.builder().exactIndexEnabled(false).build(), null);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> disabled.indexOnlyCandidates(tenantQuery())));
        assertEquals(0, client.fetches);
    }

    @Test void encryptedMetadataAndCompositeDeclarationsRetainTheLegacyPath() {
        var client = new Client();
        var failure = new IllegalStateException("Legacy path reached");
        client.commandFailure = failure;
        var encrypted = new RedisQueryIndexManager<>(client,
                RedisEncryptedBulkDeleteChecksTest.metadata(false, EncryptedColumnDescriptor.Scope.REDIS_ONLY),
                RedisEncryptedBulkDeleteChecksTest.codec(true), KEYS, null, QueryIndexConfig.defaults(),
                RelationConfig.defaults(), new QueryEvaluator(), null);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> encrypted.indexOnlyCandidates(QuerySpec.allOf())));
        assertThrows(CacheEncryptionException.class,
                () -> encrypted.indexOnlyCandidates(QuerySpec.where(QueryFilter.gt("secret", "value"))));
        client.commandFailure = null;
        var metadata = compositeMetadata();
        client.signature = RedisCompositeIndexes.signature(metadata);
        var composite = new RedisQueryIndexManager<>(client, metadata, RedisT4AggregationRedisTest.CODEC, KEYS,
                null, QueryIndexConfig.defaults(), RelationConfig.defaults(), new QueryEvaluator(), null);
        client.commandFailure = failure;
        assertSame(failure, assertThrows(IllegalStateException.class, () -> composite.indexOnlyCandidates(tenantQuery())));
        assertEquals(0, client.fetches);
    }

    @Test void classifiedAndUnclassifiedShedPoliciesBothRemainAuthoritative() {
        for (int scenario = 0; scenario < 4; scenario++) {
            var client = new Client();
            var config = RedisGuardrailConfig.builder().enabled(true).usedMemoryCriticalBytes(1)
                    .sampleIntervalMillis(60_000).shedQueryIndexReadsOnHardLimit(scenario != 0)
                    .queryPolicies(List.of(new HardLimitQueryPolicy(NAMESPACE, HardLimitQueryClass.EXACT, scenario == 0, false)));
            if (scenario >= 2)
                config.entityPolicies(List.of(new HardLimitEntityPolicy(NAMESPACE, false, false, false, false,
                        scenario == 3, false, false)));
            var guard = new RedisProducerGuard(client, config.build(), WriteBehindConfig.defaults(), KEYS, null, 0);
            var manager = manager(client, KEYS, QueryIndexConfig.defaults(), guard);
            assertEquals(scenario == 2, manager.indexOnlyCandidates(tenantQuery()).isPresent());
            assertEquals(scenario == 2 ? 1 : 0, client.fetches);
            assertEquals(scenario == 0 || scenario == 2, manager.indexOnlyCandidates(QuerySpec.allOf()).isPresent());
            assertTrue(client.healthKeys.isEmpty());
        }
    }

    @Test void managerPropagatesFailuresAndIndependentlyEnforcesTheCandidateCap() {
        var client = new Client();
        var manager = manager(client, KEYS, QueryIndexConfig.builder().maxMaterializedCandidateIds(1).build(), null);
        assertThrows(IllegalStateException.class, () -> manager.indexOnlyCandidates(tenantQuery()));
        var failure = new JedisAccessControlException("NOPERM No permissions to access a key");
        client.fetchFailure = failure;
        assertSame(failure, assertThrows(JedisAccessControlException.class, () -> manager.indexOnlyCandidates(tenantQuery())));
        assertEquals(0, client.commandAccesses);
    }

    private static Optional<RedisClientOperations.GuardedSetMembers> fetch(Evaluator evaluator, int maximum) {
        return JedisPooledOperations.guardedSetMembers(evaluator, "custom:health:\u00e9", "other:membership:\u96ea", maximum);
    }

    private static JedisAccessControlException denied(String command) {
        return new JedisAccessControlException("NOPERM User test has no permissions to run the '" + command + "' command");
    }

    private static QuerySpec tenantQuery() { return QuerySpec.where(QueryFilter.eq("tenant", "a")); }

    private static RedisQueryIndexManager<RedisT4AggregationRedisTest.Row, Long> manager(
            Client client, RedisKeyStrategy keys, QueryIndexConfig config, RedisProducerGuard guard) {
        return new RedisQueryIndexManager<>(client, RedisT4AggregationRedisTest.metadata(true),
                RedisT4AggregationRedisTest.CODEC, keys, null, config, RelationConfig.defaults(), new QueryEvaluator(), guard);
    }

    private static EntityMetadata<RedisT4AggregationRedisTest.Row, Long> compositeMetadata() {
        var base = RedisT4AggregationRedisTest.metadata(true);
        return new EntityMetadata<>() {
            public String entityName() { return base.entityName(); }
            public String tableName() { return base.tableName(); }
            public String redisNamespace() { return base.redisNamespace(); }
            public String idColumn() { return base.idColumn(); }
            public Class<RedisT4AggregationRedisTest.Row> entityType() { return base.entityType(); }
            public Function<RedisT4AggregationRedisTest.Row, Long> idAccessor() { return base.idAccessor(); }
            public List<String> columns() { return base.columns(); }
            public Map<String, String> columnTypes() { return base.columnTypes(); }
            public List<RelationDefinition> relations() { return base.relations(); }
            public Map<String, List<String>> compositeExactIndexes() { return Map.of("tenant_category", List.of("tenant", "category")); }
        };
    }

    private static final class Evaluator implements RedisClientOperations {
        Object reply = List.of(1L, List.of());
        RuntimeException failure;
        List<String> keys, arguments;
        String script;
        int evaluations;
        public UnifiedJedis commands() { throw new AssertionError("No direct command expected"); }
        public Object eval(String script, List<String> keys, List<String> arguments) {
            evaluations++;
            this.script = script; this.keys = List.copyOf(keys); this.arguments = List.copyOf(arguments);
            if (failure != null) throw failure;
            return reply;
        }
    }

    private static final class Client implements RedisClientOperations {
        Optional<GuardedSetMembers> result = Optional.of(new GuardedSetMembers(false, List.of("1", "2")));
        RuntimeException commandFailure, fetchFailure;
        boolean degraded;
        String signature;
        int fetches, maximum, commandAccesses, cardinalities;
        List<String> keys;
        final List<String> healthKeys = new ArrayList<>(), setKeys = new ArrayList<>();
        final UnifiedJedis commands = new UnifiedJedis(new CommandExecutor() {
            public <T> T executeCommand(CommandObject<T> command) { throw new AssertionError("Unexpected command"); }
            public void close() {}
        }) {
            @Override public boolean exists(String key) { healthKeys.add(key); return degraded; }
            @Override public long scard(String key) { cardinalities++; return 2; }
            @Override public Set<String> smembers(String key) { setKeys.add(key); return new LinkedHashSet<>(List.of("1", "2")); }
            @Override public String get(String key) { return signature; }
            @Override public Map<String, String> hgetAll(String key) { return Map.of(); }
            @Override public long xlen(String key) { return 0L; }
        };
        public UnifiedJedis commands() {
            commandAccesses++;
            if (commandFailure != null) throw commandFailure;
            return commands;
        }
        public Object memoryInfo(String routingKey) {
            return "used_memory:10\r\nused_memory_peak:10\r\nmaxmemory:100\r\nmaxmemory_policy:noeviction\r\n";
        }
        public Optional<GuardedSetMembers> guardedSetMembers(String degradedKey, String membershipKey, int maximum) {
            fetches++; keys = List.of(degradedKey, membershipKey); this.maximum = maximum;
            if (fetchFailure != null) throw fetchFailure;
            return result;
        }
    }
}
