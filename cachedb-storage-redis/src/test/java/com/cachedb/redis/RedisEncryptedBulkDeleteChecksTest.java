package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.ResourceLimits;
import com.reactor.cachedb.core.crypto.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.protection.ProtectionException;
import com.reactor.cachedb.core.registry.DefaultEntityRegistry;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class RedisEncryptedBulkDeleteChecksTest {
    record Row(Long id,String tenant,String secret){}
    static EntityMetadata<Row,Long> metadata(boolean purge,EncryptedColumnDescriptor.Scope scope){return metadata(purge,scope,"encrypted_rows");}
    static EntityMetadata<Row,Long> metadata(boolean purge,EncryptedColumnDescriptor.Scope scope,String namespace){return metadata(purge,scope,namespace,"entity_version");}
    static EntityMetadata<Row,Long> metadata(boolean purge,EncryptedColumnDescriptor.Scope scope,String namespace,String version){
        return new EntityMetadata<>(){
            public String entityName(){return "EncryptedDelete";}
            public String tableName(){return "encrypted_rows";}
            public String redisNamespace(){return namespace;}
            public String idColumn(){return "id";}
            public String versionColumn(){return version;}
            public Class<Row> entityType(){return Row.class;}
            public java.util.function.Function<Row,Long> idAccessor(){return Row::id;}
            public List<String> columns(){return List.of("id","tenant","secret");}
            public List<RelationDefinition> relations(){return List.of();}
            public String tenantFieldName(){return "tenant";}
            public String extractTenantId(Row row){return row.tenant();}
            public boolean tenantPurgeEnabled(){return purge;}
            public Map<String,String> columnTypes(){return Map.of("id","java.lang.Long","tenant","java.lang.String","secret","java.lang.String");}
            public Map<String,EncryptedColumnDescriptor> encryptedColumns(){return Map.of("secret",new EncryptedColumnDescriptor("default",scope,true));}
        };
    }
    static EntityCodec<Row> codec(boolean implementsQ1){return codec(implementsQ1,()->{});}
    static EntityCodec<Row> codec(boolean implementsQ1,Runnable beforeDecode){
        return new EntityCodec<>(){
            public boolean encryptionBound(){return true;}
            public String toRedisValue(Row row){throw new AssertionError("No field serialization");}
            public Row fromRedisValue(String raw){throw new AssertionError("No field decode");}
            public Map<String,Object> toColumns(Row row){throw new AssertionError("No business column access");}
            public Map<String,String> sourceColumnsFromQueued(String id,Map<String,String> q){
                beforeDecode.run();
                if(!implementsQ1)return new LinkedHashMap<>(q);
                if(!Long.toString(Long.parseLong(id)).equals(id)||!EncryptedCodecSupport.QUEUE_VERSION.equals(q.get(EncryptedCodecSupport.QUEUE_FORMAT)))
                    throw EncryptedCodecSupport.invalidValue();
                EncryptedCodecSupport.queuedTenant(q.get(EncryptedCodecSupport.QUEUE_TENANT));
                return Map.of("id",q.get("id"));
            }
        };
    }
    @Test void boundBooleanWithoutQ1DecoderCannotClaimSafeIdentityOnlyDelete(){
        var meta=metadata(false,EncryptedColumnDescriptor.Scope.REDIS_ONLY);
        assertThrows(CacheEncryptionException.class,()->RedisEncryptedBulkDeleteChecks.probeIds(meta,codec(false),List.of(1L),"a",System.nanoTime()+5_000_000_000L));
        assertDoesNotThrow(()->RedisEncryptedBulkDeleteChecks.probeIds(meta,codec(true),List.of(1L,2L),"a",System.nanoTime()+5_000_000_000L));
        assertThrows(CacheEncryptionException.class,()->RedisEncryptedBulkDeleteChecks.probeIds(meta,codec(true),List.of("01"),"a",System.nanoTime()+5_000_000_000L));
    }
    @Test void registeredPurgeContractCannotBeDowngradedByCallerMetadata(){
        var registry=new DefaultEntityRegistry(ResourceLimits.defaults());
        registry.register(metadata(true,EncryptedColumnDescriptor.Scope.REDIS_ONLY),codec(true),CachePolicy.defaults(),null,null);
        var failure=assertThrows(ProtectionException.class,()->RedisEncryptedBulkDeleteChecks.requireMapping(
                metadata(false,EncryptedColumnDescriptor.Scope.REDIS_ONLY),codec(true),registry));
        assertEquals(ProtectionException.Code.UNWIRED_PROTOCOL,failure.code());
    }
    @Test void protectedDeleteStillRequiresAnOriginalLiveProducerTicket(){
        try(var tenant=CacheTenantContext.withTenant("a")){
            for(var scope:EncryptedColumnDescriptor.Scope.values()){
                var meta=metadata(true,scope);
                var failure=assertThrows(ProtectionException.class,()->RedisEncryptedBulkDeleteChecks.requireScope(meta));
                assertEquals(ProtectionException.Code.UNWIRED_PROTOCOL,failure.code());
                assertThrows(ProtectionException.class,()->RedisEncryptedBulkDeleteChecks.probeIds(
                        meta,codec(true,()->fail("Missing producer must fail before Q1 decode")),List.of(1L),"a",System.nanoTime()+5_000_000_000L));
            }
        }
    }
    @Test void liveProducerAdmitsRegisteredIdentityOnlySourceEncryptedDeleteWithoutRecapture(){
        var meta=metadata(true,EncryptedColumnDescriptor.Scope.REDIS_AND_SOURCE);
        var bound=codec(true);
        var registry=new DefaultEntityRegistry(ResourceLimits.defaults());
        registry.register(meta,bound,CachePolicy.defaults(),null,null);
        try(var f=new ProducerFixture();var tenant=CacheTenantContext.withTenant("a")){
            f.producer.execute("a",()->{
                var original=RedisTenantProducer.current(meta.redisNamespace(),"a");
                assertEquals(17,original.value());
                assertDoesNotThrow(()->RedisEncryptedBulkDeleteChecks.requireScope(meta));
                assertDoesNotThrow(()->RedisEncryptedBulkDeleteChecks.requireMapping(meta,bound,registry));
                assertDoesNotThrow(()->RedisEncryptedBulkDeleteChecks.probeIds(
                        meta,bound,List.of(1L,2L),"a",System.nanoTime()+5_000_000_000L));
                var operation=new WriteOperation<Row,Long>(OperationType.DELETE,meta,1L,Map.of("id",1L),"",0,Instant.EPOCH);
                assertDoesNotThrow(()->RedisEncryptedBulkDeleteChecks.requireIdentityOnly(operation,1));
                assertSame(original,RedisTenantProducer.current(meta.redisNamespace(),"a"));
                assertEquals(1,f.captures);
                return null;
            });
            assertThrows(ProtectionException.class,()->RedisEncryptedBulkDeleteChecks.requireScope(meta));
        }
    }
    @Test void activeProducerCannotDowngradeOrUpgradeTheRegisteredPurgeContract(){
        try(var f=new ProducerFixture();var tenant=CacheTenantContext.withTenant("a")){
            f.producer.execute("a",()->{
                for(boolean registeredPurge:List.of(false,true)){
                    var registry=new DefaultEntityRegistry(ResourceLimits.defaults());
                    registry.register(metadata(registeredPurge,EncryptedColumnDescriptor.Scope.REDIS_ONLY),codec(true),CachePolicy.defaults(),null,null);
                    var failure=assertThrows(ProtectionException.class,()->RedisEncryptedBulkDeleteChecks.requireMapping(
                            metadata(!registeredPurge,EncryptedColumnDescriptor.Scope.REDIS_ONLY),codec(true),registry));
                    assertEquals(ProtectionException.Code.UNWIRED_PROTOCOL,failure.code());
                }
                return null;
            });
        }
    }
    @Test void protectedProbeRejectsDifferentTenantNamespaceAndAdministrativeScope(){
        var meta=metadata(true,EncryptedColumnDescriptor.Scope.REDIS_ONLY);
        var noDecode=codec(true,()->fail("Wrong scope must fail before Q1 decode"));
        try(var f=new ProducerFixture();var tenant=CacheTenantContext.withTenant("a")){
            f.producer.execute("a",()->{
                assertEquals(ProtectionException.Code.INVALID_REQUEST,assertThrows(ProtectionException.class,
                        ()->RedisEncryptedBulkDeleteChecks.probeIds(meta,noDecode,List.of(1L),"b",System.nanoTime()+5_000_000_000L)).code());
                assertThrows(ProtectionException.class,()->RedisEncryptedBulkDeleteChecks.requireScope(
                        metadata(true,EncryptedColumnDescriptor.Scope.REDIS_ONLY,"other_namespace")));
                try(var other=CacheTenantContext.withTenant("b")){
                    assertThrows(ProtectionException.class,()->RedisEncryptedBulkDeleteChecks.requireScope(meta));
                }
                try(var admin=CacheTenantContext.administrative()){
                    assertThrows(ProtectionException.class,()->RedisEncryptedBulkDeleteChecks.requireScope(meta));
                }
                return null;
            });
        }
    }
    @Test void producerRevokedDuringIdentityProbeCannotReturnSuccessfulAdmission(){
        var meta=metadata(true,EncryptedColumnDescriptor.Scope.REDIS_ONLY);
        try(var f=new ProducerFixture();var tenant=CacheTenantContext.withTenant("a")){
            f.producer.execute("a",()->{
                var bound=codec(true,()->assertEquals(1,f.producer.drain(f.barrier.key("a")).active()));
                assertEquals(ProtectionException.Code.UNWIRED_PROTOCOL,assertThrows(ProtectionException.class,
                        ()->RedisEncryptedBulkDeleteChecks.probeIds(meta,bound,List.of(1L),"a",System.nanoTime()+5_000_000_000L)).code());
                return null;
            });
        }
    }
    @Test void protectedIdentityOnlyFramingStillRejectsEncryptedInputsPayloadAndUnknownVersion(){
        var meta=metadata(true,EncryptedColumnDescriptor.Scope.REDIS_AND_SOURCE);
        try(var f=new ProducerFixture();var tenant=CacheTenantContext.withTenant("a")){
            f.producer.execute("a",()->{
                var valid=new WriteOperation<Row,Long>(OperationType.DELETE,meta,1L,Map.of("id",1L),"",0,Instant.EPOCH);
                assertThrows(CacheEncryptionException.class,()->RedisEncryptedBulkDeleteChecks.requireIdentityOnly(valid,0));
                var fields=new WriteOperation<Row,Long>(OperationType.DELETE,meta,1L,Map.of("id",1L,"secret","PII"),"",0,Instant.EPOCH);
                assertThrows(CacheEncryptionException.class,()->RedisEncryptedBulkDeleteChecks.requireIdentityOnly(fields,1));
                var payload=new WriteOperation<Row,Long>(OperationType.DELETE,meta,1L,Map.of("id",1L),"PII",0,Instant.EPOCH);
                assertThrows(CacheEncryptionException.class,()->RedisEncryptedBulkDeleteChecks.requireIdentityOnly(payload,1));
                var upsert=new WriteOperation<Row,Long>(OperationType.UPSERT,meta,1L,Map.of("id",1L),"",0,Instant.EPOCH);
                assertThrows(CacheEncryptionException.class,()->RedisEncryptedBulkDeleteChecks.requireIdentityOnly(upsert,1));
                return null;
            });
        }
    }
    @Test void protectedProbeKeepsCanonicalIdValidationAndCannotUseABoundBooleanAsQ1Proof(){
        var meta=metadata(true,EncryptedColumnDescriptor.Scope.REDIS_ONLY);
        try(var f=new ProducerFixture();var tenant=CacheTenantContext.withTenant("a")){
            f.producer.execute("a",()->{
                assertThrows(CacheEncryptionException.class,()->RedisEncryptedBulkDeleteChecks.probeIds(
                        meta,codec(true),List.of("01"),"a",System.nanoTime()+5_000_000_000L));
                assertThrows(CacheEncryptionException.class,()->RedisEncryptedBulkDeleteChecks.probeIds(
                        meta,codec(false),List.of(1L),"a",System.nanoTime()+5_000_000_000L));
                return null;
            });
        }
    }
    @Test void missingRegistrationUnboundCodecAndSourceEncryptionRemainDenied(){
        var meta=metadata(false,EncryptedColumnDescriptor.Scope.REDIS_ONLY);
        assertThrows(CacheEncryptionException.class,()->RedisEncryptedBulkDeleteChecks.requireMapping(meta,codec(true),null));
        EntityCodec<Row> unbound=new EntityCodec<>(){
            public String toRedisValue(Row row){throw new AssertionError();}
            public Row fromRedisValue(String raw){throw new AssertionError();}
            public Map<String,Object> toColumns(Row row){throw new AssertionError();}
        };
        assertThrows(CacheEncryptionException.class,()->RedisEncryptedBulkDeleteChecks.requireMapping(meta,unbound,new DefaultEntityRegistry(ResourceLimits.defaults())));
        var failure=assertThrows(CacheEncryptionException.class,()->RedisEncryptedBulkDeleteChecks.requireScope(
                metadata(false,EncryptedColumnDescriptor.Scope.REDIS_AND_SOURCE)));
        assertEquals(CacheEncryptionException.Code.UNSUPPORTED_SOURCE_OPERATION,failure.code());
    }
    @Test void mismatchedRegisteredStorageMappingCannotSelectAnotherNamespace(){
        var registry=new DefaultEntityRegistry(ResourceLimits.defaults());
        registry.register(metadata(false,EncryptedColumnDescriptor.Scope.REDIS_ONLY),codec(true),CachePolicy.defaults(),null,null);
        assertThrows(CacheEncryptionException.class,()->RedisEncryptedBulkDeleteChecks.requireMapping(
                metadata(false,EncryptedColumnDescriptor.Scope.REDIS_ONLY,"other_namespace"),codec(true),registry));
    }
    @Test void deleteFramingRejectsEncryptedFieldInputAndPayloadBeforeAnyTransport(){
        var meta=metadata(false,EncryptedColumnDescriptor.Scope.REDIS_ONLY);
        var valid=new WriteOperation<Row,Long>(OperationType.DELETE,meta,1L,Map.of("id",1L),"",0,Instant.EPOCH);
        assertDoesNotThrow(()->RedisEncryptedBulkDeleteChecks.requireIdentityOnly(valid,1));
        assertThrows(CacheEncryptionException.class,()->RedisEncryptedBulkDeleteChecks.requireIdentityOnly(valid,0));
        var fields=new WriteOperation<Row,Long>(OperationType.DELETE,meta,1L,Map.of("id",1L,"secret","PII"),"",0,Instant.EPOCH);
        assertThrows(CacheEncryptionException.class,()->RedisEncryptedBulkDeleteChecks.requireIdentityOnly(fields,1));
        var payload=new WriteOperation<Row,Long>(OperationType.DELETE,meta,1L,Map.of("id",1L),"PII",0,Instant.EPOCH);
        assertThrows(CacheEncryptionException.class,()->RedisEncryptedBulkDeleteChecks.requireIdentityOnly(payload,1));
    }
    @Test void ambiguousIdAndVersionRolesCannotEnterIdentityOnlyQueueFraming(){
        assertThrows(CacheEncryptionException.class,()->RedisEncryptedBulkDeleteChecks.requireScope(
                metadata(false,EncryptedColumnDescriptor.Scope.REDIS_ONLY,"encrypted_rows","id")));
        assertThrows(CacheEncryptionException.class,()->RedisEncryptedBulkDeleteChecks.requireScope(
                metadata(false,EncryptedColumnDescriptor.Scope.REDIS_ONLY,"encrypted_rows","")));
    }
    @Test void cleanupCommandsCannotPointOutsideTheOwnedIndexNamespace(){
        var meta=metadata(false,EncryptedColumnDescriptor.Scope.REDIS_ONLY);var keys=new RedisKeyStrategy("test");
        assertDoesNotThrow(()->RedisEncryptedBulkDeleteChecks.validateIndexCommands(meta,keys,List.of(List.of("SREM",keys.indexAllKey(meta.redisNamespace()),"1"))));
        assertThrows(CacheEncryptionException.class,()->RedisEncryptedBulkDeleteChecks.validateIndexCommands(meta,keys,List.of(List.of("SREM",keys.indexAllKey("other"),"1"))));
        var state=new RedisBulkDeleteExecutor.State(1,null,"a","foreign-set\nforeign-bytes\n9",Map.of());
        assertThrows(CacheEncryptionException.class,()->RedisEncryptedBulkDeleteChecks.validateSnapshot(meta,codec(true),keys,1L,"a",state,false));
    }
    /** Unit-only capture reply. Real generation/allocator/queue fencing is tested by ProtectedProducerRootRedisTest. */
    static final class ProducerFixture implements AutoCloseable{
        int captures;
        final RedisClientOperations client=new RedisClientOperations(){
            public redis.clients.jedis.UnifiedJedis commands(){throw new AssertionError("No Redis mutation, SQL or field crypto in helper tests");}
            public Object eval(String script,List<String> keys,List<String> args){
                assertEquals(3,keys.size());
                assertTrue(args.isEmpty());
                assertTrue(script.contains("return generation"));
                captures++;
                return "17";
            }
            public void close(){}
        };
        final RedisGenerationBarrier barrier=new RedisGenerationBarrier(client,new RedisKeyStrategy("helper"),"encrypted_rows");
        final RedisTenantProducer producer=new RedisTenantProducer(barrier,"encrypted_rows",1);
        public void close(){producer.close();}
    }
}
