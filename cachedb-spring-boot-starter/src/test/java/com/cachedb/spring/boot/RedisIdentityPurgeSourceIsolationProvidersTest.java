package com.reactor.cachedb.spring.boot;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.PurgeParticipant.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.query.QueryEvaluator;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.redis.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import redis.clients.jedis.*;

/** Physical source-isolation test, NOT a facade, full inventory or distributed read-admission certificate. */
@EnabledIfSystemProperty(named = "cachedb.snapshot.providers", matches = "true")
@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
@Timeout(60)
class RedisIdentityPurgeSourceIsolationProvidersTest {
    @ParameterizedTest @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void includeSourceFalseErasesNativeStateWithoutSourceDmlOrEnqueue(String provider) throws Exception {
        try (var source = new TenantPurgeProvidersTest.Fixture(provider);
             var redis = new JedisPooled("redis://" + System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1")
                     + ":" + Integer.getInteger("cachedb.snapshot.redis.port", 16383));
             var wire = new BoundedRedisWire(new InetSocketAddress(
                     System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1"),
                     Integer.getInteger("cachedb.snapshot.redis.port", 16383)), null, null, null, null, 0, 1000, 2000, 2);
             var admin = CacheTenantContext.administrative()) {
            source.seed("a", 2); source.seed("b", 1); source.provision();
            Lease lease = source.guard.acquireForTenant(source.binding, "a", source.options(false), false);
            source.redisStage(lease);
            String ns = source.binding.scope();
            String prefix = "isolation-" + UUID.randomUUID();
            var keys = new ClusterHashTagKeyStrategy(prefix, "scope");
            var client = JedisPooledOperations.of(redis);
            var barrier = new RedisGenerationBarrier(client, keys, ns);
            barrier.close(lease, 1);
            String id = ProtectionKey.hash("a") + "0";
            EntityMetadata<String, String> metadata = new EntityMetadata<>() {
                public String entityName() { return "SourceIsolation"; }
                public String tableName() { return source.binding.source().table(); }
                public String redisNamespace() { return ns; }
                public String idColumn() { return "id"; }
                public String tenantFieldName() { return "tenant_id"; }
                public Class<String> entityType() { return String.class; }
                public Function<String, String> idAccessor() { return Function.identity(); }
                public List<String> columns() { return List.of("id", "tenant_id", "value_text"); }
                public List<RelationDefinition> relations() { return List.of(); }
            };
            EntityCodec<String> codec = new EntityCodec<>() {
                public String toRedisValue(String value) { return "payload"; }
                public String fromRedisValue(String value) { return id; }
                public Map<String, Object> toColumns(String value) {
                    return Map.of("id", value, "tenant_id", "a", "value_text", "value");
                }
            };
            var indexes = new RedisQueryIndexManager<>(client, metadata, codec, keys, null,
                    QueryIndexConfig.defaults(), RelationConfig.defaults(), new QueryEvaluator(), null);
            var pages = new RedisPageCacheManager<>(client, metadata, codec, new CachePolicy(10, 10, true, 60, 60),
                    keys, new RedisHotSetManager(client, keys), indexes, null, ReadShapeGuardrailConfig.defaults());
            redis.set(keys.entityKey(ns, id), "payload"); redis.set(keys.versionKey(ns, id), "1");
            redis.set(keys.versionKey(ns, id) + ":tenant", "a"); indexes.reindex(id);
            String stream = keys.writeStreamKey("write-behind", ns);
            redis.xadd(stream, StreamEntryID.NEW_ENTRY, Map.of("sentinel", "unchanged"));
            long streamLength = redis.xlen(stream);
            var item = new PurgeWorkItem(lease.key().objectToken(id), PurgeWorkItem.Kind.ID, id.getBytes(StandardCharsets.UTF_8));
            RedisIdentityPurgeParticipant.Inventory work = new RedisIdentityPurgeParticipant.Inventory() {
                public boolean sealed(Lease ignored) { return true; }
                public List<PurgeWorkItem> page(Lease ignored, String after, int limit, int bytes) {
                    return after == null ? List.of(item) : List.of();
                }
            };
            var targets = List.of(pages.purgeTarget(List.of()));
            var participant = new RedisIdentityPurgeParticipant(wire, client, keys, barrier, ns, "a",
                    metadata.entityName(), RedisIdentityPurgeParticipant.layoutFingerprint(targets), Set.of(ns),
                    targets, work, (request, layout) -> source.guard.load(request.lease()).stage() == Stage.REDIS, Map.of());
            try {
                var result = participant.step(new Request(lease, 1, Phase.ERASE, "", 1, 1024 * 1024));
                assertEquals(State.MORE, result.state()); assertTrue(result.removed() > 0);
                assertFalse(redis.exists(keys.entityKey(ns, id)));
                assertEquals(2, source.rows("a")); assertEquals(1, source.rows("b"));
                assertEquals(streamLength, redis.xlen(stream));
                assertFalse(source.guard.load(lease).includeSource());
                assertEquals(0, source.guard.load(lease).sourceRows());
            } finally {
                // Only this test's explicitly scoped ephemeral namespace; durable SQL guards/fences remain.
                String cursor = "0"; int pagesSeen = 0;
                do {
                    var found = redis.scan(cursor, new redis.clients.jedis.params.ScanParams()
                            .match(prefix + "*").count(100));
                    if (!found.getResult().isEmpty()) redis.del(found.getResult().toArray(String[]::new));
                    cursor = found.getCursor();
                    if (++pagesSeen > 1000) throw new IllegalStateException("Fixture cleanup budget");
                } while (!cursor.equals("0"));
            }
        }
    }
}
