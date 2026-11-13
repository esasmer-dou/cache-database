package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.processor.EncryptionCompilerFixture;
import com.reactor.cachedb.redis.*;
import com.reactor.cachedb.starter.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import redis.clients.jedis.JedisPooled;

/** Test-only resource owner. Runtime metadata and codecs come exclusively from the real processor. */
final class GeneratedInitialNativeFixture implements AutoCloseable {
    static final String NS="generated_initial_rows", ENTITY="GeneratedInitialRow", SECRET="GENERATED_INITIAL_PRIVATE_";
    final String run=UUID.randomUUID().toString().replace("-","");
    final String prefix="generated_initial_"+run, library=prefix+"_functions";
    final GeneratedInitialSqlSandbox sandbox;
    private final boolean callerOwnedSql;
    final CountingSource source;
    final EncryptionCompilerFixture.Loaded compiled;
    final EntityMetadata<Object,Long> metadata;
    final EntityCodec<Object> unbound, bound;
    final JedisPooled redis, peerRedis;
    final BoundedRedisWire wire;
    final CacheDatabase database, peer;
    final RedisTenantProducer producer, peerProducer;
    final Binding binding;
    final JdbcTenantPurgeGuard guard;
    final Generation generation;
    final JdbcSourceOwnerOnboarding.JdbcIdCodec<Long> ids=JdbcSourceOwnerOnboarding.longIds();
    final CacheInitialNamespace<Object,Long> namespace;
    final CacheTenantRuntimeEnrollment primary, secondary;
    final RedisTenantPurgePlan plan, peerPlan;
    final RedisTenantRuntimeControl.ApprovedRoster approval;
    final CacheInitialTenantBootstrap<Object,Long> service;
    final InitialTenantBootstrap.Request request;
    final Map<Long,String> originalSource;
    final Path proof;

    GeneratedInitialNativeFixture(Path temp,boolean twoRoots)throws Exception {
        this(temp,twoRoots,null);
    }
    @SuppressWarnings("unchecked")
    GeneratedInitialNativeFixture(Path temp,boolean twoRoots,GeneratedInitialSqlSandbox suppliedSandbox)throws Exception {
        // The caller retains SQL ownership even if later runtime construction fails.
        callerOwnedSql=suppliedSandbox!=null;
        proof=Path.of("target","generated-initial-proofs",run+".txt");
        Files.createDirectories(proof.getParent());
        var result=EncryptionCompilerFixture.compile(temp,Map.of("sample/GeneratedInitialRow.java","""
                package sample;
                import com.reactor.cachedb.annotations.*;
                @CacheEntity(table="generated_initial_rows",redisNamespace="generated_initial_rows",
                    tenantPurgeEnabled=true,indexedColumns={"tenant_id","secret"})
                public record GeneratedInitialRow(@CacheId(column="id") Long id,
                    @CacheTenantId @CacheColumn("tenant_id") String tenant,
                    @CacheEncrypted(scope=EncryptionScope.REDIS_AND_SOURCE,blindIndex=true)
                    @CacheColumn("secret") String secret) {}
                ""","sample/Scenario.java","""
                package sample;
                public final class Scenario implements java.util.function.Supplier<Object[]> {
                    public Object[] get() {
                        return new Object[]{new GeneratedInitialRow(1L,"a","GENERATED_INITIAL_PRIVATE_1"),
                            GeneratedInitialRowCacheBinding.METADATA,GeneratedInitialRowCacheBinding.CODEC};
                    }
                }
                """));
        assertTrue(result.success(),result.diagnostics());
        exportCompiled(result.generated(),"generated");exportCompiled(result.classes(),"classes");
        EncryptionCompilerFixture.Loaded openedCompiled=null;
        GeneratedInitialSqlSandbox openedSandbox=null;
        JedisPooled openedRedis=null,openedPeerRedis=null;
        BoundedRedisWire openedWire=null;
        CacheDatabase openedDatabase=null,openedPeer=null;
        try {
            compiled=openedCompiled=EncryptionCompilerFixture.load(result);
            metadata=(EntityMetadata<Object,Long>)(EntityMetadata<?,?>)compiled.metadata();unbound=compiled.unbound();
            assertTrue(unbound.encryptedMapping());assertFalse(unbound.encryptionBound());
            assertSame(compiled.loader(),metadata.entityType().getClassLoader());
            sandbox=callerOwnedSql?suppliedSandbox:(openedSandbox=new GeneratedInitialSqlSandbox("postgres"));source=new CountingSource(sandbox.source);
            evidence("OWNED_FIXTURE scope="+sandbox.name+" redisPrefix="+prefix+" library="+library+" run="+run
                    +" generatedBinding=true roots="+(twoRoots?2:1));
            String host=System.getProperty("cachedb.snapshot.redis.host","redis");int port=Integer.getInteger("cachedb.snapshot.redis.port",6379);
            redis=openedRedis=new JedisPooled(host,port);redis.getPool().setMaxTotal(4);
            peerRedis=openedPeerRedis=twoRoots?new JedisPooled(host,port):null;
            if(peerRedis!=null)peerRedis.getPool().setMaxTotal(4);
            wire=openedWire=new BoundedRedisWire(new InetSocketAddress(host,port),null,null,null,null,0,2000,2000,1);
            var config=CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                    .readThrough(ReadThroughConfig.builder().mode(ReadThroughMode.REDIS_ONLY).build())
                    .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                    .redisGuardrail(RedisGuardrailConfig.builder().enabled(false).build())
                    .redisFunctions(RedisFunctionsConfig.builder().libraryName(library).upsertFunctionName(library+"_upsert")
                            .deleteFunctionName(library+"_delete").compactionCompleteFunctionName(library+"_complete").build())
                    .writeBehind(WriteBehindConfig.builder().enabled(false).streamKey(prefix+":write")
                            .deadLetterStreamKey(prefix+":dlq").compactionStreamKey(prefix+":compact").build()).build();
            var key1=EncryptionCompilerFixture.key("k1",11);var key2=EncryptionCompilerFixture.key("k2",17);
            var options=CacheRuntimeOptions.builder().keyProvider(EncryptionCompilerFixture.keys("k2",key1,key2)).build();
            database=openedDatabase=new CacheDatabase(redis,source,config,options);database.start();
            database.register(metadata,unbound,new CachePolicy(16,16,false,120,120));
            bound=(EntityCodec<Object>)database.entityRegistry().find(ENTITY).orElseThrow().codec();
            assertTrue(bound.encryptionBound());assertNotSame(unbound,bound);
            peer=openedPeer=twoRoots?new CacheDatabase(peerRedis,source,config,options):null;
            if(peer!=null){peer.start();peer.register(metadata,unbound,new CachePolicy(16,16,false,120,120));}
            binding=new Binding(metadata.entityType(),NS,new SourcePlan(NS,"tenant_id","id"),
                    RedisInitialSourceLayout.layoutToken(metadata,database.redisKeyStrategy(),config.queryIndex()),10000,128,1048576,true);
            guard=new JdbcTenantPurgeGuard(source,sandbox.dialect,2,1,Set.of(binding.fingerprint()));generation=new Generation(binding.key("a"),1);
            try(var c=source.getConnection();var q=c.createStatement()) {
                q.setQueryTimeout(5);
                q.executeUpdate("CREATE TABLE native_fixture_owner(run_token VARCHAR(32) NOT NULL PRIMARY KEY)");
                q.executeUpdate("INSERT INTO native_fixture_owner(run_token) VALUES ('"+run+"')");
                q.executeUpdate(tableDdl(sandbox.dialect));
                q.executeUpdate("CREATE INDEX generated_tenant_idx ON generated_initial_rows(tenant_id,id)");
                q.executeUpdate("CREATE INDEX generated_version_idx ON generated_initial_rows(tenant_id,entity_version)");
            }
            var old=unbound.bindEncryption(EncryptionCompilerFixture.keys("k1",key1,key2));
            try(var c=source.getConnection();var q=c.prepareStatement("INSERT INTO generated_initial_rows(id,tenant_id,secret,entity_version) VALUES (?,?,?,?)")) {
                q.setQueryTimeout(2);
                for(long id:List.of(1L,2L,3L,99L)) {
                    var row=row(id);q.setLong(1,id);q.setString(2,id==2?"b":"a");q.setString(3,old.toSourceColumns(row).get("secret").toString());
                    q.setLong(4,id==1?7:id==3?8:id==99?9000:18000);q.addBatch();
                }
                assertEquals(4,q.executeBatch().length);
            }
            originalSource=sourceSnapshot();guard.provisionClosed(generation);
            namespace=database.prepareInitialNamespace(metadata,binding,guard,ids,1L,3L,2,65536,16384,1048576,2);
            producer=namespace.bootstrap(new JdbcNamespaceBootstrapGate(guard,Set.of(namespace.descriptor().namespaceFingerprint())),generation);
            assertEquals(namespace.descriptor().incarnationId(),producer.runtimeId());
            peerProducer=peer==null?null:peer.configureTenantProducer(metadata,2);
            primary=database.prepareTenantProtection(2);secondary=peer==null?null:peer.prepareTenantProtection(2);
            plan=plan(database,primary,namespace.window());
            if(peer!=null) {
                var peerCodec=(EntityCodec<Object>)peer.entityRegistry().find(ENTITY).orElseThrow().codec();
                var window=new InitialSourceHotWindow<>(binding,metadata,peerCodec,ids,ids.formatToken(),1L,3L,2,65536,16384,1048576,
                        InitialSourceHotWindow.Profile.POINT_NO_QUOTA);
                peerPlan=plan(peer,secondary,window);assertEquals(plan.manifestToken(),peerPlan.manifestToken());
            } else peerPlan=null;
            var members=new ArrayList<UUID>();members.add(primary.descriptor(ENTITY).runtimeId());
            if(secondary!=null)members.add(secondary.descriptor(ENTITY).runtimeId());
            approval=new RedisTenantRuntimeControl.ApprovedRoster(ProtectionKey.hash("generated-review:"+run),plan.manifestToken(),
                    primary.descriptor(ENTITY).coverageToken(),members,List.of(generation.key()));
            service=namespace.createBootstrap(primary,plan,approval,64,Set.of(namespace.window().fingerprint()));
            request=new InitialTenantBootstrap.Request(generation,"a",UUID.randomUUID(),ProtectionKey.hash("generated-operator:"+run),120);
        } catch(Exception|Error failure) {
            GeneratedInitialSqlSandbox.closeAfterFailedConstruction(failure,callerOwnedSql,openedSandbox,
                    openedPeer,openedDatabase,openedWire,openedPeerRedis,openedRedis,openedCompiled);
            throw failure;
        }
    }

    static String tableDdl(JdbcTenantPurgeDialect dialect){
        String tenantType=dialect==JdbcTenantPurgeDialect.MSSQL?"NVARCHAR(64)":"VARCHAR(64)";
        return "CREATE TABLE generated_initial_rows(id DECIMAL(19,0) NOT NULL PRIMARY KEY,tenant_id "+tenantType
                +" NOT NULL,secret VARCHAR(1024),entity_version DECIMAL(19,0) NOT NULL)";
    }

    Object row(long id){return bound.fromLogicalColumns(Map.of("id",id,"tenant_id",id==2?"b":"a","secret",SECRET+id));}
    InitialTenantBootstrap.Progress activate()throws Exception {
        var stop=new AtomicBoolean();var failure=new AtomicReference<Throwable>();Thread worker=null;
        if(secondary!=null)worker=Thread.ofPlatform().name("generated-initial-peer").start(()->{
            try(var admin=CacheTenantContext.administrative()) {
                long deadline=System.nanoTime()+100_000_000_000L;
                boolean sealed=false;
                for(int round=0;!stop.get()&&round<10000&&System.nanoTime()<deadline;round++) {
                    if(!sealed) {
                        var pending=namespace.pendingClosure();
                        if(pending.isPresent()){secondary.sealClosed(peerPlan,approval,List.of(pending.orElseThrow()));sealed=true;}
                    }
                    // The approved roster is stable; polling discovers the actual replacement SQL lease.
                    // Re-sealing a previously observed lease would race the next administrative step.
                    if(sealed)secondary.pollControl(1,1000);
                    Thread.sleep(10);
                }
            }catch(Throwable t){failure.set(t);}
        });
        try {
            InitialTenantBootstrap.Progress progress=null;
            for(int call=0;call<512;call++) {
                if(failure.get()!=null)throw new AssertionError("Actual peer control failed",failure.get());
                progress=service.step(request);evidence("PROGRESS call="+call+" phase="+progress.phase());
                assertNotEquals(InitialTenantBootstrap.Phase.INCOMPLETE,progress.phase());
                if(progress.phase()==InitialTenantBootstrap.Phase.ACTIVE)return progress;
            }
            throw new AssertionError("INITIAL bounded continuation count exhausted");
        }finally {
            stop.set(true);if(worker!=null){worker.join(3000);assertFalse(worker.isAlive(),"Peer physical worker must exit");}
            if(failure.get()!=null)throw new AssertionError("Actual peer control failed",failure.get());
        }
    }
    RedisTenantPurgePlan plan(CacheDatabase root,CacheTenantRuntimeEnrollment enrollment,InitialSourceHotWindow<Object,Long> window) {
        var participants=new PurgeParticipantManifest(ENTITY,Set.of("runtime-control-roster","initial-native-window","initial-source-journal"),
                List.of(enrollment.controlParticipant(ENTITY),enrollment.initialSourceParticipant(metadata,window),
                        new JdbcInitialBootstrapPurgeParticipant(guard,binding,ENTITY,2)));
        var routes=new LinkedHashMap<String,BoundedRedisWire>();var write=root.config().writeBehind();
        routes.put(write.streamKey(),wire);routes.put(write.deadLetterStreamKey(),wire);routes.put(write.compactionStreamKey(),wire);
        for(String key:write.activeStreamKeys())routes.put(key,wire);
        return new RedisTenantPurgePlan(binding,ENTITY,"entity_version",guard,new JdbcTenantVersionAllocator(guard,sandbox.dialect,2),
                new JdbcPurgeScanJournal(guard,sandbox.dialect,2,128,10000,10*1048576),new JdbcPurgeFacadeJournal(guard,sandbox.dialect,2,256,10*1048576),
                new JdbcPurgeParticipantJournal(guard,2,1),wire,root.redisOperations(),root.redisKeyStrategy(),enrollment.barrier(ENTITY),enrollment.readFence(ENTITY),
                List.of(enrollment.pointProfileTarget(ENTITY)),Set.of(NS),Map.of(),routes,participants,event->{});
    }
    Map<Long,String> sourceSnapshot()throws SQLException {
        var values=new LinkedHashMap<Long,String>();
        try(var c=source.getConnection();var q=c.prepareStatement("SELECT id,tenant_id,secret,entity_version FROM generated_initial_rows ORDER BY id")) {
            q.setQueryTimeout(2);try(var r=q.executeQuery()){while(r.next())values.put(r.getLong(1),r.getString(2)+"|"+r.getString(3)+"|"+r.getBigDecimal(4).toPlainString());}
        }return Map.copyOf(values);
    }
    void evidence(String message)throws java.io.IOException {
        Files.writeString(proof,message+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);System.out.println("GENERATED_INITIAL "+message);
    }
    private void exportCompiled(Path from,String kind)throws Exception {
        Path destination=proof.getParent().resolve(run).resolve(kind);Files.createDirectories(destination);
        try(var walk=Files.walk(from)) {
            var files=walk.filter(Files::isRegularFile).limit(65).toList();assertTrue(files.size()<=64);long bytes=0;
            for(var file:files){bytes+=Files.size(file);assertTrue(bytes<=8*1048576);Path to=destination.resolve(from.relativize(file));Files.createDirectories(to.getParent());Files.copy(file,to);}
        }
    }
    public void close()throws Exception {
        // A supplied SQL owner disposes its sandbox after this runtime closes.
        GeneratedInitialSqlSandbox.closeFixture(callerOwnedSql,sandbox,peer,database,wire,peerRedis,redis,compiled);
    }
    static final class CountingSource implements DataSource {
        final DataSource delegate;final AtomicInteger calls=new AtomicInteger(),foreground=new AtomicInteger();
        final AtomicReferenceArray<Thread> tracked=new AtomicReferenceArray<>(4);
        CountingSource(DataSource delegate){this.delegate=delegate;}
        AutoCloseable track(){Thread thread=Thread.currentThread();for(int i=0;i<tracked.length();i++)if(tracked.compareAndSet(i,null,thread)){int slot=i;return ()->assertTrue(tracked.compareAndSet(slot,thread,null));}throw new AssertionError("Audit capacity exhausted");}
        void count(){calls.incrementAndGet();for(int i=0;i<tracked.length();i++)if(tracked.get(i)==Thread.currentThread()){foreground.incrementAndGet();return;}}
        public Connection getConnection()throws SQLException{count();return delegate.getConnection();}
        public Connection getConnection(String u,String p)throws SQLException{count();return delegate.getConnection(u,p);}
        public java.io.PrintWriter getLogWriter()throws SQLException{return delegate.getLogWriter();}
        public void setLogWriter(java.io.PrintWriter out)throws SQLException{delegate.setLogWriter(out);}
        public int getLoginTimeout()throws SQLException{return delegate.getLoginTimeout();}
        public void setLoginTimeout(int seconds)throws SQLException{delegate.setLoginTimeout(seconds);}
        public java.util.logging.Logger getParentLogger()throws SQLFeatureNotSupportedException{return delegate.getParentLogger();}
        public <T>T unwrap(Class<T> t)throws SQLException{return delegate.unwrap(t);}
        public boolean isWrapperFor(Class<?> t)throws SQLException{return delegate.isWrapperFor(t);}
    }
}
