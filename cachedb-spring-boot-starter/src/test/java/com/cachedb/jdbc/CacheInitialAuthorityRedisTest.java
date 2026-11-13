package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import static com.reactor.cachedb.jdbc.CacheInitialNativeAmbiguityRedisTest.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.InitialTenantBootstrap.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.redis.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Real native/SQL authority boundaries. No fabricated bridge, ACK, OPEN receipt or source claim. */
@EnabledIfSystemProperty(named="cachedb.snapshot.providers",matches="true")
@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
@EnabledIfSystemProperty(named="cachedb.initial.isolatedSql",matches="true")
@Timeout(120)
class CacheInitialAuthorityRedisTest {
    private static final String NS="native_rows",ENTITY="NativeInitialRow";

    @Test void publicPrepareOnSqlOpenCannotReplaceTheOriginalGrantOrDeleteAnyJournal()throws Exception {
        try(var admin=CacheTenantContext.administrative();var f=fixture()) {
            var b=bind(f);until(f,b,Phase.READY);
            f.faults.arm("UPDATE cachedb_tenant_guard SET generation=?,gate_state=?",true);
            rejected(ProtectionException.Code.STORAGE_UNAVAILABLE,()->b.service().step(b.request()));f.faults.requireConsumed();
            var saved=state(f,b.request());assertEquals(Phase.SQL_OPEN,saved.phase);assertFalse(saved.finalizing);
            var original=InitialBootstrapJournalCodec.activationFields(saved.activation);var sqlBefore=sqlRange(f);
            var bytesBefore=journalBytes(f,b.request());var payloadBefore=payloadsAbsent(f);
            var pair=b.namespace().pendingClosure().orElseThrow();
            var evidence=b.enrollment().readFence(ENTITY).closeAndDrain(pair.lease(),pair.generation(),saved.manifest,"",128,1000);
            assertEquals(TenantReadFence.State.CLOSED,evidence.state());assertEquals(1,evidence.acknowledged());
            var actualClosure=new Closure(evidence,saved.runId,saved.roster);
            var sql=sql(f,b);var first=sql.acquire(b.request());
            try {
                var same=sql.prepareActivation(first,actualClosure,saved.runtimeProof,64);
                f.evidence("AUTHORITY_PREPARE originalGrant="+saved.activation.range().request()+" observedGrant="+same.range().request()
                        +" originalReservation="+saved.activation.range().reservationEpoch()+" observedReservation="+same.range().reservationEpoch());
                assertEquals(original,InitialBootstrapJournalCodec.activationFields(same));
            }finally{sql.release(first);}
            var replacement=sql.acquire(b.request());
            try {
                rejected(ProtectionException.Code.LEASE_LOST,()->sql.prepareActivation(first,actualClosure,saved.runtimeProof,64));
                assertEquals(original,InitialBootstrapJournalCodec.activationFields(sql.activation(replacement)));
            }finally{sql.release(replacement);}
            rejected(ProtectionException.Code.RECOVERY_REQUIRED,()->b.service().step(b.request()));
            assertTrue(Arrays.equals(bytesBefore,journalBytes(f,b.request())),"No finalizing/ACTIVE or GC without native OPEN");
            assertEquals(sqlBefore,sqlRange(f));assertEquals(payloadBefore,payloadsAbsent(f));
            assertEquals("CLOSED",f.redis.hget(b.enrollment().barrier(ENTITY).generationKey(f.generation.key()),"state"));
            assertTrue(f.originalSource.equals(f.sourceSnapshot()),"Source changed");
            f.evidence("PASS authority=SQL_OPEN-nativeCLOSED directPrepareStable=true staleLeaseRejected=true rootRejected=true noGC=true sourceUnchanged=true");
        }
    }

    @Test void canonicalOpenResumesAfterPageGcAndConsumedRangeWithoutClosingOrReinstallingIt()throws Exception {
        try(var admin=CacheTenantContext.administrative();var f=fixture()) {
            var b=openedAfterGc(f);var before=state(f,b.request());
            var barrier=b.enrollment().barrier(ENTITY);var pending=b.namespace().pendingClosure().orElseThrow();
            var nativeBefore=f.redis.hgetAll(barrier.generationKey(f.generation.key()));
            var refused=assertThrows(RuntimeException.class,()->barrier.close(pending.lease(),1));
            assertTrue(refused.getMessage().contains("PURGE_FENCE_LOST"));
            assertThrows(RuntimeException.class,()->barrier.close(pending.lease(),2));
            assertThrows(RuntimeException.class,()->barrier.installClosedGeneration(pending.lease(),2));
            barrier.installClosedGeneration(pending.lease(),1); // exact OPEN replay is not a re-close
            assertEquals(nativeBefore,f.redis.hgetAll(barrier.generationKey(f.generation.key())));
            var allocator=new RedisTenantVersionAllocator(f.database.redisOperations(),f.database.redisKeyStrategy(),barrier,NS);
            String version=f.database.redisKeyStrategy().versionKey(NS,1L);
            String used=b.producer().execute("a",()->allocator.mutate(b.producer().requireCurrent("a"),version+":tenant","a",version,
                    f.redis.get(version),List.of(),List.of(),""));
            assertEquals(before.activation.range().first(),Long.parseLong(used));
            var rangeBefore=f.redis.hgetAll(allocator.allocatorKey(f.generation.key()));var sqlBefore=sqlRange(f);
            String counter=f.redis.get(allocator.counterKey(f.generation.key()));var payloadBefore=payloads(f);
            until(f,b,Phase.ACTIVE);
            assertEquals(InitialBootstrapJournalCodec.activationFields(before.activation),
                    InitialBootstrapJournalCodec.activationFields(state(f,b.request()).activation));
            assertEquals(sqlBefore,sqlRange(f));assertEquals(rangeBefore,f.redis.hgetAll(allocator.allocatorKey(f.generation.key())));
            assertEquals(counter,f.redis.get(allocator.counterKey(f.generation.key())));assertEquals(payloadBefore,payloads(f));
            var nativeAfter=f.redis.hgetAll(barrier.generationKey(f.generation.key()));
            assertTrue(Long.parseLong(nativeAfter.get("i:finalize_epoch"))>Long.parseLong(nativeBefore.get("i:finalize_epoch")));
            var unchanged=new HashMap<>(nativeAfter);unchanged.put("i:finalize_epoch",nativeBefore.get("i:finalize_epoch"));
            assertEquals(nativeBefore,unchanged,"Only the derived administrative observation epoch may advance");
            assertEquals(used,f.redis.get(version));assertTrue(f.originalSource.equals(f.sourceSnapshot()),"Source changed");
            f.evidence("PASS authority=canonicalOPEN pageGC=true consumedOriginalRange=1 sameLeaseRecloseRejected=true originalActivationAndRange=true sourceUnchanged=true");
        }
    }

    @Test void actualSqlGuardRowLockSerializesFinalizationBeforeARealNewLeaseClose()throws Exception {
        try(var admin=CacheTenantContext.administrative();var f=fixture()) {
            var b=openedAfterGc(f);var before=state(f,b.request());var rangeBefore=sqlRange(f);
            var oldSql=sql(f,b);var earlier=oldSql.acquire(b.request());oldSql.release(earlier);
            String table=firstRemaining(f,b.request());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            var backend=new AtomicInteger();assertTrue(f.faults.paused.compareAndSet(null,new InitialSqlFaults.Pause("DELETE FROM "+table,entered,release,backend)));
            // Separate real guard instance avoids a process-local semaphore masquerading as DB serialization.
            var closer=new JdbcTenantPurgeGuard(f.source,f.sandbox.dialect,2,1,Set.of(f.binding.fingerprint()));
            var options=new PurgeOptions(UUID.randomUUID(),"a",ProtectionKey.hash("authority-close:"+f.run),false,100,120,5000,
                    PurgeScope.ACTIVE_STORES_RETAINING_SECURITY_METADATA);
            try(var workers=Executors.newFixedThreadPool(2)) {
                var finishing=workers.submit(()->{try(var scope=CacheTenantContext.administrative()){return b.service().step(b.request());}});
                Future<Lease> closing=null;
                try {
                    assertTrue(entered.await(5,TimeUnit.SECONDS));assertTrue(backend.get()>0);
                    var nativeBefore=f.redis.hgetAll(b.enrollment().barrier(ENTITY).generationKey(f.generation.key()));
                    assertTrue(Long.parseLong(nativeBefore.get("i:finalize_epoch"))>earlier.lease().epoch());
                    assertThrows(RuntimeException.class,()->b.enrollment().barrier(ENTITY).close(earlier.lease(),1));
                    assertEquals(nativeBefore,f.redis.hgetAll(b.enrollment().barrier(ENTITY).generationKey(f.generation.key())));
                    closing=workers.submit(()->{try(var scope=CacheTenantContext.administrative()) {
                        try{return closer.acquireForTenant(f.binding,"a",options,false);}catch(ProtectionException busy){
                            assertEquals(ProtectionException.Code.BUSY,busy.code());return null;}
                    }});
                    assertDatabaseWaiter(f,backend.get());assertFalse(finishing.isDone());assertFalse(closing.isDone());
                }finally{release.countDown();}
                assertEquals(Phase.SQL_OPEN,finishing.get(5,TimeUnit.SECONDS).phase());
                assertNotNull(closing);Lease close=closing.get(5,TimeUnit.SECONDS);
                if(close==null)close=closer.acquireForTenant(f.binding,"a",options,false); // one explicit post-exit retry
                try {
                    assertTrue(close.epoch()>before.activation.range().controlEpoch());
                    b.enrollment().barrier(ENTITY).close(close,1);
                    var protectedBytes=journalBytes(f,b.request());
                    assertThrows(ProtectionException.class,()->b.service().step(b.request()));
                    assertTrue(Arrays.equals(protectedBytes,journalBytes(f,b.request())),"New CLOSED lease must prevent further GC");
                    assertEquals(rangeBefore,sqlRange(f));assertTrue(f.originalSource.equals(f.sourceSnapshot()),"Source changed");
                }finally{closer.release(close);}
            }
            f.evidence("PASS authority=close-vs-GC provider="+f.sandbox.provider+" physicalSqlBlockedByOwnedBackend=true separateGuard=true delayedEarlierLeaseRejected=true newLeaseCloseAfterChunk=true furtherGCRejected=true sourceUnchanged=true");
        }
    }

    private static Bound openedAfterGc(CacheInitialNativeBridgeProvidersRedisTest.Fixture f)throws Exception {
        var b=bind(f);until(f,b,Phase.READY);f.faults.arm("DELETE FROM cachedb_initial_page",true);
        rejected(ProtectionException.Code.STORAGE_UNAVAILABLE,()->b.service().step(b.request()));f.faults.requireConsumed();
        var saved=state(f,b.request());assertEquals(Phase.SQL_OPEN,saved.phase);assertTrue(saved.finalizing);
        assertEquals("OPEN",f.redis.hget(b.enrollment().barrier(ENTITY).generationKey(f.generation.key()),"state"));return b;
    }
    @Test void closeStopsNewCallsWhileAnAdmittedPhysicalCleanupKeepsItsPermitUntilExit()throws Exception {
        try(var admin=CacheTenantContext.administrative();var f=fixture()) {
            var b=openedAfterGc(f);String table=firstRemaining(f,b.request());
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            assertTrue(f.faults.paused.compareAndSet(null,new InitialSqlFaults.Pause("DELETE FROM "+table,entered,release,new AtomicInteger())));
            try(var worker=Executors.newSingleThreadExecutor()) {
                var physical=worker.submit(()->{try(var scope=CacheTenantContext.administrative()){return b.service().step(b.request());}});
                try {
                    assertTrue(entered.await(5,TimeUnit.SECONDS));int before=f.source.calls.get();
                    b.service().close();assertFalse(physical.isDone());
                    rejected(ProtectionException.Code.UNWIRED_PROTOCOL,()->b.service().step(b.request()));
                    assertEquals(before,f.source.calls.get(),"Stopped admission must not borrow SQL");
                    assertFalse(physical.isDone(),"close must not cancel the physical transaction or manufacture its completion");
                }finally{release.countDown();}
                assertEquals(Phase.SQL_OPEN,physical.get(5,TimeUnit.SECONDS).phase());
            }
            var bytes=journalBytes(f,b.request());
            rejected(ProtectionException.Code.UNWIRED_PROTOCOL,()->b.service().step(b.request()));
            assertTrue(Arrays.equals(bytes,journalBytes(f,b.request())));assertTrue(f.originalSource.equals(f.sourceSnapshot()),"Source changed");
            f.evidence("PASS authority=close admittedPhysicalChunkCompletes=true noCancelOrEarlyPermitRelease=true newAdmissionRejected=true foregroundSqlBorrow=0 sourceUnchanged=true");
        }
    }
    private static JdbcInitialTenantBootstrap<CacheInitialNativeBridgeProvidersRedisTest.Row,Long> sql(
            CacheInitialNativeBridgeProvidersRedisTest.Fixture f,Bound b)throws Exception {
        var window=b.service().window();return new JdbcInitialTenantBootstrap<>(f.guard,new JdbcTenantVersionAllocator(f.guard,f.sandbox.dialect,2),
                window,f.ids,state(f,b.request()).manifest,2,Set.of(window.fingerprint()));
    }
    private static byte[] journalBytes(CacheInitialNativeBridgeProvidersRedisTest.Fixture f,Request request)throws Exception {
        try(var c=f.source.getConnection();var q=c.prepareStatement("SELECT payload FROM cachedb_initial_bootstrap WHERE operation_id=?")) {
            q.setQueryTimeout(2);q.setMaxRows(2);q.setString(1,request.operationId().toString());
            try(var r=q.executeQuery()){assertTrue(r.next());var value=r.getBytes(1);assertTrue(value.length<=16384);assertFalse(r.next());return value;}
        }
    }
    private static List<String> payloadsAbsent(CacheInitialNativeBridgeProvidersRedisTest.Fixture f) {
        var keys=f.database.redisKeyStrategy();return Arrays.asList(f.redis.get(keys.entityKey(NS,1L)),f.redis.get(keys.entityKey(NS,2L)));
    }
    private static String firstRemaining(CacheInitialNativeBridgeProvidersRedisTest.Fixture f,Request request)throws Exception {
        for(var store:InitialSqlCleanupPlan.stores())try(var c=f.source.getConnection();var q=c.prepareStatement(store.selectFirst(f.sandbox.dialect))) {
            q.setQueryTimeout(2);q.setMaxRows(1);q.setString(1,request.generation().key().scopeToken());
            q.setString(2,request.generation().key().tenantToken());q.setString(3,request.operationId().toString());
            try(var r=q.executeQuery()){if(r.next())return store.table();}
        }throw new AssertionError("Owned real journal has no remaining GC row");
    }
    private static void assertDatabaseWaiter(CacheInitialNativeBridgeProvidersRedisTest.Fixture f,int ownedBackend)throws Exception {
        var session=f.faults.pausedBackend.get();assertNotNull(session);
        assertEquals(ownedBackend,session.id());assertEquals(f.sandbox.dialect,session.dialect());
        long deadline=System.nanoTime()+1_500_000_000L;
        try(var c=f.sandbox.admin.getConnection()) {
            var observation=InitialSqlBackendProbe.bind(c,session,f.sandbox.name);
            while(System.nanoTime()<deadline) {
                if(InitialSqlBackendProbe.hasBlockedWaiter(c,observation))return;
                Thread.sleep(10);
            }
        }fail("No actual SQL lock waiter was observed for the owned finalization backend");
    }
    private static void rejected(ProtectionException.Code code,org.junit.jupiter.api.function.Executable action) {
        var failure=assertThrows(ProtectionException.class,action);assertEquals(code,failure.code());assertNull(failure.getCause());
    }
}
