package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.*;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.core.trace.TraceContextPropagator;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class RedisMapperPublicationTest {
    private static final CachePolicy POLICY=CachePolicy.defaults().withNearCachePolicy(new NearCachePolicy(16,10,65536));
    record Row(String id,String tenant) {}
    private static final EntityMetadata<Row,String> META=new EntityMetadata<>() {
        public String entityName(){return "Row";} public String tableName(){return "rows";}
        public String redisNamespace(){return "rows";} public String idColumn(){return "id";}
        public Class<Row> entityType(){return Row.class;} public Function<Row,String> idAccessor(){return Row::id;}
        public List<String> columns(){return List.of("id","tenant");}
        public String tenantFieldName(){return "tenant";}
        public String extractTenantId(Row row){return row.tenant();}
        public List<RelationDefinition> relations(){return List.of();}
    };
    private static WriteOperation<Row,String> operation(OperationType type) {
        return new WriteOperation<>(type,META,"id",Map.of("id","id","tenant","tenant"),"",0,Instant.EPOCH,null,Map.of());
    }
    @Test void liveDecisionKeepsDeleteAndUpsertTenantSlotsStableAndRestoresPublication() {
        var publish=new AtomicBoolean(false);var decisions=new AtomicInteger();
        var mapper=new RedisFunctionArgsMapper().withNearInvalidationChannel("unique:channel")
                .withNearInvalidationPublisher(()->{decisions.incrementAndGet();return publish.get();});
        try (var tenant=CacheTenantContext.withTenant("tenant")) {
            for (boolean enabled:new boolean[]{false,true,false,true}) {
                publish.set(enabled);int before=decisions.get();
                var delete=mapper.deleteArgs(operation(OperationType.DELETE),RedisGuardrailConfig.defaults(),WriteBehindConfig.defaults(),POLICY);
                assertEquals(before+1,decisions.get());assertEquals(20,delete.size());
                assertEquals(enabled?"unique:channel":"",delete.get(16));
                assertEquals(enabled?RedisNearCacheCoordinator.message("rows","id"):"",delete.get(17));
                assertEquals("cachedb:tenant:v1",delete.get(18)); assertEquals("tenant",delete.get(19));
                var upsert=mapper.upsertArgs(operation(OperationType.UPSERT),POLICY,RedisGuardrailConfig.defaults(),WriteBehindConfig.defaults(),true);
                assertEquals(before+2,decisions.get());
                assertEquals(enabled?"unique:channel":"",upsert.get(upsert.size()-4));
                assertEquals("cachedb:tenant:v1",upsert.get(upsert.size()-2)); assertEquals("tenant",upsert.get(upsert.size()-1));
            }
        }
    }
    @Test void disabledNearCacheDoesNotConsultOrPublishAndConfiguredTraceIsUsed() {
        var trace=new TraceContextPropagator() {
            public Map<String,String> capture(){return Map.of("trace-id","configured");}
            public Scope restore(Map<String,String> ignored){return ()->{};}
        };
        var mapper=new RedisFunctionArgsMapper(trace).withNearInvalidationPublisher(()->{throw new AssertionError("No L1");});
        assertEquals("",mapper.nearInvalidation("rows","id",CachePolicy.defaults()).channel());
        try (var tenant=CacheTenantContext.withTenant("tenant")) {
            var args=mapper.deleteArgs(operation(OperationType.DELETE),RedisGuardrailConfig.defaults(),WriteBehindConfig.defaults(),CachePolicy.defaults());
            assertFalse(args.get(15).isEmpty()); assertEquals("",args.get(16));
            assertEquals("cachedb:tenant:v1",args.get(18));
        }
    }
}
