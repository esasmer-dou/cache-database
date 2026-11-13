package com.reactor.cachedb.processor;

import static com.reactor.cachedb.processor.EncryptionCompilerFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.crypto.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.queue.*;
import com.reactor.cachedb.core.registry.DefaultEntityRegistry;
import com.reactor.cachedb.redis.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncryptedStorageIntegrationTest {
    @TempDir Path temp;
    private Loaded fixture() throws Exception { return load(compile(temp, recordSources(false))); }
    private DefaultEntityRegistry registry(Loaded fixture) {
        var registry = new DefaultEntityRegistry(ResourceLimits.defaults(), keys("k1",key("k1",1)));
        registry.register(fixture.metadata(), fixture.unbound(), CachePolicy.defaults(), null, null);
        return registry;
    }
    private com.reactor.cachedb.core.model.EntityCodec<Object> codec(DefaultEntityRegistry registry, Loaded fixture) {
        @SuppressWarnings("unchecked") var codec=(com.reactor.cachedb.core.model.EntityCodec<Object>)registry.find(fixture.metadata().entityName()).orElseThrow().codec();
        return codec;
    }
    private QueuedWriteOperation queued(Loaded fixture, com.reactor.cachedb.core.model.EntityCodec<Object> codec) {
        var mapper=new RedisWriteOperationMapper();
        return mapper.fromBody(mapper.toBody(new WriteOperation<>(OperationType.UPSERT,fixture.metadata(),1L,
                codec.toQueuedColumns(fixture.entity()),codec.toRedisValue(fixture.entity()),1L,Instant.EPOCH)));
    }
    private static void safe(CacheEncryptionException failure) {
        assertNull(failure.getCause());assertFalse(failure.toString().contains(SECRET));assertFalse(failure.toString().contains(HIDDEN));
    }


    @Test void assignmentOnlyCodecPreservesNullSentinelsAadAndSourceScopesWithoutAnEntity() throws Exception {
        try(var fixture=load(compile(temp,recordSources(true)))){
            var codec=fixture.codec();
            for(String value:Arrays.asList(null,"cachedb:__NULL__","__NULL__","~P:N",SECRET)){
                var assignments=new LinkedHashMap<String,Object>();assignments.put("contact",value);
                assignments.put("amount",new java.math.BigDecimal("98765.4321"));
                var queued=codec.toQueuedPatchColumns("1","tenant-a",codec.normalizePatchColumns(assignments));
                assertFalse(queued.containsKey("hidden"));assertFalse(queued.containsKey("id"));
                assertFalse(queued.toString().contains(SECRET));assertTrue(queued.get("amount").toString().startsWith("~E1:"));
                var text=new LinkedHashMap<String,String>();queued.forEach((k,v)->text.put(k,v==null?null:v.toString()));
                var sql=codec.sourceColumnsFromQueued("1",text);assertEquals(value,sql.get("contact"));
                assertTrue(sql.get("amount").startsWith("~E1:"));assertFalse(sql.containsKey("hidden"));
                safe(assertThrows(CacheEncryptionException.class,()->codec.sourceColumnsFromQueued("2",text)));
                var wrongTenant=new LinkedHashMap<>(text);wrongTenant.put(EncryptedCodecSupport.QUEUE_TENANT,"~T1:tenant-b");
                safe(assertThrows(CacheEncryptionException.class,()->codec.sourceColumnsFromQueued("1",wrongTenant)));
            }
            safe(assertThrows(CacheEncryptionException.class,()->codec.toQueuedPatchColumns("01","tenant-a",Map.of("contact",SECRET))));
            safe(assertThrows(CacheEncryptionException.class,()->codec.toQueuedPatchColumns("1",null,Map.of("contact",SECRET))));
            safe(assertThrows(CacheEncryptionException.class,()->codec.toQueuedPatchColumns("1","tenant-a",Map.of("id",1L))));
            var framed=com.reactor.cachedb.core.codec.PayloadEntityCodec.wrap(codec,fixture.metadata(),0);
            var queued=framed.toQueuedPatchColumns("1","tenant-a",Map.of("contact",SECRET));
            assertTrue(queued.get("contact").toString().startsWith("~E1:"));
        }
    }

    @Test void fixedCryptoCodesDoNotTrustMutableCauseOrSuppressedChains() throws Exception {
        try(var fixture=fixture()){
            var tainted=new CacheEncryptionException(CacheEncryptionException.Code.INVALID_TEXT);
            tainted.initCause(new IllegalArgumentException(SECRET));tainted.addSuppressed(new IllegalStateException(HIDDEN));
            var sanitized=(CacheEncryptionException)EncryptedQueuedWrites.safeFailure(true,tainted);
            safe(sanitized);assertEquals(0,sanitized.getSuppressed().length);assertNotSame(tainted,sanitized);
            var malicious=new EntityCodec<Object>(){
                public String toRedisValue(Object entity){throw tainted;}
                public Object fromRedisValue(String value){throw tainted;}
                public Map<String,Object> toColumns(Object entity){throw tainted;}
                public EntityCodec<Object> bindEncryption(CacheKeyProvider provider){throw tainted;}
            };
            var failure=assertThrows(CacheEncryptionException.class,()->EncryptedStorageChecks.bind(
                    fixture.metadata(),malicious,keys("k1",key("k1",1))));
            safe(failure);assertEquals(0,failure.getSuppressed().length);assertNotSame(tainted,failure);
        }
    }

    @Test void defaultPlaintextBlindLiteralHookCannotFeedIndexOrNativeCursorContract() throws Exception {
        try(var fixture=fixture()){
            var missing=new EntityCodec<Object>(){
                public boolean encryptionBound(){return true;}
                public String toRedisValue(Object value){throw new AssertionError("No serialization probe");}
                public Object fromRedisValue(String value){throw new AssertionError("No decode probe");}
                public Map<String,Object> toColumns(Object value){throw new AssertionError("No logical column probe");}
            };
            safe(assertThrows(CacheEncryptionException.class,()->EncryptedStorageChecks.indexLiteral(
                    fixture.metadata(),missing,"contact",SECRET)));
            assertEquals(SECRET,EncryptedStorageChecks.indexLiteral(fixture.metadata(),missing,"status",SECRET));
        }
    }

    @Test void configuredRegistrationBindsBeforeFramingAndNeverMutatesStaticCodec() throws Exception {
        try(var fixture=fixture()){
            var registry=registry(fixture);var codec=codec(registry,fixture);
            assertTrue(codec.encryptionBound());assertFalse(fixture.unbound().encryptionBound());
            assertEquals(fixture.entity(),codec.fromRedisValue(codec.toRedisValue(fixture.entity())));
            assertEquals(codec.toIndexColumns(fixture.entity()).get("contact"),codec.indexQueryValue("contact",SECRET));
        }
    }

    @Test void missingProviderAndDefaultNoOpBinderFailBeforeRegistration() throws Exception {
        try(var fixture=fixture()){
            var registry=new DefaultEntityRegistry(ResourceLimits.defaults());
            safe(assertThrows(CacheEncryptionException.class,()->registry.register(fixture.metadata(),fixture.unbound(),CachePolicy.defaults(),null,null)));
            assertTrue(registry.all().isEmpty());
            var noOp=new com.reactor.cachedb.core.model.EntityCodec<Object>(){
                public String toRedisValue(Object value){return SECRET;}
                public Object fromRedisValue(String value){return value;}
                public Map<String,Object> toColumns(Object value){return Map.of("contact",SECRET);}
            };
            var configured=new DefaultEntityRegistry(ResourceLimits.defaults(),keys("k1",key("k1",1)));
            safe(assertThrows(CacheEncryptionException.class,()->configured.register(fixture.metadata(),noOp,CachePolicy.defaults(),null,null)));
            assertTrue(configured.all().isEmpty());
        }
    }

    @Test void providerFailuresDoNotLeakAndDoNotBlockRegistryReadLock() throws Exception {
        try(var fixture=fixture();var reader=java.util.concurrent.Executors.newSingleThreadExecutor()){
            var called=new AtomicInteger();
            var holder=new java.util.concurrent.atomic.AtomicReference<DefaultEntityRegistry>();
            var lockWasFree=new java.util.concurrent.atomic.AtomicBoolean();
            CacheKeyProvider bad=new CacheKeyProvider(){
                public KeyMaterial current(String ring){
                    called.incrementAndGet();
                    try {
                        reader.submit(()->holder.get().all()).get(2,java.util.concurrent.TimeUnit.SECONDS);
                        lockWasFree.set(true);
                    } catch(Exception timeout){throw new IllegalStateException(SECRET);}
                    throw new IllegalStateException(SECRET);
                }
                public Optional<KeyMaterial> byId(String ring,String id){throw new IllegalStateException(HIDDEN);}
            };
            var registry=new DefaultEntityRegistry(ResourceLimits.defaults(),bad);holder.set(registry);
            safe(assertThrows(CacheEncryptionException.class,()->registry.register(fixture.metadata(),fixture.unbound(),CachePolicy.defaults(),null,null)));
            assertTrue(lockWasFree.get());assertEquals(1,called.get());assertTrue(registry.all().isEmpty());
        }
    }

    @Test void registeredEncryptionCannotBeDemotedByShadowMetadataOrRemappedSource() throws Exception {
        try(var fixture=fixture()){
            var registry=registry(fixture);var metadata=fixture.metadata();var codec=codec(registry,fixture);
            var shadow=new EntityMetadata<Object,Object>(){
                public String entityName(){return metadata.entityName();}
                public String tableName(){return metadata.tableName();}
                public String redisNamespace(){return metadata.redisNamespace();}
                public String idColumn(){return metadata.idColumn();}
                public Class<Object> entityType(){return metadata.entityType();}
                public java.util.function.Function<Object,Object> idAccessor(){return metadata.idAccessor();}
                public List<String> columns(){return metadata.columns();}
                public List<RelationDefinition> relations(){return metadata.relations();}
                public Map<String,String> columnTypes(){return metadata.columnTypes();}
            };
            safe(assertThrows(CacheEncryptionException.class,()->registry.register(shadow,codec,CachePolicy.defaults(),null,null)));
            assertEquals(1,registry.all().size());assertTrue(codec(registry,fixture).encryptionBound());
            var mismatched=new SourceMapping<Object>("other_table",metadata.columns(),row->row,metadata);
            safe(assertThrows(CacheEncryptionException.class,()->mismatched.withCodec(codec)));
        }
    }

    @Test void sourceMappingRebindsExplicitlyAndOriginalDecoderStaysUnbound() throws Exception {
        try(var fixture=fixture()){
            var registry=registry(fixture);var codec=codec(registry,fixture);
            var original=SourceMapping.entity(fixture.metadata(),fixture.unbound());
            var sql=codec.toSourceColumns(fixture.entity());
            safe(assertThrows(CacheEncryptionException.class,()->original.decoder().apply(sql)));
            assertEquals(fixture.entity(),original.withCodec(codec).decoder().apply(sql));
            assertEquals(fixture.entity(),original.bindEncryption(keys("k1",key("k1",1))).decoder().apply(sql));
            safe(assertThrows(CacheEncryptionException.class,()->original.decoder().apply(sql)));
            assertTrue(compile(temp,recordSources(false)).source("sample/ProtectedRowCacheBinding.java").contains("session.sourceMapping(METADATA, CODEC)"));
        }
    }

    @Test void logicalColumnsCannotCrossEitherRedisTransportMapper() throws Exception {
        try(var fixture=fixture()){
            var codec=fixture.codec();var logical=new WriteOperation<>(OperationType.UPSERT,fixture.metadata(),1L,
                    codec.toColumns(fixture.entity()),codec.toRedisValue(fixture.entity()),1L,Instant.EPOCH);
            safe(assertThrows(CacheEncryptionException.class,()->new RedisWriteOperationMapper().toBody(logical)));
            safe(assertThrows(CacheEncryptionException.class,()->new RedisFunctionArgsMapper().upsertArgs(logical,CachePolicy.defaults(),RedisGuardrailConfig.defaults(),true)));
        }
    }

    @Test void queueConversionIsImmutableScopeAwareAndKeepsRetryOriginalProtected() throws Exception {
        try(var fixture=fixture()){
            var registry=registry(fixture);var codec=codec(registry,fixture);var operation=queued(fixture,codec);
            var before=new LinkedHashMap<>(operation.columns());
            var sql=EncryptedQueuedWrites.sourceColumns(registry,List.of(operation)).get(0);
            assertNotSame(operation,sql);
            assertEquals(SECRET,sql.columns().get("contact"));assertEquals(HIDDEN,sql.columns().get("hidden"));
            assertEquals(operation.columns().get("amount"),sql.columns().get("amount"));
            assertFalse(sql.columns().containsKey(EncryptedCodecSupport.QUEUE_FORMAT));
            assertThrows(UnsupportedOperationException.class,()->sql.columns().put("contact","changed"));
            assertEquals(before,operation.columns());
            assertEquals(fixture.entity(),codec.fromColumns(new LinkedHashMap<>(sql.columns())));
            assertFalse(new RedisWriteOperationMapper().toBody(operation).toString().contains(SECRET));
        }
    }

    @Test void allMembersAreValidatedBeforeAnySqlCopyCanBeUsed() throws Exception {
        try(var fixture=fixture()){
            var registry=registry(fixture);var codec=codec(registry,fixture);var valid=queued(fixture,codec);
            var columns=new LinkedHashMap<>(valid.columns());columns.put("contact",SECRET);
            var invalid=new QueuedWriteOperation(valid.type(),valid.entityName(),valid.tableName(),valid.redisNamespace(),
                    valid.observationTag(),valid.idColumn(),valid.versionColumn(),valid.deletedColumn(),valid.id(),columns,2,valid.createdAt());
            safe(assertThrows(CacheEncryptionException.class,()->EncryptedQueuedWrites.sourceColumns(registry,List.of(valid,invalid))));
            assertTrue(valid.columns().get("contact").startsWith("~E1:"));
            safe(assertThrows(CacheEncryptionException.class,()->EncryptedQueuedWrites.sourceColumns(null,List.of(valid))));
        }
    }

    @Test void partialPatchLiteralNullMarkersReachSqlAsStringsNotNull() throws Exception {
        try(var fixture=fixture()){
            var registry=registry(fixture);var codec=codec(registry,fixture);
            for(String value:Arrays.asList(null,"__NULL__","cachedb:__NULL__","~P:N","~P:V","")){
                var logical=codec.toColumns(fixture.entity());logical.put("contact",value);
                Object accepted=codec.fromLogicalColumns(logical);
                var assignments=codec.normalizePatchColumns(Collections.singletonMap("contact",value));
                var mapper=new RedisWriteOperationMapper();
                var operation=mapper.fromBody(mapper.toBody(new WriteOperation<>(OperationType.PATCH,fixture.metadata(),1L,
                        codec.toQueuedColumns(accepted,assignments),codec.toRedisValue(accepted),2,Instant.EPOCH)));
                var sql=EncryptedQueuedWrites.sourceColumns(registry,List.of(operation)).get(0);
                assertEquals(value,sql.columns().get("contact"));
                assertEquals(value,com.reactor.cachedb.jdbc.JdbcWriteBehindSupport.convertValue(sql.columns().get("contact"),"java.lang.String"));
            }
        }
    }

    @Test void blindIndexGuardsRejectPlaintextUnknownCaseAndEveryNonEqOperator() throws Exception {
        try(var fixture=fixture()){
            var metadata=fixture.metadata();var codec=fixture.codec();
            EncryptedStorageChecks.validateIndexColumns(metadata,codec.toIndexColumns(fixture.entity()));
            safe(assertThrows(CacheEncryptionException.class,()->EncryptedStorageChecks.validateIndexColumns(metadata,codec.toColumns(fixture.entity()))));
            EncryptedStorageChecks.validateQuery(metadata,QuerySpec.where(QueryFilter.eq("contact",SECRET)));
            for(QueryOperator operator:QueryOperator.values())if(operator!=QueryOperator.EQ){
                var filter=new QueryFilter("contact",operator,SECRET,List.of(SECRET));
                safe(assertThrows(CacheEncryptionException.class,()->EncryptedStorageChecks.validateQuery(metadata,QuerySpec.anyOf(QueryFilter.eq("status","OPEN"),filter))));
            }
            safe(assertThrows(CacheEncryptionException.class,()->EncryptedStorageChecks.validateQuery(metadata,QuerySpec.where(QueryFilter.eq("hidden",HIDDEN)))));
            safe(assertThrows(CacheEncryptionException.class,()->EncryptedStorageChecks.validateQuery(metadata,QuerySpec.allOf().orderBy(QuerySort.asc("contact")))));
            safe(assertThrows(CacheEncryptionException.class,()->EncryptedStorageChecks.indexLiteral(metadata,codec,"CONTACT",SECRET)));
            safe(assertThrows(CacheEncryptionException.class,()->EncryptedStorageChecks.indexLiteral(metadata,codec,"contactAlias",SECRET)));
            safe(assertThrows(CacheEncryptionException.class,()->EncryptedStorageChecks.validateQuery(metadata,QuerySpec.where(QueryFilter.eq("not_persisted",SECRET)))));
        }
    }

    @Test void protectedAtomicFingerprintIsKeyedStableAcrossFreshBuildersAndAesRotation() throws Exception {
        try(var fixture=fixture()){
            var sent=new java.util.concurrent.atomic.AtomicReference<List<String>>();
            var redis=(RedisClientOperations)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{RedisClientOperations.class},(proxy,method,args)->{
                        if(method.getName().equals("clustered"))return false;
                        if(method.getName().equals("eval")){
                            @SuppressWarnings("unchecked") var arguments=(List<String>)args[2];
                            sent.set(List.copyOf(arguments));
                            var reply=new ArrayList<String>();reply.add("1-0");
                            for(int i=0;i<Integer.parseInt(arguments.get(1));i++)reply.add("1");
                            return reply;
                        }
                        throw new AssertionError("Unexpected Redis operation: "+method.getName());
                    });
            String fingerprint=null;
            for(var provider:List.of(keys("k1",key("k1",1)),keys("k2",key("k1",1),key("k2",2)))){
                var registry=new DefaultEntityRegistry(ResourceLimits.defaults(),provider);
                registry.register(fixture.metadata(),fixture.unbound(),CachePolicy.defaults(),null,null);
                var batch=new RedisAtomicWriteBatch(redis,registry,new RedisKeyStrategy("f3-stable"),
                        WriteBehindConfig.defaults(),RedisGuardrailConfig.defaults(),null,"same-business-id");
                batch.put(fixture.entity());batch.delete(fixture.metadata().entityType(),2L);
                batch.commit();
                String actual=sent.get().get(0);assertTrue(actual.startsWith("~I1:"));
                if(fingerprint==null)fingerprint=actual;else assertEquals(fingerprint,actual);
                assertFalse(sent.get().toString().contains(SECRET));assertFalse(sent.get().toString().contains(HIDDEN));
            }
            var codec=fixture.codec();
            String row=codec.encryptionIntentToken(fixture.entity(),new HashSet<>(fixture.metadata().columns()));
            assertNotEquals(row,codec.encryptionIntentFingerprint(List.of(row)));
            var changed=codec.toColumns(fixture.entity());changed.put("contact",SECRET+"changed");
            assertNotEquals(row,codec.encryptionIntentToken(codec.fromLogicalColumns(changed),new HashSet<>(fixture.metadata().columns())));
        }
    }

    record PlainRow(String id,String note) {}
    @Test void missingIntentCapabilityPermanentlyRejectsAnAlreadyStagedMixedBatch() throws Exception {
        try(var fixture=fixture()){
            var registry=new DefaultEntityRegistry(ResourceLimits.defaults());
            var real=fixture.codec();
            var noIntent=new EntityCodec<Object>(){
                public boolean encryptionBound(){return true;}
                public String toRedisValue(Object row){return real.toRedisValue(row);}
                public Object fromRedisValue(String payload){return real.fromRedisValue(payload);}
                public Map<String,Object> toColumns(Object row){return real.toColumns(row);}
            };
            registry.register(fixture.metadata(),noIntent,CachePolicy.defaults(),null,null);
            var metadata=new EntityMetadata<PlainRow,String>(){
                public String entityName(){return "PlainRow";}
                public String tableName(){return "plain_rows";}
                public String redisNamespace(){return "plain_rows";}
                public String idColumn(){return "id";}
                public Class<PlainRow> entityType(){return PlainRow.class;}
                public java.util.function.Function<PlainRow,String> idAccessor(){return PlainRow::id;}
                public List<String> columns(){return List.of("id","note");}
                public List<RelationDefinition> relations(){return List.of();}
            };
            var codec=new EntityCodec<PlainRow>(){
                public String toRedisValue(PlainRow row){return com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec.encode(Map.of("id",row.id(),"note",row.note()));}
                public PlainRow fromRedisValue(String value){var map=com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec.decode(value);return new PlainRow(map.get("id"),map.get("note"));}
                public Map<String,Object> toColumns(PlainRow row){return Map.of("id",row.id(),"note",row.note());}
                public PlainRow fromColumns(Map<String,Object> values){return new PlainRow((String)values.get("id"),(String)values.get("note"));}
            };
            registry.register(metadata,codec,CachePolicy.defaults(),null,null);
            var batch=new RedisAtomicWriteBatch((RedisClientOperations)null,registry,new RedisKeyStrategy("f3-mixed"),
                    WriteBehindConfig.defaults(),RedisGuardrailConfig.defaults(),null,"mixed-business-id");
            batch.put(new PlainRow("plain","ordinary"));
            var missing=assertThrows(CacheEncryptionException.class,()->batch.put(fixture.entity()));
            assertEquals(CacheEncryptionException.Code.INTENT_KEY_UNAVAILABLE,missing.code());safe(missing);
            assertEquals(CacheEncryptionException.Code.INTENT_KEY_UNAVAILABLE,
                    assertThrows(CacheEncryptionException.class,batch::commit).code());
            assertThrows(CacheEncryptionException.class,()->batch.delete(PlainRow.class,"plain"));
        }
    }

    @Test void runtimeOptionsKeepProviderOutsideUnchangedConfigRecord() {
        CacheKeyProvider provider=new CacheKeyProvider(){
            public KeyMaterial current(String ring){throw new AssertionError();}
            public Optional<KeyMaterial> byId(String ring,String id){throw new AssertionError();}
            public String toString(){throw new AssertionError("Do not stringify a key provider");}
        };
        var options=com.reactor.cachedb.core.config.CacheRuntimeOptions.builder().keyProvider(provider).build();
        assertSame(provider,options.keyProvider());
        assertSame(provider,options.toBuilder().build().keyProvider());
        assertNull(com.reactor.cachedb.core.config.CacheRuntimeOptions.defaults().keyProvider());
        assertFalse(com.reactor.cachedb.core.config.CacheRuntimeOptions.class.isRecord());
        assertFalse(options.toString().contains(SECRET));
        var config=CacheDatabaseConfig.defaults();
        assertEquals(21,CacheDatabaseConfig.class.getRecordComponents().length);
        var legacy=new CacheDatabaseConfig(config.writeBehind(),config.writeBehindFlusherFactory(),config.resourceLimits(),
                config.keyspace(),config.runtimeCoordination(),config.redisFunctions(),config.relations(),config.pageCache(),
                config.readThrough(),config.readShapeGuardrail(),config.queryIndex(),config.projectionRefresh(),config.redisGuardrail(),
                config.deadLetterRecovery(),config.adminMonitoring(),config.adminReportJob(),config.adminHttp(),config.schemaBootstrap(),
                config.runtimeRole(),config.mutations(),config.traceContextPropagator());
        assertEquals(config,legacy);
        assertEquals(config,CacheDatabaseConfigOverrides.apply(config,new Properties(),CacheDatabaseConfigOverrides.DEFAULT_PREFIX));
    }

    @Test void java21RecordPatternRetainsTheExistingTwentyOneComponentShape() throws Exception {
        var compilation=compile(temp,Map.of("sample/ConfigPattern.java","""
                package sample;
                import com.reactor.cachedb.core.config.CacheDatabaseConfig;
                public final class ConfigPattern {
                    public static boolean matches(Object value) {
                        return value instanceof CacheDatabaseConfig(var a,var b,var c,var d,var e,var f,var g,
                            var h,var i,var j,var k,var l,var m,var n,var o,var p,var q,var r,var s,var t,var u);
                    }
                }
                """));
        assertTrue(compilation.success(),compilation.diagnostics());
    }

    @Test void preparedChunksHaveAnExplicitByteBudgetWithoutUtf8BufferAllocation() {
        assertEquals(8,EncryptedStorageChecks.preparedBytes(0,"\u011f\ud83d\ude00",Map.of("x","y")));
        long limit=EncryptedStorageChecks.MAX_PREPARED_WRITE_BYTES;
        assertEquals(limit,EncryptedStorageChecks.preparedBytes(limit-2,null,Map.of("x","y")));
        safe(assertThrows(CacheEncryptionException.class,
                ()->EncryptedStorageChecks.preparedBytes(limit-1,null,Map.of("x","y"))));
        safe(assertThrows(CacheEncryptionException.class,
                ()->EncryptedStorageChecks.preparedBytes(0,"x".repeat((int)limit+1),Map.of())));
    }

    @Test void registeredDeleteAndLegacyRecordsAreProtectedWithoutQueueMarkers() throws Exception {
        try(var fixture=fixture()){
            var registry=registry(fixture);
            var mapper=new RedisWriteOperationMapper(com.reactor.cachedb.core.trace.TraceContexts.threadLocal(),registry);
            var meta=fixture.metadata();
            var delete=new QueuedWriteOperation(OperationType.DELETE,meta.entityName(),meta.tableName(),
                    meta.redisNamespace(),"",meta.idColumn(),meta.versionColumn(),meta.deletedColumn(),
                    "1",Map.of("id","1"),2,Instant.EPOCH);
            assertTrue(mapper.protectedOperation(delete));
            var safe=mapper.safeFailure(delete,new SQLTransientException(SECRET,"40001",17,new SQLException(HIDDEN)));
            assertInstanceOf(SQLTransientException.class,safe);assertNull(safe.getCause());
            assertFalse(safe.toString().contains(SECRET));assertEquals("40001",((SQLException)safe).getSQLState());
            var legacy=new QueuedWriteOperation(OperationType.UPSERT,meta.entityName(),meta.tableName(),
                    meta.redisNamespace(),"",meta.idColumn(),meta.versionColumn(),meta.deletedColumn(),
                    "1",Map.of("id","1","contact",SECRET),2,Instant.EPOCH);
            assertTrue(mapper.protectedOperation(legacy));
            safe(assertThrows(CacheEncryptionException.class,()->mapper.toBody(legacy)));
            safe(assertThrows(CacheEncryptionException.class,()->mapper.protectedStoredFields(legacy,Map.of())));
        }
    }

    @Test void protectedRecoveryCopiesDiscardOldErrorTextAndUnknownFieldsWithoutChangingCiphertext() throws Exception {
        try(var fixture=fixture()){
            var registry=registry(fixture);var codec=codec(registry,fixture);var operation=queued(fixture,codec);
            var mapper=new RedisWriteOperationMapper(com.reactor.cachedb.core.trace.TraceContexts.threadLocal(),registry);
            var original=new LinkedHashMap<>(mapper.toBody(operation));
            original.put("deadLetterMessage",SECRET);original.put("deadLetterRootType",HIDDEN);
            original.put("unrecognized:"+SECRET,HIDDEN);original.put("deadLetterFailureCategory","TRANSIENT");
            original.put("deadLetterSqlState","40001");original.put("deadLetterVendorCode","17");
            original.put("deadLetterRetryable","true");original.put("deadLetterAttempts","2");
            var protectedFields=mapper.protectedStoredFields(operation,original);
            assertFalse(protectedFields.toString().contains(SECRET));assertFalse(protectedFields.toString().contains(HIDDEN));
            assertTrue(protectedFields.get("col:contact").startsWith("~E1:"));
            assertEquals(operation.columns(),mapper.fromBody(protectedFields).columns());
            assertEquals("40001",protectedFields.get("deadLetterSqlState"));
            assertEquals("17",protectedFields.get("deadLetterVendorCode"));assertEquals("2",protectedFields.get("deadLetterAttempts"));
            assertEquals(SECRET,original.get("deadLetterMessage"));
            var malformed=new LinkedHashMap<>(original);malformed.put("deadLetterSqlState",SECRET);
            malformed.put("deadLetterVendorCode",SECRET);malformed.put("deadLetterAttempts",SECRET);
            var sanitized=mapper.protectedStoredFields(operation,malformed);
            assertEquals("",sanitized.get("deadLetterSqlState"));assertEquals("0",sanitized.get("deadLetterVendorCode"));
            assertEquals("0",sanitized.get("deadLetterAttempts"));assertFalse(sanitized.toString().contains(SECRET));
            var plain=new RedisWriteOperationMapper().fromBody(Map.of("type","DELETE","entity","Plain",
                    "table","plain","namespace","plain","idColumn","id","id","1","version","1","createdAt",Instant.EPOCH.toString()));
            assertSame(original,mapper.protectedStoredFields(plain,original));
        }
    }

    @Test void providerClassifierCannotReintroducePlaintextDiagnosticsOrCauses() throws Exception {
        try(var fixture=fixture()){
            var registry=registry(fixture);var operation=queued(fixture,codec(registry,fixture));
            var mapper=new RedisWriteOperationMapper(com.reactor.cachedb.core.trace.TraceContexts.threadLocal(),registry);
            FailureClassifyingFlusher classifier=new FailureClassifyingFlusher(){
                public void flush(QueuedWriteOperation ignored){}
                public WriteFailureDetails classify(Exception failure){
                    assertFalse(failure.toString().contains(SECRET));assertNull(failure.getCause());
                    return new WriteFailureDetails(WriteFailureCategory.UNKNOWN,SECRET,99,true,HIDDEN,SECRET);
                }
            };
            var details=mapper.classifyFailure(operation,classifier,new SQLTransientException(SECRET,"40001",17));
            assertEquals("40001",details.sqlState());assertEquals(17,details.vendorCode());assertTrue(details.retryable());
            assertFalse(details.toString().contains(SECRET));assertFalse(details.toString().contains(HIDDEN));
            FailureClassifyingFlusher throwing=new FailureClassifyingFlusher(){
                public void flush(QueuedWriteOperation ignored){}
                public WriteFailureDetails classify(Exception failure){throw new IllegalStateException(SECRET);}
            };
            assertFalse(mapper.classifyFailure(operation,throwing,new SQLException(SECRET)).toString().contains(SECRET));
        }
    }

    @Test void malformedProtectedQueueHeadersCannotLeakParserInputOrCauses() throws Exception {
        try(var fixture=fixture()){
            var registry=registry(fixture);var operation=queued(fixture,codec(registry,fixture));
            var mapper=new RedisWriteOperationMapper(com.reactor.cachedb.core.trace.TraceContexts.threadLocal(),registry);
            for(String field:List.of("type","version","createdAt")){
                var malformed=new LinkedHashMap<>(mapper.toBody(operation));malformed.put(field,SECRET);
                safe(assertThrows(CacheEncryptionException.class,()->mapper.fromBody(malformed)));
                malformed.remove("col:"+EncryptedCodecSupport.QUEUE_FORMAT);
                safe(assertThrows(CacheEncryptionException.class,()->mapper.fromBody(malformed)));
            }
        }
    }

    @Test void protectedAtomicEnvelopeConvertsBothScopesAndFrameworkColumnsWithoutMutatingPublishedData() throws Exception {
        try(var fixture=fixture()){
            var registry=registry(fixture);var codec=codec(registry,fixture);var mapper=new RedisWriteOperationMapper();
            var columns=new LinkedHashMap<>(queued(fixture,codec).columns());columns.put(fixture.metadata().versionColumn(),"7");
            var member=new QueuedWriteOperation(OperationType.UPSERT,fixture.metadata().entityName(),fixture.metadata().tableName(),
                    fixture.metadata().redisNamespace(),"",fixture.metadata().idColumn(),fixture.metadata().versionColumn(),
                    fixture.metadata().deletedColumn(),"1",columns,7,Instant.EPOCH).inTransaction("atomic-id");
            var envelope=new QueuedWriteOperation(OperationType.UPSERT,"__atomic_batch","__atomic_batch","__atomic_batch","",
                    "id","entity_version",null,"atomic-id",Map.of(),1,Instant.EPOCH,null,null,0,"atomic-id",List.of(member),Map.of());
            var published=mapper.toBody(envelope);
            var decoded=mapper.fromBody(published);
            var sql=EncryptedQueuedWrites.sourceColumns(registry,List.of(decoded)).get(0);
            assertEquals(SECRET,sql.batchOperations().get(0).columns().get("contact"));
            assertTrue(sql.batchOperations().get(0).columns().get("amount").startsWith("~E1:"));
            assertEquals("7",sql.batchOperations().get(0).columns().get(fixture.metadata().versionColumn()));
            assertEquals(published,mapper.toBody(decoded));assertFalse(published.toString().contains(SECRET));
            assertThrows(UnsupportedOperationException.class,()->sql.batchOperations().get(0).columns().put("contact","changed"));
        }
    }

    @Test void intentMacFramesNullEmptyAndDelimitersAndFailsPreciselyWithoutMaterial() {
        var engine=new JcaFieldEncryption(keys("k1",key("k1",1)));
        String domain=engine.intentMac("default","rows","row",Arrays.asList(null,""));
        assertNotEquals(domain,engine.intentMac("default","rows","row",Arrays.asList("",null)));
        assertNotEquals(engine.intentMac("default","rows","row",List.of("a|b","c")),
                engine.intentMac("default","rows","row",List.of("a","b|c")));
        assertNotEquals(domain,engine.intentMac("default","rows","atomic",Arrays.asList(null,"")));
        assertNotEquals(domain,engine.blindIndex("default","rows","contact",""));
        var failing=new JcaFieldEncryption(new CacheKeyProvider(){
            public KeyMaterial current(String ring){throw new IllegalStateException(SECRET);}
            public Optional<KeyMaterial> byId(String ring,String id){throw new AssertionError();}
        });
        var missing=assertThrows(CacheEncryptionException.class,()->failing.intentMac("default","rows","atomic",List.of("intent")));
        assertEquals(CacheEncryptionException.Code.INTENT_KEY_UNAVAILABLE,missing.code());safe(missing);
        safe(assertThrows(CacheEncryptionException.class,
                ()->engine.intentMac("default","rows","atomic",Collections.nCopies(JcaFieldEncryption.MAX_INTENT_PARTS+1,"x"))));
        safe(assertThrows(CacheEncryptionException.class,
                ()->engine.intentMac("default","rows","atomic",List.of("x".repeat(JcaFieldEncryption.MAX_INTENT_BYTES+1)))));
    }

    @Test void intentStreamingUtf8MatchesIndependentJcaFramingAcrossBufferBoundaries() throws Exception {
        var material=key("k1",1);var engine=new JcaFieldEncryption(keys("k1",material));
        var parts=Arrays.asList("x".repeat(1023)+"\u011f\ud83d\ude00","\u0131\u015f\u00f6\u00fc\u0130",null,"");
        byte[] hmac=new byte[32];Arrays.fill(hmac,(byte)0x72);
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(hmac,"HmacSHA256"));
        var all=new ArrayList<String>();all.add("cachedb:protected-intent:v1");all.add("default");all.add("rows");all.add("atomic");
        for(String value:all){
            byte[] bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            mac.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());mac.update(bytes);
        }
        mac.update(java.nio.ByteBuffer.allocate(4).putInt(parts.size()).array());
        for(String value:parts){
            if(value==null)mac.update(java.nio.ByteBuffer.allocate(4).putInt(-1).array());
            else {byte[] bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                mac.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());mac.update(bytes);}
        }
        assertEquals("~I1:"+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal()),
                engine.intentMac("default","rows","atomic",parts));
        safe(assertThrows(CacheEncryptionException.class,
                ()->engine.intentMac("default","rows","atomic",List.of(String.valueOf((char)0xd800)))));
    }

    @Test void opaqueProtectedSourceSqlRejectsBoundAndStaticCodecsBeforeOpeningJdbc() throws Exception {
        try(var fixture=fixture()){
            var calls=new AtomicInteger();
            var forbidden=(javax.sql.DataSource)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{javax.sql.DataSource.class},(proxy,method,args)->{calls.incrementAndGet();throw new AssertionError("No opaque protected SQL");});
            for(var codec:List.of(fixture.unbound(),fixture.codec())){
                var failure=assertThrows(CacheEncryptionException.class,
                        ()->new com.reactor.cachedb.jdbc.JdbcSourceSqlRepository<>(forbidden,codec));
                assertEquals(CacheEncryptionException.Code.UNSUPPORTED_SOURCE_OPERATION,failure.code());safe(failure);
            }
            assertEquals(0,calls.get());
        }
    }

    @Test void sqlFailureRedactionPreservesOnlySafeDirectRetryMetadata() throws Exception {
        try(var fixture=fixture()){
            var operation=queued(fixture,fixture.codec());
            var sql=new SQLTransientException("SQL echoed "+SECRET,"40001",17,new IllegalStateException(HIDDEN));
            sql.setNextException(new SQLException(SECRET));
            var safe=EncryptedQueuedWrites.safeFailure(EncryptedQueuedWrites.protectedOperation(operation),sql);
            assertInstanceOf(SQLTransientException.class,safe);assertNull(safe.getCause());
            assertNull(((SQLException)safe).getNextException());assertEquals("40001",((SQLException)safe).getSQLState());
            assertFalse(safe.toString().contains(SECRET));assertEquals(17,((SQLException)safe).getErrorCode());
        }
    }
}
