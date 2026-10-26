package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.api.*;
import com.reactor.cachedb.core.cache.*;
import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.page.*;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.redis.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class HotPathEvolutionRedisTest {
    record TenantRow(Long id, String tenant, String status, long amount) {}
    record ImmutableRow(Long id, String name) {}
    @Test void deeplyImmutableNearCacheReusesReferenceWithoutDecodingAgain() throws Exception {
        EntityMetadata<ImmutableRow, Long> metadata = new EntityMetadata<>() {
            public String entityName() { return "ImmutableRow"; }
            public String tableName() { return "immutable_rows"; }
            public String redisNamespace() { return "immutable_rows"; }
            public String idColumn() { return "id"; }
            public Class<ImmutableRow> entityType() { return ImmutableRow.class; }
            public Function<ImmutableRow, Long> idAccessor() { return ImmutableRow::id; }
            public List<String> columns() { return List.of("id", "name"); }
            public List<RelationDefinition> relations() { return List.of(); }
            public boolean deeplyImmutable() { return true; }
        };
        var decodes = new AtomicInteger();
        EntityCodec<ImmutableRow> codec = new EntityCodec<>() {
            public String toRedisValue(ImmutableRow row) { return LengthPrefixedPayloadCodec.encode(Map.of("id", row.id().toString(), "name", row.name())); }
            public ImmutableRow fromRedisValue(String value) { decodes.incrementAndGet(); var fields = LengthPrefixedPayloadCodec.decode(value); return new ImmutableRow(Long.valueOf(fields.get("id")), fields.get("name")); }
            public Map<String, Object> toColumns(ImmutableRow row) { return Map.of("id", row.id(), "name", row.name()); }
        };
        try (var fixture = new Fixture(CachePolicy.defaults(), new Source())) {
            var policy = CachePolicy.builder().nearCachePolicy(new NearCachePolicy(32, 10, 65536)).build();
            fixture.database.register(metadata, codec, policy);
            var repository = fixture.database.repository(metadata, codec, policy);
            repository.save(new ImmutableRow(1L, "İstanbul"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            ImmutableRow first = null, second = null;
            while (System.nanoTime() < deadline) {
                first = repository.findById(1L).orElseThrow(); second = repository.findById(1L).orElseThrow();
                if (first == second) break;
                Thread.sleep(5);
            }
            assertSame(first, second);
            int before = decodes.get();
            for (int i = 0; i < 100; i++) assertSame(first, repository.findById(1L).orElseThrow());
            assertEquals(before, decodes.get());
            int reads = fixture.reads.get();
            assertSame(first, repository.findAll(java.util.Arrays.asList(1L, null, 1L)).get(0));
            assertEquals(reads, fixture.reads.get()); assertEquals(before, decodes.get());
            repository.save(new ImmutableRow(2L, "Ankara"));
            var mixed = repository.findAll(List.of(2L, 1L));
            assertEquals(List.of(2L, 1L), mixed.stream().map(ImmutableRow::id).toList());
            assertSame(first, mixed.get(1));
            reads = fixture.reads.get(); before = decodes.get();
            assertEquals(mixed, repository.findAll(List.of(2L, 1L)));
            assertEquals(reads, fixture.reads.get()); assertEquals(before, decodes.get());
        }
    }
    @Test void tenantScopeProtectsIdsMutationsQueriesCountsAndAtomicFunctionBoundary() throws Exception {
        EntityMetadata<TenantRow, Long> metadata = new EntityMetadata<>() {
            public String entityName() { return "TenantRow"; }
            public String tableName() { return "tenant_rows"; }
            public String redisNamespace() { return "tenant_rows"; }
            public String idColumn() { return "id"; }
            public Class<TenantRow> entityType() { return TenantRow.class; }
            public Function<TenantRow, Long> idAccessor() { return TenantRow::id; }
            public List<String> columns() { return List.of("id", "tenant", "status", "amount"); }
            public Map<String, String> columnTypes() { return Map.of("id", "java.lang.Long", "tenant", "java.lang.String", "status", "java.lang.String", "amount", "java.lang.Long"); }
            public String tenantFieldName() { return "tenant"; }
            public String extractTenantId(TenantRow row) { return row.tenant(); }
            public List<RelationDefinition> relations() { return List.of(); }
        };
        EntityCodec<TenantRow> codec = new EntityCodec<>() {
            public String toRedisValue(TenantRow row) { return LengthPrefixedPayloadCodec.encode(Map.of("id", row.id().toString(), "tenant", row.tenant(), "status", row.status(), "amount", Long.toString(row.amount()))); }
            public TenantRow fromRedisValue(String value) { var c = LengthPrefixedPayloadCodec.decode(value); return new TenantRow(Long.valueOf(c.get("id")), c.get("tenant"), c.get("status"), Long.parseLong(c.get("amount"))); }
            public Map<String, Object> toColumns(TenantRow row) { return Map.of("id", row.id(), "tenant", row.tenant(), "status", row.status(), "amount", row.amount()); }
            public TenantRow fromColumns(Map<String,Object> columns) { return new TenantRow((Long) columns.get("id"), (String) columns.get("tenant"), (String) columns.get("status"), ((Number) columns.get("amount")).longValue()); }
        };
        try (var fixture = new Fixture(CachePolicy.defaults(), new Source())) {
            class TenantSource implements EntityByIdLoader<TenantRow, Long>, EntityOwnershipLoader<Long> {
                final AtomicInteger ownershipCalls = new AtomicInteger();
                final Map<Long, String> owners = new ConcurrentHashMap<>();
                public Optional<TenantRow> load(Long id) { return Optional.empty(); }
                public Map<Long, String> loadTenantOwners(Collection<Long> ids) {
                    ownershipCalls.incrementAndGet(); var result = new LinkedHashMap<Long, String>();
                    for (Long id : ids) if (owners.containsKey(id)) result.put(id, owners.get(id));
                    return result;
                }
            }
            var source = new TenantSource();
            var near = new NearCachePolicy(64,10,2 * 1024 * 1024);
            var tenantPolicy = CachePolicy.builder().hotEntityLimit(5000).nearCachePolicy(near).build();
            fixture.database.register(metadata, codec, tenantPolicy, null, null, source, null);
            var repository = fixture.database.repository(metadata, codec, tenantPolicy);
            assertThrows(com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException.class, () -> repository.query(QuerySpec.allOf()));
            assertThrows(IllegalStateException.class, () -> repository.aggregateBy("tenant", AggregateSpec.count(null)));
            assertThrows(IllegalStateException.class, () -> repository.queryPartitions(
                    PartitionedQuerySpec.top("status", List.of("OPEN"), 2, QuerySort.desc("amount"))));
            assertThrows(com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException.class, () -> repository.save(new TenantRow(3L, "a", "OPEN", 1)));
            try (var scope = com.reactor.cachedb.core.tenant.CacheTenantContext.withTenant("a")) { repository.save(new TenantRow(1L, "a", "OPEN", 5)); }
            try (var scope = com.reactor.cachedb.core.tenant.CacheTenantContext.withTenant("b")) { repository.save(new TenantRow(2L, "b", "OPEN", 9)); }
            try (var scope = com.reactor.cachedb.core.tenant.CacheTenantContext.administrative()) {
                assertEquals(2, repository.count(QuerySpec.allOf()));
                assertThrows(IllegalStateException.class, () -> repository.projected(null),
                        "An unscoped projection must not escape a temporary administrative scope");
            }
            try (var scope = com.reactor.cachedb.core.tenant.CacheTenantContext.withTenant("a")) {
                awaitNear(fixture,repository,1L);
                try (var other = com.reactor.cachedb.core.tenant.CacheTenantContext.withTenant("b")) { awaitNear(fixture,repository,2L); }
                int nearReads = fixture.reads.get();
                assertThrows(com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException.class, () -> repository.findAll(List.of(2L)));
                assertEquals(nearReads,fixture.reads.get(),"Tenant mismatch in L1 must fail before a Redis fetch");
                assertEquals(1L, repository.findById(1L).orElseThrow().id());
                assertThrows(com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException.class, () -> repository.findById(2L));
                assertThrows(com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException.class, () -> repository.findAll(List.of(1L, 2L)));
                assertThrows(com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException.class, () -> repository.patchWithReceipt(2L, Map.of("status", "CLOSED")));
                assertThrows(com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException.class, () -> repository.deleteById(2L));
                assertThrows(com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException.class, () -> repository.save(new TenantRow(2L, "b", "CLOSED", 0)));
                assertEquals(List.of(1L), repository.query(QuerySpec.allOf()).stream().map(TenantRow::id).toList());
                assertEquals(1, repository.count(QuerySpec.allOf()));
                assertEquals(new java.math.BigDecimal("5"), repository.aggregate(AggregateSpec.sum("amount", null)).sum());
                assertThrows(com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException.class,
                        () -> repository.query(QuerySpec.where(QueryFilter.eq("tenant", "b"))));
                String before = fixture.redis.get(fixture.keys.versionKey("tenant_rows", 2L));
                var config = fixture.database.config();
                var executor = new RedisFunctionExecutor(fixture.redis, config.redisFunctions(), config.redisGuardrail(), config.writeBehind(), new RedisFunctionArgsMapper());
                var forged = new TenantRow(2L, "a", "CLOSED", 0);
                var operation = new WriteOperation<>(OperationType.UPSERT, metadata, 2L, codec.toColumns(forged), codec.toRedisValue(forged), 0, java.time.Instant.now());
                assertThrows(com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException.class, () -> executor.upsert(
                        fixture.keys.entityKey("tenant_rows", 2L), fixture.keys.versionKey("tenant_rows", 2L), fixture.keys.tombstoneKey("tenant_rows", 2L),
                        fixture.keys.writeStreamKey(config.writeBehind().streamKey(), "tenant_rows"),
                        fixture.keys.compactionPayloadKey("tenant_rows", 2L), fixture.keys.compactionPendingKey("tenant_rows", 2L),
                        fixture.keys.compactionStreamKey(config.writeBehind().compactionStreamKey(), "tenant_rows", 2L, config.writeBehind().compactionShardCount()),
                        fixture.keys.compactionStatsKey("tenant_rows"), operation, CachePolicy.defaults(), true));
                assertEquals(before, fixture.redis.get(fixture.keys.versionKey("tenant_rows", 2L)));
                source.owners.put(99L, "b"); // Ownership must include a row hidden by a soft-delete entity loader.
                assertThrows(com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException.class,
                        () -> repository.save(new TenantRow(99L, "a", "OPEN", 1)));
                assertThrows(com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException.class,
                        () -> repository.saveAll(List.of(new TenantRow(4L, "a", "OPEN", 1), new TenantRow(2L, "a", "OPEN", 1))));
                assertFalse(fixture.redis.exists(fixture.keys.entityKey("tenant_rows", 4L)));
                var freshOwnership = fixture.database.repository(metadata,codec,CachePolicy.builder().hotEntityLimit(5001).nearCachePolicy(near).build());
                int ownerGets = fixture.ownerGets.get();
                freshOwnership.patchWithReceipt(1L,Map.of("amount",6L));
                assertEquals(ownerGets,fixture.ownerGets.get());
                assertEquals(4,fixture.lastMget.size(),"Initial PATCH must fold ownership into its state MGET");
                freshOwnership.patchWithReceipt(1L,Map.of("amount",5L));
                assertEquals(ownerGets,fixture.ownerGets.get());
                assertEquals(3,fixture.lastMget.size(),"Verified owner must not need another GET or MGET column");
                int calls = source.ownershipCalls.get();
                var batch = new ArrayList<TenantRow>();
                for (long id = 100; id < 1100; id++) batch.add(new TenantRow(id, "a", "OPEN", 1));
                assertEquals(1000, repository.saveAll(batch).size());
                assertEquals(calls + 2, source.ownershipCalls.get(), "1000 unknown owners must use two bounded source calls, not N+1");
                var partitioned = repository.queryPartitions(PartitionedQuerySpec.top("status", List.of("OPEN"), 2, QuerySort.desc("amount")));
                assertEquals(2, partitioned.get("OPEN").size());
                assertTrue(partitioned.get("OPEN").stream().allMatch(row -> row.tenant().equals("a")));
                assertEquals(1001, repository.aggregateBy("tenant", AggregateSpec.count(null)).get("a").count());
                assertThrows(IllegalStateException.class, () -> repository.aggregateBy("tenant", AggregateSpec.count(QueryFilter.eq("tenant", "b"))));
                assertThrows(IllegalStateException.class, () -> repository.queryPartitions(
                        PartitionedQuerySpec.top("tenant", List.of("b"), 2, QuerySort.desc("amount"))));
                var atomic = fixture.database.inTransaction(tx -> tx.put(new TenantRow(2001L, "a", "OPEN", 3)));
                assertEquals(1, atomic.versions().size());
                assertEquals("a", fixture.redis.get(fixture.keys.versionKey("tenant_rows", 2001L) + ":tenant"));
                String versionBefore = fixture.redis.get(fixture.keys.versionKey("tenant_rows", 1L));
                assertThrows(IllegalStateException.class, () -> fixture.database.inTransaction(tx -> {
                    tx.put(new TenantRow(1L, "a", "CLOSED", 1)); tx.delete(TenantRow.class, 2L);
                }));
                assertEquals(versionBefore, fixture.redis.get(fixture.keys.versionKey("tenant_rows", 1L)));
                assertThrows(IllegalStateException.class, () -> fixture.database.inTransaction(tx -> tx.delete(TenantRow.class, 99999L)));
                String marker = fixture.keys.versionKey("tenant_rows",1L) + ":tenant";
                fixture.beforeEval = () -> fixture.redis.set(marker,"b");
                try {
                    assertThrows(IllegalStateException.class, () -> fixture.database.inTransaction(tx -> {
                        tx.put(new TenantRow(2002L,"a","OPEN",1)); tx.put(new TenantRow(1L,"a","OPEN",5));
                    }));
                    assertFalse(fixture.redis.exists(fixture.keys.entityKey("tenant_rows",2002L)));
                    assertFalse(fixture.redis.exists(fixture.keys.versionKey("tenant_rows",2002L)));
                    assertFalse(fixture.redis.exists(fixture.keys.versionKey("tenant_rows",2002L) + ":tenant"));
                } finally { fixture.beforeEval=null; fixture.redis.set(marker,"a"); }
                var projectionExtractions = new AtomicInteger();
                var projection = com.reactor.cachedb.core.projection.EntityProjection.<TenantRow,TenantRow,Long>of("tenant_summary",
                        new com.reactor.cachedb.core.projection.ProjectionCodec<>() {
                            public String toRedisValue(TenantRow row) { return codec.toRedisValue(row); }
                            public TenantRow fromRedisValue(String raw) { return codec.fromRedisValue(raw); }
                        }, TenantRow::id, metadata.columns(), row -> {
                            projectionExtractions.incrementAndGet();
                            return codec.toColumns(row);
                        }, row -> row);
                var projected = repository.projected(projection);
                assertEquals(1L, projected.findById(1L).orElseThrow().id());
                assertEquals(2001L, projected.findById(2001L).orElseThrow().id());
                assertThrows(IllegalStateException.class, () -> projected.findById(2L));
                // Materialize the foreign projection in a legitimate scope, then check pre-window isolation.
                try (var other = com.reactor.cachedb.core.tenant.CacheTenantContext.withTenant("b")) {
                    assertEquals(2L, projected.findById(2L).orElseThrow().id());
                }
                var foreignCandidates = java.util.stream.IntStream.range(0,10001).mapToObj(i -> "foreign" + i).toArray(String[]::new);
                fixture.redis.sadd(fixture.keys.indexAllKey("tenant_rows:projection:tenant_summary"),foreignCandidates);
                var ownPage = projected.query(QuerySpec.allOf().orderBy(QuerySort.desc("amount")).limitTo(1));
                assertEquals(1, ownPage.size()); assertTrue(ownPage.stream().allMatch(row -> row.tenant().equals("a")));
                projectionExtractions.set(0);
                assertEquals(List.of(1L,2001L), projected.query(QuerySpec.allOf().orderBy(QuerySort.desc("amount")).limitTo(10))
                        .stream().map(TenantRow::id).toList());
                assertEquals(2, projectionExtractions.get(), "Tenant projection columns must be extracted once per row, not per sort comparison");
            }
        }
    }
    static final class Row {
        final Long id; String name; final long score;
        Row(Long id, String name, long score) { this.id = id; this.name = name; this.score = score; }
    }
    static final EntityMetadata<Row, Long> METADATA = new EntityMetadata<>() {
        public String entityName() { return "EvolutionRow"; }
        public String tableName() { return "evolution_rows"; }
        public String redisNamespace() { return "evolution"; }
        public String idColumn() { return "id"; }
        public Class<Row> entityType() { return Row.class; }
        public Function<Row, Long> idAccessor() { return row -> row.id; }
        public List<String> columns() { return List.of("id", "name", "score"); }
        public Set<String> indexedColumns() { return Set.of("id", "score"); }
        public Map<String, String> columnTypes() { return Map.of("id", "java.lang.Long", "name", "java.lang.String", "score", "java.lang.Long"); }
        public List<RelationDefinition> relations() { return List.of(); }
    };
    static final class Codec implements EntityCodec<Row> {
        final AtomicInteger extractions = new AtomicInteger();
        public String toRedisValue(Row row) { return LengthPrefixedPayloadCodec.encode(Map.of("id", row.id.toString(), "name", row.name, "score", Long.toString(row.score))); }
        public Row fromRedisValue(String value) { var columns = LengthPrefixedPayloadCodec.decode(value); return new Row(Long.valueOf(columns.get("id")), columns.get("name"), Long.parseLong(columns.get("score"))); }
        public Map<String, Object> toColumns(Row row) { extractions.incrementAndGet(); return Map.of("id", row.id, "name", row.name, "score", row.score); }
        public Row fromColumns(Map<String, Object> values) { return new Row(Long.valueOf(values.get("id").toString()), (String) values.get("name"), Long.parseLong(values.get("score").toString())); }
        public boolean directColumnAccess() { return true; }
        public Object entityColumnValue(Row row, String column) { return switch (column) { case "id" -> row.id; case "name" -> row.name; case "score" -> row.score; default -> null; }; }
    }
    static class Source implements VersionedEntitySourceLoader<Row, Long> {
        final AtomicInteger calls = new AtomicInteger();
        public Optional<VersionedEntity<Row>> loadVersionedById(Long id) {
            calls.incrementAndGet();
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return Optional.of(new VersionedEntity<>(new Row(id, "Şehir", 7), 1));
        }
        public List<VersionedEntity<Row>> loadVersionedPage(PageWindow window) { return List.of(); }
        public List<VersionedEntity<Row>> loadVersionedQuery(QuerySpec query) { return List.of(); }
    }
    static final class Fixture implements AutoCloseable {
        final AtomicInteger ownerGets = new AtomicInteger();
        final JedisPooled redis = new JedisPooled("redis://" + System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1") + ":" + Integer.getInteger("cachedb.snapshot.redis.port", 16383)) {
            @Override public String get(String key) { if (key.endsWith(":tenant")) ownerGets.incrementAndGet(); return super.get(key); }
        };
        final Codec codec = new Codec();
        final String prefix;
        final RedisKeyStrategy keys;
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger orderedReads = new AtomicInteger();
        volatile CountDownLatch refreshGate;
        volatile List<String> lastMget = List.of();
        volatile Runnable beforeEval;
        final CacheDatabase database;
        final EntityRepository<Row, Long> repository;
        Fixture(CachePolicy policy, Source source) {
            this(policy, source, "evolution-" + UUID.randomUUID());
        }
        Fixture(CachePolicy policy, Source source, String prefix) {
            this.prefix = prefix; this.keys = new RedisKeyStrategy(prefix);
            var jdbc = new JdbcDataSource(); jdbc.setURL("jdbc:h2:mem:" + prefix);
            RedisClientOperations client = new RedisClientOperations() {
                public redis.clients.jedis.UnifiedJedis commands() { return redis; }
                public List<String> mget(String... keys) {
                    lastMget = List.of(keys);
                    if (Thread.currentThread().getName().equals("cachedb-near-refresh") && refreshGate != null) {
                        try { if (!refreshGate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Refresh test gate timed out"); }
                        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IllegalStateException(stopped); }
                    }
                    reads.incrementAndGet(); return redis.mget(keys);
                }
                public Object eval(String script,List<String> keys,List<String> args) {
                    Runnable callback=beforeEval; beforeEval=null; if (callback!=null) callback.run();
                    return redis.eval(script,keys,args);
                }
                public Optional<List<OrderedPayload>> orderedPayloadWindow(String index, String tomb, String entities,
                        boolean reverse, int offset, int limit) {
                    var result = fetchOrderedPayloadWindow(index, tomb, entities, reverse, offset, limit);
                    if (result.isPresent()) orderedReads.incrementAndGet();
                    return result;
                }
                public void close() { }
            };
            database = new CacheDatabase(client, jdbc, CacheDatabaseConfig.builder()
                    .runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                    .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                    .writeBehind(WriteBehindConfig.builder().streamKey(prefix + ":writes")
                            .deadLetterStreamKey(prefix + ":deadletters").consumerGroup(prefix).build())
                    .readThrough(ReadThroughConfig.builder().mode(ReadThroughMode.READ_THROUGH_BY_ID).build()).build());
            database.register(METADATA, codec, policy, null, source, source, source);
            repository = database.repository(METADATA, codec, policy);
            database.start();
        }
        public void close() { database.close(); redis.close(); }
    }

    @Test void sixtyFourRepositoryMissesUseOneSourceLoadAndNeverShareMutableEntities() throws Exception {
        var source = new Source();
        try (var fixture = new Fixture(CachePolicy.defaults(), source)) {
            var executor = Executors.newFixedThreadPool(64); var start = new CyclicBarrier(64);
            try {
                var futures = new ArrayList<Future<Row>>();
                for (int i = 0; i < 64; i++) futures.add(executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return fixture.repository.findById(11L).orElseThrow(); }));
                var identities = Collections.newSetFromMap(new IdentityHashMap<Row, Boolean>());
                for (var future : futures) { Row row = future.get(15, TimeUnit.SECONDS); assertEquals("Şehir", row.name); identities.add(row); }
                assertEquals(1, source.calls.get()); assertEquals(64, identities.size());
                identities.iterator().next().name = "mutated";
                assertEquals("Şehir", fixture.repository.findById(11L).orElseThrow().name);
            } finally { executor.shutdownNow(); }
        }
    }

    @Test void orderedQueryFetchesOnceAndSeedsL1WithoutSeparatePayloadReads() throws Exception {
        var policy = CachePolicy.builder().nearCachePolicy(new NearCachePolicy(32, 5, 65536)).build();
        try (var fixture = new Fixture(policy, new Source())) {
            fixture.repository.saveAll(List.of(new Row(1L, "a", 1), new Row(2L, "b", 2)));
            var query = QuerySpec.builder().sort(QuerySort.desc("score")).limit(2).build();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean seeded = false;
            while (!seeded && System.nanoTime() < deadline) {
                int reads = fixture.reads.get(); int windows = fixture.orderedReads.get();
                assertEquals(List.of(2L, 1L), fixture.repository.query(query).stream().map(row -> row.id).toList());
                assertEquals(reads, fixture.reads.get());
                assertEquals(windows + 1, fixture.orderedReads.get());
                fixture.repository.findById(2L).orElseThrow();
                seeded = reads == fixture.reads.get();
                if (!seeded) Thread.sleep(5);
            }
            assertTrue(seeded, "Ordered data/TTL response must seed a connected L1 without MGET");
        }
    }

    @Test void legacySchemaBodiesUpcastAcrossPointBulkAndQueryWithoutRewritingOnRead() {
        EntityMetadata<Row, Long> metadata = new EntityMetadata<>() {
            public String entityName() { return "UpcastRow"; }
            public String tableName() { return "evolution_rows"; }
            public String redisNamespace() { return "upcast_rows"; }
            public String idColumn() { return "id"; }
            public Class<Row> entityType() { return Row.class; }
            public Function<Row, Long> idAccessor() { return row -> row.id; }
            public List<String> columns() { return METADATA.columns(); }
            public Map<String, String> columnTypes() { return METADATA.columnTypes(); }
            public List<RelationDefinition> relations() { return List.of(); }
            public int schemaVersion() { return 2; }
            public com.reactor.cachedb.core.codec.CachePayloadUpcaster payloadUpcaster() {
                return new com.reactor.cachedb.core.codec.CachePayloadUpcaster() {
                    public int targetVersion() { return 2; }
                    public String upcast(String body, int from, int to) {
                        assertEquals(1, from); assertEquals(2, to);
                        var values = LengthPrefixedPayloadCodec.decode(body);
                        values.put("name", values.remove("old_name"));
                        return LengthPrefixedPayloadCodec.encode(values);
                    }
                };
            }
        };
        try (var fixture = new Fixture(CachePolicy.defaults(), new Source())) {
            fixture.database.register(metadata, fixture.codec, CachePolicy.defaults());
            var repository = fixture.database.repository(metadata, fixture.codec);
            repository.save(new Row(1L, "İstanbul", 7));
            String key = fixture.keys.entityKey("upcast_rows", 1L);
            String legacy = LengthPrefixedPayloadCodec.encode(Map.of("id", "1", "old_name", "İstanbul", "score", "7"));
            fixture.redis.set(key, legacy);
            assertEquals("İstanbul", repository.findById(1L).orElseThrow().name);
            assertEquals("İstanbul", repository.findAll(List.of(1L)).get(0).name);
            assertEquals("İstanbul", repository.query(QuerySpec.where(QueryFilter.eq("score", 7L))).get(0).name);
            assertEquals(legacy, fixture.redis.get(key));
            repository.save(new Row(1L, "İzmir", 8));
            assertTrue(fixture.redis.get(key).startsWith("~v2:"));
        }
    }

    @Test void numericDeltaRejectsBoundsWithoutChangingPayloadVersionOrQueue() {
        try (var fixture = new Fixture(CachePolicy.defaults(), new Source())) {
            fixture.repository.save(new Row(1L, "stok", 10));
            var receipt = fixture.repository.patchWithReceipt(1L, Map.of("score", new PatchDeltaSpec("score", "score", -3L, 0, 100)));
            assertEquals(7, receipt.entity().score);
            String payload = fixture.redis.get(fixture.keys.entityKey("evolution", 1L));
            String version = fixture.redis.get(fixture.keys.versionKey("evolution", 1L));
            long queued = fixture.redis.xlen(fixture.keys.compactionStreamKey(fixture.database.config().writeBehind().compactionStreamKey(), "evolution", 1L, fixture.database.config().writeBehind().compactionShardCount()));
            assertThrows(com.reactor.cachedb.core.exception.CacheDeltaBoundsException.class, () ->
                    fixture.repository.patchWithReceipt(1L, Map.of("score", new PatchDeltaSpec("score", "score", -8L, 0, 100))));
            assertEquals(payload, fixture.redis.get(fixture.keys.entityKey("evolution", 1L)));
            assertEquals(version, fixture.redis.get(fixture.keys.versionKey("evolution", 1L)));
            assertEquals(queued, fixture.redis.xlen(fixture.keys.compactionStreamKey(fixture.database.config().writeBehind().compactionStreamKey(), "evolution", 1L, fixture.database.config().writeBehind().compactionShardCount())));
            assertEquals("stok", fixture.repository.findById(1L).orElseThrow().name);
        }
    }

    @Test void concurrentNumericDeltasNeverLoseAnAcceptedChange() throws Exception {
        try (var fixture = new Fixture(CachePolicy.defaults(), new Source())) {
            ((RedisEntityRepository<Row, Long>) fixture.repository).withMutationConfig(new MutationConfig(16, true));
            fixture.repository.save(new Row(1L, "stock", 100));
            var pool = Executors.newFixedThreadPool(4);
            try {
                var start = new CyclicBarrier(4); var jobs = new ArrayList<Future<?>>();
                for (int worker = 0; worker < 4; worker++) jobs.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    for (int i = 0; i < 8; i++) {
                        boolean accepted = false;
                        for (int retry = 0; retry < 20 && !accepted; retry++) try {
                            fixture.repository.patchWithReceipt(1L, Map.of("score", new PatchDeltaSpec("score", "score", -1L, 0, 100)));
                            accepted = true;
                        } catch (OptimisticWriteConflictException conflict) { Thread.yield(); }
                        assertTrue(accepted, "Bounded contention test exceeded caller retry budget");
                    }
                    return null;
                }));
                for (var job : jobs) job.get(30, TimeUnit.SECONDS);
                assertEquals(68, fixture.repository.findById(1L).orElseThrow().score);
                assertEquals(1, fixture.repository.query(QuerySpec.where(QueryFilter.eq("score", 68L))).size());
            } finally { pool.shutdownNow(); }
        }
    }

    @Test void indexedAggregateNeverLoadsEntityPayloadAndFallbackRemainsBoundedAndCorrect() {
        try (var fixture = new Fixture(CachePolicy.defaults(), new Source())) {
            fixture.repository.saveAll(List.of(new Row(1L, "a", 7), new Row(2L, "b", -2), new Row(3L, "c", 7)));
            int reads = fixture.reads.get();
            assertEquals(2, fixture.repository.count(QuerySpec.where(QueryFilter.eq("score", 7L))));
            var result = fixture.repository.aggregate(AggregateSpec.sum("score", null));
            assertEquals(new java.math.BigDecimal("12"), result.sum());
            assertEquals(3, result.count());
            var facets = fixture.repository.aggregateBy("score", AggregateSpec.count(null));
            assertEquals(List.of("-2", "7"), List.copyOf(facets.keySet()));
            assertEquals(1, facets.get("-2").count());
            assertEquals(2, facets.get("7").count());
            var filtered = fixture.repository.aggregateBy("score", AggregateSpec.sum("score", QueryFilter.eq("score", 7L)));
            assertEquals(Set.of("7"), filtered.keySet());
            assertEquals(new java.math.BigDecimal("14"), filtered.get("7").sum());
            assertThrows(IllegalArgumentException.class, () -> fixture.repository.aggregateBy("name", AggregateSpec.count(null)));
            assertEquals(reads, fixture.reads.get(), "Index-only aggregates must not call entity MGET");
            assertEquals(1, fixture.repository.count(QuerySpec.where(QueryFilter.eq("name", "a"))));
            fixture.repository.patchWithReceipt(1L, Map.of("score", 8L));
            assertEquals(new java.math.BigDecimal("13"), fixture.repository.aggregate(AggregateSpec.sum("score", null)).sum());
            fixture.repository.deleteById(2L);
            assertEquals(2, fixture.repository.count(QuerySpec.allOf()));
        }
    }

    @Test void compressedEntitiesRoundTripAcrossPointBulkPageAndQueryReads() {
        String text = "İstanbul ürün açıklaması ".repeat(500);
        Source source = new Source() {
            public List<VersionedEntity<Row>> loadVersionedPage(PageWindow window) {
                return List.of(new VersionedEntity<>(new Row(1L, text, 7), 1));
            }
        };
        try (var fixture = new Fixture(CachePolicy.builder().compressionThresholdBytes(1024).build(), source)) {
            fixture.repository.save(new Row(1L, text, 7));
            assertTrue(fixture.redis.get(fixture.keys.entityKey("evolution", 1L)).startsWith("~Z:"));
            assertEquals(text, fixture.repository.findById(1L).orElseThrow().name);
            assertEquals(text, fixture.repository.findAll(List.of(1L)).get(0).name);
            assertEquals(text, fixture.repository.findPage(new PageWindow(0, 10)).get(0).name);
            assertEquals(text, fixture.repository.query(QuerySpec.where(QueryFilter.eq("score", 7L))).get(0).name);
            fixture.repository.patchWithReceipt(1L, Map.of("score", 8L));
            assertEquals(text, fixture.repository.findById(1L).orElseThrow().name);
        }
    }

    @Test void watcherTransitionsHaveDefensivePayloadsAndCloseUnregisters() throws Exception {
        try (var fixture = new Fixture(CachePolicy.defaults(), new Source())) {
            var events = new LinkedBlockingQueue<com.reactor.cachedb.core.watch.CacheChangeEvent<Row, Long>>();
            var registration = fixture.repository.registerWatcher(QuerySpec.where(QueryFilter.eq("name", "HIGH_RISK")), events::add);
            fixture.repository.save(new Row(1L, "NORMAL", 7));
            assertNull(events.poll(30, TimeUnit.MILLISECONDS));
            fixture.repository.patchWithReceipt(1L, Map.of("name", "HIGH_RISK"));
            assertEquals(com.reactor.cachedb.core.watch.CacheChangeType.ENTERED, events.poll(5, TimeUnit.SECONDS).changeType());
            fixture.repository.patchWithReceipt(1L, Map.of("score", 8L));
            var update = events.poll(5, TimeUnit.SECONDS);
            assertEquals(com.reactor.cachedb.core.watch.CacheChangeType.UPDATED, update.changeType());
            update.currentEntity().name = "changed by listener";
            assertEquals("HIGH_RISK", fixture.repository.findById(1L).orElseThrow().name);
            fixture.repository.patchWithReceipt(1L, Map.of("name", "CLOSED"));
            assertEquals(com.reactor.cachedb.core.watch.CacheChangeType.EXITED, events.poll(5, TimeUnit.SECONDS).changeType());
            registration.close(); assertFalse(registration.isActive());
            fixture.repository.patchWithReceipt(1L, Map.of("name", "HIGH_RISK"));
            assertNull(events.poll(30, TimeUnit.MILLISECONDS));
        }
    }

    @Test void sparseBatchExtractsOnceAndResidualQueriesDoNotMaterializeMaps() {
        try (var fixture = new Fixture(CachePolicy.defaults(), new Source())) {
            var rows = new ArrayList<Row>(); for (long id = 1; id <= 128; id++) rows.add(new Row(id, "name" + id, id));
            fixture.repository.saveAll(rows);
            assertEquals(128, fixture.codec.extractions.get());
            assertEquals(Set.of("id", "score"), fixture.redis.hkeys(fixture.keys.indexMetaKey("evolution", 1L)));
            fixture.codec.extractions.set(0);
            assertEquals(1, fixture.repository.query(QuerySpec.builder().filter(QueryFilter.eq("name", "name7")).limit(10).build()).size());
            assertEquals(0, fixture.codec.extractions.get());
        }
    }

    @Test void patchQueuesOnlyDeltaPreservesUnicodeAndUpdatesAffectedIndexes() {
        try (var fixture = new Fixture(CachePolicy.defaults(), new Source())) {
            fixture.repository.save(new Row(1L, "Şehir", 7));
            var receipt = fixture.repository.patchWithReceipt(1L, Map.of("score", 9L));
            assertEquals(OperationType.PATCH, receipt.operationType());
            Row row = fixture.repository.findById(1L).orElseThrow();
            assertEquals("Şehir", row.name); assertEquals(9L, row.score);
            assertEquals(9d, fixture.redis.zscore(fixture.keys.indexSortKey("evolution", "score"), "1"));
            fixture.repository.deleteById(1L);
            assertThrows(IllegalStateException.class, () -> fixture.repository.patchWithReceipt(1L, Map.of("score", 10L)));
        }
    }

    @Test void unitOfWorkDiscardsBeforeCommitAndRejectsPrematureReceipts() {
        try (var fixture = new Fixture(CachePolicy.defaults(), new Source())) {
            assertThrows(IllegalArgumentException.class, () -> fixture.database.inTransaction(tx -> { tx.put(new Row(1L, "a", 1)); throw new IllegalArgumentException("abort"); }));
            assertFalse(fixture.redis.exists(fixture.keys.entityKey("evolution", 1L)));
            assertThrows(IllegalStateException.class, () -> fixture.database.inTransaction(tx -> fixture.repository.saveWithReceipt(new Row(2L, "b", 2))));
            assertFalse(fixture.redis.exists(fixture.keys.entityKey("evolution", 2L)));
            var receipt = fixture.database.inTransaction(tx -> { fixture.repository.save(new Row(1L, "a", 1)); tx.put(new Row(2L, "b", 2)); });
            assertEquals(List.of(1L, 1L), receipt.versions());
            assertTrue(fixture.redis.exists(fixture.keys.entityKey("evolution", 1L)));
            assertTrue(fixture.redis.exists(fixture.keys.entityKey("evolution", 2L)));
        }
    }

    @Test void batchReplayIdentityDoesNotDependOnTraceContext() {
        try (var fixture = new Fixture(CachePolicy.defaults(), new Source())) {
            CacheWriteBatchReceipt first;
            try (var scope = com.reactor.cachedb.core.trace.TraceContexts.withMetadata(Map.of("correlationId", "first"))) {
                first = fixture.database.writeBatch("retry-same-business-command").put(new Row(1L, "a", 1)).commit();
            }
            try (var scope = com.reactor.cachedb.core.trace.TraceContexts.withMetadata(Map.of("correlationId", "retry"))) {
                assertEquals(first, fixture.database.writeBatch("retry-same-business-command").put(new Row(1L, "a", 1)).commit());
            }
        }
    }

    @Test void nearHitsAvoidRedisAndAWriteInvalidatesAnotherRuntimeBeforeTtlExpiry() throws Exception {
        var policy = CachePolicy.builder().nearCachePolicy(new NearCachePolicy(32, 10, 65536)).build();
        String prefix = "near-runtimes-" + UUID.randomUUID();
        try (var first = new Fixture(policy, new Source(), prefix); var second = new Fixture(policy, new Source(), prefix)) {
            first.repository.save(new Row(1L, "Şehir", 7));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean hit = false;
            while (!hit && System.nanoTime() < deadline) {
                second.repository.findById(1L).orElseThrow();
                int before = second.reads.get();
                Row copy = second.repository.findById(1L).orElseThrow(); copy.name = "local mutation";
                hit = second.reads.get() == before;
                if (!hit) Thread.sleep(5);
            }
            assertTrue(hit, "Subscriber did not become ready for a true zero-Redis L1 hit");
            assertEquals("Şehir", second.repository.findById(1L).orElseThrow().name);
            first.repository.patchWithReceipt(1L, Map.of("name", "İzmir"));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!"İzmir".equals(second.repository.findById(1L).orElseThrow().name) && System.nanoTime() < deadline) Thread.sleep(2);
            assertEquals("İzmir", second.repository.findById(1L).orElseThrow().name);
            first.repository.deleteById(1L);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (second.repository.findById(1L).isPresent() && System.nanoTime() < deadline) Thread.sleep(2);
            assertTrue(second.repository.findById(1L).isEmpty());
        }
    }

    @Test void causalTokenBypassesStaleAndUnknownVersionsThenUsesVerifiedL1() throws Exception {
        var policy = CachePolicy.builder().nearCachePolicy(new NearCachePolicy(32, 10, 65536)).build();
        try (var fixture = new Fixture(policy, new Source())) {
            fixture.repository.save(new Row(1L, "old", 1));
            awaitNear(fixture, 1L);
            fixture.redis.mset(fixture.keys.entityKey("evolution", 1L), fixture.codec.toRedisValue(new Row(1L, "new", 2)),
                    fixture.keys.versionKey("evolution", 1L), "2");
            assertEquals("old", fixture.repository.findById(1L).orElseThrow().name);
            String token;
            try (var scope = com.reactor.cachedb.core.causal.CacheCausalContext.withMinVersion("evolution", 1L, 2)) {
                int before = fixture.reads.get();
                assertEquals("new", fixture.repository.findById(1L).orElseThrow().name);
                assertEquals(before + 1, fixture.reads.get());
                before = fixture.reads.get();
                assertEquals("new", fixture.repository.findAll(List.of(1L)).get(0).name);
                assertEquals(before, fixture.reads.get()); token = com.reactor.cachedb.core.causal.CacheCausalContext.encodeToken();
            }
            try (var scope = com.reactor.cachedb.core.causal.CacheCausalContext.withToken(token)) {
                assertEquals("new", fixture.repository.findHotById(1L).optional().orElseThrow().name);
            }
            try (var scope = com.reactor.cachedb.core.causal.CacheCausalContext.withMinVersion("evolution", 1L, 3)) {
                assertThrows(IllegalStateException.class, () -> fixture.repository.findById(1L));
            }
            fixture.redis.del(fixture.keys.entityKey("evolution",1L));
            fixture.redis.set(fixture.keys.versionKey("evolution",1L),"3");
            try (var scope = com.reactor.cachedb.core.causal.CacheCausalContext.withMinVersion("evolution",1L,3)) {
                assertThrows(IllegalStateException.class,() -> fixture.repository.findById(1L),"An unavailable causal payload must not fall back to older SQL data");
            }
        }
    }
    @Test void softExpiredReadReturnsBeforeRedisRefreshAndLaterObservesUpdatedPayload() throws Exception {
        var policy = CachePolicy.builder().nearCachePolicy(new NearCachePolicy(32, 4, 65536, 1)).build();
        try (var fixture = new Fixture(policy, new Source())) {
            fixture.repository.save(new Row(1L, "old", 1)); awaitNear(fixture, 1L);
            fixture.refreshGate = new CountDownLatch(1);
            fixture.redis.mset(fixture.keys.entityKey("evolution", 1L), fixture.codec.toRedisValue(new Row(1L, "fresh", 2)),
                    fixture.keys.versionKey("evolution", 1L), "2");
            Thread.sleep(1050);
            try { assertEquals("old", fixture.repository.findById(1L).orElseThrow().name); }
            finally { fixture.refreshGate.countDown(); }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!fixture.repository.findById(1L).orElseThrow().name.equals("fresh") && System.nanoTime() < deadline) Thread.sleep(2);
            assertEquals("fresh", fixture.repository.findById(1L).orElseThrow().name);
        }
    }
    private static void awaitNear(Fixture fixture, Long id) throws Exception {
        awaitNear(fixture,fixture.repository,id);
    }
    private static <T> void awaitNear(Fixture fixture,EntityRepository<T,Long> repository,Long id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            repository.findById(id).orElseThrow(); int reads = fixture.reads.get();
            repository.findById(id).orElseThrow();
            if (reads == fixture.reads.get()) return;
            Thread.sleep(2);
        }
        fail("Near cache did not become available");
    }

    @Test void fiveHundredColumnsStoreExactlyThreeIndexMetadataFieldsAndExtractOnce() {
        var extractions = new AtomicInteger();
        var names = new ArrayList<String>(); names.add("id"); for (int i = 1; i < 500; i++) names.add("c" + i);
        EntityMetadata<Map<String, Object>, Long> metadata = new EntityMetadata<>() {
            public String entityName() { return "WideRow"; }
            public String tableName() { return "wide_rows"; }
            public String redisNamespace() { return "wide"; }
            public String idColumn() { return "id"; }
            @SuppressWarnings("unchecked") public Class<Map<String, Object>> entityType() { return (Class<Map<String, Object>>) (Class<?>) Map.class; }
            public Function<Map<String, Object>, Long> idAccessor() { return row -> (Long) row.get("id"); }
            public List<String> columns() { return names; }
            public Set<String> indexedColumns() { return Set.of("id", "c1", "c2"); }
            public List<RelationDefinition> relations() { return List.of(); }
        };
        EntityCodec<Map<String, Object>> codec = new EntityCodec<>() {
            public Map<String, Object> toColumns(Map<String, Object> row) { extractions.incrementAndGet(); return row; }
            public String toRedisValue(Map<String, Object> row) { var strings = new LinkedHashMap<String, String>(); row.forEach((key, value) -> strings.put(key, value.toString())); return LengthPrefixedPayloadCodec.encode(strings); }
            public Map<String, Object> fromRedisValue(String value) { return new LinkedHashMap<>(LengthPrefixedPayloadCodec.decode(value)); }
        };
        String prefix = "wide-" + UUID.randomUUID();
        var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:" + prefix);
        try (var redis = new JedisPooled("redis://" + System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1") + ":" + Integer.getInteger("cachedb.snapshot.redis.port", 16383));
                var database = new CacheDatabase(redis, source, CacheDatabaseConfig.builder()
                    .runtimeRole(CacheRuntimeRole.CLIENT_ONLY).keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                    .writeBehind(WriteBehindConfig.builder().streamKey(prefix + ":writes").deadLetterStreamKey(prefix + ":dlq").build())
                    .resourceLimits(ResourceLimits.builder().maxColumnsPerOperation(512).build()).build())) {
            var repository = database.repository(metadata, codec); database.start();
            var rows = new ArrayList<Map<String, Object>>();
            for (long id = 1; id <= 8; id++) { var row = new LinkedHashMap<String, Object>(); row.put("id", id); for (int i = 1; i < 500; i++) row.put("c" + i, "value" + i); rows.add(row); }
            repository.saveAll(rows);
            assertEquals(8, extractions.get());
            for (long id = 1; id <= 8; id++) assertEquals(Set.of("id", "c1", "c2"), redis.hkeys(new RedisKeyStrategy(prefix).indexMetaKey("wide", id)));
        }
    }

    @Test void missingPublishPermissionRejectsBeforeAnyEntityVersionOrStreamMutation() {
        var policy = CachePolicy.builder().nearCachePolicy(new NearCachePolicy(32, 2, 65536)).build();
        try (var fixture = new Fixture(CachePolicy.defaults(), new Source())) {
            String user = "near-denied-" + UUID.randomUUID();
            fixture.redis.sendCommand(redis.clients.jedis.Protocol.Command.ACL, "SETUSER", user, "on", ">fixture-only", "~*", "&*", "+@all", "-publish");
            try (var denied = new JedisPooled(new redis.clients.jedis.HostAndPort(
                    System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1"), Integer.getInteger("cachedb.snapshot.redis.port", 16383)),
                    redis.clients.jedis.DefaultJedisClientConfig.builder().user(user).password("fixture-only").build())) {
                var functions = new RedisFunctionExecutor(denied, RedisFunctionsConfig.defaults(), RedisGuardrailConfig.defaults(),
                        new RedisFunctionArgsMapper().withNearInvalidationChannel(fixture.keys.nearInvalidationChannel()));
                var row = new Row(90L, "safe", 1);
                var operation = new WriteOperation<>(OperationType.UPSERT, METADATA, row.id,
                        fixture.codec.toColumns(row), fixture.codec.toRedisValue(row), 0, java.time.Instant.now());
                var failure = assertThrows(RuntimeException.class, () -> functions.upsert(
                        fixture.keys.entityKey("evolution", row.id), fixture.keys.versionKey("evolution", row.id),
                        fixture.keys.tombstoneKey("evolution", row.id), fixture.prefix + ":denied-stream",
                        fixture.prefix + ":compact-payload", fixture.prefix + ":compact-pending", fixture.prefix + ":compact-stream",
                        fixture.prefix + ":compact-stats", operation, policy, true));
                assertTrue(failure.getMessage().contains("PUBLISH"));
                assertFalse(fixture.redis.exists(fixture.keys.entityKey("evolution", row.id)));
                assertFalse(fixture.redis.exists(fixture.keys.versionKey("evolution", row.id)));
                assertFalse(fixture.redis.exists(fixture.prefix + ":denied-stream"));
            } finally { fixture.redis.sendCommand(redis.clients.jedis.Protocol.Command.ACL, "DELUSER", user); }
        }
    }
}
