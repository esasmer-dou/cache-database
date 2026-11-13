package com.reactor.cachedb.spring.boot;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.query.QueryEvaluator;
import com.reactor.cachedb.core.registry.DefaultEntityRegistry;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.jdbc.*;
import com.reactor.cachedb.redis.*;
import com.reactor.cachedb.starter.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import redis.clients.jedis.*;

/**
 * Real native facade/provider orchestration with a sealed one-runtime fixture gate.
 * This fixture gate certifies ONLY the explicitly controlled test routes, NOT production runtime wiring.
 * V019, V019_1 and V019_2 must be provisioned in disposable fixtures; absence fails rather than skips.
 */
@EnabledIfSystemProperty(named="cachedb.snapshot.providers",matches="true")
@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
@Timeout(300)
class CacheDataProtectionProvidersTest {
    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void completePathIncludesColdOwnersQueueSourceFreshVerificationAndDurableDisposal(String provider)throws Exception{
        try(var admin=CacheTenantContext.administrative();var f=new Fixture(provider)){
            f.seed();var result=f.finish(true);
            assertEquals(CacheDataProtection.Status.COMPLETE,result.status());
            assertEquals(Stage.COMPLETE,result.receipt().stage());assertEquals(2,result.receipt().sourceRows());
            assertEquals(2,result.receipt().redisRows());assertEquals(1,result.queueEntries());assertNotNull(result.evidence());
            assertEquals(0,f.source.rows("a"));assertEquals(1,f.source.rows("b"));
            assertFalse(f.redis.exists(f.keys.versionKey(f.ns,f.cold)));
            assertFalse(f.redis.exists(f.keys.versionKey(f.ns,f.hot)+":tenant"));
            assertEquals("foreign",f.redis.get(f.keys.entityKey(f.ns,f.foreign)));
            assertEquals(1,f.redis.xlen(f.stream));assertEquals("CLOSED",f.redis.hget(f.barrier.generationKey(f.binding.key("a")),"state"));
            try(var c=f.source.source.getConnection()){
                c.setAutoCommit(false);
                assertThrows(java.sql.SQLException.class,()->f.guard.authorizeWrite(c,new Generation(f.binding.key("a"),1)));
                c.rollback();
            }
            assertEquals(0,f.temporaryRows("cachedb_purge_work"));
            assertEquals(0,f.temporaryRows("cachedb_purge_scan"));
            assertEquals(2,f.temporaryRows("cachedb_purge_facade")); // control + non-PII read-drain checkpoint
            assertEquals(result,f.facade().purgeTenant(String.class,"a",f.options(true)));
            assertTrue(f.auditEvents.get()>0);
        }
    }
    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void redisOnlyLeavesSourceAndPermanentlyClosedMigrationRequired(String provider)throws Exception{
        try(var admin=CacheTenantContext.administrative();var f=new Fixture(provider)){
            f.seed();var result=f.finish(false);
            assertEquals(CacheDataProtection.Status.MIGRATION_REQUIRED,result.status());
            assertEquals(Stage.REQUIRES_MIGRATION,result.receipt().stage());assertEquals(0,result.receipt().sourceRows());
            assertEquals(2,f.source.rows("a"));assertEquals(1,f.source.rows("b"));assertEquals(1,f.redis.xlen(f.stream));
            assertEquals(1,result.queueEntries());
            Lease lease=f.guard.acquireForTenant(f.binding,"a",f.options(false),false);
            try{assertEquals(ProtectionException.Code.MIGRATION_REQUIRED,
                    assertThrows(ProtectionException.class,()->f.guard.prepareReenable(lease)).code());}
            finally{f.guard.release(lease);}
        }
    }
    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void unattestedLegacyQueueOrOwnerNeverBecomesPassAndForeignDataIsUntouched(String provider)throws Exception{
        try(var admin=CacheTenantContext.administrative();var f=new Fixture(provider)){
            f.seed();f.redis.set(f.keys.entityKey(f.ns,"legacy-orphan"),"unattributed");
            f.redis.xadd(f.stream,StreamEntryID.NEW_ENTRY,Map.of("sentinel","unknown legacy envelope"));
            var result=f.finish(true);
            assertEquals(CacheDataProtection.Status.INCOMPLETE,result.status());
            assertEquals(0,result.receipt().sourceRows());assertEquals(2,f.source.rows("a"));
            assertEquals("unattributed",f.redis.get(f.keys.entityKey(f.ns,"legacy-orphan")));
            assertEquals("foreign",f.redis.get(f.keys.entityKey(f.ns,f.foreign)));
            assertEquals("CLOSED",f.redis.hget(f.barrier.generationKey(f.binding.key("a")),"state"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void independentFreshSweepDetectsAnOrphanNotPresentInSealedInventory(String provider)throws Exception{
        try(var admin=CacheTenantContext.administrative();var f=new Fixture(provider)){
            f.seed();f.fence.injectAfterErase.set(true);
            assertEquals(CacheDataProtection.Status.INCOMPLETE,f.finish(true).status());
            assertEquals("unattributed",f.redis.get(f.keys.entityKey(f.ns,"late-orphan")));
            assertEquals("foreign",f.redis.get(f.keys.entityKey(f.ns,f.foreign)));
        }
    }
    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void noDestructionWhileRuntimeReadIsInflightOrManifestIsUnattested(String provider)throws Exception{
        try(var admin=CacheTenantContext.administrative();var f=new Fixture(provider)){
            f.seed();f.fence.activeReads.set(1);
            var first=f.facade().purgeTenant(String.class,"a",f.options(true));
            assertEquals(CacheDataProtection.Status.RUNNING,first.status());assertEquals(2,f.source.rows("a"));
            assertTrue(f.redis.exists(f.keys.versionKey(f.ns,f.cold)));
            f.fence.activeReads.set(0);f.fence.historyAttested.set(false);
            var blocked=f.facade().purgeTenant(String.class,"a",f.options(true),first.continuation());
            assertEquals(CacheDataProtection.Status.INCOMPLETE,blocked.status());assertEquals(2,f.source.rows("a"));
        }
    }


    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void durableEmptyPayloadsAndEveryScanKindRoundTripWithoutChangingAdmission(String provider)throws Exception{
        try(var admin=CacheTenantContext.administrative();var f=new TenantPurgeProvidersTest.Fixture(provider)){
            f.provision();
            var lease=f.guard.acquireForTenant(f.binding,"a",f.options(false),false);
            try{
                var journal=new JdbcPurgeFacadeJournal(f.guard,f.dialect,2,16,1048576);
                var empty=journal.create(lease,"binary-roundtrip","",new byte[0],null);
                assertArrayEquals(new byte[0],journal.get(lease,empty.id()).payload());
                var zero=journal.commit(lease,empty,"0",new byte[]{0},false,null,0,0,0);
                assertArrayEquals(new byte[]{0},new JdbcPurgeFacadeJournal(f.guard,f.dialect,2,16,1048576)
                        .get(lease,empty.id()).payload());
                var cleared=journal.commit(lease,zero,"",new byte[0],true,null,0,0,0);
                assertEquals("",cleared.cursor());assertArrayEquals(new byte[0],journal.get(lease,empty.id()).payload());
                var scans=new JdbcPurgeScanJournal(f.guard,f.dialect,2,16,128,1048576);
                var sources=Arrays.stream(JdbcPurgeScanJournal.Kind.values())
                        .map(kind->new JdbcPurgeScanJournal.Source(ProtectionKey.hash("scan-kind-"+kind),kind,"fixture-"+kind))
                        .toList();
                scans.register(lease,sources);scans.register(lease,sources);
                var seen=EnumSet.noneOf(JdbcPurgeScanJournal.Kind.class);
                for(int i=0;i<sources.size();i++){
                    var pending=scans.next(lease);assertNotNull(pending);assertTrue(seen.add(pending.source().kind()));
                    byte[] encoded="*0\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                    scans.stage(lease,pending.source(),new PurgeScanFrame("0","0",true,0,ProtectionKey.hash("fixture-topology"),encoded));
                    var resumed=scans.next(lease);assertNotNull(resumed.frame());assertArrayEquals(encoded,resumed.frame().encoded());
                    scans.consume(lease,resumed,0,List.of(),List.of(),0);
                }
                assertEquals(EnumSet.allOf(JdbcPurgeScanJournal.Kind.class),seen);
                assertTrue(scans.discoveryComplete(lease));assertNull(scans.next(lease));
                // A zero-length logical payload accepts only the canonical empty representation.
                f.guard.inPurgeTransaction(lease,c->{
                    try(var q=c.prepareStatement("UPDATE cachedb_purge_facade SET payload=? WHERE scope_token=? AND tenant_token=? AND operation_id=? AND checkpoint_id=?")){
                        q.setQueryTimeout(2);q.setBytes(1,new byte[]{7});q.setString(2,lease.key().scopeToken());
                        q.setString(3,lease.key().tenantToken());q.setString(4,lease.operationId().toString());q.setString(5,empty.id());
                        assertEquals(1,q.executeUpdate());return null;
                    }
                });
                assertEquals(ProtectionException.Code.INVALID_PROGRESS,assertThrows(ProtectionException.class,
                        ()->journal.get(lease,empty.id())).code());
                try(var c=f.source.getConnection()){
                    c.setAutoCommit(false);
                    assertEquals("CDBGC",assertThrows(java.sql.SQLException.class,()->f.guard.authorizeWrite(c,f.generation(1))).getSQLState());
                    c.rollback();
                }
            }finally{f.guard.release(lease);}
        }
    }


    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void primaryFailureSurvivesCallCleanupFailureAndAllAdmissionRemainsClosed(String provider)throws Exception{
        try(var admin=CacheTenantContext.administrative();var f=new Fixture(provider)){
            f.seed();
            var primary=new ProtectionException(ProtectionException.Code.INDEX_REQUIRED);
            var facade=f.facade();
            f.fence.onDrain.set(()->{
                var key=f.binding.key("a");
                try(var c=f.source.source.getConnection();var q=c.prepareStatement(
                        "UPDATE cachedb_purge_progress SET payload=? WHERE scope_token=? AND tenant_token=?")){
                    q.setQueryTimeout(2);q.setBytes(1,new byte[]{7});q.setString(2,key.scopeToken());q.setString(3,key.tenantToken());
                    assertEquals(1,q.executeUpdate());
                }catch(java.sql.SQLException failure){throw new AssertionError("Fixture fault injection failed");}
                throw primary;
            });
            var failure=assertThrows(ProtectionException.class,()->facade.purgeTenant(String.class,"a",f.options(true)));
            assertSame(primary,failure);assertEquals(1,failure.getSuppressed().length);
            assertInstanceOf(ProtectionException.class,failure.getSuppressed()[0]);
            assertEquals(ProtectionException.Code.INVALID_PROGRESS,((ProtectionException)failure.getSuppressed()[0]).code());
            assertNull(failure.getSuppressed()[0].getCause());
            assertEquals(2,f.source.rows("a"));assertEquals(1,f.source.rows("b"));
            assertTrue(f.redis.exists(f.keys.versionKey(f.ns,f.cold)));
            assertEquals("CLOSED",f.redis.hget(f.barrier.generationKey(f.binding.key("a")),"state"));
            try(var c=f.source.source.getConnection();var q=c.prepareStatement(
                    "SELECT gate_state FROM cachedb_tenant_guard WHERE scope_token=? AND tenant_token=?")){
                q.setQueryTimeout(2);q.setString(1,f.binding.key("a").scopeToken());q.setString(2,f.binding.key("a").tenantToken());
                try(var r=q.executeQuery()){assertTrue(r.next());assertEquals("CLOSED",r.getString(1));}
            }
            f.fence.onDrain.set(null);
            // Cleanup failure did not leak the facade's bounded administrator permit.
            assertNotEquals(ProtectionException.Code.BUSY,assertThrows(ProtectionException.class,
                    ()->facade.purgeTenant(String.class,"a",f.options(true))).code());
        }
    }


    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void pendingAuditPrefixCoversEveryHexBucketAndTreatsUnderscoreLiterally(String provider)throws Exception{
        try(var admin=CacheTenantContext.administrative();var f=new TenantPurgeProvidersTest.Fixture(provider)){
            f.provision();var lease=f.guard.acquireForTenant(f.binding,"a",f.options(false),false);
            try{
                var journal=new JdbcPurgeFacadeJournal(f.guard,f.dialect,2,32,1048576);
                for(char hex:"0123456789abcdef".toCharArray())
                    journal.create(lease,"audit-source-"+hex+"0".repeat(63),"",new byte[0],null);
                var seen=new HashSet<String>();
                for(int i=0;i<16;i++){
                    var row=journal.next(lease,"audit-source-");assertNotNull(row);
                    assertTrue(row.id().startsWith("audit-source-"));assertTrue(seen.add(row.id()));
                    journal.commit(lease,row,"",new byte[0],true,null,0,0,0);
                }
                assertEquals(16,seen.size());assertNull(journal.next(lease,"audit-source-"));
                journal.create(lease,"literal_id0","",new byte[0],null);
                journal.create(lease,"literalxid0","",new byte[0],null);
                assertEquals("literal_id0",journal.next(lease,"literal_").id());
            }finally{f.guard.release(lease);}
        }
    }

    static final class Fixture implements AutoCloseable {
        final TenantPurgeProvidersTest.Fixture source;
        final String prefix="facade-"+UUID.randomUUID(),ns,stream,cold,hot,foreign;
        final RedisKeyStrategy keys=new ClusterHashTagKeyStrategy(prefix,"scope");
        final JedisPooled redis;
        final RedisClientOperations client;
        final BoundedRedisWire wire;
        final RedisGenerationBarrier barrier;
        final Binding binding;
        final JdbcTenantPurgeGuard guard;
        final DefaultEntityRegistry registry=new DefaultEntityRegistry(ResourceLimits.defaults());
        final FixtureFence fence;
        final RedisTenantPurgePlan plan;
        final RedisQueryIndexManager<String,String> indexes;
        final AtomicInteger auditEvents=new AtomicInteger();
        Fixture(String provider)throws Exception{
            source=new TenantPurgeProvidersTest.Fixture(provider);
            JedisPooled openedRedis=null;
            BoundedRedisWire openedWire=null;
            try {
                ns=source.binding.scope();
                redis=openedRedis=new JedisPooled("redis://"+System.getProperty("cachedb.snapshot.redis.host","127.0.0.1")+":"
                        +Integer.getInteger("cachedb.snapshot.redis.port",16383));
                client=JedisPooledOperations.of(redis);
                wire=openedWire=new BoundedRedisWire(new InetSocketAddress(System.getProperty("cachedb.snapshot.redis.host","127.0.0.1"),
                        Integer.getInteger("cachedb.snapshot.redis.port",16383)),null,null,null,null,0,1000,2000,2);
                barrier=new RedisGenerationBarrier(client,keys,ns);
                cold=ProtectionKey.hash("a")+"0";hot=ProtectionKey.hash("a")+"1";foreign=ProtectionKey.hash("b")+"0";
                var meta=new EntityMetadata<String,String>(){
                    public String entityName(){return "PurgeFacadeRow";}
                    public String tableName(){return source.binding.source().table();}
                    public String redisNamespace(){return ns;}
                    public String idColumn(){return "id";}
                    public String tenantFieldName(){return "tenant_id";}
                    public boolean tenantPurgeEnabled(){return true;}
                    public Class<String> entityType(){return String.class;}
                    public Function<String,String> idAccessor(){return Function.identity();}
                    public List<String> columns(){return List.of("id","tenant_id","value_text");}
                    public List<RelationDefinition> relations(){return List.of();}
                };
                EntityCodec<String> codec=new EntityCodec<>(){
                    public String toRedisValue(String id){return id;}
                    public String fromRedisValue(String value){return value;}
                    public Map<String,Object> toColumns(String id){return Map.of("id",id,"tenant_id",id.equals(foreign)?"b":"a","value_text","v");}
                };
                var policy=new CachePolicy(32,8,true,60,60);
                registry.register(meta,codec,policy,null,null);
                indexes=new RedisQueryIndexManager<>(client,meta,codec,keys,null,
                        QueryIndexConfig.defaults(),RelationConfig.defaults(),new QueryEvaluator(),null);
                var pages=new RedisPageCacheManager<>(client,meta,codec,policy,keys,new RedisHotSetManager(client,keys),indexes,null,
                        ReadShapeGuardrailConfig.defaults());
                var targets=List.of(pages.purgeTarget(List.of(new RedisPurgeTarget.Page(0,RedisPurgeTarget.PageKind.LIST))));
                binding=new Binding(String.class,ns,source.binding.source(),RedisIdentityPurgeParticipant.layoutFingerprint(targets),
                        10000,256,1048576,true);
                guard=new JdbcTenantPurgeGuard(source.source,source.dialect,2,2,Set.of(binding.fingerprint()));
                source.provision();
                stream=keys.writeStreamKey(prefix+":{scope}:write-behind",ns);
                assertEquals(0,redis.xlen(stream));
                fence=new FixtureFence(this);
                plan=new RedisTenantPurgePlan(binding,meta.entityName(),"entity_version",guard,
                        new JdbcTenantVersionAllocator(guard,source.dialect,2),
                        new JdbcPurgeScanJournal(guard,source.dialect,2,128,10000,10*1048576),
                        new JdbcPurgeFacadeJournal(guard,source.dialect,2,256,10*1048576),
                        new JdbcPurgeParticipantJournal(guard,2,2),wire,client,keys,barrier,fence,targets,Set.of(ns),Map.of(),
                        Map.of(stream,wire),new PurgeParticipantManifest(meta.entityName(),Set.of(),List.of()),e->auditEvents.incrementAndGet());
            } catch (Exception | Error failure) {
                FixtureResources.closeOnFailure(failure,openedWire,openedRedis,source);
                throw failure;
            }
        }
        void seed()throws Exception{
            source.seed("a",2);source.seed("b",1);
            redis.set(keys.versionKey(ns,cold),"700");redis.set(keys.versionKey(ns,cold)+":tenant","a");
            redis.set(keys.tombstoneKey(ns,cold),"700"); // deliberately NOT in the hot/exact index
            for(String id:List.of(hot,foreign)){
                redis.set(keys.versionKey(ns,id),"701");redis.set(keys.versionKey(ns,id)+":tenant",id.equals(foreign)?"b":"a");
                redis.set(keys.entityKey(ns,id),id.equals(foreign)?"foreign":"owned");
                indexes.reindex(id);redis.rpush(keys.pageKey(ns,0),id);
            }
            redis.xadd(stream,StreamEntryID.NEW_ENTRY,fields("a",hot));
            redis.xadd(stream,StreamEntryID.NEW_ENTRY,fields("b",foreign));
        }
        Map<String,String> fields(String tenant,String id){
            var key=binding.key(tenant);
            return Map.of("namespace",ns,"id",id,"version","701","type","UPSERT","generationScope",key.scopeToken(),
                    "tenantToken",key.tenantToken(),"tenantGeneration","1");
        }
        CacheDataProtection facade(){return new CacheDataProtection(registry,List.of(plan),1);}
        PurgeOptions options(boolean includeSource){
            return new PurgeOptions(source.operation,"a",ProtectionKey.hash("fixture-operator"),includeSource,128,120,30000,
                    PurgeScope.ACTIVE_STORES_RETAINING_SECURITY_METADATA);
        }
        CacheDataProtection.Result finish(boolean includeSource){
            CacheDataProtection.Result result=null;CacheDataProtection.Continuation continuation=null;
            for(int i=0;i<5000;i++){
                // New facade instance every call: native pending work/offsets are durable, never a Java work queue.
                result=facade().purgeTenant(String.class,"a",options(includeSource),continuation);
                continuation=result.continuation();
                if(result.status()!=CacheDataProtection.Status.RUNNING)return result;
            }
            fail("Purge fixture exceeded the bounded step count");return result;
        }
        long temporaryRows(String table)throws Exception{
            if(!Set.of("cachedb_purge_work","cachedb_purge_scan","cachedb_purge_facade").contains(table))throw new IllegalArgumentException();
            try(var c=source.source.getConnection();var q=c.prepareStatement("SELECT COUNT(*) FROM "+table+" WHERE scope_token=? AND tenant_token=? AND operation_id=?")){
                q.setQueryTimeout(2);q.setString(1,binding.key("a").scopeToken());q.setString(2,binding.key("a").tenantToken());
                q.setString(3,source.operation.toString());try(var r=q.executeQuery()){assertTrue(r.next());return r.getLong(1);}
            }
        }
        public void close()throws Exception{
            FixtureResources.close(wire,()->{
                // Disposable random-prefix fixture only; no production global scan/delete or SQL-fence cleanup.
                String cursor="0";int count=0;
                do{
                    if(++count>1000)throw new IllegalStateException("Fixture cleanup budget");
                    var found=redis.scan(cursor,new redis.clients.jedis.params.ScanParams().match(prefix+"*").count(100));
                    if(found.getResult().size()>10000)throw new IllegalStateException("Fixture reply budget");
                    for(int i=0;i<found.getResult().size();i+=128)
                        redis.del(found.getResult().subList(i,Math.min(i+128,found.getResult().size())).toArray(String[]::new));
                    cursor=found.getCursor();
                }while(!cursor.equals("0"));
            },redis,source);
        }
    }
    /** Actual gate for the finite fixture route set; intentionally not a production read-fence implementation. */
    static final class FixtureFence implements TenantReadFence {
        final Fixture f;
        final AtomicInteger activeReads=new AtomicInteger();
        final AtomicReference<Runnable> onDrain=new AtomicReference<>();
        final AtomicBoolean admissionClosed=new AtomicBoolean(),historyAttested=new AtomicBoolean(true),injectAfterErase=new AtomicBoolean();
        FixtureFence(Fixture f){this.f=f;}
        public Evidence closeAndDrain(Lease lease,long generation,String manifest,String checkpoint,int maxOperations,int maxMillis){
            Runnable action=onDrain.get();if(action!=null)action.run();
            admissionClosed.set(true);
            if(!historyAttested.get())return new Evidence(State.INCOMPLETE,"",null,1,0);
            if(activeReads.get()!=0)return new Evidence(State.DRAINING,"drain",null,1,0);
            f.guard.load(lease);f.barrier.assertClosed(lease,generation);
            if(f.guard.load(lease).stage()==Stage.VERIFYING&&injectAfterErase.compareAndSet(true,false))
                f.redis.set(f.keys.entityKey(f.ns,"late-orphan"),"unattributed");
            return new Evidence(State.CLOSED,"closed",token(lease,generation,manifest),1,1);
        }
        public void requireClosed(Lease lease,long generation,String manifest,Evidence evidence){
            if(!admissionClosed.get()||activeReads.get()!=0||!historyAttested.get()
                    ||!token(lease,generation,manifest).equals(evidence.token()))throw new ProtectionException(ProtectionException.Code.INCOMPLETE_INVENTORY);
            f.guard.load(lease);f.barrier.assertClosed(lease,generation);
        }
        public void requireActivationCoverage(ProtectionKey key, String manifest) {
            if (!historyAttested.get() || !key.equals(f.binding.key("a")) || !manifest.equals(f.plan.manifestToken()))
                throw new ProtectionException(ProtectionException.Code.INCOMPLETE_INVENTORY);
        }
        public void release(Lease lease){} // release never reopens the fixture gate
        private static String token(Lease lease,long generation,String manifest){
            return ProtectionKey.hash(lease.key().scopeToken()+lease.key().tenantToken()+lease.operationId()+lease.owner()+lease.epoch()+":"+generation+manifest);
        }
    }
}
