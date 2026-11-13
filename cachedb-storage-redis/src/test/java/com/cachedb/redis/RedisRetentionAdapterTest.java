package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.retention.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.core.trace.TraceContexts;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
@Timeout(60)
class RedisRetentionAdapterTest {
    record Row(String id,String tenant,Instant expires,String name) {}
    @Test void evictCleansNativeIndexesAndL1ButPreservesWriteSafetyAndNeverEnqueuesSql() {
        try (var f=new Fixture(RetentionPolicy.Action.EVICT_CACHE); var admin=CacheTenantContext.administrative()) {
            f.seed("old","a",true); f.seed("fresh","a",false); f.seed("peer","b",false);
            var near=new RedisNearCacheCoordinator.Store(new com.reactor.cachedb.core.cache.NearCachePolicy(16,10,65536));
            near.put("old",f.codec.toRedisValue(f.row("old","a",true)),10000,near.epoch());
            var result=f.adapter(near).sweep(128);
            assertEquals(1,result.evicted()); assertEquals(0,result.deleteAccepted());
            assertNull(f.redis.get(f.keys.entityKey(f.ns,"old"))); assertNotNull(f.redis.get(f.keys.entityKey(f.ns,"fresh")));
            assertNotNull(f.redis.get(f.keys.entityKey(f.ns,"peer")));
            assertEquals("7",f.redis.get(f.keys.versionKey(f.ns,"old")));
            assertEquals("a",f.redis.get(f.keys.versionKey(f.ns,"old")+":tenant"));
            assertNull(f.redis.hget(f.keys.indexMetaKey(f.ns,"old"),"expires"));
            assertFalse(f.redis.sismember(f.keys.indexAllKey(f.ns),"old")); assertNull(near.get("old"));
            assertEquals(0,f.redis.xlen(f.stream));
        }
    }
    @Test void nativeDeleteEnqueuesNormalVersionedReceiptFieldsWithImmutableGeneration() {
        try (var f=new Fixture(RetentionPolicy.Action.DELETE); var admin=CacheTenantContext.administrative()) {
            f.seed("old","a",true); f.seed("fresh","a",false);
            var result=f.adapter(null).sweep(128); assertEquals(1,result.deleteAccepted());
            assertEquals("8",f.redis.get(f.keys.tombstoneKey(f.ns,"old")));
            var entries=f.redis.xrange(f.stream,new redis.clients.jedis.StreamEntryID("0-0"),new redis.clients.jedis.StreamEntryID("9999999999999-0"));
            assertEquals(1,entries.size()); var fields=entries.get(0).getFields();
            assertEquals("DELETE",fields.get("type")); assertEquals("8",fields.get("version"));
            assertEquals("1",fields.get("tenantGeneration"));
            assertEquals(RedisRetentionAdapter.hash(f.ns+"\0a"),fields.get("tenantToken"));
            assertNotNull(f.redis.get(f.keys.entityKey(f.ns,"fresh")));
        }
    }
    @Test void staleSortedScoreCannotDeleteFreshPayloadAndClosedTenantIsNotMutated() {
        try (var f=new Fixture(RetentionPolicy.Action.DELETE); var admin=CacheTenantContext.administrative()) {
            f.seed("fresh","a",false);
            f.redis.zadd(f.keys.indexSortKey(f.ns,"expires"),0,"fresh");
            f.seed("closed","b",true);
            f.redis.hset(f.keys.namespacePrefix(f.ns)+":generation:"+RedisRetentionAdapter.hash(f.ns+"\0b"),"state","CLOSED");
            var result=f.adapter(null).sweep(128);
            assertEquals(0,result.deleteAccepted()); assertEquals(2,result.skipped());
            assertEquals(0,f.redis.xlen(f.stream)); assertNotNull(f.redis.get(f.keys.entityKey(f.ns,"closed")));
        }
    }
    @Test void boundedPassDoesNotExceedQuotaAndRequiresAdmin() {
        try (var f=new Fixture(RetentionPolicy.Action.EVICT_CACHE)) {
            var target=f.adapter(null);
            assertEquals(RetentionException.Code.ADMIN_REQUIRED,assertThrows(RetentionException.class,()->target.sweep(128)).code());
            try (var admin=CacheTenantContext.administrative()) {
                for (int i=0;i<140;i++) f.seed("old"+i,"a",true);
                var first=target.sweep(130); assertEquals(130,first.examined()); assertEquals(130,first.evicted());
                assertEquals(10,target.sweep(128).evicted());
                assertThrows(RetentionException.class,()->target.sweep(10001));
            }
        }
    }
    @Test void bothActionsHonorTheLivePublicationDecisionWithoutDuplicateDeletePublication() throws Exception {
        for (var action:RetentionPolicy.Action.values()) {
            try (var f=new Fixture(action); var subscriber=new Subscription(f.channel);
                    var admin=CacheTenantContext.administrative()) {
                var target=f.adapter(null);
                f.publish.set(false); f.seed("silent","a",true); assertEquals(1,target.sweep(128).indexCleaned());
                f.redis.publish(f.channel,"barrier-one");
                assertEquals("barrier-one",subscriber.messages.poll(3,java.util.concurrent.TimeUnit.SECONDS));
                f.publish.set(true); f.seed("visible","a",true); assertEquals(1,target.sweep(128).indexCleaned());
                assertEquals(RedisNearCacheCoordinator.message(f.ns,"visible"),subscriber.messages.poll(3,java.util.concurrent.TimeUnit.SECONDS));
                f.publish.set(false); f.seed("silent-again","a",true); target.sweep(128);
                f.redis.publish(f.channel,"barrier-two");
                assertEquals("barrier-two",subscriber.messages.poll(3,java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(subscriber.messages.isEmpty());
            }
        }
    }
    @Test void openButChangedGenerationCannotAuthorizeAnOldSnapshotForEitherAction() {
        for (var action:RetentionPolicy.Action.values()) {
            try (var f=new Fixture(action); var admin=CacheTenantContext.administrative()) {
                f.seed("old","a",true);
                f.beforePublication.set(()->f.redis.hset(f.generation("a"),"generation","2"));
                var result=f.adapter(null).sweep(128);
                assertEquals(0,result.indexCleaned()); assertEquals(1,result.skipped());
                assertNotNull(f.redis.get(f.keys.entityKey(f.ns,"old")));
                assertEquals("7",f.redis.get(f.keys.versionKey(f.ns,"old"))); assertEquals(0,f.redis.xlen(f.stream));
            }
        }
    }
    @Test void replacedPayloadWithoutVersionAdvanceIsNotDeleted() {
        try (var f=new Fixture(RetentionPolicy.Action.DELETE); var admin=CacheTenantContext.administrative()) {
            f.seed("old","a",true);
            f.beforePublication.set(()->f.redis.set(f.keys.entityKey(f.ns,"old"),f.codec.toRedisValue(f.row("old","a",false))));
            var result=f.adapter(null).sweep(128);
            assertEquals(0,result.deleteAccepted()); assertEquals(1,result.skipped());
            assertEquals("7",f.redis.get(f.keys.versionKey(f.ns,"old"))); assertEquals(0,f.redis.xlen(f.stream));
        }
    }
    @Test void preAndPostIoInvalidationsRejectSegmentAndGlobalLateFillsEvenOnStorageFailure() {
        try (var f=new Fixture(RetentionPolicy.Action.DELETE); var admin=CacheTenantContext.administrative()) {
            f.seed("old","a",true);
            var near=new RedisNearCacheCoordinator.Store(new com.reactor.cachedb.core.cache.NearCachePolicy(16,10,65536));
            long global=near.epoch(); var stamp=near.capture("old");
            near.put("old","cached",null,-1,stamp,7);
            f.beforePublication.set(()->f.redis.set(f.stream,"wrong-type"));
            assertEquals(RetentionException.Code.UNSUPPORTED_LAYOUT,
                    assertThrows(RetentionException.class,()->f.adapter(near).sweep(128)).code());
            assertTrue(near.epoch()>=global+2);
            assertTrue(near.capture("old").segmentEpoch()>=stamp.segmentEpoch()+2);
            near.put("old","late-global",null,-1,global,7); assertNull(near.get("old"));
            near.put("old","late-segment",null,-1,stamp,7); assertNull(near.get("old"));
            assertNotNull(f.redis.get(f.keys.entityKey(f.ns,"old")));
        }
    }
    @Test void normalFcallUsesTheSameGenerationGuardAndReceiptFieldsAsRetention() {
        try (var f=new Fixture(RetentionPolicy.Action.DELETE); var admin=CacheTenantContext.administrative()) {
            f.seed("old","a",true);
            String library="f3_"+f.ns;
            var config=RedisFunctionsConfig.builder().libraryName(library).upsertFunctionName(library+"_upsert")
                    .deleteFunctionName(library+"_delete").compactionCompleteFunctionName(library+"_complete").build();
            f.redis.functionLoad(new RedisFunctionLibrarySource().render(config));
            try {
                var operation=new WriteOperation<Row,String>(OperationType.DELETE,f.metadata,"old",Map.of("id","old"),
                        "",0,f.clock.instant(),null,Map.of());
                var args=new ArrayList<String>();
                try (var tenant=CacheTenantContext.withTenant("a")) {
                    args.addAll(f.mapper.deleteArgs(operation,RedisGuardrailConfig.defaults(),f.write(),f.cachePolicy));
                }
                while(args.size()<20) args.add("");
                args.addAll(List.of("7","cachedb:generation:v1","9","1",RedisRetentionAdapter.hash(f.ns+"\0a"),RedisRetentionAdapter.hash(f.ns),"cachedb:version-range:v1","10","11"));
                var k=List.of(f.keys.entityKey(f.ns,"old"),f.keys.versionKey(f.ns,"old"),f.keys.tombstoneKey(f.ns,"old"),f.stream,
                        f.keys.compactionPayloadKey(f.ns,"old"),f.keys.compactionPendingKey(f.ns,"old"),
                        f.stream+":compaction",f.keys.compactionStatsKey(f.ns),f.generation("a"),
                        f.keys.namespacePrefix(f.ns)+":security:version:"+RedisRetentionAdapter.hash(f.ns+"\0a"),
                        f.keys.namespacePrefix(f.ns)+":security:version:"+RedisRetentionAdapter.hash(f.ns+"\0a")+":next",
                        f.keys.versionKey(f.ns,"old")+":tenant");
                f.redis.hset(f.generation("a"),"state","CLOSED");
                assertThrows(redis.clients.jedis.exceptions.JedisDataException.class,
                        ()->JedisPooledOperations.of(f.redis).fcall(config.deleteFunctionName(),k,args));
                assertEquals("7",f.redis.get(f.keys.versionKey(f.ns,"old")));
                f.redis.hset(f.generation("a"),"state","OPEN");
                assertEquals(8L,((Number)JedisPooledOperations.of(f.redis).fcall(config.deleteFunctionName(),k,args)).longValue());
                var entries=f.redis.xrange(f.stream,new redis.clients.jedis.StreamEntryID("0-0"),new redis.clients.jedis.StreamEntryID("9999999999999-0"));
                assertEquals("1",entries.get(0).getFields().get("tenantGeneration"));
            } finally { f.redis.functionDelete(library); }
        }
    }
    @Test void rangeTooSmallForTheWholeBatchRejectsBeforeAnyDeleteOrEnqueue() {
        try(var f=new Fixture(RetentionPolicy.Action.DELETE);var admin=CacheTenantContext.administrative()){
            f.seed("one","a",true);f.seed("two","a",true);
            String allocator=f.keys.namespacePrefix(f.ns)+":security:version:"+RedisRetentionAdapter.hash(f.ns+"\0a");
            f.redis.hset(allocator,"ceiling","8"); // Only one allocation remains for two candidates.
            assertThrows(RetentionException.class,()->f.adapter(null).sweep(128));
            assertEquals("7",f.redis.get(allocator+":next"));assertEquals(0,f.redis.xlen(f.stream));
            for(String id:List.of("one","two")){
                assertNotNull(f.redis.get(f.keys.entityKey(f.ns,id)));
                assertEquals("7",f.redis.get(f.keys.versionKey(f.ns,id)));
            }
        }
    }

    static final class Subscription implements AutoCloseable {
        final java.util.concurrent.ArrayBlockingQueue<String> messages=new java.util.concurrent.ArrayBlockingQueue<>(8);
        final java.util.concurrent.CountDownLatch ready=new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch finished=new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        final redis.clients.jedis.Jedis connection;
        final redis.clients.jedis.JedisPubSub listener;
        Subscription(String channel) throws Exception {
            var config=redis.clients.jedis.DefaultJedisClientConfig.builder().connectionTimeoutMillis(3000).socketTimeoutMillis(5000).build();
            connection=new redis.clients.jedis.Jedis(new redis.clients.jedis.HostAndPort(
                    System.getProperty("cachedb.snapshot.redis.host","127.0.0.1"),Integer.getInteger("cachedb.snapshot.redis.port",16383)),config);
            listener=new redis.clients.jedis.JedisPubSub() {
                @Override public void onSubscribe(String ignored,int count){ready.countDown();}
                @Override public void onMessage(String ignored,String message) {
                    if (!messages.offer(message)) failure.compareAndSet(null,new AssertionError("Bounded subscriber overflow"));
                }
            };
            Thread.ofVirtual().name("retention-test-subscriber").start(()->{
                try { connection.subscribe(listener,channel); }
                catch (RuntimeException error) { failure.compareAndSet(null,error); }
                finally { connection.close(); finished.countDown(); }
            });
            if (!ready.await(3,java.util.concurrent.TimeUnit.SECONDS)) {
                connection.close(); assertTrue(finished.await(3,java.util.concurrent.TimeUnit.SECONDS));
                fail("Subscriber did not become ready");
            }
        }
        public void close() throws Exception {
            listener.unsubscribe();
            if (!finished.await(3,java.util.concurrent.TimeUnit.SECONDS)) {
                connection.close(); assertTrue(finished.await(3,java.util.concurrent.TimeUnit.SECONDS),"Subscriber did not exit");
            }
            assertNull(failure.get());
        }
    }
    static final class Fixture implements AutoCloseable {
        final String ns="retention_"+UUID.randomUUID().toString().replace("-","");
        final RedisKeyStrategy keys=new RedisKeyStrategy("f3");
        final String stream=keys.namespacePrefix(ns)+":stream",channel=keys.namespacePrefix(ns)+":notify";
        final com.reactor.cachedb.core.cache.CachePolicy cachePolicy=com.reactor.cachedb.core.cache.CachePolicy.defaults()
                .withNearCachePolicy(new com.reactor.cachedb.core.cache.NearCachePolicy(16,10,65536));
        final java.util.concurrent.atomic.AtomicBoolean publish=new java.util.concurrent.atomic.AtomicBoolean(true);
        final java.util.concurrent.atomic.AtomicReference<Runnable> beforePublication=new java.util.concurrent.atomic.AtomicReference<>();
        final RedisFunctionArgsMapper mapper=new RedisFunctionArgsMapper(TraceContexts.threadLocal())
                .withNearInvalidationChannel(channel).withNearInvalidationPublisher(()->{
                    Runnable action=beforePublication.getAndSet(null); if(action!=null) action.run(); return publish.get();
                });
        final JedisPooled redis=new JedisPooled("redis://"+System.getProperty("cachedb.snapshot.redis.host","127.0.0.1")+":"
                +Integer.getInteger("cachedb.snapshot.redis.port",16383));
        final Clock clock=Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"),ZoneOffset.UTC);
        final RetentionPolicy.Action action;
        final EntityMetadata<Row,String> metadata;
        Fixture(RetentionPolicy.Action action) {
            this.action=action;
            metadata=new EntityMetadata<>() {
                public String entityName(){return "RetentionRow";} public String tableName(){return "retention_rows";}
                public String redisNamespace(){return ns;} public String idColumn(){return "id";}
                public Class<Row> entityType(){return Row.class;} public Function<Row,String> idAccessor(){return Row::id;}
                public List<String> columns(){return List.of("id","tenant","expires","name");}
                public String tenantFieldName(){return "tenant";} public String extractTenantId(Row row){return row.tenant();}
                public Map<String,String> columnTypes(){return Map.of("expires","java.time.Instant");}
                public Optional<RetentionPolicy> retentionPolicy(){return Optional.of(new RetentionPolicy("expires",1,action,RetentionPolicy.ScoreUnit.EPOCH_MILLIS));}
                public List<RelationDefinition> relations(){return List.of();}
            };
        }
        final EntityCodec<Row> codec=new EntityCodec<>() {
            public String toRedisValue(Row row){return LengthPrefixedPayloadCodec.encode(Map.of("id",row.id(),"tenant",row.tenant(),"expires",row.expires().toString(),"name",row.name()));}
            public Row fromRedisValue(String text){var map=LengthPrefixedPayloadCodec.decode(text);return new Row(map.get("id"),map.get("tenant"),Instant.parse(map.get("expires")),map.get("name"));}
            public Map<String,Object> toColumns(Row row){return Map.of("id",row.id(),"tenant",row.tenant(),"expires",row.expires(),"name",row.name());}
        };
        Row row(String id,String tenant,boolean old){return new Row(id,tenant,clock.instant().minusSeconds(old?172800:0),"name");}
        void seed(String id,String tenant,boolean old) {
            Row row=row(id,tenant,old); redis.set(keys.entityKey(ns,id),codec.toRedisValue(row));
            redis.set(keys.versionKey(ns,id),"7"); redis.set(keys.versionKey(ns,id)+":tenant",tenant);
            redis.hset(keys.indexMetaKey(ns,id),Map.of("id",id,"tenant",tenant,"expires",row.expires().toString(),"name","name"));
            redis.sadd(keys.indexAllKey(ns),id); redis.zadd(keys.indexSortKey(ns,"expires"),row.expires().toEpochMilli(),id);
            redis.hset(keys.namespacePrefix(ns)+":generation:"+RedisRetentionAdapter.hash(ns+"\0"+tenant),Map.of("state","OPEN","generation","1","epoch","1"));
            String allocator=keys.namespacePrefix(ns)+":security:version:"+RedisRetentionAdapter.hash(ns+"\0"+tenant);
            if(!redis.exists(allocator)){
                var ops=JedisPooledOperations.of(redis);var barrier=new RedisGenerationBarrier(ops,keys,ns);
                var versions=new RedisTenantVersionAllocator(ops,keys,barrier,ns);
                versions.install(new com.reactor.cachedb.core.protection.TenantVersionRange(barrier.key(tenant),1,1,1,8,1_000_007,
                        UUID.randomUUID(),versions.runId(barrier.key(tenant)),RedisRetentionAdapter.hash("fixture")));
            }
        }
        String generation(String tenant){return keys.namespacePrefix(ns)+":generation:"+RedisRetentionAdapter.hash(ns+"\0"+tenant);}
        WriteBehindConfig write(){return WriteBehindConfig.builder().streamKey(stream).durableCompactionEnabled(false).build();}
        RedisRetentionAdapter<Row,String> adapter(RedisNearCacheCoordinator.Store local) {
            var client=JedisPooledOperations.of(redis); var guardrails=RedisGuardrailConfig.builder().enabled(false).build();
            var write=write();
            return new RedisRetentionAdapter<>(client,keys,metadata,codec,Function.identity(),cachePolicy,QueryIndexConfig.defaults(),
                    RedisFunctionsConfig.defaults(),guardrails,write,mapper,
                    new RedisProducerGuard(client,guardrails,write,keys,stream+":diagnostics",100),
                    local,clock,new RedisRetentionAdapter.Settings(stream,stream+":compaction",1,4096,8*1024*1024,15000,true));
        }
        public void close() {
            try {
                String cursor="0"; int pages=0;
                do {
                    if (++pages>1000) throw new IllegalStateException("Fixture cleanup bound");
                    var page=redis.scan(cursor,new redis.clients.jedis.params.ScanParams().match("f3:"+ns+":*").count(128));
                    cursor=page.getCursor(); if (!page.getResult().isEmpty()) redis.del(page.getResult().toArray(String[]::new));
                } while(!cursor.equals("0"));
            } finally { redis.close(); }
        }
    }
}
