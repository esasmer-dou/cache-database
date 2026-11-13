package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.*;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.processor.EncryptionCompilerFixture;
import com.reactor.cachedb.redis.*;
import com.reactor.cachedb.starter.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.*;

/** Rejection controls use real unconsumed SQL authority; only the supported case is a positive. */
@EnabledIfSystemProperty(named="cachedb.snapshot.providers",matches="true")
@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
@EnabledIfSystemProperty(named="cachedb.initial.isolatedSql",matches="true")
@Timeout(120)
class CacheInitialProfileSafetyRedisTest {
    @TempDir Path temp;
    static final CachePolicy POLICY=new CachePolicy(16,16,false,120,120);
    static final NearCachePolicy NEAR=new NearCachePolicy(16,10,1048576);

    @Test void globalL1RejectsEvenWhenThisBindingDisablesIt()throws Exception {
        try(var f=new Fixture(temp)) {f.rejectedCreation(POLICY.withNearCachePolicy(NEAR),POLICY,false,false,false);}
    }
    @Test void explicitEntityL1RejectsWithGlobalDefaultDisabled()throws Exception {
        try(var f=new Fixture(temp)) {f.rejectedCreation(POLICY,POLICY.withNearCachePolicy(NEAR),false,false,false);}
    }
    @Test void generatedAnnotationL1RejectsDespiteDisabledConfiguredPolicy()throws Exception {
        try(var f=new Fixture(temp)) {f.rejectedCreation(POLICY,POLICY,true,false,false);}
    }
    @Test void customForegroundAdapterRejectsBeforeItsFirstEvalOrSqlBorrow()throws Exception {
        try(var f=new Fixture(temp)) {f.rejectedCreation(POLICY,POLICY,false,true,false);}
    }
    @Test void customBackgroundAdapterRejectsBeforeItsFirstEvalOrSqlBorrow()throws Exception {
        try(var f=new Fixture(temp)) {f.rejectedCreation(POLICY,POLICY,false,false,true);}
    }
    @Test void splitCanonicalPoolsAreNotTheTestedSingleRuntimeTransport()throws Exception {
        try(var f=new Fixture(temp);var other=f.client();var admin=CacheTenantContext.administrative();
                var db=f.database(new JedisPooledOperations(f.redis),new JedisPooledOperations(other),POLICY)) {
            db.register(f.metadata,f.codec,POLICY);var guard=f.guard(db,f.metadata);int before=f.source.calls.get();
            rejected(()->f.namespace(db,f.metadata,guard));assertEquals(before,f.source.calls.get());f.unconsumed(db,f.metadata);
        }
    }
    @Test void lateRegisteredEntityPolicyL1RejectsBeforeBirthConsumption()throws Exception {lateBeforeBirth(false);}
    @Test void lateRegisteredGeneratedAnnotationL1RejectsBeforeBirthConsumption()throws Exception {lateBeforeBirth(true);}
    void lateBeforeBirth(boolean annotation)throws Exception {
        try(var f=new Fixture(temp);var admin=CacheTenantContext.administrative();var db=f.database(f.actual(),f.actual(),POLICY)) {
            db.register(f.metadata,f.codec,POLICY);var guard=f.guard(db,f.metadata);guard.provisionClosed(new Generation(f.binding(db,f.metadata).key("a"),1));
            var ns=f.namespace(db,f.metadata,guard);var descriptor=ns.descriptor();
            db.register(annotation?f.nearMetadata:f.plainMetadata,annotation?f.nearCodec:f.plainCodec,annotation?POLICY:POLICY.withNearCachePolicy(NEAR));
            int before=f.source.calls.get();
            rejected(()->ns.bootstrap(new JdbcNamespaceBootstrapGate(guard,Set.of(descriptor.namespaceFingerprint())),new Generation(f.binding(db,f.metadata).key("a"),1)));
            assertEquals(before,f.source.calls.get());f.unconsumed(db,f.metadata);
        }
    }
    @Test void lateEntityL1AfterBirthRejectsCoordinatorBeforeAnySqlPhase()throws Exception {
        try(var f=new Fixture(temp);var admin=CacheTenantContext.administrative();
                var root=new KilledInitialParticipantProcess.Root(f.run,f.classes,f.source)) {
            root.guard.provisionClosed(root.generation);
            var ns=root.database.prepareInitialNamespace(root.metadata,root.binding,root.guard,root.ids,1L,3L,2,65536,16384,1048576,2);
            String namespaceFingerprint=ns.descriptor().namespaceFingerprint();
            root.enroll(ns.bootstrap(new JdbcNamespaceBootstrapGate(root.guard,Set.of(namespaceFingerprint)),root.generation),ns.window());
            var descriptor=root.enrollment.descriptor("GeneratedInitialRow");
            var approval=new RedisTenantRuntimeControl.ApprovedRoster(ProtectionKey.hash("profile-review:"+f.run),root.plan.manifestToken(),descriptor.coverageToken(),List.of(descriptor.runtimeId()),List.of(root.generation.key()));
            var service=ns.createBootstrap(root.enrollment,root.plan,approval,64,Set.of(ns.window().fingerprint()));
            String birth=f.birth();assertEquals(1,f.count("cachedb_namespace_bootstrap"));
            root.database.register(f.nearMetadata,f.nearCodec,POLICY);
            int before=f.source.calls.get();
            var request=new InitialTenantBootstrap.Request(root.generation,"a",UUID.randomUUID(),ProtectionKey.hash("profile-step:"+f.run),120);
            rejected(()->ns.bootstrap(new JdbcNamespaceBootstrapGate(root.guard,Set.of(namespaceFingerprint)),root.generation));
            rejected(()->service.step(request));assertEquals(before,f.source.calls.get());
            assertEquals(birth,f.birth());assertEquals(0,f.count("cachedb_initial_bootstrap"));
            assertEquals("COLLECTING",root.redis.hget(root.enrollment.controlStoreKey("GeneratedInitialRow"),"state"));
            f.evidence("PASS lateAfterBirth=true existingBirthUnchanged=true noSqlPhase=true noNewGrant=true");
        }
    }
    @Test @EnabledIfSystemProperty(named="cachedb.redis.cluster",matches="true")
    void actualSixNodeClusterAdapterRejectsBeforeSqlOrLibraryLoad()throws Exception {
        var pool=new org.apache.commons.pool2.impl.GenericObjectPoolConfig<redis.clients.jedis.Connection>();pool.setMaxTotal(2);pool.setMaxWait(Duration.ofMillis(1000));
        var config=DefaultJedisClientConfig.builder().connectionTimeoutMillis(2000).socketTimeoutMillis(2000).build();
        try(var f=new Fixture(temp);var client=new JedisCluster(Set.of(clusterAddress()),config,2,Duration.ofSeconds(2),pool);
                var admin=CacheTenantContext.administrative()) {
            assertTrue(client.getClusterNodes().size()>=6&&client.getClusterNodes().size()<=16);
            var actual=new JedisClusterOperations(client);
            try(var db=f.database(actual,actual,POLICY)) {
                db.register(f.metadata,f.codec,POLICY);var guard=f.guard(db,f.metadata);int before=f.source.calls.get();
                rejected(()->f.namespace(db,f.metadata,guard));assertEquals(before,f.source.calls.get());
                assertEquals(0,f.count("cachedb_namespace_bootstrap"));
                assertFalse(client.exists(RedisTenantRuntimeControl.rosterKey(db.redisKeyStrategy(),f.metadata.redisNamespace())));
                for(var node:client.getClusterNodes().values())try(var jedis=new Jedis(node.getResource())) {
                    assertTrue(jedis.info("cluster").contains("cluster_enabled:1"));assertTrue(jedis.functionList(f.library).isEmpty());
                }
                f.evidence("PASS actualClusterNodes="+client.getClusterNodes().size()+" rejection=UNWIRED_PROTOCOL births=0 ownLibraryLoaded=false");
            }
        }
    }
    @Test @EnabledIfSystemProperty(named="cachedb.redis.cluster",matches="true")
    void actualClusterNodeCannotMasqueradeAsSupportedPooledStandalone()throws Exception {
        try(var f=new Fixture(temp);var node=f.client(clusterAddress());
                var admin=CacheTenantContext.administrative()) {
            try(var jedis=new Jedis(node.getPool().getResource())){assertTrue(jedis.info("cluster").contains("cluster_enabled:1"));}
            var ops=new JedisPooledOperations(node);
            try(var db=f.database(ops,ops,POLICY)) {
                db.register(f.metadata,f.codec,POLICY);var guard=f.guard(db,f.metadata);int before=f.source.calls.get();
                rejected(()->f.namespace(db,f.metadata,guard));assertEquals(before,f.source.calls.get());
                assertEquals(0,f.count("cachedb_namespace_bootstrap"));assertTrue(node.functionList(f.library).isEmpty());
                f.evidence("PASS actualPooledClusterNode=true rejection=UNWIRED_PROTOCOL births=0 ownLibraryLoaded=false");
            }
        }
    }
    @Test void canonicalSupportedGeneratedEncryptedPgInitialStillReachesActive()throws Exception {
        try(var admin=CacheTenantContext.administrative();var f=new KilledInitialParticipantRedisTest.Fixture(temp)) {
            var local=f.root.enrollment.descriptor("GeneratedInitialRow");
            var approval=new RedisTenantRuntimeControl.ApprovedRoster(ProtectionKey.hash("supported-profile:"+f.run),f.root.plan.manifestToken(),local.coverageToken(),List.of(local.runtimeId()),List.of(f.root.generation.key()));
            var service=f.namespace.createBootstrap(f.root.enrollment,f.root.plan,approval,64,Set.of(f.namespace.window().fingerprint()));
            var request=new InitialTenantBootstrap.Request(f.root.generation,"a",UUID.randomUUID(),ProtectionKey.hash("profile-operator:"+f.run),120);
            boolean active=false;
            for(int call=0;call<512;call++) {
                var progress=service.step(request);assertNotEquals(InitialTenantBootstrap.Phase.INCOMPLETE,progress.phase());
                if(progress.phase()==InitialTenantBootstrap.Phase.ACTIVE){assertEquals(2,progress.warmedRows());active=true;break;}
            }
            assertTrue(active);assertEquals(f.originalSource,f.source());int before=f.root.source.calls.get();
            for(long id:List.of(1L,3L))f.root.enrollment.deliver(f.root.metadata,"a",repository->repository.findById(id),row->assertEquals(f.root.row(id),row.orElseThrow()));
            assertEquals(before,f.root.source.calls.get());
            f.evidence("PASS canonicalSupportedGeneratedPg=true actualActive=true warmed=2 sourceUnchanged=true foregroundSqlBorrows=0");
        }
    }
    static HostAndPort clusterAddress(){return new HostAndPort(System.getProperty("cachedb.redis.cluster.host","redis-cluster-0.redis-cluster.cachedb-v019-validation.svc.cluster.local"),Integer.getInteger("cachedb.redis.cluster.port",6379));}
    static void rejected(org.junit.jupiter.api.function.Executable action) {
        var failure=assertThrows(ProtectionException.class,action);assertEquals(ProtectionException.Code.UNWIRED_PROTOCOL,failure.code());
        assertNull(failure.getCause());assertEquals(0,failure.getSuppressed().length);assertFalse(failure.toString().contains(GeneratedInitialNativeFixture.SECRET));
    }
    static final class ProbeAdapter implements RedisClientOperations {
        final JedisPooled redis;final AtomicInteger calls=new AtomicInteger();ProbeAdapter(JedisPooled redis){this.redis=redis;}
        public JedisPooled commands(){return redis;}
        public Object eval(String program,List<String> keys,List<String> arguments){calls.incrementAndGet();throw new AssertionError("Unsupported adapter must be rejected before EVAL");}
        public void close(){}
    }
    static final class Fixture implements AutoCloseable {
        final String run=UUID.randomUUID().toString().replace("-",""),prefix="profile_initial_"+run,library=prefix+"_functions";
        final Path proof=Path.of("target","generated-initial-proofs","profile-"+run+".txt");final Path classes;
        final GeneratedInitialSqlSandbox sandbox;final GeneratedInitialNativeFixture.CountingSource source;
        final EncryptionCompilerFixture.Loaded compiled;final EntityMetadata<Object,Long> metadata,nearMetadata,plainMetadata;
        final EntityCodec<Object> codec,nearCodec,plainCodec;final JedisPooled redis;
        @SuppressWarnings("unchecked") Fixture(Path temp)throws Exception {
            var result=EncryptionCompilerFixture.compile(temp,Map.of("sample/GeneratedInitialRow.java","""
                    package sample;
                    import com.reactor.cachedb.annotations.*;
                    @CacheEntity(table="generated_initial_rows",redisNamespace="generated_initial_rows",tenantPurgeEnabled=true,indexedColumns={"tenant_id","secret"})
                    public record GeneratedInitialRow(@CacheId(column="id") Long id,@CacheTenantId @CacheColumn("tenant_id") String tenant,
                        @CacheEncrypted(scope=EncryptionScope.REDIS_AND_SOURCE,blindIndex=true) @CacheColumn("secret") String secret) {}
                    ""","sample/NearInitialRow.java","""
                    package sample;
                    import com.reactor.cachedb.annotations.*;
                    @CacheNearPolicy(maxEntries=16,ttlSeconds=10,maxBytes=1048576)
                    @CacheEntity(table="near_initial_rows",redisNamespace="near_initial_rows",tenantPurgeEnabled=true,indexedColumns={"tenant_id"})
                    public record NearInitialRow(@CacheId(column="id") Long id,@CacheTenantId @CacheColumn("tenant_id") String tenant) {}
                    ""","sample/PlainInitialRow.java","""
                    package sample;
                    import com.reactor.cachedb.annotations.*;
                    @CacheEntity(table="plain_initial_rows",redisNamespace="plain_initial_rows",indexedColumns={"tenant_id"})
                    public record PlainInitialRow(@CacheId(column="id") Long id,@CacheColumn("tenant_id") String tenant) {}
                    ""","sample/Scenario.java","""
                    package sample;
                    public final class Scenario implements java.util.function.Supplier<Object[]> {
                        public Object[] get(){return new Object[]{new Object[]{NearInitialRowCacheBinding.METADATA,NearInitialRowCacheBinding.CODEC,PlainInitialRowCacheBinding.METADATA,PlainInitialRowCacheBinding.CODEC},GeneratedInitialRowCacheBinding.METADATA,GeneratedInitialRowCacheBinding.CODEC};}
                    }
                    """));assertTrue(result.success(),result.diagnostics());classes=result.classes();
            EncryptionCompilerFixture.Loaded openedCompiled=null;
            GeneratedInitialSqlSandbox openedSandbox=null;
            JedisPooled openedRedis=null;
            try {
                compiled=openedCompiled=EncryptionCompilerFixture.load(result);metadata=(EntityMetadata<Object,Long>)(EntityMetadata<?,?>)compiled.metadata();codec=compiled.unbound();
                var near=(Object[])compiled.entity();nearMetadata=(EntityMetadata<Object,Long>)near[0];nearCodec=(EntityCodec<Object>)near[1];assertTrue(nearMetadata.nearCachePolicy().enabled());
                plainMetadata=(EntityMetadata<Object,Long>)near[2];plainCodec=(EntityCodec<Object>)near[3];assertFalse(plainMetadata.nearCachePolicy().enabled());
                sandbox=openedSandbox=new GeneratedInitialSqlSandbox("postgres");source=new GeneratedInitialNativeFixture.CountingSource(sandbox.source);redis=openedRedis=client();
                try(var c=source.getConnection();var q=c.createStatement()) {
                    q.setQueryTimeout(5);q.executeUpdate("CREATE TABLE generated_initial_rows(id DECIMAL(19,0) NOT NULL PRIMARY KEY,tenant_id VARCHAR(64) NOT NULL,secret VARCHAR(1024),entity_version DECIMAL(19,0) NOT NULL)");
                    q.executeUpdate("CREATE INDEX profile_tenant_idx ON generated_initial_rows(tenant_id,id)");q.executeUpdate("CREATE INDEX profile_version_idx ON generated_initial_rows(tenant_id,entity_version)");
                }
                Files.createDirectories(proof.getParent());evidence("OWNED_FIXTURE scope="+sandbox.name+" prefix="+prefix+" library="+library);
                KilledInitialParticipantRedisTest.Fixture.copy(result.generated(),proof.getParent().resolve("profile-"+run).resolve("generated"));
                KilledInitialParticipantRedisTest.Fixture.copy(result.classes(),proof.getParent().resolve("profile-"+run).resolve("classes"));
            } catch(Exception|Error failure) {
                GeneratedInitialSqlSandbox.closeAfterFailedConstruction(failure,false,openedSandbox,openedRedis,openedCompiled);
                throw failure;
            }
        }
        JedisPooled client(){return client(new HostAndPort(System.getProperty("cachedb.snapshot.redis.host","redis"),Integer.getInteger("cachedb.snapshot.redis.port",6379)));}
        JedisPooled client(HostAndPort address){var pool=new org.apache.commons.pool2.impl.GenericObjectPoolConfig<redis.clients.jedis.Connection>();pool.setMaxTotal(4);pool.setMaxWait(Duration.ofMillis(1000));return new JedisPooled(address,DefaultJedisClientConfig.builder().connectionTimeoutMillis(2000).socketTimeoutMillis(2000).build(),pool);}
        RedisClientOperations actual(){return new JedisPooledOperations(redis);}
        CacheDatabase database(RedisClientOperations foreground,RedisClientOperations background,CachePolicy global) {
            var config=CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                    .readThrough(ReadThroughConfig.builder().mode(ReadThroughMode.REDIS_ONLY).build()).keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                    .resourceLimits(ResourceLimits.builder().defaultCachePolicy(global).build())
                    .redisGuardrail(RedisGuardrailConfig.builder().enabled(false).build())
                    .redisFunctions(RedisFunctionsConfig.builder().libraryName(library).upsertFunctionName(library+"_upsert").deleteFunctionName(library+"_delete").compactionCompleteFunctionName(library+"_complete").build())
                    .writeBehind(WriteBehindConfig.builder().enabled(false).streamKey(prefix+":write").deadLetterStreamKey(prefix+":dlq").compactionStreamKey(prefix+":compact").build()).build();
            return new CacheDatabase(foreground,background,source,config,CacheRuntimeOptions.builder().keyProvider(EncryptionCompilerFixture.keys("k2",EncryptionCompilerFixture.key("k1",11),EncryptionCompilerFixture.key("k2",17))).build());
        }
        Binding binding(CacheDatabase db,EntityMetadata<Object,Long> meta){return new Binding(meta.entityType(),meta.redisNamespace(),new SourcePlan(meta.tableName(),"tenant_id","id"),RedisInitialSourceLayout.layoutToken(meta,db.redisKeyStrategy(),db.config().queryIndex()),10000,128,1048576,true);}
        JdbcTenantPurgeGuard guard(CacheDatabase db,EntityMetadata<Object,Long> meta){return new JdbcTenantPurgeGuard(source,JdbcTenantPurgeDialect.POSTGRES,2,1,Set.of(binding(db,meta).fingerprint()));}
        CacheInitialNamespace<Object,Long> namespace(CacheDatabase db,EntityMetadata<Object,Long> meta,JdbcTenantPurgeGuard guard){return db.prepareInitialNamespace(meta,binding(db,meta),guard,JdbcSourceOwnerOnboarding.longIds(),1L,3L,2,65536,16384,1048576,2);}
        void rejectedCreation(CachePolicy global,CachePolicy configured,boolean annotation,boolean customFg,boolean customBg)throws Exception {
            var fg=new ProbeAdapter(redis);var bg=new ProbeAdapter(redis);
            try(var admin=CacheTenantContext.administrative();var db=database(customFg?fg:actual(),customBg?bg:actual(),global)) {
                var meta=annotation?nearMetadata:metadata;db.register(meta,annotation?nearCodec:codec,configured);var guard=guard(db,meta);int before=source.calls.get();
                rejected(()->namespace(db,meta,guard));assertEquals(before,source.calls.get());assertEquals(0,fg.calls.get());assertEquals(0,bg.calls.get());unconsumed(db,meta);
            }
        }
        void unconsumed(CacheDatabase db,EntityMetadata<?,?> meta)throws Exception {
            assertEquals(0,count("cachedb_namespace_bootstrap"));assertEquals(0,count("cachedb_initial_bootstrap"));
            assertFalse(redis.exists(RedisTenantRuntimeControl.rosterKey(db.redisKeyStrategy(),meta.redisNamespace())));assertTrue(redis.functionList(library).isEmpty());
            evidence("PASS rejection=UNWIRED_PROTOCOL births=0 phases=0 ownRosterAbsent=true ownLibraryAbsent=true");
        }
        long count(String table)throws SQLException {
            if(!Set.of("cachedb_namespace_bootstrap","cachedb_initial_bootstrap").contains(table))throw new AssertionError("Unsupported fixture table");
            try(var c=source.getConnection();var q=c.prepareStatement("SELECT COUNT(*) FROM "+table)){q.setQueryTimeout(2);try(var r=q.executeQuery()){assertTrue(r.next());return r.getLong(1);}}
        }
        String birth()throws SQLException {
            try(var c=source.getConnection();var q=c.prepareStatement("SELECT roster_token,binding_token,mapping_token,incarnation_id,redis_run_id FROM cachedb_namespace_bootstrap")) {
                q.setQueryTimeout(2);try(var r=q.executeQuery()){assertTrue(r.next());var value=new StringBuilder();for(int i=1;i<=5;i++)value.append(r.getString(i)).append(':');assertFalse(r.next());return value.toString();}
            }
        }
        void evidence(String value)throws java.io.IOException{Files.writeString(proof,value+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);System.out.println("INITIAL_PROFILE "+value);}
        public void close()throws Exception{GeneratedInitialSqlSandbox.closeFixture(false,sandbox,redis,compiled);}
    }
}
