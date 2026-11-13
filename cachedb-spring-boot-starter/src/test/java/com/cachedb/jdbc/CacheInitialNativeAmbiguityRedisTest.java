package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import static com.reactor.cachedb.jdbc.CacheInitialNativeBridgeProvidersRedisTest.controlPlan;
import static com.reactor.cachedb.jdbc.CacheInitialNativeBridgeProvidersRedisTest.nativeProvider;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.InitialTenantBootstrap.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.redis.*;
import com.reactor.cachedb.starter.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Real selected-provider commit-loss controls over canonical Redis adapters, never injected native proofs. */
@EnabledIfSystemProperty(named="cachedb.snapshot.providers",matches="true")
@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
@EnabledIfSystemProperty(named="cachedb.initial.isolatedSql",matches="true")
@Timeout(120)
class CacheInitialNativeAmbiguityRedisTest {
    private static final String NS="native_rows",ENTITY="NativeInitialRow";
    private static final String SECRET="private-initial-fixture-sensitive-value";

    @Test void committedBirthLostReplyCannotDispatchOrConsumeAnotherGrant()throws Exception {
        try(var admin=CacheTenantContext.administrative();var f=fixture()) {
            var namespace=namespace(f);var gate=new JdbcNamespaceBootstrapGate(f.guard,Set.of(namespace.descriptor().namespaceFingerprint()));
            f.faults.arm("INSERT INTO cachedb_namespace_bootstrap",true);
            redacted(ProtectionException.Code.STORAGE_UNAVAILABLE,()->namespace.bootstrap(gate,f.generation));
            consumed(f);var birth=birth(f);
            String roster=RedisTenantRuntimeControl.rosterKey(f.database.redisKeyStrategy(),NS);
            assertFalse(f.redis.exists(roster));assertEquals(0,count(f,"cachedb_initial_bootstrap"));
            int before=f.source.calls.get();
            redacted(ProtectionException.Code.MIGRATION_REQUIRED,()->namespace.bootstrap(gate,f.generation));
            assertEquals(before,f.source.calls.get(),"The same once-only capability must not reach SQL again");
            try(var replacement=new CacheDatabase(f.redis,f.source,f.database.config(),f.database.runtimeOptions())) {
                replacement.register(f.metadata,new CacheInitialNativeBridgeProvidersRedisTest.Codec(null),new CachePolicy(16,16,false,120,120));
                var other=replacement.prepareInitialNamespace(f.metadata,f.binding,f.guard,f.ids,1L,2L,2,65536,16384,1048576,1);
                assertNotEquals(namespace.descriptor().incarnationId(),other.descriptor().incarnationId());
                redacted(ProtectionException.Code.MIGRATION_REQUIRED,()->other.bootstrap(
                        new JdbcNamespaceBootstrapGate(f.guard,Set.of(other.descriptor().namespaceFingerprint())),f.generation));
            }
            assertEquals(birth,birth(f));assertFalse(f.redis.exists(roster));
            assertEquals("CLOSED",sqlGuard(f).get(0));assertTrue(f.originalSource.equals(f.sourceSnapshot()),"Source changed");
            f.evidence("PASS ambiguity=birth-commit-before-native consumedBirth=1 nativeRosterAbsent=true replacementRejected=true sourceUnchanged=true");
        }
    }

    @Test void pageCommitLostReplyReusesExactCapturedCiphertextBeforeHydration()throws Exception {
        pageLoss("INSERT INTO cachedb_initial_page",false);
    }

    @Test void hydratedPageAckCommitLostReplyDoesNotReencryptOrDuplicateIt()throws Exception {
        pageLoss("UPDATE cachedb_initial_page SET is_applied=1",true);
    }

    private void pageLoss(String prefix,boolean alreadyHydrated)throws Exception {
        try(var admin=CacheTenantContext.administrative();var f=fixture()) {
            var b=bind(f);until(f,b,Phase.HOT_WINDOW);
            f.faults.arm(prefix,true);redacted(ProtectionException.Code.STORAGE_UNAVAILABLE,()->b.service.step(b.request));consumed(f);
            var original=page(f);assertEquals(alreadyHydrated?1:0,original.applied());
            var captured=state(f,b.request);assertEquals(b.request.operationId(),captured.operation);assertNull(captured.activation);
            assertEquals(1,captured.pages);assertEquals(2,original.rows().size());
            for(var row:original.rows().entrySet()) {
                var keys=f.database.redisKeyStrategy();String payload=f.redis.get(keys.entityKey(NS,row.getKey()));
                if(alreadyHydrated)assertTrue(row.getValue().equals(payload),"Hydrated payload differs from committed page");
                else {assertNull(payload);assertFalse(f.redis.exists(keys.versionKey(NS,row.getKey())));}
            }
            until(f,b,Phase.READY);
            var after=page(f);assertEquals(original.id(),after.id());
            assertTrue(Arrays.equals(original.encoded(),after.encoded()),"Captured ciphertext/nonces/proofs changed");
            assertEquals(1,after.applied());assertEquals(1,count(f,"cachedb_initial_page"));
            assertEquals(2,state(f,b.request).warmed);assertNull(state(f,b.request).activation);
            for(var row:original.rows().entrySet()) {
                var keys=f.database.redisKeyStrategy();String payload=f.redis.get(keys.entityKey(NS,row.getKey()));
                assertTrue(row.getValue().equals(payload),"Replay changed captured Redis ciphertext");
                assertTrue(payload.contains("~E1:k2:"));assertFalse(payload.contains(SECRET));
                assertEquals(row.getKey()==1?"7":"8",f.redis.get(keys.versionKey(NS,row.getKey())));
                assertEquals("a",f.redis.get(keys.versionKey(NS,row.getKey())+":tenant"));
            }
            assertTrue(f.originalSource.equals(f.sourceSnapshot()),"Source changed");
            f.evidence("PASS ambiguity="+(alreadyHydrated?"hydrate-before-ack-reply":"page-persist-before-hydrate")
                    +" originalOperation=true capturedPageExact=true currentKeyCiphertext=true warmed=2 pages=1 sourceUnchanged=true");
        }
    }

    @Test void sqlOpenLostReplyPreservesOriginalActivationAndRangeOrRequiresExplicitRecovery()throws Exception {
        try(var admin=CacheTenantContext.administrative();var f=fixture()) {
            var b=bind(f);until(f,b,Phase.READY);
            f.faults.arm("UPDATE cachedb_tenant_guard SET generation=?,gate_state=?",true);
            redacted(ProtectionException.Code.STORAGE_UNAVAILABLE,()->b.service.step(b.request));consumed(f);
            var committed=state(f,b.request);assertEquals(Phase.SQL_OPEN,committed.phase);assertNotNull(committed.activation);
            assertFalse(committed.finalizing);assertEquals("OPEN",sqlGuard(f).get(0));
            String generation=b.enrollment.barrier(ENTITY).generationKey(f.generation.key());
            assertEquals("CLOSED",f.redis.hget(generation,"state"));
            var generationBefore=f.redis.hgetAll(generation);
            var allocator=new RedisTenantVersionAllocator(f.database.redisOperations(),f.database.redisKeyStrategy(),b.enrollment.barrier(ENTITY),NS);
            var rangeBefore=f.redis.hgetAll(allocator.allocatorKey(f.generation.key()));
            String counterBefore=f.redis.get(allocator.counterKey(f.generation.key()));
            var sqlBefore=sqlRange(f);var payloadBefore=payloads(f);var activationBefore=committed.activation;
            // A replacement lease is NOT permission to allocate/reinstall a different published intent.
            ProtectionException rejection=null;Progress returned=null;
            try {returned=b.service.step(b.request);}catch(ProtectionException failure){rejection=failure;}
            var after=state(f,b.request);var sqlAfter=sqlRange(f);
            var nativeAfter=f.redis.hgetAll(allocator.allocatorKey(f.generation.key()));
            String counterAfter=f.redis.get(allocator.counterKey(f.generation.key()));
            var payloadAfter=payloads(f);var guardAfter=sqlGuard(f);
            var generationAfter=f.redis.hgetAll(generation);String nativeState=generationAfter.get("state");
            boolean sourceSame=f.originalSource.equals(f.sourceSnapshot());
            var original=activationBefore.range();var observed=after.activation.range();
            // Export bounded grant/security facts BEFORE assertions, including on the old unsafe runtime.
            // No entity payloads, source values, provider messages or causes enter this evidence.
            f.evidence("P1_SQL_OPEN_OBSERVATION operation="+b.request.operationId()+" originalGrant="+original.request()
                    +" observedGrant="+observed.request()+" originalReservation="+original.reservationEpoch()
                    +" observedReservation="+observed.reservationEpoch()+" originalFirst="+original.first()+" originalLast="+original.last()
                    +" observedFirst="+observed.first()+" observedLast="+observed.last()
                    +" originalControl="+original.controlEpoch()+" replacementControl="+guardAfter.get(1)
                    +" sqlPhase="+after.phase+" sqlGate="+guardAfter.get(0)+" nativeState="+nativeState
                    +" outcome="+(rejection==null?"RETURNED_"+returned.phase():rejection.code().name())
                    +" activationSame="+activationFacts(activationBefore).equals(activationFacts(after.activation))+" sqlRangeSame="+sqlBefore.equals(sqlAfter)
                    +" nativeRangeSame="+rangeBefore.equals(nativeAfter)+" nativeFenceSame="+generationBefore.equals(generationAfter)
                    +" counterSame="+Objects.equals(counterBefore,counterAfter)
                    +" payloadSame="+payloadBefore.equals(payloadAfter)+" sourceSame="+sourceSame);
            final ProtectionException refused=rejection;
            assertAll("SQL_OPEN original-grant preservation",
                    ()->{assertNotNull(refused,"Replacement lease was accepted instead of requiring explicit recovery");
                        assertEquals(ProtectionException.Code.RECOVERY_REQUIRED,refused.code());assertNull(refused.getCause());
                        assertEquals(0,refused.getSuppressed().length);assertFalse(refused.toString().contains(SECRET));},
                    ()->assertEquals(Phase.SQL_OPEN,after.phase),
                    ()->assertEquals(activationFacts(activationBefore),activationFacts(after.activation)),
                    ()->assertEquals(sqlBefore,sqlAfter),
                    ()->assertEquals(rangeBefore,nativeAfter),
                    ()->assertEquals(counterBefore,counterAfter),
                    ()->assertEquals(payloadBefore,payloadAfter),
                    ()->assertEquals("OPEN",guardAfter.get(0)),
                    ()->assertEquals("CLOSED",nativeState),
                    ()->assertEquals(generationBefore,generationAfter),
                    ()->assertTrue(Long.parseLong(guardAfter.get(1))>original.controlEpoch()),
                    ()->assertTrue(sourceSame,"Source changed"));
            f.evidence("PASS ambiguity=SQL_OPEN-before-native originalActivationRetained=true replacementLease=true newRange=0 reinstall=0 explicitRecoveryRequired=true sourceUnchanged=true");
        }
    }

    private static List<String> activationFacts(Activation value) {
        var range=value.range();
        return List.of(value.token(),value.planToken(),value.manifestToken(),value.inventoryToken(),
                Long.toString(value.window().rows()),value.window().digest(),value.runtimeProof(),
                range.key().scopeToken(),range.key().tenantToken(),Long.toString(range.generation()),
                Long.toString(range.controlEpoch()),Long.toString(range.reservationEpoch()),
                Long.toString(range.first()),Long.toString(range.last()),range.request().toString(),
                range.runId(),range.inventory());
    }
    static CacheInitialNativeBridgeProvidersRedisTest.Fixture fixture()throws Exception {
        var f=new CacheInitialNativeBridgeProvidersRedisTest.Fixture(nativeProvider(),false,true);
        assertInstanceOf(JedisPooledOperations.class,f.database.redisOperations());assertNull(f.trace);assertNotNull(f.faults);return f;
    }
    record Bound(CacheInitialTenantBootstrap<CacheInitialNativeBridgeProvidersRedisTest.Row,Long> service,
            CacheTenantRuntimeEnrollment enrollment,Request request,
            CacheInitialNamespace<CacheInitialNativeBridgeProvidersRedisTest.Row,Long> namespace,RedisTenantProducer producer) {}
    static CacheInitialNamespace<CacheInitialNativeBridgeProvidersRedisTest.Row,Long> namespace(CacheInitialNativeBridgeProvidersRedisTest.Fixture f) {
        return f.database.prepareInitialNamespace(f.metadata,f.binding,f.guard,f.ids,1L,2L,2,65536,16384,1048576,1);
    }
    static Bound bind(CacheInitialNativeBridgeProvidersRedisTest.Fixture f) {
        var ns=namespace(f);var producer=ns.bootstrap(new JdbcNamespaceBootstrapGate(f.guard,Set.of(ns.descriptor().namespaceFingerprint())),f.generation);
        var enrollment=f.database.prepareTenantProtection(1);var plan=controlPlan(f,f.database,enrollment,ns.window());
        var local=enrollment.descriptor(ENTITY);
        var approval=new RedisTenantRuntimeControl.ApprovedRoster(ProtectionKey.hash("ambiguity-review:"+f.run),plan.manifestToken(),
                local.coverageToken(),List.of(local.runtimeId()),List.of(f.generation.key()));
        return new Bound(ns.createBootstrap(enrollment,plan,approval,64,Set.of(ns.window().fingerprint())),enrollment,
                new Request(f.generation,"a",UUID.randomUUID(),ProtectionKey.hash("ambiguity-operator:"+f.run),120),ns,producer);
    }
    static void until(CacheInitialNativeBridgeProvidersRedisTest.Fixture f,Bound b,Phase phase)throws Exception {
        for(int call=0;call<512;call++) {
            var p=b.service.step(b.request);assertNotEquals(Phase.INCOMPLETE,p.phase());
            if(p.phase()==phase){f.evidence("AMBIGUITY_PHASE phase="+phase+" calls="+(call+1));return;}
        }fail("Original bounded continuation count exhausted");
    }
    private static void consumed(CacheInitialNativeBridgeProvidersRedisTest.Fixture f) {
        f.faults.requireConsumed();assertEquals(1,f.faults.fired.get());
    }
    private static void redacted(ProtectionException.Code expected,org.junit.jupiter.api.function.Executable action) {
        var failure=assertThrows(ProtectionException.class,action);assertEquals(expected,failure.code());assertNull(failure.getCause());
        assertEquals(0,failure.getSuppressed().length);assertFalse(failure.toString().contains(SECRET));
    }
    static InitialBootstrapState state(CacheInitialNativeBridgeProvidersRedisTest.Fixture f,Request request)throws SQLException {
        try(var c=f.source.getConnection();var q=c.prepareStatement("SELECT payload FROM cachedb_initial_bootstrap WHERE operation_id=?")) {
            q.setQueryTimeout(2);q.setMaxRows(2);q.setString(1,request.operationId().toString());
            try(var r=q.executeQuery()){assertTrue(r.next());byte[] bytes=r.getBytes(1);assertTrue(bytes.length<=InitialBootstrapState.MAX_BYTES);
                var result=InitialBootstrapState.decode(bytes,f.generation.key());assertFalse(r.next());return result;}
        }
    }
    private record StoredPage(UUID id,byte[] encoded,int applied,Map<Long,String> rows) {}
    private static StoredPage page(CacheInitialNativeBridgeProvidersRedisTest.Fixture f)throws Exception {
        try(var c=f.source.getConnection();var q=c.prepareStatement("SELECT payload,is_applied FROM cachedb_initial_page ORDER BY page_no")) {
            q.setQueryTimeout(2);q.setMaxRows(2);
            try(var r=q.executeQuery()) {
                assertTrue(r.next());byte[] bytes=r.getBytes(1);int applied=r.getInt(2);assertTrue(bytes.length<=65536);assertFalse(r.next());
                assertFalse(new String(bytes,StandardCharsets.ISO_8859_1).contains(SECRET));
                try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
                    assertEquals(1,in.readInt());UUID id=new UUID(in.readLong(),in.readLong());assertTrue(in.readBoolean());
                    int count=in.readInt();assertEquals(2,count);var rows=new LinkedHashMap<Long,String>();
                    for(int i=0;i<count;i++) {
                        long key=Long.parseLong(text(in,1024));assertEquals(key==1?7:8,in.readLong());ProtectionKey.token(in.readUTF());
                        assertNull(rows.put(key,text(in,16384)));
                    }assertEquals(-1,in.read());return new StoredPage(id,bytes,applied,Map.copyOf(rows));
                }
            }
        }
    }
    private static String text(DataInputStream in,int max)throws IOException {
        int bytes=in.readInt();assertTrue(bytes>=0&&bytes<=max&&bytes<=in.available());return new String(in.readNBytes(bytes),StandardCharsets.UTF_8);
    }
    private static long count(CacheInitialNativeBridgeProvidersRedisTest.Fixture f,String table)throws SQLException {
        if(!Set.of("cachedb_initial_page","cachedb_initial_bootstrap").contains(table))throw new AssertionError("Fixture table not permitted");
        try(var c=f.source.getConnection();var q=c.prepareStatement("SELECT COUNT(*) FROM "+table)) {
            q.setQueryTimeout(2);try(var r=q.executeQuery()){assertTrue(r.next());return r.getLong(1);}
        }
    }
    private static List<String> birth(CacheInitialNativeBridgeProvidersRedisTest.Fixture f)throws SQLException {
        return facts(f,"SELECT roster_token,binding_token,mapping_token,incarnation_id,redis_run_id FROM cachedb_namespace_bootstrap",5);
    }
    private static List<String> sqlGuard(CacheInitialNativeBridgeProvidersRedisTest.Fixture f)throws SQLException {
        return facts(f,"SELECT gate_state,lease_epoch FROM cachedb_tenant_guard",2);
    }
    static List<String> sqlRange(CacheInitialNativeBridgeProvidersRedisTest.Fixture f)throws SQLException {
        return facts(f,"SELECT highwater,reservation_epoch,last_request,last_control_epoch,last_first,last_last,last_run_id,inventory_token FROM cachedb_tenant_allocator",8);
    }
    private static List<String> facts(CacheInitialNativeBridgeProvidersRedisTest.Fixture f,String sql,int columns)throws SQLException {
        try(var c=f.source.getConnection();var q=c.prepareStatement(sql)) {
            q.setQueryTimeout(2);q.setMaxRows(2);try(var r=q.executeQuery()){assertTrue(r.next());var values=new ArrayList<String>();
                for(int i=1;i<=columns;i++)values.add(r.getString(i));assertFalse(r.next());return List.copyOf(values);}
        }
    }
    static List<String> payloads(CacheInitialNativeBridgeProvidersRedisTest.Fixture f) {
        return List.of(f.redis.get(f.database.redisKeyStrategy().entityKey(NS,1L)),f.redis.get(f.database.redisKeyStrategy().entityKey(NS,2L)));
    }
}
