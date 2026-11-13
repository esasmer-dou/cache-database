package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.InitialTenantBootstrap.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.redis.*;
import com.reactor.cachedb.starter.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;
import static com.reactor.cachedb.jdbc.CacheInitialNativeBridgeProvidersRedisTest.controlPlan;
import static com.reactor.cachedb.jdbc.CacheInitialNativeBridgeProvidersRedisTest.nativeProvider;

/** Actual Redis/SQL fault and admission controls. No fake native proof or direct owner/range seeding. */
@EnabledIfSystemProperty(named="cachedb.snapshot.providers",matches="true")
@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
@EnabledIfSystemProperty(named="cachedb.initial.isolatedSql",matches="true")
@Timeout(120)
class CacheInitialNativeRecoveryRedisTest {
    private static final String NS="native_rows",ENTITY="NativeInitialRow";

    @Test void openedActivationSurvivesPageGcConsumedRangeAndReplacementAdministrativeLeases()throws Exception {
        try(var admin=CacheTenantContext.administrative();var f=new CacheInitialNativeBridgeProvidersRedisTest.Fixture(nativeProvider(),true)) {
            var b=bind(f);runUntil(f,b,Phase.READY);
            f.faults.arm("DELETE FROM cachedb_initial_page",true);
            var lost=assertThrows(ProtectionException.class,()->b.service.step(b.request));
            assertEquals(ProtectionException.Code.STORAGE_UNAVAILABLE,lost.code());assertNull(lost.getCause());
            f.faults.requireConsumed();assertEquals(1,f.faults.fired.get());
            var captured=state(f,b.request);
            assertEquals(Phase.SQL_OPEN,captured.phase);assertTrue(captured.finalizing);
            assertEquals(0,count(f,"cachedb_initial_page"));
            assertEquals("OPEN",f.redis.hget(b.enrollment.barrier(ENTITY).generationKey(f.generation.key()),"state"));
            var actual=new RedisTenantVersionAllocator(f.database.redisOperations(),f.database.redisKeyStrategy(),b.enrollment.barrier(ENTITY),NS);
            var keys=f.database.redisKeyStrategy();String version=keys.versionKey(NS,1L);
            String used=b.producer.execute("a",()->actual.mutate(b.producer.requireCurrent("a"),version+":tenant","a",version,
                    f.redis.get(version),List.of(),List.of(),""));
            assertEquals(captured.activation.range().first(),Long.parseLong(used));
            assertTrue(Long.parseLong(used)<captured.activation.range().last());
            var generationBefore=f.redis.hgetAll(b.enrollment.barrier(ENTITY).generationKey(f.generation.key()));
            var rangeBefore=f.redis.hgetAll(actual.allocatorKey(f.generation.key()));
            var sqlRangeBefore=sqlRange(f);String counterBefore=f.redis.get(actual.counterKey(f.generation.key()));
            var payloadBefore=List.of(f.redis.get(keys.entityKey(NS,1L)),f.redis.get(keys.entityKey(NS,2L)));
            int closeAttempts=f.trace.closes.get(),installs=f.trace.installs.get();
            assertTrue(closeAttempts>0);assertTrue(installs>0);
            long originalEpoch=captured.activation.range().controlEpoch();
            runUntil(f,b,Phase.ACTIVE);
            assertTrue(guardEpoch(f)>originalEpoch,"Recovery must acquire a real replacement SQL administrative lease");
            assertEquals(closeAttempts,f.trace.closes.get(),"OPEN recovery must not submit a CLOSED barrier program");
            assertEquals(installs,f.trace.installs.get(),"OPEN recovery must not submit any range installation program");
            var generationAfter=f.redis.hgetAll(b.enrollment.barrier(ENTITY).generationKey(f.generation.key()));
            assertTrue(Long.parseLong(generationAfter.get("i:finalize_epoch"))>Long.parseLong(generationBefore.get("i:finalize_epoch")));
            var unchanged=new HashMap<>(generationAfter);unchanged.put("i:finalize_epoch",generationBefore.get("i:finalize_epoch"));
            assertEquals(generationBefore,unchanged,"Only the derived finalization observation epoch may advance");
            assertEquals(rangeBefore,f.redis.hgetAll(actual.allocatorKey(f.generation.key())));
            assertEquals(counterBefore,f.redis.get(actual.counterKey(f.generation.key())));
            assertEquals(sqlRangeBefore,sqlRange(f),"No new SQL reservation may be issued");
            assertEquals(payloadBefore,List.of(f.redis.get(keys.entityKey(NS,1L)),f.redis.get(keys.entityKey(NS,2L))));
            var after=state(f,b.request);assertEquals(activationProof(captured.activation),activationProof(after.activation));
            assertEquals(0,count(f,"cachedb_initial_page"));
            assertEquals(f.originalSource,f.sourceSnapshot(),"Recovery must not change source ciphertext or versions");
            f.evidence("PASS recovery=OPEN originalActivation=true acknowledgedPagesGc=true originalRangeConsumed=1"
                    +" replacementSqlLeases=true closeAttemptsAdded=0 rangeInstallsAdded=0 newSqlReservations=0 sourceUnchanged=true");
        }
    }

    @Test void rosterLossWithAnActiveDeliveryAndThirdOrdinaryRootCannotRecreateConsumedBirthGrant()throws Exception {
        var thirdTrace=new CacheInitialNativeBridgeProvidersRedisTest.NativeCommandCounts();
        try(var admin=CacheTenantContext.administrative();var f=new CacheInitialNativeBridgeProvidersRedisTest.Fixture(nativeProvider(),true);
                var secondRedis=client();var thirdRedis=thirdTrace.client(System.getProperty("cachedb.snapshot.redis.host","redis"),
                        Integer.getInteger("cachedb.snapshot.redis.port",6379));
                var second=new CacheDatabase(secondRedis,f.source,f.database.config(),f.database.runtimeOptions());
                var third=new CacheDatabase(thirdRedis,f.source,f.database.config(),f.database.runtimeOptions())) {
            assertSame(thirdRedis,assertInstanceOf(JedisPooledOperations.class,third.redisOperations()).pooledClient());
            var b=bind(f);runUntil(f,b,Phase.ACTIVE);
            second.start();second.register(f.metadata,new CacheInitialNativeBridgeProvidersRedisTest.Codec(null),new CachePolicy(16,16,false,120,120));
            third.start();third.register(f.metadata,new CacheInitialNativeBridgeProvidersRedisTest.Codec(null),new CachePolicy(16,16,false,120,120));
            String roster=b.enrollment.controlStoreKey(ENTITY);String birthBefore=birth(f);
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var physicallyExited=new AtomicBoolean();
            try(var worker=Executors.newSingleThreadExecutor()) {
                var delivery=worker.submit(()->{
                    try {
                        b.enrollment.deliver(f.metadata,"a",repo->repo.findById(1L),row->{
                            assertTrue(row.isPresent());entered.countDown();
                            try {if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Owned callback fixture wait exhausted");}
                            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError("Fixture interrupted");}
                        });
                    } finally {physicallyExited.set(true);}
                });
                try {
                    assertTrue(entered.await(5,TimeUnit.SECONDS));assertFalse(physicallyExited.get());
                    // A normal late root invalidates the old seal before it can publish protected work.
                    var secondProducer=second.configureTenantProducer(f.metadata,1);
                    assertNotEquals(b.producer.runtimeId(),secondProducer.runtimeId());
                    assertEquals("REVIEW_REQUIRED",f.redis.hget(roster,"state"));
                    assertEquals("2",f.redis.hget(roster,"enrolled"));
                    assertFalse(delivery.isDone(),"Loss of authority must not release a physically active callback");
                    assertEquals(1,f.redis.del(roster),"Inject loss of ONLY the exact owned roster key");
                    int births=f.trace.births.get();assertEquals(1,births);
                    assertCode(ProtectionException.Code.MIGRATION_REQUIRED,()->third.configureTenantProducer(f.metadata,1));
                    // A fresh tenant's pristine CLOSED guard must not reset this namespace's consumed grant.
                    var fresh=new com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation(f.binding.key("fresh-tenant"),1);
                    f.guard.provisionClosed(fresh);
                    var prepared=third.prepareInitialNamespace(f.metadata,f.binding,f.guard,f.ids,1L,2L,2,65536,16384,1048576,1);
                    var gate=new JdbcNamespaceBootstrapGate(f.guard,Set.of(prepared.descriptor().namespaceFingerprint()));
                    assertCode(ProtectionException.Code.MIGRATION_REQUIRED,()->prepared.bootstrap(gate,fresh));
                    var raw=third.repository(f.metadata,codec(third));
                    try(var tenant=CacheTenantContext.withTenant("a")) {
                        assertThrows(ProtectionException.class,()->raw.findById(1L));
                        assertThrows(ProtectionException.class,()->raw.saveWithReceipt(new CacheInitialNativeBridgeProvidersRedisTest.Row(1L,"a","fixture-write-must-not-be-accepted")));
                    }
                    assertEquals(birthBefore,birth(f));assertEquals(births,f.trace.births.get());assertFalse(f.redis.exists(roster));
                    assertEquals(0,thirdTrace.births.get(),"Consumed SQL authority must reject before native creation dispatch");
                    assertTrue(b.enrollment.pollControl(1,1000).incomplete()>0);
                    assertFalse(delivery.isDone());assertFalse(physicallyExited.get());
                } finally {release.countDown();}
                var completion=assertThrows(ExecutionException.class,()->delivery.get(5,TimeUnit.SECONDS));
                assertInstanceOf(ProtectionException.class,completion.getCause());
                assertNull(completion.getCause().getCause());assertTrue(physicallyExited.get());
            }
            assertFalse(f.redis.exists(roster));assertEquals(birthBefore,birth(f));
            assertEquals(f.originalSource,f.sourceSnapshot());
            f.evidence("PASS rosterLoss=true actualRoots=3 separateRedisClients=3 callbackHeldThroughLoss=true"
                    +" physicalExitRequired=true lateRootInvalidatesSeal=true thirdOrdinaryAdmissionRejected=true"
                    +" consumedSqlBirthUnchanged=true nativeBirthNotRepeated=true noRosterRecreation=true sourceUnchanged=true");
        }
    }

    private record Bound(CacheInitialNamespace<CacheInitialNativeBridgeProvidersRedisTest.Row,Long> namespace,
            RedisTenantProducer producer,CacheTenantRuntimeEnrollment enrollment,
            CacheInitialTenantBootstrap<CacheInitialNativeBridgeProvidersRedisTest.Row,Long> service,Request request) {}
    private static Bound bind(CacheInitialNativeBridgeProvidersRedisTest.Fixture f) {
        var ns=f.database.prepareInitialNamespace(f.metadata,f.binding,f.guard,f.ids,1L,2L,2,65536,16384,1048576,1);
        var producer=ns.bootstrap(new JdbcNamespaceBootstrapGate(f.guard,Set.of(ns.descriptor().namespaceFingerprint())),f.generation);
        var enrollment=f.database.prepareTenantProtection(1);var plan=controlPlan(f,f.database,enrollment,ns.window());
        var local=enrollment.descriptor(ENTITY);
        var approval=new RedisTenantRuntimeControl.ApprovedRoster(ProtectionKey.hash("recovery-review:"+f.run),plan.manifestToken(),
                local.coverageToken(),List.of(local.runtimeId()),List.of(f.generation.key()));
        return new Bound(ns,producer,enrollment,ns.createBootstrap(enrollment,plan,approval,64,Set.of(ns.window().fingerprint())),
                new Request(f.generation,"a",UUID.randomUUID(),ProtectionKey.hash("recovery-operator:"+f.run),120));
    }
    private static void runUntil(CacheInitialNativeBridgeProvidersRedisTest.Fixture f,Bound b,Phase expected)throws Exception {
        for(int call=0;call<512;call++) {
            var progress=b.service.step(b.request);
            assertNotEquals(Phase.INCOMPLETE,progress.phase());
            if(progress.phase()==expected){f.evidence("RECOVERY_PHASE phase="+expected+" calls="+(call+1));return;}
        }
        fail("Fixed continuation budget exhausted before expected phase");
    }
    private static InitialBootstrapState state(CacheInitialNativeBridgeProvidersRedisTest.Fixture f,Request request)throws SQLException {
        try(var c=f.source.getConnection();var q=c.prepareStatement("SELECT payload FROM cachedb_initial_bootstrap WHERE operation_id=?")) {
            q.setQueryTimeout(2);q.setString(1,request.operationId().toString());
            try(var r=q.executeQuery()){assertTrue(r.next());byte[] bytes=r.getBytes(1);assertTrue(bytes.length<=InitialBootstrapState.MAX_BYTES);
                var state=InitialBootstrapState.decode(bytes,f.generation.key());assertFalse(r.next());return state;}
        }
    }
    private static long count(CacheInitialNativeBridgeProvidersRedisTest.Fixture f,String table)throws SQLException {
        if(!table.equals("cachedb_initial_page"))throw new AssertionError("Unsupported fixture table");
        try(var c=f.source.getConnection();var q=c.prepareStatement("SELECT COUNT(*) FROM "+table)) {
            q.setQueryTimeout(2);try(var r=q.executeQuery()){assertTrue(r.next());return r.getLong(1);}
        }
    }
    private static long guardEpoch(CacheInitialNativeBridgeProvidersRedisTest.Fixture f)throws SQLException {
        try(var c=f.source.getConnection();var q=c.prepareStatement("SELECT lease_epoch FROM cachedb_tenant_guard")) {
            q.setQueryTimeout(2);try(var r=q.executeQuery()){assertTrue(r.next());long epoch=r.getLong(1);assertFalse(r.next());return epoch;}
        }
    }
    private static List<String> sqlRange(CacheInitialNativeBridgeProvidersRedisTest.Fixture f)throws SQLException {
        try(var c=f.source.getConnection();var q=c.prepareStatement("SELECT highwater,reservation_epoch,last_request,last_control_epoch,last_first,last_last,last_run_id,inventory_token FROM cachedb_tenant_allocator")) {
            q.setQueryTimeout(2);try(var r=q.executeQuery()){assertTrue(r.next());var values=new ArrayList<String>();
                for(int i=1;i<=8;i++)values.add(r.getString(i));assertFalse(r.next());return List.copyOf(values);}
        }
    }
    private static String birth(CacheInitialNativeBridgeProvidersRedisTest.Fixture f)throws SQLException {
        try(var c=f.source.getConnection();var q=c.prepareStatement("SELECT roster_token,binding_token,mapping_token,incarnation_id,redis_run_id FROM cachedb_namespace_bootstrap")) {
            q.setQueryTimeout(2);try(var r=q.executeQuery()){assertTrue(r.next());var value=new StringBuilder();
                for(int i=1;i<=5;i++)value.append(r.getString(i)).append(':');assertFalse(r.next());return value.toString();}
        }
    }
    private static List<String> activationProof(Activation activation) {
        var range=activation.range();
        return List.of(activation.token(),activation.planToken(),activation.manifestToken(),activation.inventoryToken(),
                activation.runtimeProof(),activation.window().digest(),Long.toString(activation.window().rows()),
                range.key().scopeToken(),range.key().tenantToken(),Long.toString(range.generation()),Long.toString(range.controlEpoch()),
                Long.toString(range.reservationEpoch()),Long.toString(range.first()),Long.toString(range.last()),
                range.request().toString(),range.runId(),range.inventory());
    }
    @SuppressWarnings("unchecked") private static EntityCodec<CacheInitialNativeBridgeProvidersRedisTest.Row> codec(CacheDatabase root) {
        return (EntityCodec<CacheInitialNativeBridgeProvidersRedisTest.Row>)root.entityRegistry().find(ENTITY).orElseThrow().codec();
    }
    private static JedisPooled client(){return new JedisPooled(System.getProperty("cachedb.snapshot.redis.host","redis"),Integer.getInteger("cachedb.snapshot.redis.port",6379));}
    private static void assertCode(ProtectionException.Code code,org.junit.jupiter.api.function.Executable action) {
        var failure=assertThrows(ProtectionException.class,action);assertEquals(code,failure.code());assertNull(failure.getCause());
    }
}
