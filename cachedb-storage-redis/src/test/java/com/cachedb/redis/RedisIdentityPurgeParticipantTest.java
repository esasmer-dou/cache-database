package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.*;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.PurgeParticipant.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.query.QueryEvaluator;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class RedisIdentityPurgeParticipantTest {
    record Row(String id, String tenant, String status, int rank, String label) {}
    static final EntityCodec<Row> CODEC = new EntityCodec<>() {
        public String toRedisValue(Row row) { return row.id() + "|" + row.label(); }
        public Row fromRedisValue(String value) { throw new UnsupportedOperationException(); }
        public Map<String, Object> toColumns(Row row) {
            return Map.of("id", row.id(), "tenant", row.tenant(), "status", row.status(), "rank", row.rank(), "label", row.label());
        }
    };
    static EntityMetadata<Row, String> metadata(String ns) {
        return new EntityMetadata<>() {
            public String entityName() { return "PurgeRow"; }
            public String tableName() { return "purge_rows"; }
            public String redisNamespace() { return ns; }
            public String idColumn() { return "id"; }
            public Class<Row> entityType() { return Row.class; }
            public Function<Row, String> idAccessor() { return Row::id; }
            public String tenantFieldName() { return "tenant"; }
            public List<String> columns() { return List.of("id", "tenant", "status", "rank", "label"); }
            public Map<String, String> columnTypes() {
                return Map.of("id", "java.lang.String", "tenant", "java.lang.String", "status", "java.lang.String",
                        "rank", "int", "label", "java.lang.String");
            }
            public Map<String, List<String>> compositeExactIndexes() { return Map.of("tenant_status", List.of("tenant", "status")); }
            public Map<String, String> compositeSortColumns() { return Map.of("tenant_status", "rank"); }
            public Map<String, List<String>> partitionedSortIndexes() { return Map.of("tenant", List.of("rank")); }
            public List<RelationDefinition> relations() { return List.of(); }
        };
    }
    static final class Work implements RedisIdentityPurgeParticipant.Inventory {
        final NavigableMap<String, PurgeWorkItem> items = new TreeMap<>();
        boolean sealed = true;
        public boolean sealed(Lease lease) { return sealed; }
        public List<PurgeWorkItem> page(Lease lease, String after, int limit, int bytes) {
            return (after == null ? items : items.tailMap(after, false)).values().stream().limit(limit).toList();
        }
        void add(ProtectionKey key, String id) {
            items.put(key.objectToken(id), new PurgeWorkItem(key.objectToken(id), PurgeWorkItem.Kind.ID,
                    id.getBytes(StandardCharsets.UTF_8)));
        }
    }
    static final class Fixture implements AutoCloseable {
        final String prefix = "purge-native-" + UUID.randomUUID();
        final String ns = "rows";
        final RedisKeyStrategy keys = new ClusterHashTagKeyStrategy(prefix, "scope");
        final JedisPooled redis = new JedisPooled(RedisTestEndpoint.uri());
        final RedisClientOperations client = JedisPooledOperations.of(redis);
        final EntityMetadata<Row, String> meta = metadata(ns);
        final RedisGenerationBarrier barrier = new RedisGenerationBarrier(client, keys, ns);
        final ProtectionKey pair = barrier.key("a");
        final UUID operation = UUID.randomUUID();
        Lease lease = new Lease(pair, operation, UUID.randomUUID(), 1, 120);
        final Work work = new Work();
        final AtomicBoolean qualified = new AtomicBoolean(true);
        final RedisQueryIndexManager<Row, String> indexes;
        final RedisPageCacheManager<Row, String> pages;
        final RedisNearCacheCoordinator.Store l1 = new RedisNearCacheCoordinator.Store(new NearCachePolicy(16, 10, 65536));
        final BoundedRedisWire wire = new BoundedRedisWire(new InetSocketAddress(
                System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1"),
                Integer.getInteger("cachedb.snapshot.redis.port", 16383)), null, null, null, null, 0, 1000, 2000, 4);
        Fixture() {
            redis.set(keys.compositeIndexSignatureKey(ns), RedisCompositeIndexes.signature(meta));
            indexes = new RedisQueryIndexManager<>(client, meta, CODEC, keys, null,
                    QueryIndexConfig.builder().prefixIndexEnabled(true).textIndexEnabled(true).build(),
                    RelationConfig.defaults(), new QueryEvaluator(), null);
            pages = new RedisPageCacheManager<>(client, meta, CODEC, new CachePolicy(100, 20, true, 60, 60),
                    keys, new RedisHotSetManager(client, keys), indexes, null, ReadShapeGuardrailConfig.defaults());
        }
        void seed(Row row) {
            redis.set(keys.entityKey(ns, row.id()), CODEC.toRedisValue(row));
            redis.set(keys.versionKey(ns, row.id()), "42");
            redis.set(keys.versionKey(ns, row.id()) + ":tenant", row.tenant());
            redis.set(keys.tombstoneKey(ns, row.id()), "0");
            redis.set(keys.durabilityKey(ns, row.id()), "40");
            indexes.reindex(row);
            redis.zadd(keys.hotSetKey(ns), 1, row.id());
        }
        void quota(String id, long bytes) {
            String set = keys.tenantHotSetKey(ns, "tenant", "a");
            String budget = keys.tenantHotPayloadBytesKey(ns, "tenant", "a");
            redis.zadd(set, 1, id); redis.incrBy(budget, bytes);
            redis.set(keys.tenantHotOwnerKey(ns, id), set + "\n" + budget + "\n" + bytes);
        }
        void closeAdmission() {
            try (var admin = CacheTenantContext.administrative()) { barrier.close(lease, 1); }
        }
        RedisIdentityPurgeParticipant participant() {
            return participant(List.of(pages.purgeTarget(List.of(
                    new RedisPurgeTarget.Page(0, RedisPurgeTarget.PageKind.LIST),
                    new RedisPurgeTarget.Page(1, RedisPurgeTarget.PageKind.ZSET)))));
        }
        RedisIdentityPurgeParticipant participant(List<RedisPurgeTarget> targets) {
            return new RedisIdentityPurgeParticipant(wire, client, keys, barrier, ns, "a", meta.entityName(),
                    RedisIdentityPurgeParticipant.layoutFingerprint(targets),
                    targets.stream().map(RedisPurgeTarget::namespace).collect(java.util.stream.Collectors.toSet()),
                    targets, work, (request, layout) -> qualified.get(), Map.of(ns, l1));
        }
        Request request(Phase phase, String checkpoint) { return new Request(lease, 1, phase, checkpoint, 128, 1024 * 1024); }
        Result step(RedisIdentityPurgeParticipant participant, Phase phase, String checkpoint) {
            try (var admin = CacheTenantContext.administrative()) { return participant.step(request(phase, checkpoint)); }
        }
        public void close() {
            wire.close();
            try {
                String cursor = "0"; int passes = 0;
                do {
                    var page = redis.scan(cursor, new redis.clients.jedis.params.ScanParams().match(prefix + "*").count(100));
                    if (!page.getResult().isEmpty()) redis.del(page.getResult().toArray(String[]::new));
                    cursor = page.getCursor();
                    if (++passes > 1000) throw new IllegalStateException("Fixture cleanup budget");
                } while (!cursor.equals("0"));
            } finally { redis.close(); }
        }
    }

    @Test void removesNativeStoresMembershipsAndOnlyOwnedQuotaWithStableCrashResumeCounts() {
        try (var f = new Fixture()) {
            var own = new Row("own", "a", "active", 10, "private label");
            var other = new Row("foreign", "b", "active", 10, "peer label");
            f.seed(own); f.seed(other); f.quota("own", 120);
            f.redis.rpush(f.keys.pageKey(f.ns, 0), "own", "foreign", "own");
            f.redis.zadd(f.keys.pageKey(f.ns, 1), 1, "own"); f.redis.zadd(f.keys.pageKey(f.ns, 1), 1, "foreign");
            f.l1.put("own", "private label", 10000, f.l1.epoch());
            long observed = f.l1.epoch();
            f.work.add(f.pair, "own"); f.closeAdmission();
            var participant = f.participant();
            String stream = f.keys.writeStreamKey(f.prefix+":{scope}:write-behind", f.ns);
            f.redis.xadd(stream, redis.clients.jedis.StreamEntryID.NEW_ENTRY, Map.of("marker", "source-unchanged"));
            long streamLength = f.redis.xlen(stream);
            String allocator = f.keys.namespacePrefix(f.ns) + ":security:version:" + f.pair.tenantToken();
            f.redis.hset(allocator, "highwater", "1000");
            var result = f.step(participant, Phase.ERASE, "");
            assertEquals(State.MORE, result.state()); assertTrue(result.removed() > 0);
            assertEquals(result.removed(), f.step(participant, Phase.ERASE, "").removed(),
                    "lost Java result before checkpoint persistence must recover original counts");
            f.lease = new Lease(f.pair, f.operation, UUID.randomUUID(), 2, 120);
            f.closeAdmission();
            assertEquals(result.removed(), f.step(f.participant(), Phase.ERASE, "").removed(),
                    "new participant/lease owner must read the SAME persistent receipt");
            for (var key : participantTargets(f).getFirst().ownedKeys("own")) assertFalse(f.redis.exists(key.key()));
            assertEquals(List.of("foreign"), f.redis.lrange(f.keys.pageKey(f.ns, 0), 0, -1));
            assertNull(f.redis.zscore(f.keys.pageKey(f.ns, 1), "own"));
            assertNotNull(f.redis.zscore(f.keys.pageKey(f.ns, 1), "foreign"));
            assertFalse(f.redis.exists(f.keys.tenantHotPayloadBytesKey(f.ns, "tenant", "a")));
            assertEquals(CODEC.toRedisValue(other), f.redis.get(f.keys.entityKey(f.ns, "foreign")));
            assertEquals("1000", f.redis.hget(allocator, "highwater"));
            assertEquals(streamLength, f.redis.xlen(stream), "purge must not enqueue SQL DELETE");
            assertNull(f.l1.get("own"));
            f.l1.put("own", "late-fill", 10000, observed);
            assertNull(f.l1.get("own"), "pre-I/O L1 epoch cannot repopulate after invalidation");
            for (var action : f.indexes.purgeLayout().removals("own", "a", serialized(own, f), false)) {
                if (action.type().equals("set")) assertFalse(f.redis.sismember(action.key(), "own"));
                else assertNull(f.redis.zscore(action.key(), "own"));
            }
            assertEquals(State.MORE, f.step(participant, Phase.VERIFY, "").state());
            assertEquals(State.VERIFIED, f.step(participant, Phase.VERIFY, "v:" + f.pair.objectToken("own")).state());
            assertEquals(-1, f.redis.pttl(participant.receiptKey(f.lease, f.pair.objectToken("own"))));
        }
    }
    private static List<RedisPurgeTarget> participantTargets(Fixture f) {
        return List.of(f.pages.purgeTarget(List.of(new RedisPurgeTarget.Page(0, RedisPurgeTarget.PageKind.LIST),
                new RedisPurgeTarget.Page(1, RedisPurgeTarget.PageKind.ZSET))));
    }
    private static Map<String, String> serialized(Row row, Fixture f) {
        var values = new LinkedHashMap<String, String>();
        CODEC.toColumns(row).forEach((key, value) -> values.put(key, RedisIndexKeySpace.serializeValue(value)));
        RedisCompositeIndexes.append(f.keys, f.meta, CODEC.toColumns(row), values);
        return values;
    }


    @Test void acknowledgedCompactionAllowsIdentityErasureButUnknownFieldsAndMalformedAckStillBlock() {
        try(var f=new Fixture()){
            var row=new Row("own","a","active",1,"keep");
            f.seed(row);f.work.add(f.pair,"own");f.closeAdmission();
            String gate=f.barrier.generationKey(f.pair),request=ProtectionKey.hash("compaction-work");
            try(var admin=CacheTenantContext.administrative()){
                var cleaner=new RedisCompactionPurgeCleanup(f.client,f.keys,f.barrier,f.ns);
                assertEquals(0,cleaner.erase(f.lease,1,"a","own",request));
                cleaner.acknowledge(f.lease,1,request);
            }
            assertEquals(request,f.redis.hget(gate,"compaction_ack"));assertEquals(7,f.redis.hlen(gate));
            var participant=f.participant();
            f.redis.hset(gate,"compaction_ack","x".repeat(65536));
            assertEquals(State.INCOMPLETE,f.step(participant,Phase.ERASE,"").state());
            f.redis.hset(gate,"compaction_ack","invalid");
            assertEquals(State.INCOMPLETE,f.step(participant,Phase.ERASE,"").state());
            assertEquals(CODEC.toRedisValue(row),f.redis.get(f.keys.entityKey(f.ns,"own")));
            f.redis.hset(gate,"compaction_ack",request);f.redis.hset(gate,"unknown_field","invalid");
            assertEquals(State.INCOMPLETE,f.step(participant,Phase.ERASE,"").state());
            assertTrue(f.redis.exists(f.keys.versionKey(f.ns,"own")));
            f.redis.hdel(gate,"unknown_field");
            assertEquals(State.MORE,f.step(participant,Phase.ERASE,"").state());
            assertFalse(f.redis.exists(f.keys.entityKey(f.ns,"own")));
            f.redis.del(f.barrier.leaseKey(f.pair));
            assertThrows(ProtectionException.class,()->f.step(participant,Phase.VERIFY,""));
        }
    }

    @Test void foreignUnknownOrExpiringOwnerAndUnsealedInventoryNeverEraseAnything() {
        try (var f = new Fixture()) {
            f.seed(new Row("one", "b", "active", 1, "keep"));
            f.work.add(f.pair, "one"); f.closeAdmission(); var p = f.participant();
            assertEquals(State.INCOMPLETE, f.step(p, Phase.ERASE, "").state());
            f.redis.del(f.keys.versionKey(f.ns, "one") + ":tenant");
            assertEquals(State.INCOMPLETE, f.step(p, Phase.ERASE, "").state());
            f.redis.setex(f.keys.versionKey(f.ns, "one") + ":tenant", 60, "a");
            assertEquals(State.INCOMPLETE, f.step(p, Phase.ERASE, "").state());
            f.work.sealed = false;
            assertEquals(State.INCOMPLETE, f.step(p, Phase.ERASE, "").state());
            assertEquals("one|keep", f.redis.get(f.keys.entityKey(f.ns, "one")));
        }
    }

    @Test void wrongTypeUnknownMetadataAndForeignCompositeKeyFailBeforeAnyDeletion() {
        try (var f = new Fixture()) {
            var row = new Row("one", "a", "active", 1, "keep");
            f.seed(row); f.work.add(f.pair, "one"); f.closeAdmission(); var p = f.participant();
            String page = f.keys.pageKey(f.ns, 1);
            f.redis.set(page, "wrong-type");
            assertEquals(State.INCOMPLETE, f.step(p, Phase.ERASE, "").state());
            assertEquals("one|keep", f.redis.get(f.keys.entityKey(f.ns, "one")));
            f.redis.del(page);
            f.redis.hset(f.keys.indexMetaKey(f.ns, "one"), "$unknown-schema", "do-not-ignore");
            assertEquals(State.INCOMPLETE, f.step(p, Phase.ERASE, "").state());
            f.redis.hdel(f.keys.indexMetaKey(f.ns, "one"), "$unknown-schema");
            String foreignKey = f.keys.indexExactKey(f.ns, "foreign-field", "foreign-value");
            f.redis.sadd(foreignKey, "one");
            f.redis.hset(f.keys.indexMetaKey(f.ns, "one"), RedisCompositeIndexes.MARKER + "tenant_status", foreignKey);
            assertEquals(State.INCOMPLETE, f.step(p, Phase.ERASE, "").state());
            assertTrue(f.redis.sismember(foreignKey, "one")); assertTrue(f.redis.exists(f.keys.versionKey(f.ns, "one")));
        }
    }

    @Test void budgetsReadAckSchemaAndLiveLeaseAreFailClosedAndErrorsAreRedacted() {
        try (var f = new Fixture()) {
            f.seed(new Row("private-id", "a", "active", 1, "secret")); f.work.add(f.pair, "private-id");
            f.closeAdmission(); var p = f.participant();
            f.qualified.set(false);
            assertEquals(State.INCOMPLETE, f.step(p, Phase.ERASE, "").state());
            f.qualified.set(true);
            try (var admin = CacheTenantContext.administrative()) {
                assertEquals(State.INCOMPLETE, p.step(new Request(f.lease, 1, Phase.ERASE, "", 1, 1024)).state());
            }
            f.redis.set(f.keys.compositeIndexSignatureKey(f.ns), "unknown-layout");
            assertEquals(State.INCOMPLETE, f.step(p, Phase.ERASE, "").state());
            f.redis.set(f.keys.compositeIndexSignatureKey(f.ns), RedisCompositeIndexes.signature(f.meta));
            f.redis.del(f.barrier.leaseKey(f.pair));
            var error = assertThrows(ProtectionException.class, () -> f.step(p, Phase.ERASE, ""));
            assertFalse(error.toString().contains("private-id")); assertFalse(error.toString().contains("secret"));
            assertNull(error.getCause()); assertTrue(f.redis.exists(f.keys.entityKey(f.ns, "private-id")));
        }
    }

    @Test void missingLeaseRejectsEveryPhaseWithoutChangingOwnedData() {
        try (var f = new Fixture()) {
            f.seed(new Row("private-id", "a", "active", 1, "secret"));
            f.work.add(f.pair, "private-id"); f.closeAdmission();
            var participant = f.participant();
            String owner = f.keys.versionKey(f.ns, "private-id") + ":tenant";
            String entity = f.keys.entityKey(f.ns, "private-id");
            String receipt = participant.receiptKey(f.lease, f.pair.objectToken("private-id"));
            f.redis.del(f.barrier.leaseKey(f.pair));
            for (var phase : Phase.values()) {
                var failure = assertThrows(ProtectionException.class, () -> f.step(participant, phase, ""));
                assertEquals(ProtectionException.Code.STORAGE_UNAVAILABLE, failure.code());
                assertNull(failure.getCause()); assertFalse(failure.toString().contains("private-id"));
                assertEquals("private-id|secret", f.redis.get(entity));
                assertEquals("a", f.redis.get(owner)); assertFalse(f.redis.exists(receipt));
            }
            // Empty sealed inventory still needs a live fence before certifying completion.
            f.work.items.clear();
            for (var phase : Phase.values())
                assertThrows(ProtectionException.class, () -> f.step(participant, phase, ""));
        }
    }

    @Test void recoveredRemovalReceiptCannotBypassALostLease() {
        try (var f = new Fixture()) {
            f.seed(new Row("one", "a", "active", 1, "keep"));
            f.work.add(f.pair, "one"); f.closeAdmission();
            var participant = f.participant();
            var removed = f.step(participant, Phase.ERASE, "");
            assertEquals(State.MORE, removed.state()); assertTrue(removed.removed() > 0);
            String receiptKey = participant.receiptKey(f.lease, f.pair.objectToken("one"));
            String receipt = f.redis.get(receiptKey); assertNotNull(receipt);
            f.redis.del(f.barrier.leaseKey(f.pair));
            assertThrows(ProtectionException.class, () -> f.step(participant, Phase.ERASE, ""));
            assertThrows(ProtectionException.class, () -> f.step(participant, Phase.VERIFY, ""));
            assertThrows(ProtectionException.class, () -> f.step(participant, Phase.ERASE, removed.checkpoint()));
            assertEquals(receipt, f.redis.get(receiptKey));
        }
    }


    @Test void quotaUnderflowOrForeignHotOwnerPreservesAllStores() {
        try (var f = new Fixture()) {
            f.seed(new Row("one", "a", "active", 1, "keep")); f.quota("one", 100);
            f.work.add(f.pair, "one"); f.closeAdmission(); var p = f.participant();
            f.redis.set(f.keys.tenantHotPayloadBytesKey(f.ns, "tenant", "a"), "99");
            assertEquals(State.INCOMPLETE, f.step(p, Phase.ERASE, "").state());
            assertEquals("one|keep", f.redis.get(f.keys.entityKey(f.ns, "one")));
            f.redis.set(f.keys.tenantHotOwnerKey(f.ns, "one"), "foreign-set\nforeign-budget\n100");
            assertEquals(State.INCOMPLETE, f.step(p, Phase.ERASE, "").state());
            assertTrue(f.redis.exists(f.keys.indexMetaKey(f.ns, "one")));
        }
    }

    @Test void concurrentSameIdentityProducesOneStablePhysicalRemovalReceipt() throws Exception {
        try (var f = new Fixture(); var executor = Executors.newFixedThreadPool(2)) {
            f.seed(new Row("one", "a", "active", 1, "keep")); f.work.add(f.pair, "one"); f.closeAdmission();
            var p = f.participant(); var start = new CountDownLatch(1);
            Callable<Result> call = () -> { start.await(); return f.step(p, Phase.ERASE, ""); };
            var a = executor.submit(call); var b = executor.submit(call); start.countDown();
            var left = a.get(10, TimeUnit.SECONDS); var right = b.get(10, TimeUnit.SECONDS);
            assertEquals(State.MORE, left.state()); assertEquals(left.removed(), right.removed());
            assertEquals(left.checkpoint(), right.checkpoint());
        }
    }

    @Test void literalNullCompositeUsesCanonicalBuilderWithoutTrustingStoredKey() {
        try (var f = new Fixture()) {
            var row = new Row("one", "a", "__NULL__", 1, "keep");
            f.seed(row); f.work.add(f.pair, "one"); f.closeAdmission();
            assertEquals(State.MORE, f.step(f.participant(), Phase.ERASE, "").state());
        }
    }

    @Test void explicitProjectionUsesItsRuntimeKeysAndMixedOwnerFailsBeforeBaseDeletion() {
        try (var f = new Fixture()) {
            var row = new Row("one", "a", "active", 1, "private");
            f.seed(row);
            var projection = com.reactor.cachedb.core.projection.EntityProjection.<Row, Row, String>of(
                    "detail", new com.reactor.cachedb.core.projection.ProjectionCodec<>() {
                        public String toRedisValue(Row value) { return CODEC.toRedisValue(value); }
                        public Row fromRedisValue(String value) { throw new UnsupportedOperationException(); }
                    }, Row::id, f.meta.columns(), CODEC::toColumns, Function.identity());
            var metadata = new com.reactor.cachedb.core.projection.ProjectionEntityMetadata<>(f.meta, projection);
            var codec = new com.reactor.cachedb.core.projection.ProjectionEntityCodec<Row, String>(projection);
            var manager = new RedisQueryIndexManager<>(f.client, metadata, codec, f.keys, null,
                    QueryIndexConfig.defaults(), RelationConfig.defaults(), new QueryEvaluator(), null);
            var runtime = new RedisProjectionRuntime<>(f.client, metadata, codec, f.keys,
                    new CachePolicy(100, 20, true, 60, 60), manager,
                    new com.reactor.cachedb.core.projection.EntityProjectionBinding<>("PurgeRow", projection));
            runtime.upsert(row);
            assertThrows(ProtectionException.class, () -> runtime.purgeTarget(true, true));
            assertThrows(ProtectionException.class, () -> runtime.purgeTarget(false, false));
            var targets = new ArrayList<>(participantTargets(f));
            targets.add(runtime.purgeTarget(true, false));
            f.work.add(f.pair, "one"); f.closeAdmission();
            var participant = f.participant(targets);
            f.redis.hset(f.keys.indexMetaKey(metadata.redisNamespace(), "one"),
                    RedisQueryIndexManager.PROJECTION_TENANT_MARKER, "b");
            assertEquals(State.INCOMPLETE, f.step(participant, Phase.ERASE, "").state());
            assertTrue(f.redis.exists(f.keys.entityKey(f.ns, "one")));
            assertTrue(f.redis.exists(f.keys.entityKey(runtime.metadata().redisNamespace(), "one")));
            f.redis.hset(f.keys.indexMetaKey(metadata.redisNamespace(), "one"),
                    RedisQueryIndexManager.PROJECTION_TENANT_MARKER, "a");
            assertEquals(State.MORE, f.step(participant, Phase.ERASE, "").state());
            assertFalse(f.redis.exists(f.keys.entityKey(runtime.metadata().redisNamespace(), "one")));
            assertFalse(f.redis.exists(f.keys.indexMetaKey(metadata.redisNamespace(), "one")));
            runtime.attachQueryCacheInvalidator(() -> {});
            runtime.attachQueryCacheInvalidator(() -> { throw new IllegalStateException("not an acknowledgement"); });
            assertThrows(ProtectionException.class, () -> runtime.purgeTarget(true, false),
                    "overwritten cache owners are unknown stores, never a successful cleanup");
        }
    }

    @Test void unknownManifestHistoricalSchemaAndOversizeMetadataNeverReceiveSuccess() {
        try (var f = new Fixture()) {
            var targets = participantTargets(f);
            assertThrows(ProtectionException.class, () -> new RedisIdentityPurgeParticipant(f.wire, f.client,
                    f.keys, f.barrier, f.ns, "a", "PurgeRow", ProtectionKey.hash("unknown-layout"), Set.of(f.ns),
                    targets, f.work, (request, layout) -> true, Map.of()));
            assertThrows(ProtectionException.class, () -> new RedisIdentityPurgeParticipant(f.wire, f.client,
                    f.keys, f.barrier, f.ns, "a", "PurgeRow", RedisIdentityPurgeParticipant.layoutFingerprint(targets),
                    Set.of(f.ns, "unregistered-projection"), targets, f.work, (request, layout) -> true, Map.of()));
            f.seed(new Row("one", "a", "active", 1, "keep")); f.work.add(f.pair, "one"); f.closeAdmission();
            f.redis.hset(f.keys.indexMetaKey(f.ns, "one"), "label", "x".repeat(8193));
            assertEquals(State.INCOMPLETE, f.step(f.participant(), Phase.ERASE, "").state());
            assertTrue(f.redis.exists(f.keys.entityKey(f.ns, "one")));
        }
    }

    @Test void missingMetadataIsNotProofThatActiveIndexMembershipsWereErased() {
        try (var f = new Fixture()) {
            f.seed(new Row("one", "a", "active", 1, "keep")); f.work.add(f.pair, "one"); f.closeAdmission();
            f.redis.del(f.keys.indexMetaKey(f.ns, "one"));
            assertEquals(State.INCOMPLETE, f.step(f.participant(), Phase.ERASE, "").state());
            assertTrue(f.redis.exists(f.keys.entityKey(f.ns, "one")));
        }
    }

    @Test void deniedLateMutationAclCannotLeaveAnUnjournaledPrefixOfDeletions() {
        try (var f = new Fixture()) {
            f.seed(new Row("one", "a", "active", 1, "keep")); f.work.add(f.pair, "one"); f.closeAdmission();
            String user = "purge-" + UUID.randomUUID();
            String password = UUID.randomUUID().toString();
            f.redis.sendCommand(redis.clients.jedis.Protocol.Command.ACL,
                    "SETUSER", user, "on", ">" + password, "~" + f.prefix + "*", "+@all", "-zrem");
            try (var restricted = new BoundedRedisWire(new InetSocketAddress(
                    System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1"),
                    Integer.getInteger("cachedb.snapshot.redis.port", 16383)), null, null,
                    user, password.toCharArray(), 0, 1000, 2000, 2)) {
                var targets = participantTargets(f);
                var participant = new RedisIdentityPurgeParticipant(restricted, f.client, f.keys, f.barrier,
                        f.ns, "a", "PurgeRow", RedisIdentityPurgeParticipant.layoutFingerprint(targets), Set.of(f.ns),
                        targets, f.work, (request, layout) -> true, Map.of(f.ns, f.l1));
                assertEquals(State.INCOMPLETE, f.step(participant, Phase.ERASE, "").state());
                assertTrue(f.redis.exists(f.keys.entityKey(f.ns, "one")));
                assertTrue(f.redis.exists(f.keys.versionKey(f.ns, "one") + ":tenant"));
                assertFalse(f.redis.exists(participant.receiptKey(f.lease, f.pair.objectToken("one"))));
            } finally { f.redis.sendCommand(redis.clients.jedis.Protocol.Command.ACL, "DELUSER", user); }
        }
    }

    @Test void closeDoesNotRequireDiscoveryToHaveAlreadyFinished() {
        try (var f = new Fixture()) {
            f.work.sealed = false; f.closeAdmission(); var participant = f.participant();
            assertEquals(State.VERIFIED, f.step(participant, Phase.CLOSE, "").state());
            assertEquals(State.MORE, f.step(participant, Phase.DISCOVER, "").state());
            assertEquals(State.INCOMPLETE, f.step(participant, Phase.ERASE, "").state());
            f.qualified.set(false);
            assertEquals(State.INCOMPLETE, f.step(participant, Phase.DISCOVER, "").state());
        }
    }

    @Test void corruptOrUnknownGuardSchemaIsBoundedAndNeverCertifiesClose() {
        try (var f = new Fixture()) {
            f.seed(new Row("one", "a", "active", 1, "keep")); f.work.add(f.pair, "one"); f.closeAdmission();
            var participant = f.participant();
            f.redis.hset(f.barrier.generationKey(f.pair), "state", "x".repeat(65));
            assertEquals(State.INCOMPLETE, f.step(participant, Phase.CLOSE, "").state());
            assertEquals(State.INCOMPLETE, f.step(participant, Phase.ERASE, "").state());
            f.closeAdmission();
            f.redis.hset(f.barrier.generationKey(f.pair), "unknown-layout", "do-not-ignore");
            assertEquals(State.INCOMPLETE, f.step(participant, Phase.CLOSE, "").state());
            assertTrue(f.redis.exists(f.keys.entityKey(f.ns, "one")));
        }
    }
}
