package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.projection.*;
import com.reactor.cachedb.core.query.QueryEvaluator;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.redis.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
@Timeout(60)
class RedisAsyncProjectionBulkSafetyTest {
    static EntityProjection<RedisBulkDeleteRedisTest.Row,RedisBulkDeleteRedisTest.Row,String> projection(
            Function<RedisBulkDeleteRedisTest.Row,RedisBulkDeleteRedisTest.Row> projector){
        return EntityProjection.<RedisBulkDeleteRedisTest.Row,RedisBulkDeleteRedisTest.Row,String>of(
                "async_safe",new ProjectionCodec<>(){
                    public String toRedisValue(RedisBulkDeleteRedisTest.Row row){return RedisBulkDeleteRedisTest.CODEC.toRedisValue(row);}
                    public RedisBulkDeleteRedisTest.Row fromRedisValue(String raw){return RedisBulkDeleteRedisTest.CODEC.fromRedisValue(raw);}
                },RedisBulkDeleteRedisTest.Row::id,List.of("id","tenant_id","value_text"),RedisBulkDeleteRedisTest.CODEC::toColumns,projector).asyncRefresh();
    }
    static RedisProjectionRefreshWorker worker(RedisBulkDeleteRedisTest.Fixture f){
        var base=f.database.config();
        return new RedisProjectionRefreshWorker(f.client,base.projectionRefresh(),new RedisProjectionRefreshQueue(f.client,base.projectionRefresh()),
                f.keys,f.database.entityRegistry(),base.queryIndex(),base.relations(),new QueryEvaluator(),null);
    }
    static void await(BooleanSupplier ready,String detail)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!ready.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(2);
        assertTrue(ready.getAsBoolean(),detail);
    }
    static void unblock(CountDownLatch release){
        try{assertTrue(release.await(5,TimeUnit.SECONDS),"Deterministic projector gate timed out");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
    }
    static void delayedRefreshContract(RedisClientOperations client,boolean recreate)throws Exception{
        try(var f=new RedisBulkDeleteRedisTest.Fixture(client,false);var tenant=CacheTenantContext.withTenant("a")){
            var started=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
            var p=projection(row->{if(once.compareAndSet(false,true)){started.countDown();unblock(release);}return row;});
            f.repository.projected(p);f.seed(1);
            String key=f.keys.entityKey(new ProjectionEntityMetadata<>(f.metadata,p).redisNamespace(),"1");
            f.client.commands().set(key,RedisBulkDeleteRedisTest.CODEC.toRedisValue(new RedisBulkDeleteRedisTest.Row("1","a","old")));
            try(var worker=worker(f)){
                worker.start();
                try{
                    assertTrue(started.await(5,TimeUnit.SECONDS),"Worker must hold a real pre-delete projection result");
                    var receipt=f.repository.bulkDelete(List.of("1")).get(0);
                    assertTrue(receipt.version()>1);assertFalse(f.client.commands().exists(key));
                    if(recreate)f.repository.save(new RedisBulkDeleteRedisTest.Row("1","a","new"));
                }finally{release.countDown();}
                await(()->worker.snapshot().processedCount()>0,"A pending stale event must be reconciled, not acknowledged as published");
                if(recreate)await(()->{
                    String payload=f.client.commands().get(key);
                    return payload!=null&&"new".equals(RedisBulkDeleteRedisTest.CODEC.fromRedisValue(payload).value());
                },"An older result must not replace the newer parent projection");
                else assertFalse(f.client.commands().exists(key),"An older result must not resurrect a deleted projection");
                assertEquals(0,f.ownerSourceCalls.get());assertEquals(0,f.source.connections.get());
                assertEquals(0,worker.snapshot().failedCount());
            }
        }
    }
    @Test void delayedAsyncRefreshCannotResurrectACompletedBulkDelete()throws Exception{
        delayedRefreshContract(RedisBulkDeleteRedisTest.standalone(),false);
    }
    @Test void delayedAsyncRefreshCannotOverwriteALaterRecreation()throws Exception{
        delayedRefreshContract(RedisBulkDeleteRedisTest.standalone(),true);
    }
    @Test void legacySingleDeleteJobReconcilesALaterSaveInsteadOfErasingItsProjection()throws Exception{
        try(var f=new RedisBulkDeleteRedisTest.Fixture(RedisBulkDeleteRedisTest.standalone(),false);var tenant=CacheTenantContext.withTenant("a")){
            var p=projection(Function.identity());f.repository.projected(p);f.seed(1);
            f.repository.deleteById("1");f.repository.save(new RedisBulkDeleteRedisTest.Row("1","a","new"));
            String key=f.keys.entityKey(new ProjectionEntityMetadata<>(f.metadata,p).redisNamespace(),"1");
            try(var worker=worker(f)){
                worker.start();await(()->worker.snapshot().processedCount()>=3,"Original UPSERT, DELETE and later UPSERT must all complete");
                assertEquals("new",RedisBulkDeleteRedisTest.CODEC.fromRedisValue(f.client.commands().get(key)).value());
                assertEquals(0,f.ownerSourceCalls.get());assertEquals(0,f.source.connections.get());
            }
        }
    }
    @Test void delayedForegroundWarmCannotPublishAfterBulkCleanup()throws Exception{
        try(var f=new RedisBulkDeleteRedisTest.Fixture(RedisBulkDeleteRedisTest.standalone(),false);var tenant=CacheTenantContext.withTenant("a")){
            var p=projection(Function.identity());var projected=f.repository.projected(p);f.seed(1);
            var started=new CountDownLatch(1);var release=new CountDownLatch(1);
            f.decodeHook.set(()->{started.countDown();unblock(release);});
            try(var reader=Executors.newSingleThreadExecutor()){
                var read=reader.submit(()->{
                    try(var scope=CacheTenantContext.withTenant("a")){return projected.findById("1");}
                });
                try{
                    assertTrue(started.await(5,TimeUnit.SECONDS),"Fallback must hold a real pre-delete parent value");
                    f.repository.bulkDelete(List.of("1"));
                }finally{release.countDown();}
                read.get(5,TimeUnit.SECONDS);
            }
            String key=f.keys.entityKey(new ProjectionEntityMetadata<>(f.metadata,p).redisNamespace(),"1");
            assertFalse(f.client.commands().exists(key),"Foreground warming must queue reconciliation, not publish the old value");
            long events=f.client.commands().xlen(f.database.config().projectionRefresh().streamKey());
            assertTrue(events>0);
            try(var worker=worker(f)){
                worker.start();await(()->worker.snapshot().processedCount()>=events,"Every queued warm/refresh must reconcile the deleted parent");
                assertFalse(f.client.commands().exists(key));assertEquals(0,f.ownerSourceCalls.get());
            }
        }
    }
    @Test void deadLetterReplayAfterBulkDeleteCannotResurrectItsOldProjection()throws Exception{
        try(var f=new RedisBulkDeleteRedisTest.Fixture(RedisBulkDeleteRedisTest.standalone(),false);var tenant=CacheTenantContext.withTenant("a")){
            var failing=new AtomicBoolean(true);var p=projection(row->{if(failing.get())throw new IllegalStateException("Injected projection failure");return row;});
            f.repository.projected(p);f.seed(1);
            try(var worker=worker(f)){
                worker.start();await(()->worker.snapshot().failedCount()==1,"The real worker must persist the failed event in DLQ");
                var failures=worker.failures(1);assertEquals(1,failures.size());
                f.repository.bulkDelete(List.of("1"));failing.set(false);
                assertTrue(worker.replayFailure(failures.get(0).entryId()).replayed());
                await(()->worker.snapshot().processedCount()==1,"Replayed refresh must reconcile the tombstone");
                String key=f.keys.entityKey(new ProjectionEntityMetadata<>(f.metadata,p).redisNamespace(),"1");
                assertFalse(f.client.commands().exists(key));assertEquals(0,f.ownerSourceCalls.get());
            }
        }
    }
}
