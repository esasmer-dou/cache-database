package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.projection.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.redis.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;

/** Two actual roots/producers/read fences. Component Redis grants are NOT SQL onboarding certificates. */
@EnabledIfSystemProperty(named="cachedb.snapshot.redis", matches="true")
@Timeout(60)
class CacheDatabaseTenantRuntimeIsolationRedisTest {
    record Row(Long id,String tenant,String name) {}
    private static final String NAMESPACE="runtime_isolation_rows";
    private static final Row ROW=new Row(1L,"a","value");
    private static final CachePolicy POLICY=CachePolicy.defaults();
    private static final EntityCodec<Row> CODEC=new EntityCodec<>() {
        public String toRedisValue(Row row){return row.id()+"|"+row.tenant()+"|"+row.name();}
        public Row fromRedisValue(String value){
            var fields=value.split("\\|",-1);
            if(fields.length!=3)throw new IllegalArgumentException("Fixture payload");
            return new Row(Long.valueOf(fields[0]),fields[1],fields[2]);
        }
        public Map<String,Object> toColumns(Row row){return Map.of("id",row.id(),"tenant",row.tenant(),"name",row.name());}
        public Row fromColumns(Map<String,Object> values){return new Row(((Number)values.get("id")).longValue(),
                (String)values.get("tenant"),(String)values.get("name"));}
    };
    private static final EntityMetadata<Row,Long> META=new EntityMetadata<>() {
        public String entityName(){return "RuntimeIsolationRow";}
        public String tableName(){return "runtime_isolation_rows";}
        public String redisNamespace(){return NAMESPACE;}
        public String idColumn(){return "id";}
        public Class<Row> entityType(){return Row.class;}
        public Function<Row,Long> idAccessor(){return Row::id;}
        public String tenantFieldName(){return "tenant";}
        public String extractTenantId(Row row){return row.tenant();}
        public boolean tenantPurgeEnabled(){return true;}
        public List<String> columns(){return List.of("id","tenant","name");}
        public Set<String> indexedColumns(){return Set.of("tenant");}
        public Map<String,String> columnTypes(){return Map.of("id","java.lang.Long","tenant","java.lang.String","name","java.lang.String");}
        public List<RelationDefinition> relations(){return List.of();}
    };

    @Test void equalNamespaceAndTenantCannotBorrowAnotherDatabasesTicketOrClosedBarrier() {
        try(var f=new Pair();var tenant=CacheTenantContext.withTenant("a")) {
            assertNotSame(f.a.producer.readFence(),f.b.producer.readFence());
            assertNotEquals(f.a.producer.readFence().runtimeId(),f.b.producer.readFence().runtimeId());
            f.a.producer.execute("a",()->{
                assertTrue(f.a.producer.ownsCurrent("a"));assertFalse(f.b.producer.ownsCurrent("a"));
                for(Runnable attempted:mutations(f.b.repository)) {
                    var failure=assertThrows(ProtectionException.class,attempted::run);
                    assertEquals(ProtectionException.Code.INVALID_REQUEST,failure.code());
                    assertTrue(f.a.producer.ownsCurrent("a"),"Foreign rejection must preserve the owning ticket");
                    f.b.assertUntouched();
                }
                assertEquals(101,f.a.repository.saveWithReceipt(ROW).version());
                return null;
            });
            // The fix is not blanket quarantine: the second root can enter its OWN physical ticket.
            assertEquals(501,f.b.repository.saveWithReceipt(ROW).version());
            try(var admin=CacheTenantContext.administrative()){f.b.barrier.close(f.b.lease(2),1);}
            f.a.producer.execute("a",()->{
                assertThrows(ProtectionException.class,()->f.b.repository.saveWithReceipt(ROW));
                assertEquals("501",f.b.redis.get(f.b.keys.versionKey(NAMESPACE,1L)));
                return null;
            });
            assertThrows(RuntimeException.class,()->f.b.repository.findById(1L));
            assertEquals(0,f.a.source.calls.get());assertEquals(0,f.b.source.calls.get());
        }
    }

    @Test void ownedAndForeignTicketsCannotBypassFetchWatcherOrProjectionQuarantine() {
        try(var f=new Pair();var tenant=CacheTenantContext.withTenant("a")) {
            var aFetch=f.a.repository.withFetchPlan(FetchPlan.of("unreviewed"));
            var bFetch=f.b.repository.withFetchPlan(FetchPlan.of("unreviewed"));
            f.a.producer.execute("a",()->{
                assertUnwired(()->aFetch.saveWithReceipt(ROW));
                assertUnwired(()->bFetch.saveWithReceipt(ROW));
                assertEquals(ProtectionException.Code.INCOMPLETE_INVENTORY,assertThrows(ProtectionException.class,
                        ()->f.b.repository.registerWatcher(QuerySpec.builder().build(),event->{})).code());
                assertEquals(ProtectionException.Code.INCOMPLETE_INVENTORY,assertThrows(ProtectionException.class,
                        ()->f.a.repository.registerWatcher(QuerySpec.builder().build(),event->{})).code());
                return null;
            });
            ProjectionCodec<Row> projectionCodec=new ProjectionCodec<>() {
                public String toRedisValue(Row row){return CODEC.toRedisValue(row);}
                public Row fromRedisValue(String value){return CODEC.fromRedisValue(value);}
            };
            var projection=EntityProjection.<Row,Row,Long>of("copy",projectionCodec,Row::id,
                    META.columns(),CODEC::toColumns,Function.identity());
            f.b.database.registerProjection(META,projection);
            f.a.producer.execute("a",()->{
                for(Runnable attempted:mutations(f.b.repository))assertUnwired(attempted);
                return null;
            });
            f.b.producer.execute("a",()->{
                for(Runnable attempted:mutations(f.b.repository))assertUnwired(attempted);
                return null;
            });
            f.b.assertUntouched();
            assertEquals(101,f.a.repository.saveWithReceipt(ROW).version());
            assertEquals(0,f.a.source.calls.get());assertEquals(0,f.b.source.calls.get());
        }
    }

    @Test void anotherRootsTicketCannotHideAStillRunningProducerFromClosure() throws Exception {
        try(var f=new Pair();var executor=Executors.newSingleThreadExecutor()) {
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            Future<Integer> physical=executor.submit(()->f.b.producer.execute("a",()->{
                entered.countDown();
                try{if(!release.await(15,TimeUnit.SECONDS))throw new AssertionError("Release timeout");}
                catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}
                return 1;
            }));
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                var closing=f.b.lease(2);
                try(var admin=CacheTenantContext.administrative()){
                    f.b.barrier.close(closing,1);
                    assertTrue(f.b.producer.readFence().closeAndDrain(closing,1).drained());
                }
                try(var tenant=CacheTenantContext.withTenant("a")) {
                    f.a.producer.execute("a",()->{
                        assertThrows(ProtectionException.class,()->f.b.repository.saveWithReceipt(ROW));
                        var drain=f.b.producer.drain(f.b.barrier.key("a"));
                        assertEquals(1,drain.active());
                        assertFalse(drain.drained(),"Read drain/foreign ticket cannot certify producer closure");
                        f.b.assertUntouched();return null;
                    });
                }
            } finally {release.countDown();}
            assertEquals(1,physical.get(5,TimeUnit.SECONDS));
            assertTrue(f.b.producer.drain(f.b.barrier.key("a")).drained());
        }
    }

    private static List<Runnable> mutations(EntityRepository<Row,Long> repository) {
        return List.of(()->repository.saveWithReceipt(ROW),()->repository.saveAll(List.of(ROW)),
                ()->repository.patchWithReceipt(1L,Map.of("name","changed")),
                ()->repository.deleteWithReceipt(1L),()->repository.bulkDelete(List.of(1L)));
    }
    @Test void joinAfterCaptureRejectsActualFunctionWritesBeforeVersionQueueOrPayloadChanges() {
        try (var f=new Pair();var tenant=CacheTenantContext.withTenant("a")) {
            assertEquals(101,f.a.repository.saveWithReceipt(ROW).version());
            var observed=List.of(f.a.keys.entityKey(NAMESPACE,1L),f.a.keys.versionKey(NAMESPACE,1L),
                    f.a.keys.tombstoneKey(NAMESPACE,1L),f.a.keys.compactionPayloadKey(NAMESPACE,1L),
                    f.a.keys.compactionPendingKey(NAMESPACE,1L),f.a.keys.compactionStatsKey(NAMESPACE),
                    f.a.barrier.versionAllocatorKey(f.a.barrier.key("a"))+":next",f.a.stream);
            byte[][] before=new byte[observed.size()][];
            for(int i=0;i<observed.size();i++)before[i]=f.a.redis.dump(observed.get(i));
            f.a.producer.execute("a",()->{
                try(var late=new Root(f.a)) {
                    assertNotEquals(f.a.producer.runtimeId(),late.producer.runtimeId());
                    String registry=new RedisTenantRuntimeControl(f.a.database.redisOperations(),f.a.keys,f.a.producer).rosterKey();
                    assertEquals("REVIEW_REQUIRED",f.a.redis.hget(registry,"state"));
                    for(Runnable write:List.<Runnable>of(()->f.a.repository.saveWithReceipt(ROW),
                            ()->f.a.repository.patchWithReceipt(1L,Map.of("name","changed")),
                            ()->f.a.repository.deleteWithReceipt(1L))) {
                        var failure=assertThrows(RuntimeException.class,write::run);
                        assertTrue(failure.getMessage().contains("CDB_RUNTIME_NOT_APPROVED"),
                                failure.getClass().getName()+" "+(failure instanceof ProtectionException p?p.code():"driver-rejection"));
                        for(int i=0;i<observed.size();i++)assertArrayEquals(before[i],f.a.redis.dump(observed.get(i)),"Durable fixture state changed");
                    }
                    assertEquals(0,late.source.calls.get());return null;
                }
            });
            assertEquals(0,f.a.source.calls.get());
        }
    }
    private static void assertUnwired(Runnable action) {
        assertEquals(ProtectionException.Code.UNWIRED_PROTOCOL,assertThrows(ProtectionException.class,action::run).code());
    }
    private static final class Pair implements AutoCloseable {
        final String prefix="runtime_isolation_"+UUID.randomUUID().toString().replace("-","");
        Root a,b;
        Pair() {
            try { a=new Root(prefix+"_a",101);b=new Root(prefix+"_b",501); }
            catch(RuntimeException|Error failure) {
                try{close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
                throw failure;
            }
        }
        public void close(){try{if(b!=null)b.close();}finally{if(a!=null)a.close();}}
    }
    private static final class Root implements AutoCloseable {
        final String prefix,library,stream;final long first;
        final boolean ownsFixture;
        final JedisPooled redis;
        final TenantRuntimeInitialFixture source;
        final CacheDatabase database;final RedisKeyStrategy keys;final RedisGenerationBarrier barrier;
        final RedisTenantProducer producer;final EntityRepository<Row,Long> repository;
        final UUID operation=UUID.randomUUID();
        Root(String prefix,long first) {this(prefix,first,true,null);}
        Root(Root existing) {this(existing.prefix,existing.first,false,existing.source);}
        Root(String prefix,long first,boolean bootstrap,TenantRuntimeInitialFixture suppliedSource) {
            this.prefix=prefix;this.first=first;ownsFixture=bootstrap;library=prefix+"_functions";stream=prefix+":stream";
            TenantRuntimeInitialFixture openedSource=null;
            JedisPooled openedRedis=null;
            CacheDatabase openedDatabase=null;
            try {
                source=bootstrap?(openedSource=TenantRuntimeInitialFixture.create(META)):suppliedSource;
                redis=openedRedis=new JedisPooled(
                        System.getProperty("cachedb.snapshot.redis.host","127.0.0.1"),
                        Integer.getInteger("cachedb.snapshot.redis.port",16383));
                var functions=RedisFunctionsConfig.builder().libraryName(library)
                        .upsertFunctionName(library+"_upsert").deleteFunctionName(library+"_delete")
                        .compactionCompleteFunctionName(library+"_complete").build();
                var write=WriteBehindConfig.builder().enabled(false).streamKey(stream)
                        .deadLetterStreamKey(prefix+":dlq").compactionStreamKey(prefix+":compaction-stream").build();
                var config=CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                        .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build()).redisFunctions(functions)
                        .redisGuardrail(RedisGuardrailConfig.builder().enabled(false).build()).writeBehind(write).build();
                database=openedDatabase=new CacheDatabase(redis,source,config);
            } catch(RuntimeException|Error failure) {
                TenantRuntimeInitialFixture.closeAfterFailedConstruction(failure,openedDatabase,openedRedis,openedSource);
                throw failure;
            }
            try {
                database.start();database.register(META,CODEC,POLICY);
                producer=bootstrap?source.bootstrap(database,META,"a",8):database.configureTenantProducer(META,8);
                repository=database.repository(META,CODEC,POLICY);
                keys=database.redisKeyStrategy();
                barrier=new RedisGenerationBarrier(database.redisOperations(),keys,NAMESPACE);
                if(!bootstrap)return;
                try(var admin=CacheTenantContext.administrative()) {
                    var lease=lease(1);barrier.close(lease,1);
                    // Actual standard-root admission; these component tokens do not certify source ownership/purge.
                    var control=new RedisTenantRuntimeControl(database.redisOperations(),keys,producer);
                    control.sealClosed(new RedisTenantRuntimeControl.ApprovedRoster(ProtectionKey.hash("fixture-approval"),
                            ProtectionKey.hash("fixture-manifest"),ProtectionKey.hash("fixture-coverage"),
                            List.of(producer.runtimeId()),List.of(lease.key())),
                            List.of(new RedisTenantRuntimeControl.ClosedPair(lease,1)));
                    var allocator=new RedisTenantVersionAllocator(database.redisOperations(),keys,barrier,NAMESPACE);
                    allocator.install(new TenantVersionRange(barrier.key("a"),1,1,1,first,first+999,
                            UUID.randomUUID(),allocator.runId(barrier.key("a")),ProtectionKey.hash("component-only-inventory")));
                    // Component-only Redis grant. This test does not forge a SQL onboarding/closure certificate.
                    barrier.openPreparedGeneration(lease,1);
                    redis.set(keys.versionKey(NAMESPACE,1L)+":tenant","a");
                }
            } catch(RuntimeException|Error failure) {
                try{close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
                throw failure;
            }
        }
        Lease lease(long epoch){return new Lease(barrier.key("a"),operation,UUID.randomUUID(),epoch,120);}
        void assertUntouched() {
            assertFalse(redis.exists(keys.entityKey(NAMESPACE,1L)));
            assertFalse(redis.exists(keys.versionKey(NAMESPACE,1L)));
            assertEquals("a",redis.get(keys.versionKey(NAMESPACE,1L)+":tenant"));
            assertEquals(Long.toString(first-1),redis.get(barrier.versionAllocatorKey(barrier.key("a"))+":next"));
            assertEquals(0,redis.xlen(stream));
        }
        public void close() {
            try{database.close();}
            finally {
                try {
                    if(ownsFixture) {
                        // Collect before deleting; bounded disposable fixture scope only.
                        var found=new HashSet<String>();String cursor="0";int pages=0;
                        do {
                            if(++pages>1000)throw new AssertionError("Fixture cleanup page bound");
                            var page=redis.scan(cursor,new redis.clients.jedis.params.ScanParams().match(prefix+":*").count(128));
                            found.addAll(page.getResult());if(found.size()>10000)throw new AssertionError("Fixture cleanup key bound");
                            cursor=page.getCursor();
                        }while(!cursor.equals("0"));
                        if(!found.isEmpty())redis.del(found.toArray(String[]::new));
                        redis.functionDelete(library);redis.del("cachedb:function-library:"+library+":marker");
                    }
                } finally{try{redis.close();}finally{if(ownsFixture)source.close();}}
            }
        }
    }
}

