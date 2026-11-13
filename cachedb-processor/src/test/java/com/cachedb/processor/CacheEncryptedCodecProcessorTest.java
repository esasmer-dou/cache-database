package com.reactor.cachedb.processor;

import static com.reactor.cachedb.processor.EncryptionCompilerFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;
import com.reactor.cachedb.core.codec.PayloadEntityCodec;
import com.reactor.cachedb.core.crypto.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.redis.RedisFunctionArgsMapper;
import com.reactor.cachedb.redis.RedisWriteOperationMapper;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.RedisGuardrailConfig;
import com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheEncryptedCodecProcessorTest {
    @TempDir Path temp;

    private Loaded fixture() throws Exception { return load(compile(temp, recordSources(true))); }

    @Test void separateLogicalSqlRedisAndIndexRepresentationsRoundTripRecords() throws Exception {
        try (var fixture = fixture()) {
            var codec = fixture.codec();
            assertTrue(codec.encryptionBound());
            assertFalse(fixture.unbound().encryptionBound());
            var logical = codec.toColumns(fixture.entity());
            assertEquals(SECRET, logical.get("contact"));
            assertEquals(new BigDecimal("98765.4321"), logical.get("amount"));
            assertEquals(fixture.entity(), codec.fromLogicalColumns(logical));
            var source = codec.toSourceColumns(fixture.entity());
            assertEquals(SECRET, source.get("contact"));
            assertTrue(((String) source.get("amount")).startsWith("~E1:k1:"));
            assertEquals(fixture.entity(), codec.fromColumns(source));
            String wire = codec.toRedisValue(fixture.entity());
            absent(wire);
            var fields = LengthPrefixedPayloadCodec.decode(wire);
            for (String column : List.of("contact", "amount", "hidden")) assertTrue(fields.get(column).startsWith("~E1:k1:"));
            assertEquals(fixture.entity(), codec.fromRedisValue(wire));
            var index = codec.toIndexColumns(fixture.entity());
            absent(index);
            assertFalse(index.containsKey("hidden"));
            assertEquals(index.get("contact"), codec.indexQueryValue("contact", SECRET));
            assertEquals(index.get("amount"), codec.indexQueryValue("amount", new BigDecimal("98765.4321")));
            assertEquals("OPEN", codec.indexQueryValue("status", "OPEN"));
            failClosed(() -> codec.indexQueryValue("hidden", HIDDEN));
            failClosed(() -> codec.indexQueryValue("amount", "98765.4321"));
            failClosed(() -> codec.indexQueryValue("CONTACT", SECRET));
            failClosed(() -> codec.indexQueryValue("not_persisted", SECRET));
            assertEquals("java.lang.String", fixture.metadata().columnTypes().get("amount"));
            assertTrue(fixture.metadata().encryptedColumns().get("amount").encryptsSource());
            assertFalse(fixture.metadata().encryptedColumns().get("contact").encryptsSource());
            assertThrows(UnsupportedOperationException.class, () -> fixture.metadata().encryptedColumns().clear());
        }
    }

    @Test void everyUnboundStoragePolicyQueueAndIndexEntryPointFailsBeforeAcceptance() throws Exception {
        try (var fixture = fixture()) {
            var codec = fixture.unbound();
            failClosed(() -> codec.bindEncryption(null));
            failClosed(() -> codec.toColumns(fixture.entity()));
            failClosed(() -> codec.toSourceColumns(fixture.entity()));
            failClosed(() -> codec.toRedisValue(fixture.entity()));
            failClosed(() -> codec.fromRedisValue("legacy"));
            failClosed(() -> codec.fromColumns(Map.of()));
            failClosed(() -> codec.fromLogicalColumns(Map.of()));
            failClosed(() -> codec.toQueuedColumns(fixture.entity()));
            failClosed(() -> codec.toQueuedColumns(fixture.entity(), Map.of("contact", SECRET)));
            failClosed(() -> codec.normalizePatchColumns(Map.of("contact", SECRET)));
            failClosed(() -> codec.entityColumnValue(fixture.entity(), "contact"));
            failClosed(() -> codec.toIndexColumns(fixture.entity()));
            failClosed(() -> codec.indexQueryValue("contact", SECRET));
            failClosed(() -> codec.sourceColumnsFromQueued("1", Map.of()));
            assertEquals(3, fixture.metadata().encryptedColumns().size());
        }
    }

    @Test void queueMapperFunctionArgsCompactionAndDlqCopiesContainNoProtectedPlaintext() throws Exception {
        try (var fixture = fixture(); var tenant = CacheTenantContext.withTenant("tenant-a")) {
            var codec = fixture.codec();
            var protectedColumns = codec.toQueuedColumns(fixture.entity());
            absent(protectedColumns);
            var operation = new WriteOperation<>(OperationType.UPSERT, fixture.metadata(), 1L, protectedColumns,
                    codec.toRedisValue(fixture.entity()), 1L, Instant.EPOCH);
            var mapper = new RedisWriteOperationMapper();
            var streamBody = mapper.toBody(operation);
            absent(streamBody);
            absent(new RedisFunctionArgsMapper().upsertArgs(operation, CachePolicy.defaults(), RedisGuardrailConfig.defaults(), true));
            try (var missingTenant = CacheTenantContext.empty()) {
                assertThrows(CacheTenantAccessDeniedException.class, () -> new RedisFunctionArgsMapper().upsertArgs(
                        operation, CachePolicy.defaults(), RedisGuardrailConfig.defaults(), true));
            }
            var decoded = mapper.fromBody(streamBody);
            var compacted = mapper.toBody(decoded);
            var dlq = mapper.toBody(mapper.fromBody(compacted));
            absent(compacted);
            absent(dlq);
            var source = codec.sourceColumnsFromQueued(decoded.id(), decoded.columns());
            assertEquals(SECRET, source.get("contact"));
            assertEquals(HIDDEN, source.get("hidden"));
            assertEquals(decoded.columns().get("amount"), source.get("amount"));
            assertFalse(source.containsKey(EncryptedCodecSupport.QUEUE_FORMAT));
            assertFalse(source.containsKey(EncryptedCodecSupport.QUEUE_TENANT));
            assertEquals(new BigDecimal("98765.4321"), codec.toColumns(codec.fromColumns(new LinkedHashMap<>(source))).get("amount"));
            absent(decoded.columns()); // SQL conversion must not mutate the Redis retry/DLQ operation.
            assertEquals(streamBody, mapper.toBody(decoded));
        }
    }

    @Test void partialPatchQueueAssignmentsEncryptBothScopesAndPreserveTransportNullSentinels() throws Exception {
        try (var fixture = fixture()) {
            var codec = fixture.codec();
            var logical = codec.toColumns(fixture.entity());
            logical.put("contact", "__NULL__");
            logical.put("hidden", null);
            Object updated = codec.fromLogicalColumns(logical);
            Map<String, Object> assignments = new LinkedHashMap<>();
            assignments.put("contact", "__NULL__");
            assignments.put("amount", logical.get("amount"));
            assignments.put("hidden", null);
            var normalized = codec.normalizePatchColumns(assignments);
            assertEquals("__NULL__",normalized.get("contact"));
            assertNull(normalized.get("hidden"));
            var queued = codec.toQueuedColumns(updated, normalized);
            assertFalse(queued.containsKey("id"));
            assertFalse(queued.containsKey("tenant_id"));
            var mapper = new RedisWriteOperationMapper();
            var operation = new WriteOperation<>(OperationType.PATCH, fixture.metadata(), 1L, queued,
                    codec.toRedisValue(updated), 2L, Instant.EPOCH);
            var body = mapper.toBody(operation);
            assertTrue(body.get("col:contact").startsWith("~P:V~E1:"));
            assertEquals("~P:N", body.get("col:hidden"));
            var decoded = mapper.fromBody(body);
            var sql = codec.sourceColumnsFromQueued(decoded.id(), decoded.columns());
            assertEquals("__NULL__", sql.get("contact"));
            assertNull(sql.get("hidden"));
            assertTrue(sql.get("amount").startsWith("~E1:"));
            assertEquals(Set.of("contact", "amount", "hidden"), sql.keySet());
            failClosed(() -> codec.toQueuedColumns(updated, Map.of("contact", "not-the-accepted-value")));
            failClosed(() -> codec.toQueuedColumns(updated, Map.of("amount", new PatchDeltaSpec("amount", "amount", 1, 0, 10))));
        }
    }

    @Test void legacyPlaintextQueueBadProtocolWrongIdTenantAndTamperingNeverReachSql() throws Exception {
        try (var fixture = fixture()) {
            var codec = fixture.codec();
            var mapper = new RedisWriteOperationMapper();
            var operation = new WriteOperation<>(OperationType.UPSERT, fixture.metadata(), 1L,
                    codec.toQueuedColumns(fixture.entity()), codec.toRedisValue(fixture.entity()), 1L, Instant.EPOCH);
            var queued = mapper.fromBody(mapper.toBody(operation)).columns();
            failClosed(() -> codec.sourceColumnsFromQueued("2", queued));
            for (String column : List.of("contact", "amount", "hidden")) {
                var legacy = new LinkedHashMap<>(queued);
                legacy.put(column, SECRET);
                failClosed(() -> codec.sourceColumnsFromQueued("1", legacy));
            }
            var noVersion = new LinkedHashMap<>(queued);
            noVersion.remove(EncryptedCodecSupport.QUEUE_FORMAT);
            failClosed(() -> codec.sourceColumnsFromQueued("1", noVersion));
            var future = new LinkedHashMap<>(queued);
            future.put(EncryptedCodecSupport.QUEUE_FORMAT, "~Q2");
            failClosed(() -> codec.sourceColumnsFromQueued("1", future));
            var swapped = new LinkedHashMap<>(queued);
            swapped.put(EncryptedCodecSupport.QUEUE_TENANT, "~T1:tenant-b");
            swapped.remove("tenant_id");
            failClosed(() -> codec.sourceColumnsFromQueued("1", swapped));
            var tampered = new LinkedHashMap<>(queued);
            String value = tampered.get("contact");
            int start = value.lastIndexOf(':') + 1;
            byte[] packed = Base64.getUrlDecoder().decode(value.substring(start));
            packed[12] ^= 1;
            tampered.put("contact", value.substring(0, start) + Base64.getUrlEncoder().withoutPadding().encodeToString(packed));
            failClosed(() -> codec.sourceColumnsFromQueued("1", tampered));
        }
    }

    @Test void redisSwapsLegacyValuesAndWrongSqlSchemaFailClosed() throws Exception {
        try (var fixture = fixture()) {
            var codec = fixture.codec();
            var fields = LengthPrefixedPayloadCodec.decode(codec.toRedisValue(fixture.entity()));
            fields.put("contact", SECRET);
            var legacy = fields;
            failClosed(() -> codec.fromRedisValue(LengthPrefixedPayloadCodec.encode(legacy)));
            fields = LengthPrefixedPayloadCodec.decode(codec.toRedisValue(fixture.entity()));
            fields.put("tenant_id", "tenant-b");
            var swapped = fields;
            failClosed(() -> codec.fromRedisValue(LengthPrefixedPayloadCodec.encode(swapped)));
            var sql = codec.toSourceColumns(fixture.entity());
            sql.put("amount", 100L);
            failClosed(() -> codec.fromColumns(sql));
            sql.put("amount", "98765.4321");
            failClosed(() -> codec.fromColumns(sql));
        }
    }

    @Test void boundRuntimeInstancesAndRotationDoNotMutateStaticCodecOrHistoricalKeys() throws Exception {
        try (var fixture = fixture()) {
            var old = fixture.unbound().bindEncryption(keys("k1", key("k1", 1)));
            var rotated = fixture.unbound().bindEncryption(keys("k2", key("k1", 1), key("k2", 2)));
            assertNotSame(old, rotated);
            String wire = old.toRedisValue(fixture.entity());
            assertEquals(fixture.entity(), rotated.fromRedisValue(wire));
            assertTrue(LengthPrefixedPayloadCodec.decode(rotated.toRedisValue(fixture.entity())).get("contact").startsWith("~E1:k2:"));
            assertEquals(old.toIndexColumns(fixture.entity()), rotated.toIndexColumns(fixture.entity()));
            var mapper = new RedisWriteOperationMapper();
            var historical = mapper.fromBody(mapper.toBody(new WriteOperation<>(OperationType.UPSERT, fixture.metadata(), 1L,
                    old.toQueuedColumns(fixture.entity()), wire, 1L, Instant.EPOCH)));
            assertEquals(SECRET, rotated.sourceColumnsFromQueued("1", historical.columns()).get("contact"));
            failClosed(() -> fixture.unbound().toRedisValue(fixture.entity()));
        }
    }

    @Test @org.junit.jupiter.api.Timeout(30) void boundCodecsShareNoMutableContextAcrossConcurrentRedisQueueAndIndexOperations() throws Exception {
        try (var fixture = fixture(); var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var old = fixture.unbound().bindEncryption(keys("k1", key("k1", 1)));
            var rotated = fixture.unbound().bindEncryption(keys("k2", key("k1", 1), key("k2", 2)));
            var expectedIndex = old.toIndexColumns(fixture.entity());
            List<java.util.concurrent.Future<String>> pending = new ArrayList<>();
            for (int i = 0; i < 128; i++) {
                var writer = i % 2 == 0 ? old : rotated;
                pending.add(executor.submit(() -> {
                    String wire = writer.toRedisValue(fixture.entity());
                    absent(wire);
                    assertEquals(fixture.entity(), rotated.fromRedisValue(wire));
                    assertEquals(expectedIndex, writer.toIndexColumns(fixture.entity()));
                    var queued = writer.toQueuedColumns(fixture.entity());
                    Map<String, String> decoded = new LinkedHashMap<>();
                    queued.forEach((column, value) -> decoded.put(column, value == null ? null : String.valueOf(value)));
                    var sql = rotated.sourceColumnsFromQueued("1", decoded);
                    assertEquals(SECRET, sql.get("contact"));
                    assertEquals(fixture.entity(), rotated.fromColumns(new LinkedHashMap<>(sql)));
                    absent(decoded);
                    return wire;
                }));
            }
            Set<String> distinct = new HashSet<>();
            for (var operation : pending) assertTrue(distinct.add(operation.get(10, java.util.concurrent.TimeUnit.SECONDS)));
            assertEquals(128, distinct.size());
            failClosed(() -> fixture.unbound().toRedisValue(fixture.entity()));
        }
    }

    @Test void nullEmptyJsonLookingAndMarkerLookingStringsRemainDistinct() throws Exception {
        try (var fixture = fixture()) {
            var codec = fixture.codec();
            for (String value : Arrays.asList(null, "", "null", "__NULL__", "cachedb:__NULL__", "~P:N", "~P:V", "~E1:k1:literal", "{\"nested\":[null,\"text\"]}")) {
                var columns = codec.toColumns(fixture.entity());
                columns.put("contact", value);
                Object row = codec.fromLogicalColumns(columns);
                assertEquals(value, codec.toColumns(codec.fromRedisValue(codec.toRedisValue(row))).get("contact"));
                assertEquals(value, codec.toColumns(codec.fromColumns(codec.toSourceColumns(row))).get("contact"));
                var mapper = new RedisWriteOperationMapper();
                var queued = mapper.fromBody(mapper.toBody(new WriteOperation<>(OperationType.PATCH, fixture.metadata(), 1L,
                        codec.toQueuedColumns(row, codec.normalizePatchColumns(Collections.singletonMap("contact", value))), codec.toRedisValue(row), 1L, Instant.EPOCH)));
                assertEquals(value, codec.sourceColumnsFromQueued("1", queued.columns()).get("contact"));
                assertEquals(codec.toIndexColumns(row).get("contact"), codec.indexQueryValue("contact", value));
            }
        }
    }

    @Test void outerPayloadFramingDelegatesEveryNewHookIncludingQueueAndSourceMapping() throws Exception {
        try (var fixture = fixture()) {
            var framed = PayloadEntityCodec.wrap(fixture.unbound(), fixture.metadata(), 1)
                    .bindEncryption(keys("k1", key("k1", 1)));
            assertTrue(framed.encryptionBound());
            assertFalse(PayloadEntityCodec.wrap(fixture.unbound(), fixture.metadata(), 1).encryptionBound());
            String wire = framed.toRedisValue(fixture.entity());
            assertEquals(fixture.entity(), framed.fromRedisValue(wire));
            assertEquals(fixture.entity(), framed.fromColumns(framed.toSourceColumns(fixture.entity())));
            assertEquals(fixture.entity(), framed.fromLogicalColumns(framed.toColumns(fixture.entity())));
            assertEquals(framed.toIndexColumns(fixture.entity()).get("contact"), framed.indexQueryValue("contact", SECRET));
            var mapper = new RedisWriteOperationMapper();
            var queued = mapper.fromBody(mapper.toBody(new WriteOperation<>(OperationType.UPSERT, fixture.metadata(), 1L,
                    framed.toQueuedColumns(fixture.entity()), wire, 1L, Instant.EPOCH)));
            assertEquals(SECRET, framed.sourceColumnsFromQueued("1", queued.columns()).get("contact"));
            assertEquals(fixture.entity(), SourceMapping.entity(fixture.metadata(), framed).decoder().apply(framed.toSourceColumns(fixture.entity())));
        }
    }

    @Test void providerCausesAndAdminProtectedFieldValuesAreNeverExposed() throws Exception {
        try (var fixture = fixture()) {
            failClosed(() -> fixture.unbound().bindEncryption(new CacheKeyProvider() {
                public KeyMaterial current(String ring) { throw new IllegalStateException(SECRET); }
                public Optional<KeyMaterial> byId(String ring, String id) { throw new IllegalStateException(HIDDEN); }
            }));
            var columns = fixture.codec().toColumns(fixture.entity());
            columns.put("CONTACT", new Object() { public String toString() { throw new AssertionError("Must not stringify protected values"); } });
            var redacted = CacheFieldRedactor.redactColumns(fixture.metadata(), columns);
            assertEquals("[REDACTED]", redacted.get("CONTACT"));
            assertEquals("[REDACTED]", redacted.get("amount"));
            absent(redacted);
        }
    }

    static void absent(Object stored) {
        String text = String.valueOf(stored);
        assertFalse(text.contains(SECRET));
        assertFalse(text.contains(HIDDEN));
        assertFalse(text.contains("98765.4321"));
    }

    @Test void wholeCustomJsonFieldsAndMutableBeansUseExistingCanonicalCodecsWithoutReflection() throws Exception {
        var sources = new LinkedHashMap<String, String>();
        sources.put("sample/Details.java", """
                package sample;
                @com.reactor.cachedb.annotations.CacheJsonRecord public record Details(String value) {}
                """);
        sources.put("sample/DetailsFieldCodec.java", """
                package sample;
                public final class DetailsFieldCodec implements com.reactor.cachedb.core.codec.FieldValueCodec<Details> {
                    public String encode(Details value) {
                        if (value == null) throw new IllegalStateException("null encode must be bypassed");
                        if (value.value().equals("fail")) throw new IllegalStateException("%s");
                        try {
                            var out = new java.io.ByteArrayOutputStream();
                            DetailsJsonCodec.INSTANCE.encodeArray(java.util.List.of(value),out);
                            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
                        } catch (java.io.IOException failure) { throw new IllegalStateException("%s",failure); }
                    }
                    public Details decode(String value) {
                        if (value == null) throw new IllegalStateException("null decode must be bypassed");
                        try { return DetailsJsonCodec.INSTANCE.decodeArray(value).get(0); }
                        catch (java.io.IOException failure) { throw new IllegalStateException("%s",failure); }
                    }
                    public Object toColumnValue(Details value) {
                        if (value == null) throw new IllegalStateException("null SQL conversion must be bypassed");
                        return encode(value);
                    }
                }
                """.formatted(SECRET, SECRET, SECRET));
        sources.put("sample/ProtectedRow.java", """
                package sample; import com.reactor.cachedb.annotations.*;
                @CacheEntity(table="beans",indexedColumns={"id"}) public class ProtectedRow {
                    @CacheId(column="id") public Long id;
                    @CacheColumn("details") @CacheCodec(DetailsFieldCodec.class)
                    @CacheEncrypted(scope=EncryptionScope.REDIS_AND_SOURCE,blindIndex=true) public Details details;
                }
                """);
        sources.put("sample/Scenario.java", """
                package sample;
                public class Scenario implements java.util.function.Supplier<Object[]> {
                    public Object[] get() { var row=new ProtectedRow();row.id=1L;row.details=new Details("%s");
                        return new Object[]{row,ProtectedRowCacheBinding.METADATA,ProtectedRowCacheBinding.CODEC}; }
                }
                """.formatted(SECRET));
        var compiled = compile(temp, sources);
        assertTrue(compiled.success(), compiled.diagnostics());
        String generated = compiled.source("sample/ProtectedRowCacheBinding.java");
        assertFalse(generated.contains("java.lang.reflect"));
        assertFalse(generated.contains("ThreadLocal"));
        assertFalse(generated.contains("static final sample.DetailsFieldCodec"));
        assertTrue(generated.contains("private final sample.DetailsFieldCodec"));
        try (var fixture = load(compiled)) {
            var codec = fixture.codec();
            var logical = codec.toColumns(fixture.entity());
            String wire = codec.toRedisValue(fixture.entity());
            assertFalse(wire.contains(SECRET));
            assertEquals(logical, codec.toColumns(codec.fromRedisValue(wire)));
            assertEquals(logical, codec.toColumns(codec.fromColumns(codec.toSourceColumns(fixture.entity()))));
            Object details = fixture.loader().loadClass("sample.Details").getConstructor(String.class).newInstance(SECRET);
            assertEquals(codec.toIndexColumns(fixture.entity()).get("details"), codec.indexQueryValue("details", details));
            var mapper = new RedisWriteOperationMapper();
            var queued = mapper.fromBody(mapper.toBody(new WriteOperation<>(OperationType.UPSERT, fixture.metadata(),1L,
                    codec.toQueuedColumns(fixture.entity()),wire,1L,Instant.EPOCH)));
            assertFalse(queued.columns().get("details").contains(SECRET));
            assertEquals(queued.columns().get("details"), codec.sourceColumnsFromQueued("1",queued.columns()).get("details"));
            var nullable = new LinkedHashMap<>(logical); nullable.put("details",null);
            Object row = codec.fromLogicalColumns(nullable);
            assertNull(codec.toSourceColumns(row).get("details"));
            assertNull(codec.toColumns(codec.fromRedisValue(codec.toRedisValue(row))).get("details"));
            assertNull(codec.indexQueryValue("details",null));
            var normalized = codec.normalizePatchColumns(Collections.singletonMap("details", null));
            assertNull(normalized.get("details"));
            var nullQueued = mapper.fromBody(mapper.toBody(new WriteOperation<>(OperationType.PATCH, fixture.metadata(), 1L,
                    codec.toQueuedColumns(row, normalized), codec.toRedisValue(row), 2L, Instant.EPOCH)));
            assertNull(codec.sourceColumnsFromQueued("1", nullQueued.columns()).get("details"));
            Object bad = fixture.loader().loadClass("sample.Details").getConstructor(String.class).newInstance("fail");
            fixture.loader().loadClass("sample.ProtectedRow").getField("details").set(fixture.entity(),bad);
            failClosed(() -> codec.toRedisValue(fixture.entity()));
            failClosed(() -> codec.toIndexColumns(fixture.entity()));
            failClosed(() -> codec.indexQueryValue("details",bad));
        }
    }

    @Test void unencryptedBindingsKeepLegacyWireDefaultsAndF1CompositeSortAdditions() throws Exception {
        var sources = Map.of("sample/ProtectedRow.java", """
                package sample; import com.reactor.cachedb.annotations.*;
                @CacheEntity(table="legacy")
                @CacheCompositeIndex(columns={"tenant","status"},sortBy="created")
                public record ProtectedRow(@CacheId(column="id") Long id,@CacheColumn("tenant") String tenant,
                    @CacheColumn("status") String status,@CacheColumn("created") java.time.Instant created,
                    @CacheColumn("note") String note) {}
                """, "sample/Scenario.java", """
                package sample; public class Scenario implements java.util.function.Supplier<Object[]> {
                    public Object[] get() {return new Object[]{new ProtectedRow(1L,"a","OPEN",java.time.Instant.EPOCH,"~E1:legacy:ordinary"),
                        ProtectedRowCacheBinding.METADATA,ProtectedRowCacheBinding.CODEC};}
                }
                """);
        try (var fixture = load(compile(temp,sources))) {
            var codec = fixture.unbound();
            assertSame(codec,codec.bindEncryption(null));
            assertFalse(codec.encryptionBound());
            assertTrue(fixture.metadata().encryptedColumns().isEmpty());
            assertEquals(Map.of("tenant_status","created"),fixture.metadata().compositeSortColumns());
            var fields = new LinkedHashMap<String,String>();
            fields.put("id","1"); fields.put("tenant","a"); fields.put("status","OPEN");
            fields.put("created",Instant.EPOCH.toString()); fields.put("note","~E1:legacy:ordinary");
            assertEquals(LengthPrefixedPayloadCodec.encode(fields),codec.toRedisValue(fixture.entity()));
            assertEquals(fixture.entity(),codec.fromRedisValue(codec.toRedisValue(fixture.entity())));
            assertEquals(codec.toColumns(fixture.entity()),codec.toIndexColumns(fixture.entity()));
            assertEquals(codec.toColumns(fixture.entity()),codec.toQueuedColumns(fixture.entity()));
            assertEquals(codec.toColumns(fixture.entity()),codec.toSourceColumns(fixture.entity()));
            assertEquals(Map.of("a","b"),codec.sourceColumnsFromQueued("1",Map.of("a","b")));
        }
    }

    @Test void compilationRejectsForbiddenRolesIndexesPartitionsCompositesAndOpaqueFactories() throws Exception {
        String baseline = recordSources(true).get("sample/ProtectedRow.java");
        List<String> invalid = List.of(
                baseline.replace("indexedColumns={\"id\",\"tenant_id\",\"status\"}","indexedColumns={\"*\"}"),
                baseline.replace("@CacheId(column=\"id\")", "@CacheEncrypted @CacheId(column=\"id\")"),
                baseline.replace("@CacheTenantId", "@CacheEncrypted @CacheTenantId"),
                baseline.replace("public record", "@CacheCompositeIndex(columns={\"status\",\"contact\"}) public record"),
                baseline.replace("public record", "@CacheCompositeIndex(columns={\"tenant_id\",\"status\"},sortBy=\"amount\") public record"),
                baseline.replace("public record", "@CachePartitionedIndex(partitionBy=\"contact\",sortBy={\"status\"}) public record"),
                baseline.replace("public record", "@CachePartitionedIndex(partitionBy=\"status\",sortBy={\"contact\"}) public record"),
                baseline.replace("@CacheColumn(\"hidden\") String hidden", "@CacheVersion(column=\"revision\") Long hidden"),
                baseline.replace("@CacheColumn(\"hidden\") String hidden", "@CacheSoftDelete(column=\"removed\") Boolean hidden"),
                baseline.replace("@CacheEncrypted @CacheColumn(\"hidden\")", "@CacheEncrypted(keyRing=\"bad|ring\") @CacheColumn(\"hidden\")")
        );
        for (String source : invalid) {
            var result = compile(temp,Map.of("sample/ProtectedRow.java",source));
            assertFalse(result.success(),source);
            assertTrue(result.diagnostics().contains("F-3 encryption"),result.diagnostics());
        }
    }

    @Test void primitiveBoxedEnumUuidTemporalAndNumericQueueConversionsReuseExistingScalarFunctions() throws Exception {
        var sources=Map.of("sample/ProtectedRow.java","""
                package sample;import com.reactor.cachedb.annotations.*;
                @CacheEntity(table="scalars",indexedColumns={"id"}) public record ProtectedRow(
                    @CacheId(column="id") Long id,
                    @CacheColumn("i") @CacheEncrypted(blindIndex=true) int i,
                    @CacheColumn("number") @CacheEncrypted(blindIndex=true) Long number,
                    @CacheColumn("flag") @CacheEncrypted(blindIndex=true) Boolean flag,
                    @CacheColumn("state") @CacheEncrypted(blindIndex=true) State state,
                    @CacheColumn("key") @CacheEncrypted(blindIndex=true) java.util.UUID key,
                    @CacheColumn("at") @CacheEncrypted(blindIndex=true) java.time.Instant at,
                    @CacheColumn("day") @CacheEncrypted(blindIndex=true) java.time.LocalDate day,
                    @CacheColumn("time") @CacheEncrypted(blindIndex=true) java.time.LocalTime time,
                    @CacheColumn("local") @CacheEncrypted(blindIndex=true) java.time.LocalDateTime local,
                    @CacheColumn("offset") @CacheEncrypted(blindIndex=true) java.time.OffsetDateTime offset) {}
                ""","sample/State.java","package sample;public enum State{OPEN,CLOSED}","sample/Scenario.java","""
                package sample;public class Scenario implements java.util.function.Supplier<Object[]> {
                    public Object[] get(){return new Object[]{new ProtectedRow(1L,7,11L,true,State.OPEN,
                        java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),java.time.Instant.EPOCH,
                        java.time.LocalDate.of(2026,10,27),java.time.LocalTime.NOON,java.time.LocalDateTime.of(2026,10,27,12,0),
                        java.time.OffsetDateTime.parse("2026-10-27T12:00:00+03:00")),ProtectedRowCacheBinding.METADATA,ProtectedRowCacheBinding.CODEC};}
                }
                """);
        try(var fixture=load(compile(temp,sources))){
            var codec=fixture.codec();
            assertEquals(fixture.entity(),codec.fromRedisValue(codec.toRedisValue(fixture.entity())));
            var mapper=new RedisWriteOperationMapper();
            var queued=mapper.fromBody(mapper.toBody(new WriteOperation<>(OperationType.UPSERT,fixture.metadata(),1L,
                    codec.toQueuedColumns(fixture.entity()),codec.toRedisValue(fixture.entity()),1L,Instant.EPOCH)));
            var sql=codec.sourceColumnsFromQueued("1",queued.columns());
            assertEquals(fixture.entity(),codec.fromColumns(new LinkedHashMap<>(sql)));
            assertEquals(7,com.reactor.cachedb.jdbc.JdbcWriteBehindSupport.convertValue(sql.get("i"),fixture.metadata().columnTypes().get("i")));
            assertEquals(11L,com.reactor.cachedb.jdbc.JdbcWriteBehindSupport.convertValue(sql.get("number"),fixture.metadata().columnTypes().get("number")));
            assertEquals(codec.toIndexColumns(fixture.entity()).get("i"),codec.indexQueryValue("i",7));
            assertEquals(codec.toIndexColumns(fixture.entity()).get("number"),codec.indexQueryValue("number",11L));
            failClosed(()->codec.indexQueryValue("number",11));
        }
    }

    @Test void opaqueNamedQueriesAndProjectionSourcesCannotSilentlySerializePlaintext() throws Exception {
        String entity=recordSources(true).get("sample/ProtectedRow.java");
        var named=compile(temp,Map.of("sample/ProtectedRow.java",entity.replace("String hidden) {}","""
                String hidden) {
                    @CacheNamedQuery public static com.reactor.cachedb.core.query.QuerySpec opaque(){
                        return com.reactor.cachedb.core.query.QuerySpec.builder().build();}
                }
                """)));
        assertFalse(named.success());
        assertTrue(named.diagnostics().contains("F-3 encryption"),named.diagnostics());
        var projection=compile(temp,Map.of("sample/ProtectedRow.java",entity,"sample/View.java","""
                package sample;import com.reactor.cachedb.annotations.*;
                @CacheProjectionRecord(source=ProtectedRow.class,id="id") public record View(Long id,String contact){}
                """));
        assertFalse(projection.success());
        assertTrue(projection.diagnostics().contains("F-3 encryption"),projection.diagnostics());
        var nestedJson=compile(temp,Map.of("sample/Value.java","""
                package sample;import com.reactor.cachedb.annotations.*;
                @CacheJsonRecord public record Value(@CacheEncrypted String secret){}
                """));
        assertFalse(nestedJson.success());
        assertTrue(nestedJson.diagnostics().contains("requires an enclosing @CacheEntity"),nestedJson.diagnostics());
    }

    @Test void unsupportedArraysGenericCodecsWideShapesAndMisplacedAnnotationsAreCompileErrors() throws Exception {
        for (String declaration : List.of(
                "@CacheEncrypted public String getSecret(){return null;}",
                "@CacheEncrypted public String transientValue;",
                "@CacheEncrypted @CacheColumn(\"x\") public static String x;")) {
            var result=compile(temp,Map.of("sample/ProtectedRow.java","""
                    package sample;import com.reactor.cachedb.annotations.*;
                    @CacheEntity(table="rows",indexedColumns={"id"}) public class ProtectedRow {
                        @CacheId(column="id") public Long id;%s
                    }
                    """.formatted(declaration)));
            assertFalse(result.success(),result.diagnostics());
            if (!declaration.contains("getSecret")) assertTrue(result.diagnostics().contains("F-3 encryption"),result.diagnostics());
        }
        for (boolean encryptedBudget : List.of(false,true)) {
            StringBuilder fields=new StringBuilder("@CacheId(column=\"id\") Long id");
            int count=encryptedBudget?33:128;
            for(int i=0;i<count;i++)fields.append(", ").append(encryptedBudget||i==0?"@CacheEncrypted ":"")
                    .append("@CacheColumn(\"c").append(i).append("\") String c").append(i);
            var result=compile(temp,Map.of("sample/ProtectedRow.java","""
                    package sample;import com.reactor.cachedb.annotations.*;
                    @CacheEntity(table="rows",indexedColumns={"id"}) public record ProtectedRow(%s) {}
                    """.formatted(fields)));
            assertFalse(result.success());
            assertTrue(result.diagnostics().contains("at most 32 encrypted and 128"),result.diagnostics());
        }
        for (String type : List.of("byte[]","java.util.List<String>")) {
            var result=compile(temp,Map.of("sample/ProtectedRow.java","""
                    package sample;import com.reactor.cachedb.annotations.*;
                    @CacheEntity(table="rows",indexedColumns={"id"}) public record ProtectedRow(
                        @CacheId(column="id") Long id,@CacheColumn("value") @CacheEncrypted @CacheCodec(ValueCodec.class) %s value) {}
                    """.formatted(type),"sample/ValueCodec.java","""
                    package sample;public class ValueCodec implements com.reactor.cachedb.core.codec.FieldValueCodec<%s>{
                        public String encode(%s value){return null;}public %s decode(String value){return null;}}
                    """.formatted(type,type,type)));
            assertFalse(result.success());
            assertTrue(result.diagnostics().contains("generic, array and type-variable"),result.diagnostics());
        }
    }

    @Test void repositoryProcessorRejectsAllNonEqAndReadSideAggregateSortSeekAndDeltaUses() throws Exception {
        String entity=recordSources(true).get("sample/ProtectedRow.java");
        List<String> methods=new ArrayList<>();
        methods.add("@HotRoute(\"r\") @CacheRouteQuery(fixedLimit=9,predicates=@CachePredicate(field=\"hidden\",constants=\"x\")) com.reactor.cachedb.core.repository.HotWindow<ProtectedRow> find();");
        for(String operator:List.of("NE","GT","GTE","LT","LTE","IN","CONTAINS","STARTS_WITH"))methods.add("""
                @HotRoute("r") @CacheRouteQuery(fixedLimit=9,predicates=@CachePredicate(field="contact",operator=CachePredicate.Operator.%s,constants="x"))
                com.reactor.cachedb.core.repository.HotWindow<ProtectedRow> find();
                """.formatted(operator));
        methods.add("@HotRoute(\"r\") @CacheRouteQuery(fixedLimit=9,orderBy=@CacheOrder(field=\"contact\")) com.reactor.cachedb.core.repository.HotWindow<ProtectedRow> find();");
        methods.add("@HotRoute(\"r\") @CacheRouteQuery(fixedLimit=9,orderBy=@CacheOrder(field=\"contact\",direction=CacheOrder.Direction.DESC),seek={@CacheSeek(field=\"contact\",param=\"cursor\"),@CacheSeek(field=\"id\",param=\"id\")}) com.reactor.cachedb.core.repository.HotWindow<ProtectedRow> find(String cursor,Long id);");
        methods.add("@CacheAggregate(field=\"contact\") long count();");
        methods.add("@CacheAggregate(function=com.reactor.cachedb.core.query.AggregateFunction.SUM,field=\"amount\") java.math.BigDecimal sum();");
        methods.add("@CacheFacet(groupBy=\"contact\") java.util.Map<String,Long> counts();");
        methods.add("@CacheFacet(groupBy=\"status\",field=\"contact\") java.util.Map<String,Long> counts();");
        methods.add("@CachePatch boolean patch(@CacheId Long id,@CacheDelta(\"amount\") java.math.BigDecimal amount);");
        methods.add("@SourceRoute(\"r\") @CacheRouteQuery(fixedLimit=9,predicates=@CachePredicate(field=\"amount\",parameter=\"amount\")) com.reactor.cachedb.core.repository.SourceWindow<ProtectedRow> find(java.math.BigDecimal amount);");
        for(String method:methods){
            var result=compile(temp,Map.of("sample/ProtectedRow.java",entity,"sample/Rows.java","""
                    package sample;import com.reactor.cachedb.annotations.*;
                    @CacheRepository(entity=ProtectedRow.class) public interface Rows extends com.reactor.cachedb.core.repository.CacheDbRepository<ProtectedRow,Long>{%s}
                    """.formatted(method)));
            assertFalse(result.success(),method);
            assertTrue(result.diagnostics().contains("F-3 encryption"),method+result.diagnostics());
        }
        var valid=compile(temp,Map.of("sample/ProtectedRow.java",entity,"sample/Rows.java","""
                package sample;import com.reactor.cachedb.annotations.*;
                @CacheRepository(entity=ProtectedRow.class) public interface Rows extends com.reactor.cachedb.core.repository.CacheDbRepository<ProtectedRow,Long>{
                    @HotRoute("r") @CacheRouteQuery(fixedLimit=9,predicates=@CachePredicate(field="contact",parameter="contact"))
                    com.reactor.cachedb.core.repository.HotWindow<ProtectedRow> find(String contact);
                    long countByContact(String contact);
                }
                """));
        assertTrue(valid.success(),valid.diagnostics());
    }

    @Test void fieldOnlyAnnotationRejectsGetterAndRecordPropagationCannotLeaveComponentsPlaintext() throws Exception {
        var targets=com.reactor.cachedb.annotations.CacheEncrypted.class.getAnnotation(java.lang.annotation.Target.class).value();
        assertEquals(Set.of(java.lang.annotation.ElementType.FIELD,java.lang.annotation.ElementType.RECORD_COMPONENT),Set.of(targets));
        var getter=compile(temp,Map.of("sample/ProtectedRow.java","""
                package sample;import com.reactor.cachedb.annotations.*;
                @CacheEntity(table="rows",indexedColumns={"id"}) public class ProtectedRow {
                    @CacheId(column="id") public Long id;@CacheColumn("secret") public String secret;
                    @CacheEncrypted public String getSecret(){return secret;}
                }
                """));
        assertFalse(getter.success());
        var components=new java.util.concurrent.atomic.AtomicInteger();
        var fields=new java.util.concurrent.atomic.AtomicInteger();
        var methods=new java.util.concurrent.atomic.AtomicInteger();
        var audit=new javax.annotation.processing.AbstractProcessor(){
            public Set<String> getSupportedAnnotationTypes(){return Set.of("*");}
            public javax.lang.model.SourceVersion getSupportedSourceVersion(){return javax.lang.model.SourceVersion.latestSupported();}
            public boolean process(Set<? extends javax.lang.model.element.TypeElement> annotations,javax.annotation.processing.RoundEnvironment round){
                for(var root:round.getRootElements())if(root instanceof javax.lang.model.element.TypeElement type
                        && type.getQualifiedName().contentEquals("sample.ProtectedRow")){
                    for(var component:type.getRecordComponents())if(component.getAnnotation(com.reactor.cachedb.annotations.CacheEncrypted.class)!=null)components.incrementAndGet();
                    for(var member:type.getEnclosedElements())if(member.getAnnotation(com.reactor.cachedb.annotations.CacheEncrypted.class)!=null){
                        if(member.getKind()==javax.lang.model.element.ElementKind.FIELD)fields.incrementAndGet();
                        if(member.getKind()==javax.lang.model.element.ElementKind.METHOD)methods.incrementAndGet();
                    }
                }
                return false;
            }
        };
        var compiled=compile(temp,recordSources(true),audit);
        assertTrue(compiled.success(),compiled.diagnostics());
        assertEquals(3,components.get());assertEquals(3,fields.get());assertEquals(0,methods.get());
        try(var fixture=load(compiled)){
            assertEquals(Set.of("contact","amount","hidden"),fixture.metadata().encryptedColumns().keySet());
            var codec=fixture.codec();
            var values=LengthPrefixedPayloadCodec.decode(codec.toRedisValue(fixture.entity()));
            for(String column:List.of("contact","amount","hidden"))assertTrue(values.get(column).startsWith("~E1:"));
            assertEquals(fixture.entity(),codec.fromRedisValue(codec.toRedisValue(fixture.entity())));
        }
    }

    static void failClosed(Runnable action) {
        var failure = assertThrows(CacheEncryptionException.class, action::run);
        assertNull(failure.getCause());
        absent(failure.toString());
    }
}
