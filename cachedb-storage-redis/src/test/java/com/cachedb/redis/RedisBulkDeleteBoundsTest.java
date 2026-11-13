package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.config.RedisReadCoalescingConfig;
import java.util.*;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.UnifiedJedis;

class RedisBulkDeleteBoundsTest {
    @Test void duplicateInputsStillCountTowardThePreMutationTenThousandLimit() {
        assertThrows(IllegalArgumentException.class,()->RedisBulkDeleteExecutor.copyIds(Collections.nCopies(10_001,1L)));
        assertEquals(List.of(1L,2L),RedisBulkDeleteExecutor.copyIds(List.of(1L,1L,2L)));
    }
    @Test void malformedAndOversizedIdsFailWithoutInvokingArbitraryObjectToString() {
        Object hostile=new Object(){@Override public String toString(){throw new AssertionError("No magic ID coercion");}};
        assertThrows(IllegalArgumentException.class,()->RedisBulkDeleteExecutor.copyIds(List.of(hostile)));
        assertThrows(IllegalArgumentException.class,()->RedisBulkDeleteExecutor.copyIds(List.of("x".repeat(4097))));
        assertThrows(NullPointerException.class,()->RedisBulkDeleteExecutor.copyIds(Arrays.asList(1L,null)));
        var wide=new AbstractCollection<String>(){
            public int size(){return 5000;}
            public Iterator<String> iterator(){return java.util.stream.IntStream.range(0,5000).mapToObj(i->"x".repeat(4090)+i).iterator();}
        };
        assertThrows(IllegalArgumentException.class,()->RedisBulkDeleteExecutor.copyIds(wide));
    }
    @Test void twoPerRuntimeMutationAdmissionsAreSharedBoundedAndForeclosedOnClose() {
        RedisClientOperations client=new RedisClientOperations(){
            public UnifiedJedis commands(){throw new AssertionError("No network access");}
        };
        var state=new RedisRepositoryReadState(client,RedisReadCoalescingConfig.defaults());
        try {
            assertTrue(state.acquireBulkDelete());assertTrue(state.acquireBulkDelete());
            assertFalse(state.acquireBulkDelete());state.releaseBulkDelete();
            assertTrue(state.acquireBulkDelete());
            state.releaseBulkDelete();state.releaseBulkDelete();
        } finally {state.close();}
        assertThrows(java.util.concurrent.RejectedExecutionException.class,state::acquireBulkDelete);
    }
    @Test void commandCaptureReusesJedisBuildersWithoutNetworkOrUnsupportedCommands() {
        try(var commands=new RedisDeleteCommandBuffer()){
            commands.srem("exact","id");commands.zrem("sort","id");commands.del("meta");
            assertEquals(List.of(List.of("SREM","exact","id"),List.of("ZREM","sort","id"),List.of("DEL","meta")),commands.commands());
            assertThrows(IllegalArgumentException.class,()->commands.get("payload"));
        }
    }
    @Test void purgeProtectedBulkCannotReachTheLegacyIncrementOrAnyRedisCommand() {
        RedisClientOperations client=new RedisClientOperations(){
            public UnifiedJedis commands(){throw new AssertionError("No Redis command before original-generation integration");}
        };
        var metadata=new com.reactor.cachedb.core.model.EntityMetadata<String,String>(){
            public String entityName(){return "protected";}
            public String tableName(){return "protected_rows";}
            public String redisNamespace(){return "protected_rows";}
            public String idColumn(){return "id";}
            public Class<String> entityType(){return String.class;}
            public java.util.function.Function<String,String> idAccessor(){return value->value;}
            public List<String> columns(){return List.of("id");}
            public List<com.reactor.cachedb.core.model.RelationDefinition> relations(){return List.of();}
            public boolean tenantPurgeEnabled(){return true;}
        };
        var operation=new com.reactor.cachedb.core.model.WriteOperation<String,String>(
                com.reactor.cachedb.core.model.OperationType.DELETE,metadata,"id",Map.of("id","id"),"",0,java.time.Instant.now());
        var executor=new RedisFunctionExecutor(client,com.reactor.cachedb.core.config.RedisFunctionsConfig.defaults(),
                com.reactor.cachedb.core.config.RedisGuardrailConfig.defaults(),new RedisFunctionArgsMapper());
        var request=new RedisFunctionExecutor.DeleteRequest(Collections.nCopies(8,"key"),operation,
                com.reactor.cachedb.core.cache.CachePolicy.defaults(),0,"hot",null,List.of());
        var failure=assertThrows(com.reactor.cachedb.core.protection.ProtectionException.class,
                ()->executor.prepareDeleteBatch(List.of(request)));
        assertEquals(com.reactor.cachedb.core.protection.ProtectionException.Code.UNWIRED_PROTOCOL,failure.code());
    }

    @Test void customFunctionLibrariesCannotSilentlyClaimTheCanonicalBulkContract() {
        RedisClientOperations client=new RedisClientOperations(){
            public UnifiedJedis commands(){throw new AssertionError("No network for capability checks");}
        };
        var override=new RedisFunctionExecutor(client,com.reactor.cachedb.core.config.RedisFunctionsConfig.builder()
                .sourceOverride("custom").build(),com.reactor.cachedb.core.config.RedisGuardrailConfig.defaults(),new RedisFunctionArgsMapper());
        var template=new RedisFunctionExecutor(client,com.reactor.cachedb.core.config.RedisFunctionsConfig.builder()
                .templateResourcePath("/functions/custom.lua").build(),com.reactor.cachedb.core.config.RedisGuardrailConfig.defaults(),new RedisFunctionArgsMapper());
        assertFalse(override.supportsBulkDelete());assertFalse(template.supportsBulkDelete());
    }

    @Test void countAndByteWireGuardsRejectBeforeTheNetworkBoundary() {
        assertThrows(IllegalArgumentException.class,()->RedisBulkDeleteExecutor.validateWire(
                Collections.nCopies(16385,"k"),List.of()));
        assertThrows(IllegalArgumentException.class,()->RedisBulkDeleteExecutor.validateWire(
                List.of("x".repeat(16385)),List.of()));
        assertThrows(IllegalArgumentException.class,()->RedisBulkDeleteExecutor.validateWire(
                List.of("key"),List.of("x".repeat(16*1024*1024))));
    }
}
