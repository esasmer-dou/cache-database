package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import static com.reactor.cachedb.redis.ProtectedReadFixture.*;
import com.reactor.cachedb.core.async.CacheAsyncExecutor;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.page.VersionedEntity;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.projection.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.core.trace.TraceContexts;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class RedisProtectedPointReadsTest {
    static final Row ROW=new Row("id","a","sensitive_value");
    static final RedisKeyStrategy KEYS=new RedisKeyStrategy("read_test");

    static RedisProtectedPointReads<Row,String> points(State state,RedisTenantReadFence fence,
            RedisProtectedPointReads.Source<Row,String> source,RedisProtectedPointReads.Fill<Row> fill) {
        state.command=plan->Arrays.asList(CODEC.toRedisValue(ROW),null,"9","-1");
        return new RedisProtectedPointReads<>(fence,metadata(NS),CODEC,KEYS,null,row->true,
                source,fill,Set.of("reviewed"));
    }

    @Test void protectedActualRepositoryPointBulkVersionedAndCloneNeverUseLegacyIo() {
        State state=new State();
        state.command=plan->Arrays.asList(CODEC.toRedisValue(ROW),null,"9","-1");
        try(var fence=new RedisTenantReadFence(state,4,List.of());var tenant=CacheTenantContext.withTenant("a")) {
            var repo=repository();
            repo.withTenantReadFence(fence,null,null,Set.of());
            assertEquals(ROW,repo.findById("id").orElseThrow());
            assertEquals(ROW,repo.findHotById("id").value());
            assertEquals(9,repo.findVersionedById("id").orElseThrow().version());
            assertEquals(List.of(ROW,ROW),repo.findAll(List.of("id","id")));
            assertEquals(ROW,repo.withFetchPlan(FetchPlan.empty()).findById("id").orElseThrow());
            state.closeGeneration();
            assertThrows(ProtectionException.class,()->repo.findById("id"));
        }
    }

    @Test void protectedAsyncUsesTheExistingBoundedWorkerNotNativeRawTransport() throws Exception {
        State state=new State();
        Thread caller=Thread.currentThread();
        state.command=plan->{
            assertNotSame(caller,Thread.currentThread(),"Protected I/O must not execute inline");
            assertTrue(Thread.currentThread().isVirtual(),"Use the runtime-owned virtual worker");
            assertEquals("a",CacheTenantContext.currentTenant().orElseThrow());
            return Arrays.asList(CODEC.toRedisValue(ROW),null,"9","-1");
        };
        var raw=new RedisReadFakeClient();
        try(var fence=new RedisTenantReadFence(state,4,List.of());
                var runtime=new RedisRepositoryReadState(raw,RedisReadCoalescingConfig.defaults());
                var gate=new CacheAsyncExecutor(4,TraceContexts.threadLocal());
                var tenant=CacheTenantContext.withTenant("a")) {
            var repo=repository().withReadRuntime(runtime).withTenantReadFence(fence,null,null,Set.of());
            assertEquals(ROW,repo.async(gate).findByIdAsync("id").get(5,TimeUnit.SECONDS).orElseThrow());
            assertEquals(List.of(ROW),repo.async(gate).findAllAsync(List.of("id")).get(5,TimeUnit.SECONDS));
            assertEquals(4,gate.snapshot().maxConcurrent());
            assertEquals(2,gate.snapshot().accepted());
            assertEquals(0,gate.snapshot().rejected());
            assertEquals(2,state.executions.get());
            assertEquals(0,raw.calls.get());
        }
    }

    @Test void unboundReadAndUncertifiedQueryOrFetchPlanFailBeforeAnyLegacyIo() {
        try(var tenant=CacheTenantContext.withTenant("a")) {
            var repo=repository();
            assertEquals(ProtectionException.Code.UNWIRED_PROTOCOL,assertThrows(ProtectionException.class,()->repo.findById("id")).code());
            assertEquals(ProtectionException.Code.INCOMPLETE_INVENTORY,assertThrows(ProtectionException.class,
                    ()->repo.query(QuerySpec.where(QueryFilter.eq("value","sensitive_value")).limitTo(10))).code());
            assertThrows(ProtectionException.class,()->repo.withFetchPlan(FetchPlan.of("unknown")).findById("id"));
        }
    }

    @Test void sourceCloseBeforeFillDoesNotPublishOrReturnAndDrainWaitsForPhysicalLoader() throws Exception {
        State state=new State();var loaded=new CountDownLatch(1);var release=new CountDownLatch(1);
        var plans=new AtomicInteger();
        try(var fence=new RedisTenantReadFence(state,4,List.of());var worker=Executors.newSingleThreadExecutor()) {
            var bridge=points(state,fence,(original,id)->{loaded.countDown();await(release);return Optional.of(new VersionedEntity<>(ROW,9));},
                    (original,row,version)->{plans.incrementAndGet();return plan(original);});
            state.command=p->Arrays.asList(null,null,"9","-2");
            Future<?> result=worker.submit(()->bridge.findById("a","id",0));
            await(loaded);state.closeGeneration();
            assertFalse(fence.closeAndDrain(LEASE,1).drained());
            release.countDown();
            assertInstanceOf(ProtectionException.class,assertThrows(ExecutionException.class,()->result.get(5,TimeUnit.SECONDS)).getCause());
            assertEquals(0,plans.get());
            assertTrue(fence.closeAndDrain(LEASE,1).drained());
        } finally {release.countDown();}
    }

    @Test void sourceFillCarriesOriginalObjectAndCausalMissNeverUsesSource() {
        State state=new State();var calls=new AtomicInteger();
        var originalStamp=new java.util.concurrent.atomic.AtomicReference<com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation>();
        try(var fence=new RedisTenantReadFence(state,4,List.of())) {
            var bridge=points(state,fence,(original,id)->{originalStamp.set(original);calls.incrementAndGet();return Optional.of(new VersionedEntity<>(ROW,9));},
                    (original,row,version)->{assertSame(originalStamp.get(),original);return plan(original);});
            state.command=p->p.body().equals("return 1")?1L:Arrays.asList(null,null,"9","-2");
            assertEquals(ROW,bridge.findById("a","id",0).orElseThrow());
            assertEquals(1,calls.get());
            assertThrows(ProtectionException.class,()->bridge.findById("a","id",9));
            assertEquals(1,calls.get());
        }
    }

    @Test void admittedUnwrittenZeroIsAbsentAcrossPointBulkAndVersionedReadsWithoutSource() {
        State state=new State();var calls=new AtomicInteger();var fills=new AtomicInteger();
        try(var fence=new RedisTenantReadFence(state,4,List.of())) {
            var bridge=points(state,fence,(original,id)->{calls.incrementAndGet();return Optional.of(new VersionedEntity<>(ROW,9));},
                    (original,row,version)->{fills.incrementAndGet();return plan(original);});
            state.command=p->Arrays.asList(null,null,"0","-2");
            assertTrue(bridge.findById("a","fresh",0).isEmpty());
            assertTrue(bridge.findHotById("a","fresh",0).isNotCached());
            assertTrue(bridge.findVersionedById("a","fresh",0).isEmpty());
            assertTrue(bridge.findAll("a",List.of("fresh","fresh"),id->0).isEmpty());
            state.command=p->p.keys().getFirst().equals(KEYS.entityKey(NS,"fresh"))
                    ?Arrays.asList(null,null,"0","-2"):Arrays.asList(CODEC.toRedisValue(ROW),null,"9","-1");
            assertEquals(List.of(ROW,ROW),bridge.findAll("a",List.of("fresh","id","fresh","id"),
                    id->id.equals("id")?9:0));
            assertEquals(9,bridge.findVersionedById("a","id",9).orElseThrow().version());
            assertEquals(0,calls.get());assertEquals(0,fills.get());
        }
    }

    @Test void admittedUnwrittenZeroIsAbsentForProjectionAndRefreshWithoutSource() {
        State state=new State();var calls=new AtomicInteger();var projected=new AtomicInteger();var planned=new AtomicInteger();
        try(var fence=new RedisTenantReadFence(state,4,List.of())) {
            var bridge=points(state,fence,(original,id)->{calls.incrementAndGet();return Optional.of(new VersionedEntity<>(ROW,9));},null);
            var projection=projection("reviewed",row->{projected.incrementAndGet();return row;});
            var view=bridge.projected("a",projection);
            state.command=p->Arrays.asList(null,null,"0","-2");
            assertTrue(view.findById("fresh").isEmpty());
            assertTrue(view.findAll(List.of("fresh","fresh")).isEmpty());
            assertEquals(0,bridge.refreshProjection("a",projection,List.of("fresh"),(original,rows)->{
                planned.incrementAndGet();assertTrue(rows.isEmpty());return List.of();
            }));
            assertEquals(1,planned.get());assertEquals(0,projected.get());assertEquals(0,calls.get());
            state.command=p->p.keys().getFirst().equals(KEYS.entityKey(NS,"fresh"))
                    ?Arrays.asList(null,null,"0","-2"):Arrays.asList(CODEC.toRedisValue(ROW),null,"9","-1");
            assertEquals(List.of(ROW,ROW),view.findAll(List.of("fresh","id","fresh","id")));
            assertEquals(2,projected.get());assertEquals(0,calls.get());
        }
    }

    @Test void admittedUnwrittenZeroRejectsPositiveAndNegativeMinimumVersionsWithoutSource() {
        State state=new State();var calls=new AtomicInteger();
        try(var fence=new RedisTenantReadFence(state,4,List.of())) {
            var bridge=points(state,fence,(original,id)->{calls.incrementAndGet();return Optional.empty();},null);
            state.command=p->Arrays.asList(null,null,"0","-2");
            assertEquals(ProtectionException.Code.GENERATION_MISMATCH,assertThrows(ProtectionException.class,
                    ()->bridge.findById("a","fresh",1)).code());
            assertEquals(ProtectionException.Code.GENERATION_MISMATCH,assertThrows(ProtectionException.class,
                    ()->bridge.findHotById("a","fresh",1)).code());
            assertEquals(ProtectionException.Code.GENERATION_MISMATCH,assertThrows(ProtectionException.class,
                    ()->bridge.findVersionedById("a","fresh",1)).code());
            assertEquals(ProtectionException.Code.GENERATION_MISMATCH,assertThrows(ProtectionException.class,
                    ()->bridge.findAll("a",List.of("fresh"),id->1)).code());
            assertEquals(ProtectionException.Code.INVALID_REQUEST,assertThrows(ProtectionException.class,
                    ()->bridge.findById("a","fresh",-1)).code());
            assertEquals(ProtectionException.Code.INVALID_REQUEST,assertThrows(ProtectionException.class,
                    ()->bridge.findHotById("a","fresh",-1)).code());
            assertEquals(ProtectionException.Code.INVALID_REQUEST,assertThrows(ProtectionException.class,
                    ()->bridge.findVersionedById("a","fresh",-1)).code());
            assertEquals(ProtectionException.Code.INVALID_REQUEST,assertThrows(ProtectionException.class,
                    ()->bridge.findAll("a",List.of("fresh"),id->-1)).code());
            assertEquals(0,calls.get());
        }
    }

    @Test void zeroVersionWithPayloadOrTombstoneRequiresMigrationWithoutSource() {
        State state=new State();var calls=new AtomicInteger();
        try(var fence=new RedisTenantReadFence(state,4,List.of())) {
            var bridge=points(state,fence,(original,id)->{calls.incrementAndGet();return Optional.empty();},null);
            for(var reply:List.of(Arrays.asList(CODEC.toRedisValue(ROW),null,"0","-1"),
                    Arrays.asList("",null,"0","-1"),Arrays.asList(null,"9","0","-2"),
                    Arrays.asList(null,"","0","-2"),Arrays.asList(CODEC.toRedisValue(ROW),"9","0","-1"))) {
                state.command=p->reply;
                for(long minimum:new long[]{0,1}) {
                    assertEquals(ProtectionException.Code.MIGRATION_REQUIRED,assertThrows(ProtectionException.class,
                            ()->bridge.findById("a","id",minimum)).code());
                    assertEquals(ProtectionException.Code.MIGRATION_REQUIRED,assertThrows(ProtectionException.class,
                            ()->bridge.findAll("a",List.of("id"),id->minimum)).code());
                }
                assertEquals(ProtectionException.Code.MIGRATION_REQUIRED,assertThrows(ProtectionException.class,
                        ()->bridge.projected("a",projection("reviewed",Function.identity())).findById("id")).code());
            }
            assertEquals(0,calls.get());
        }
    }

    @Test void malformedVersionMarkersRequireMigrationWithoutSourceOrEntityDecode() {
        State state=new State();var calls=new AtomicInteger();
        try(var fence=new RedisTenantReadFence(state,4,List.of())) {
            var bridge=points(state,fence,(original,id)->{calls.incrementAndGet();return Optional.empty();},null);
            for(String version:List.of("","00","-0","+0","0.0"," 0","0 ","-1","not-a-version","01","+1","9223372036854775808")) {
                state.command=p->Arrays.asList(null,null,version,"-2");
                assertEquals(ProtectionException.Code.MIGRATION_REQUIRED,assertThrows(ProtectionException.class,
                        ()->bridge.findById("a","id",0)).code());
                state.command=p->Arrays.asList("invalid_payload",null,version,"-1");
                assertEquals(ProtectionException.Code.MIGRATION_REQUIRED,assertThrows(ProtectionException.class,
                        ()->bridge.findById("a","id",0)).code());
            }
            state.command=p->Arrays.asList(CODEC.toRedisValue(ROW),null,null,"-1");
            assertEquals(ProtectionException.Code.MIGRATION_REQUIRED,assertThrows(ProtectionException.class,
                    ()->bridge.findById("a","id",0)).code());
            assertEquals(0,calls.get());
        }
    }

    @Test void admittedUnwrittenZeroStillRequiresFinalGenerationValidation() {
        State state=new State();var calls=new AtomicInteger();
        try(var fence=new RedisTenantReadFence(state,4,List.of())) {
            var bridge=points(state,fence,(original,id)->{calls.incrementAndGet();return Optional.empty();},null);
            state.command=p->{state.closeGeneration();return Arrays.asList(null,null,"0","-2");};
            assertEquals(ProtectionException.Code.GENERATION_MISMATCH,assertThrows(ProtectionException.class,
                    ()->bridge.findById("a","fresh",0)).code());
            assertEquals(0,calls.get());
        }
    }

    @Test void unknownProjectionNeverReadsAndIdentityChangingProjectionIsIncomplete() {
        State state=new State();
        try(var fence=new RedisTenantReadFence(state,4,List.of())) {
            var bridge=points(state,fence,null,null);
            assertEquals(ProtectionException.Code.INCOMPLETE_INVENTORY,assertThrows(ProtectionException.class,
                    ()->bridge.projected("a",projection("unknown",Function.identity()))).code());
            assertEquals(0,state.executions.get());
            var changed=projection("reviewed",row->new Row("another",row.tenant(),row.value()));
            assertEquals(ProtectionException.Code.INCOMPLETE_INVENTORY,assertThrows(ProtectionException.class,
                    ()->bridge.projected("a",changed).findById("id")).code());
        }
    }

    @Test void projectionCloseDuringProjectorRejectsHeldResultAndDuringPlannerRejectsRefresh() throws Exception {
        State state=new State();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var fence=new RedisTenantReadFence(state,4,List.of());var worker=Executors.newSingleThreadExecutor()) {
            var bridge=points(state,fence,null,null);
            var projection=projection("reviewed",row->{entered.countDown();await(release);return row;});
            Future<?> result=worker.submit(()->bridge.projected("a",projection).findById("id"));
            await(entered);state.closeGeneration();assertFalse(fence.closeAndDrain(LEASE,1).drained());release.countDown();
            assertInstanceOf(ProtectionException.class,assertThrows(ExecutionException.class,()->result.get(5,TimeUnit.SECONDS)).getCause());
            assertTrue(fence.closeAndDrain(LEASE,1).drained());
        } finally {release.countDown();}
        State next=new State();var planning=new CountDownLatch(1);var publish=new CountDownLatch(1);
        try(var fence=new RedisTenantReadFence(next,4,List.of());var worker=Executors.newSingleThreadExecutor()) {
            var bridge=points(next,fence,null,null);
            Future<?> result=worker.submit(()->bridge.refreshProjection("a",projection("reviewed",Function.identity()),List.of("id"),
                    (original,rows)->{planning.countDown();await(publish);return List.of(plan(original));}));
            await(planning);next.closeGeneration();assertFalse(fence.closeAndDrain(LEASE,1).drained());publish.countDown();
            assertInstanceOf(ProtectionException.class,assertThrows(ExecutionException.class,()->result.get(5,TimeUnit.SECONDS)).getCause());
            assertEquals(1,next.executions.get()); // Base read only, no refresh publication.
        } finally {publish.countDown();}
    }

    @Test void protectedBulkAndByteBudgetsAndErrorsAreBoundedWithoutSensitiveCauses() {
        State state=new State();
        try(var fence=new RedisTenantReadFence(state,4,List.of())) {
            var bridge=points(state,fence,null,null);
            assertThrows(ProtectionException.class,()->bridge.findAll("a",Collections.nCopies(129,"id"),id->0));
            state.command=p->Arrays.asList(CODEC.toRedisValue(new Row("id","a","x".repeat(100000))),null,"9","-1");
            assertEquals(ProtectionException.Code.INVALID_REQUEST,assertThrows(ProtectionException.class,
                    ()->bridge.findAll("a",Collections.nCopies(100,"id"),id->0)).code());
            state.command=p->{throw new IllegalStateException("sensitive_value");};
            var error=assertThrows(ProtectionException.class,()->bridge.findById("a","id",0));
            assertNull(error.getCause());assertFalse(error.toString().contains("sensitive_value"));
        }
    }

    static EntityProjection<Row,Row,String> projection(String name,Function<Row,Row> projector) {
        return EntityProjection.of(name,new ProjectionCodec<Row>() {
            public String toRedisValue(Row row) { return CODEC.toRedisValue(row); }
            public Row fromRedisValue(String payload) { return CODEC.fromRedisValue(payload); }
        },Row::id,List.of("id","value"),row->Map.of("id",row.id(),"value",row.value()),projector);
    }

    static RedisEntityRepository<Row,String> repository() {
        RedisClientOperations unused=new RedisClientOperations() {
            public redis.clients.jedis.UnifiedJedis commands() { throw new AssertionError("Legacy transport must not run"); }
        };
        var metadata=metadata(NS);var policy=CachePolicy.defaults();var keys=KEYS;
        var page=new RedisPageCacheManager<>(unused,metadata,CODEC,policy,keys,null,null,null,ReadShapeGuardrailConfig.defaults());
        return new RedisEntityRepository<>(unused,metadata,CODEC,null,keys,FetchPlan.empty(),policy,
                null,null,RedisGuardrailConfig.defaults(),"unused","unused_compact",1,null,null,
                RelationConfig.defaults(),QueryIndexConfig.defaults(),null,null,null,PageCacheConfig.defaults(),
                ReadThroughConfig.defaults(),ReadShapeGuardrailConfig.defaults(),page,new QueryEvaluator(),
                null,null,unused,null,null);
    }
}
