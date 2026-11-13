package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.crypto.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.redis.*;
import com.reactor.cachedb.starter.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;

/** Real root/SQL/Redis path. Owned SQL is disposed after each run; raw proof files stay cluster-private. */
@EnabledIfSystemProperty(named="cachedb.snapshot.providers",matches="true")
@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
@EnabledIfSystemProperty(named="cachedb.initial.isolatedSql",matches="true")
@Timeout(120)
class CacheInitialNativeBridgeProvidersRedisTest {
    private static final String NS="native_rows", ENTITY="NativeInitialRow";
    static final String SECRET="private-initial-fixture-sensitive-value";
    record Row(Long id,String tenant,String secret) {}

    static String nativeProvider() {
        String provider=System.getProperty("cachedb.initial.native.provider","postgres");
        return switch(provider) {
            case "postgres","mssql","oracle" -> provider;
            default -> throw new IllegalArgumentException("cachedb.initial.native.provider must be postgres, mssql or oracle");
        };
    }

    @Test void encryptedFixtureQueueEnvelopeHonorsProtectedWriteContract() {
        var codec=new Codec(keys(true));var metadata=metadata();
        var row=new Row(17L,"a",SECRET);var queued=codec.toQueuedColumns(row);
        assertDoesNotThrow(()->EncryptedStorageChecks.validateQueuedColumns(metadata,queued));
        assertEquals(Set.of("id","tenant_id","secret",EncryptedCodecSupport.QUEUE_FORMAT,
                EncryptedCodecSupport.QUEUE_TENANT),queued.keySet());
        assertEquals(EncryptedCodecSupport.QUEUE_VERSION,queued.get(EncryptedCodecSupport.QUEUE_FORMAT));
        assertEquals(row.tenant(),EncryptedCodecSupport.queuedTenant((String)queued.get(EncryptedCodecSupport.QUEUE_TENANT)));
        var ciphertext=assertInstanceOf(String.class,queued.get("secret"));
        assertTrue(ciphertext.startsWith("~E1:k2:"));assertFalse(ciphertext.contains(SECRET));
        assertTrue(row.secret().equals(codec.fromColumns(queued).secret()),"Queue ciphertext must retain the source codec round trip");
        assertEquals(Set.of("id","tenant_id","secret"),codec.toSourceColumns(row).keySet());

        var wireColumns=new LinkedHashMap<String,String>();
        queued.forEach((column,value)->wireColumns.put(column,String.valueOf(value)));
        var originalWireColumns=Map.copyOf(wireColumns);
        var source=codec.sourceColumnsFromQueued("17",wireColumns);
        assertEquals(Set.of("id","tenant_id","secret"),source.keySet());
        assertTrue(ciphertext.equals(source.get("secret")),"SQL must retain the authenticated queue ciphertext");
        assertEquals(row,codec.fromColumns(new LinkedHashMap<String,Object>(source)));
        assertEquals(originalWireColumns,wireColumns,"SQL conversion must not mutate the retained queue envelope");
        var partial=new LinkedHashMap<>(wireColumns);partial.remove("id");partial.remove("tenant_id");
        assertEquals(Map.of("secret",ciphertext),codec.sourceColumnsFromQueued("17",partial));
        var versioned=new LinkedHashMap<>(wireColumns);versioned.put("entity_version","19");
        assertEquals("19",codec.sourceColumnsFromQueued("17",versioned).get("entity_version"));
        for(String malformedId:List.of("017","not-an-id"))
            assertEquals(CacheEncryptionException.Code.INVALID_TEXT,assertThrows(CacheEncryptionException.class,
                    ()->codec.sourceColumnsFromQueued(malformedId,wireColumns)).code());
        var wrongRow=new LinkedHashMap<>(wireColumns);wrongRow.put("id","18");
        assertThrows(CacheEncryptionException.class,()->codec.sourceColumnsFromQueued("17",wrongRow));
        assertThrows(CacheEncryptionException.class,()->codec.sourceColumnsFromQueued("18",wrongRow),
                "Ciphertext must authenticate against the original row ID");
        var wrongTenant=new LinkedHashMap<>(wireColumns);wrongTenant.put(EncryptedCodecSupport.QUEUE_TENANT,"~T1:b");
        assertThrows(CacheEncryptionException.class,()->codec.sourceColumnsFromQueued("17",wrongTenant));
        var plaintext=new LinkedHashMap<>(wireColumns);plaintext.put("secret",SECRET);
        assertThrows(CacheEncryptionException.class,()->codec.sourceColumnsFromQueued("17",plaintext));
        var unknown=new LinkedHashMap<>(wireColumns);unknown.put("unexpected_column","x");
        assertThrows(CacheEncryptionException.class,()->codec.sourceColumnsFromQueued("17",unknown));
        var invalidVersion=new LinkedHashMap<>(wireColumns);invalidVersion.put("entity_version","0");
        assertThrows(CacheEncryptionException.class,()->codec.sourceColumnsFromQueued("17",invalidVersion));
        assertThrows(CacheEncryptionException.class,()->codec.sourceColumnsFromQueued("17",Map.of(
                EncryptedCodecSupport.QUEUE_FORMAT,EncryptedCodecSupport.QUEUE_VERSION,
                EncryptedCodecSupport.QUEUE_TENANT,"~T1:a")));

        var missingFormat=new LinkedHashMap<>(queued);missingFormat.remove(EncryptedCodecSupport.QUEUE_FORMAT);
        assertEquals(CacheEncryptionException.Code.INVALID_TEXT,assertThrows(CacheEncryptionException.class,
                ()->EncryptedStorageChecks.validateQueuedColumns(metadata,missingFormat)).code());
        var missingTenant=new LinkedHashMap<>(queued);missingTenant.remove(EncryptedCodecSupport.QUEUE_TENANT);
        assertEquals(CacheEncryptionException.Code.INVALID_TEXT,assertThrows(CacheEncryptionException.class,
                ()->EncryptedStorageChecks.validateQueuedColumns(metadata,missingTenant)).code());
        var invalidTenant=new LinkedHashMap<>(queued);invalidTenant.put(EncryptedCodecSupport.QUEUE_TENANT,row.tenant());
        assertEquals(CacheEncryptionException.Code.INVALID_CONTEXT,assertThrows(CacheEncryptionException.class,
                ()->EncryptedStorageChecks.validateQueuedColumns(metadata,invalidTenant)).code());
    }

    @Test void nonemptyEncryptedSourceReachesOpenThroughTheActualRootNamespace() throws Exception {
        String provider=nativeProvider();
        try(var admin=CacheTenantContext.administrative();var f=new Fixture(provider)) {
            var namespace=f.database.prepareInitialNamespace(f.metadata,f.binding,f.guard,f.ids,
                    1L,2L,2,65536,16384,1048576,1);
            var descriptor=namespace.descriptor();
            var producer=namespace.bootstrap(new JdbcNamespaceBootstrapGate(f.guard,
                    Set.of(descriptor.namespaceFingerprint())),f.generation);
            assertEquals(descriptor.incarnationId(),producer.runtimeId());
            var enrollment=f.database.prepareTenantProtection(1);
            var local=enrollment.descriptor(ENTITY);
            assertEquals(descriptor.incarnationId(),local.runtimeId());
            var allocator=new JdbcTenantVersionAllocator(f.guard,f.sandbox.dialect,2);
            var manifest=new PurgeParticipantManifest(ENTITY,
                    Set.of("runtime-control-roster","initial-native-window","initial-source-journal"),
                    List.of(enrollment.controlParticipant(ENTITY),enrollment.initialSourceParticipant(f.metadata,namespace.window()),
                            new JdbcInitialBootstrapPurgeParticipant(f.guard,f.binding,ENTITY,2)));
            var routes=new LinkedHashMap<String,BoundedRedisWire>();
            var write=f.database.config().writeBehind();
            routes.put(write.streamKey(),f.wire);routes.put(write.deadLetterStreamKey(),f.wire);
            routes.put(write.compactionStreamKey(),f.wire);
            for(String stream:write.activeStreamKeys())routes.put(stream,f.wire);
            var plan=new RedisTenantPurgePlan(f.binding,ENTITY,"entity_version",f.guard,allocator,
                    new JdbcPurgeScanJournal(f.guard,f.sandbox.dialect,2,128,10000,10*1048576),
                    new JdbcPurgeFacadeJournal(f.guard,f.sandbox.dialect,2,256,10*1048576),
                    new JdbcPurgeParticipantJournal(f.guard,2,1),f.wire,f.database.redisOperations(),
                    f.database.redisKeyStrategy(),enrollment.barrier(ENTITY),enrollment.readFence(ENTITY),
                    List.of(enrollment.pointProfileTarget(ENTITY)),Set.of(NS),Map.of(),routes,manifest,event->{});
            var approval=new RedisTenantRuntimeControl.ApprovedRoster(ProtectionKey.hash("review:"+f.run),
                    plan.manifestToken(),local.coverageToken(),List.of(local.runtimeId()),List.of(f.generation.key()));
            var service=namespace.createBootstrap(enrollment,plan,approval,64,Set.of(namespace.window().fingerprint()));
            var request=new InitialTenantBootstrap.Request(f.generation,"a",UUID.randomUUID(),
                    ProtectionKey.hash("native-provider-operator:"+f.run),120);
            var phases=new EnumMap<InitialTenantBootstrap.Phase,Integer>(InitialTenantBootstrap.Phase.class);
            InitialTenantBootstrap.Progress progress=null;
            for(int call=0;call<512;call++) {
                progress=service.step(request);phases.merge(progress.phase(),1,Integer::sum);
                f.evidence("PROGRESS provider="+provider+" call="+call+" phase="+progress.phase()
                        +" source="+progress.sourceRows()+" warmed="+progress.warmedRows()+" high="+progress.highwater());
                assertNotEquals(InitialTenantBootstrap.Phase.INCOMPLETE,progress.phase());
                if(progress.phase()==InitialTenantBootstrap.Phase.ACTIVE)break;
            }
            assertNotNull(progress);assertEquals(InitialTenantBootstrap.Phase.ACTIVE,progress.phase());
            assertEquals(2,progress.warmedRows());assertTrue(progress.highwater()>=9000);
            assertTrue(phases.containsKey(InitialTenantBootstrap.Phase.HOT_WINDOW));
            assertTrue(phases.containsKey(InitialTenantBootstrap.Phase.VERIFY_WINDOW));
            var keys=f.database.redisKeyStrategy();
            assertEquals("OPEN",f.redis.hget(enrollment.barrier(ENTITY).generationKey(f.generation.key()),"state"));
            assertEquals(f.generation,enrollment.barrier(ENTITY).captureOpen(f.generation.key()));
            try(var c=f.source.getConnection()) {
                c.setAutoCommit(false);f.guard.authorizeWrite(c,f.generation);c.rollback();
                try(var q=c.prepareStatement("SELECT incarnation_id,mapping_token FROM cachedb_namespace_bootstrap WHERE roster_token=?")) {
                    q.setQueryTimeout(2);q.setString(1,descriptor.rosterKeyToken());
                    try(var r=q.executeQuery()) {
                        assertTrue(r.next());assertEquals(descriptor.incarnationId().toString(),r.getString(1));
                        assertEquals(namespace.window().fingerprint(),r.getString(2));assertFalse(r.next());
                    }
                }
            }
            for(long id:List.of(1L,2L)) {
                String raw=f.redis.get(keys.entityKey(NS,id));assertNotNull(raw);
                assertFalse(raw.contains(SECRET));assertTrue(raw.contains("~E1:k2:"));
                assertEquals(id==1?"7":"8",f.redis.get(keys.versionKey(NS,id)));
                assertEquals("a",f.redis.get(keys.versionKey(NS,id)+":tenant"));
                var index=f.redis.hgetAll(keys.indexMetaKey(NS,id));
                assertFalse(index.toString().contains(SECRET));
                assertTrue(index.toString().contains(f.bound.indexQueryValue("secret",SECRET+id).toString()));
            }
            assertFalse(f.redis.exists(keys.entityKey(NS,99L)));assertFalse(f.redis.exists(keys.entityKey(NS,3L)));
            for(String stream:routes.keySet())assertEquals(0,f.redis.xlen(stream));
            assertEquals(f.originalSource,f.sourceSnapshot(),"Source storage must remain byte-for-byte unchanged");
            int before=f.source.calls.get();var deliveries=new AtomicInteger();
            for(long id:List.of(1L,2L))enrollment.deliver(f.metadata,"a",repo->repo.findById(id),result->{
                assertTrue(result.isPresent());
                assertTrue(new Row(id,"a",SECRET+id).equals(result.orElseThrow()),"Logical row mismatch");
                deliveries.incrementAndGet();
            });
            assertEquals(2,deliveries.get());assertEquals(before,f.source.calls.get(),"Point delivery must not borrow SQL");
            f.evidence("PASS provider="+provider+" actualRoots=1 warmed=2 coldExcluded=1 foreignExcluded=1"
                    +" encryptedPayload=true blindIndex=true sourceUnchanged=true foregroundSqlBorrows=0"
                    +" originalIncarnation="+local.runtimeId()+" sqlAuthorizedOpen=true nativeOpen=true");
        }
    }

    @Test void twoActualRootsAcknowledgeOneInitialLeaseWithAHalfSecondPeerLoop() throws Exception {
        try(var admin=CacheTenantContext.administrative();var f=new Fixture(nativeProvider());
                var peerRedis=new JedisPooled(System.getProperty("cachedb.snapshot.redis.host","redis"),
                        Integer.getInteger("cachedb.snapshot.redis.port",6379));
                var peer=new CacheDatabase(peerRedis,f.source,f.database.config(),f.database.runtimeOptions())) {
            assertNotSame(f.redis,peerRedis);
            assertSame(f.redis,f.database.redisOperations().commands());assertSame(peerRedis,peer.redisOperations().commands());
            peer.start();peer.register(f.metadata,new Codec(null),new CachePolicy(16,16,false,120,120));
            var namespace=f.database.prepareInitialNamespace(f.metadata,f.binding,f.guard,f.ids,
                    1L,2L,2,65536,16384,1048576,1);
            namespace.bootstrap(new JdbcNamespaceBootstrapGate(f.guard,
                    Set.of(namespace.descriptor().namespaceFingerprint())),f.generation);
            peer.configureTenantProducer(f.metadata,1);
            var primary=f.database.prepareTenantProtection(1);var secondary=peer.prepareTenantProtection(1);
            @SuppressWarnings("unchecked") var peerCodec=(EntityCodec<Row>)peer.entityRegistry().find(ENTITY).orElseThrow().codec();
            var peerWindow=new InitialSourceHotWindow<>(f.binding,f.metadata,peerCodec,f.ids,f.ids.formatToken(),
                    1L,2L,2,65536,16384,1048576,InitialSourceHotWindow.Profile.POINT_NO_QUOTA);
            var primaryPlan=controlPlan(f,f.database,primary,namespace.window());
            var peerPlan=controlPlan(f,peer,secondary,peerWindow);
            assertEquals(primaryPlan.manifestToken(),peerPlan.manifestToken());
            var first=primary.descriptor(ENTITY);var second=secondary.descriptor(ENTITY);
            assertNotEquals(first.runtimeId(),second.runtimeId());assertEquals(first.coverageToken(),second.coverageToken());
            var approval=new RedisTenantRuntimeControl.ApprovedRoster(ProtectionKey.hash("peer-review:"+f.run),
                    primaryPlan.manifestToken(),first.coverageToken(),List.of(first.runtimeId(),second.runtimeId()),List.of(f.generation.key()));
            var service=namespace.createBootstrap(primary,primaryPlan,approval,64,Set.of(namespace.window().fingerprint()));
            var request=new InitialTenantBootstrap.Request(f.generation,"a",UUID.randomUUID(),ProtectionKey.hash("peer-operator:"+f.run),120);
            try(var worker=java.util.concurrent.Executors.newSingleThreadExecutor()) {
                var result=worker.submit(() -> {
                    try(var peerAdmin=CacheTenantContext.administrative()) {
                        long stop=System.nanoTime()+5_000_000_000L;
                        Optional<RedisTenantRuntimeControl.ClosedPair> pending=Optional.empty();
                        for(int probe=0;probe<500 && pending.isEmpty() && System.nanoTime()<stop;probe++) {
                            pending=namespace.pendingClosure();if(pending.isEmpty())Thread.sleep(10);
                        }
                        assertTrue(pending.isPresent(),"Actual SQL INITIAL lease was not published");
                        // Existing bounded control loop: first poll offset400ms, then period500ms.
                        Thread.sleep(400);
                        secondary.sealClosed(peerPlan,approval,List.of(pending.orElseThrow()));
                        for(int round=0;round<10 && System.nanoTime()<stop;round++) {
                            var poll=secondary.pollControl(1,1000);assertEquals(0,poll.incomplete());
                            if(poll.locallyAcknowledged()==1)return poll;
                            Thread.sleep(500);
                        }
                        throw new AssertionError("Peer control budget exhausted without actual ACK");
                    }
                });
                var progress=service.step(request);
                assertEquals(InitialTenantBootstrap.Phase.NATIVE_INVENTORY,progress.phase());
                assertEquals(1,result.get(5,java.util.concurrent.TimeUnit.SECONDS).locallyAcknowledged());
            }
            assertEquals("2",f.redis.hget(primary.controlStoreKey(ENTITY),"enrolled"));
            assertEquals("2",f.redis.hget(primary.controlStoreKey(ENTITY),"required"));
            assertEquals("CLOSED",f.redis.hget(primary.barrier(ENTITY).generationKey(f.generation.key()),"state"));
            assertFalse(f.redis.exists(f.database.redisKeyStrategy().entityKey(NS,1L)));
            f.evidence("PASS actualRoots=2 separateRedisClients=2 sameInitialLease=true peerPeriodMillis=500 peerOffsetMillis=400 realAckCount=2"
                    +" phase=NATIVE_INVENTORY nativeState=CLOSED warmed=0 noFakeClosure=true");
        }
    }

    @Test void stoppingAnEnrolledActualRootDoesNotRetireItOrManufactureAnAcknowledgement() throws Exception {
        try(var admin=CacheTenantContext.administrative();var f=new Fixture(nativeProvider());
                var peer=new CacheDatabase(f.redis,f.source,f.database.config(),f.database.runtimeOptions())) {
            peer.start();peer.register(f.metadata,new Codec(null),new CachePolicy(16,16,false,120,120));
            var namespace=f.database.prepareInitialNamespace(f.metadata,f.binding,f.guard,f.ids,
                    1L,2L,2,65536,16384,1048576,1);
            namespace.bootstrap(new JdbcNamespaceBootstrapGate(f.guard,
                    Set.of(namespace.descriptor().namespaceFingerprint())),f.generation);
            peer.configureTenantProducer(f.metadata,1);
            var secondary=peer.prepareTenantProtection(1);var stopped=secondary.descriptor(ENTITY).runtimeId();
            peer.close();
            var primary=f.database.prepareTenantProtection(1);var local=primary.descriptor(ENTITY);
            var plan=controlPlan(f,f.database,primary,namespace.window());
            var approval=new RedisTenantRuntimeControl.ApprovedRoster(ProtectionKey.hash("missing-peer-review:"+f.run),
                    plan.manifestToken(),local.coverageToken(),List.of(local.runtimeId(),stopped),List.of(f.generation.key()));
            var service=namespace.createBootstrap(primary,plan,approval,64,Set.of(namespace.window().fingerprint()));
            var request=new InitialTenantBootstrap.Request(f.generation,"a",UUID.randomUUID(),ProtectionKey.hash("missing-peer:"+f.run),120);
            var failure=assertThrows(ProtectionException.class,()->service.step(request));
            assertEquals(ProtectionException.Code.BUSY,failure.code());assertNull(failure.getCause());
            assertFalse(failure.toString().contains(SECRET));assertEquals(0,failure.getSuppressed().length);
            assertEquals("2",f.redis.hget(primary.controlStoreKey(ENTITY),"enrolled"));
            assertEquals("2",f.redis.hget(primary.controlStoreKey(ENTITY),"required"));
            assertEquals("1",f.redis.hget(primary.controlStoreKey(ENTITY),"e:"+stopped));
            assertEquals("CLOSED",f.redis.hget(primary.barrier(ENTITY).generationKey(f.generation.key()),"state"));
            assertFalse(f.redis.exists(f.database.redisKeyStrategy().entityKey(NS,1L)));
            assertFalse(f.redis.exists(f.database.redisKeyStrategy().versionKey(NS,1L)+":tenant"));
            assertEquals(f.originalSource,f.sourceSnapshot(),"Failed closure must not alter the source");
            f.evidence("PASS actualRoots=2 stoppedRootRetained=true missingAckRejected=true result=BUSY"
                    +" nativeState=CLOSED warmed=0 sourceUnchanged=true gracefulCloseIsNotRetirement=true");
        }
    }

    static RedisTenantPurgePlan controlPlan(Fixture f,CacheDatabase root,CacheTenantRuntimeEnrollment enrollment,
            InitialSourceHotWindow<Row,Long> window) {
        var participants=new PurgeParticipantManifest(ENTITY,Set.of("runtime-control-roster","initial-native-window","initial-source-journal"),
                List.of(enrollment.controlParticipant(ENTITY),enrollment.initialSourceParticipant(f.metadata,window),
                        new JdbcInitialBootstrapPurgeParticipant(f.guard,f.binding,ENTITY,2)));
        var routes=new LinkedHashMap<String,BoundedRedisWire>();var write=root.config().writeBehind();
        routes.put(write.streamKey(),f.wire);routes.put(write.deadLetterStreamKey(),f.wire);routes.put(write.compactionStreamKey(),f.wire);
        for(String stream:write.activeStreamKeys())routes.put(stream,f.wire);
        return new RedisTenantPurgePlan(f.binding,ENTITY,"entity_version",f.guard,new JdbcTenantVersionAllocator(f.guard,f.sandbox.dialect,2),
                new JdbcPurgeScanJournal(f.guard,f.sandbox.dialect,2,128,10000,10*1048576),
                new JdbcPurgeFacadeJournal(f.guard,f.sandbox.dialect,2,256,10*1048576),new JdbcPurgeParticipantJournal(f.guard,2,1),
                f.wire,root.redisOperations(),root.redisKeyStrategy(),enrollment.barrier(ENTITY),enrollment.readFence(ENTITY),
                List.of(enrollment.pointProfileTarget(ENTITY)),Set.of(NS),Map.of(),routes,participants,event->{});
    }

    static final class Fixture implements AutoCloseable {
        final String run=UUID.randomUUID().toString().replace("-","");
        final String prefix="native_initial_"+run,library=prefix+"_functions";
        final InitialSqlSandbox sandbox;
        final CountingSource source;
        final InitialSqlFaults faults;
        final NativeCommandCounts trace;
        final JedisPooled redis;
        final BoundedRedisWire wire;
        final CacheDatabase database;
        final EntityMetadata<Row,Long> metadata=metadata();
        final Binding binding;
        final JdbcTenantPurgeGuard guard;
        final Generation generation;
        final JdbcSourceOwnerOnboarding.JdbcIdCodec<Long> ids=JdbcSourceOwnerOnboarding.longIds();
        final EntityCodec<Row> bound;
        final Map<Long,String> originalSource;
        final Path proof;
        Fixture(String provider)throws Exception {this(provider,false);}
        Fixture(String provider,boolean instrumented)throws Exception {this(provider,instrumented,false);}
        Fixture(String provider,boolean instrumented,boolean sqlOnlyFaults)throws Exception {
            InitialSqlSandbox openedSandbox=null;
            JedisPooled openedRedis=null;
            BoundedRedisWire openedWire=null;
            CacheDatabase openedDatabase=null;
            try {
                sandbox=openedSandbox=new InitialSqlSandbox(provider);
                proof=Path.of("target","native-initial-proofs",provider+"-"+run+".txt");
                Files.createDirectories(proof.getParent());
                evidence("OWNED_FIXTURE provider="+provider+" scope="+sandbox.name+" redisPrefix="+prefix+" library="+library+" run="+run);
                faults=instrumented||sqlOnlyFaults?new InitialSqlFaults(sandbox.source,sandbox.dialect):null;
                source=new CountingSource(faults==null?sandbox.source:faults);
                String host=System.getProperty("cachedb.snapshot.redis.host","redis");
                int port=Integer.getInteger("cachedb.snapshot.redis.port",6379);
                trace=instrumented?new NativeCommandCounts():null;
                redis=openedRedis=trace==null?new JedisPooled(host,port):trace.client(host,port);
                redis.getPool().setMaxTotal(4);
                wire=openedWire=new BoundedRedisWire(new InetSocketAddress(host,port),null,null,null,null,0,2000,2000,1);
                var config=CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                        .readThrough(ReadThroughConfig.builder().mode(ReadThroughMode.REDIS_ONLY).build())
                        .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                        .redisGuardrail(RedisGuardrailConfig.builder().enabled(false).build())
                        .redisFunctions(RedisFunctionsConfig.builder().libraryName(library).upsertFunctionName(library+"_upsert")
                                .deleteFunctionName(library+"_delete").compactionCompleteFunctionName(library+"_complete").build())
                        .writeBehind(WriteBehindConfig.builder().enabled(false).streamKey(prefix+":write")
                                .deadLetterStreamKey(prefix+":dlq").compactionStreamKey(prefix+":compact").build()).build();
                var options=CacheRuntimeOptions.builder().keyProvider(keys(true)).build();
                database=openedDatabase=new CacheDatabase(redis,source,config,options);
                assertSame(redis,assertInstanceOf(JedisPooledOperations.class,database.redisOperations()).pooledClient());
                assertSame(redis,assertInstanceOf(JedisPooledOperations.class,database.backgroundRedisOperations()).pooledClient());
                database.start();database.register(metadata,new Codec(null),new CachePolicy(16,16,false,120,120));
                @SuppressWarnings("unchecked") var registered=(EntityCodec<Row>)database.entityRegistry().find(ENTITY).orElseThrow().codec();
                bound=registered;
                binding=new Binding(Row.class,NS,new SourcePlan(NS,"tenant_id","id"),
                        RedisInitialSourceLayout.layoutToken(metadata,database.redisKeyStrategy(),config.queryIndex()),10000,128,1048576,true);
                guard=new JdbcTenantPurgeGuard(source,sandbox.dialect,2,1,Set.of(binding.fingerprint()));
                generation=new Generation(binding.key("a"),1);
                try(var c=source.getConnection();var q=c.createStatement()) {
                    q.setQueryTimeout(5);
                    q.executeUpdate("CREATE TABLE native_fixture_owner(run_token VARCHAR(32) NOT NULL PRIMARY KEY)");
                    q.executeUpdate("INSERT INTO native_fixture_owner(run_token) VALUES ('"+run+"')");
                    q.executeUpdate("CREATE TABLE native_rows(id DECIMAL(19,0) NOT NULL PRIMARY KEY,tenant_id "
                            +(provider.equals("mssql")?"NVARCHAR(64)":"VARCHAR(64)")+" NOT NULL,secret VARCHAR(1024),entity_version DECIMAL(19,0) NOT NULL)");
                    q.executeUpdate("CREATE INDEX native_tenant_idx ON native_rows(tenant_id,id)");
                    q.executeUpdate("CREATE INDEX native_version_idx ON native_rows(tenant_id,entity_version)");
                }
                var old=new Codec(keys(false));
                try(var c=source.getConnection();var q=c.prepareStatement("INSERT INTO native_rows(id,tenant_id,secret,entity_version) VALUES (?,?,?,?)")) {
                    q.setQueryTimeout(2);
                    for(long id:List.of(1L,2L,99L,3L)) {
                        var row=new Row(id,id==3?"b":"a",SECRET+id);
                        q.setLong(1,id);q.setString(2,row.tenant());q.setString(3,old.toSourceColumns(row).get("secret").toString());
                        q.setLong(4,id==1?7:id==2?8:id==99?9000:18000);q.addBatch();
                    }
                    assertEquals(4,q.executeBatch().length);
                }
                originalSource=sourceSnapshot();guard.provisionClosed(generation);
                for(long id:List.of(1L,2L,99L,3L))assertFalse(redis.exists(database.redisKeyStrategy().entityKey(NS,id)));
            }catch(Exception|Error failure){
                try{closeOwnedResources(openedDatabase,openedWire,openedRedis,openedSandbox);}
                catch(Exception|Error cleanup){if(failure!=cleanup)failure.addSuppressed(cleanup);}
                throw failure;
            }
        }
        Map<Long,String> sourceSnapshot()throws SQLException {
            var values=new LinkedHashMap<Long,String>();
            try(var c=source.getConnection();var q=c.prepareStatement("SELECT id,tenant_id,secret,entity_version FROM native_rows ORDER BY id")) {
                q.setQueryTimeout(2);try(var r=q.executeQuery()) {
                    while(r.next())values.put(r.getLong(1),r.getString(2)+"|"+r.getString(3)+"|"+r.getBigDecimal(4).toPlainString());
                }
            }
            return Map.copyOf(values);
        }
        void evidence(String value)throws java.io.IOException {
            Files.writeString(proof,value+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);System.out.println("NATIVE_INITIAL "+value);
        }
        public void close()throws Exception {
            // Raw proof files stay cluster-private; runtime closure must not retain the owned SQL scope.
            closeOwnedResources(database,wire,redis,sandbox);
        }
        static void closeOwnedResources(AutoCloseable database,AutoCloseable wire,
                AutoCloseable redis,AutoCloseable sandbox)throws Exception {
            Throwable failure=null;
            for(var resource:new AutoCloseable[]{database,wire,redis,sandbox}) {
                if(resource==null)continue;
                try{resource.close();}
                catch(Exception|Error cleanup){
                    if(failure==null)failure=cleanup;
                    else if(failure!=cleanup)failure.addSuppressed(cleanup);
                }
            }
            if(failure instanceof Exception checked)throw checked;
            if(failure instanceof Error fatal)throw fatal;
        }
    }

    private static EntityMetadata<Row,Long> metadata() {
        return new EntityMetadata<>() {
            public String entityName(){return ENTITY;}public String tableName(){return NS;}public String redisNamespace(){return NS;}
            public String idColumn(){return "id";}public Class<Row> entityType(){return Row.class;}public Function<Row,Long> idAccessor(){return Row::id;}
            public String tenantFieldName(){return "tenant_id";}public String extractTenantId(Row row){return row.tenant();}
            public boolean tenantPurgeEnabled(){return true;}public List<String> columns(){return List.of("id","tenant_id","secret");}
            public Set<String> indexedColumns(){return Set.of("tenant_id","secret");}
            public Map<String,String> columnTypes(){return Map.of("id","java.lang.Long","tenant_id","java.lang.String","secret","java.lang.String");}
            public Map<String,EncryptedColumnDescriptor> encryptedColumns(){return Map.of("secret",
                    new EncryptedColumnDescriptor("initial",EncryptedColumnDescriptor.Scope.REDIS_AND_SOURCE,true));}
            public List<RelationDefinition> relations(){return List.of();}
        };
    }
    private static CacheKeyProvider keys(boolean current) {
        byte[] aes1=new byte[32],aes2=new byte[32],blind=new byte[32];
        Arrays.fill(aes1,(byte)11);Arrays.fill(aes2,(byte)17);Arrays.fill(blind,(byte)23);
        return new StaticKeyProvider(Map.of("initial",List.of(new KeyMaterial("k1",aes1,blind),new KeyMaterial("k2",aes2,blind))),
                Map.of("initial",current?"k2":"k1"));
    }
    static final class Codec implements EntityCodec<Row> {
        final JcaFieldEncryption crypto;
        Codec(CacheKeyProvider keys){crypto=keys==null?null:new JcaFieldEncryption(keys);}
        public EntityCodec<Row> bindEncryption(CacheKeyProvider keys){return new Codec(keys);}
        public boolean encryptedMapping(){return true;}public boolean encryptionBound(){return crypto!=null;}
        public String toRedisValue(Row row) {
            var fields=new LinkedHashMap<String,String>();fields.put("id",row.id().toString());fields.put("tenant_id",row.tenant());
            fields.put("secret",crypto.encrypt("initial",NS,row.id().toString(),"secret",row.secret()));return LengthPrefixedPayloadCodec.encode(fields);
        }
        public Row fromRedisValue(String raw) {
            var fields=LengthPrefixedPayloadCodec.decode(raw);String id=fields.get("id");
            return new Row(Long.valueOf(id),fields.get("tenant_id"),crypto.decrypt("initial",NS,id,"secret",fields.get("secret")));
        }
        public Map<String,Object> toColumns(Row row){return Map.of("id",row.id(),"tenant_id",row.tenant(),"secret",row.secret());}
        public Map<String,Object> toSourceColumns(Row row) {
            var values=new LinkedHashMap<>(toColumns(row));values.put("secret",crypto.encrypt("initial",NS,row.id().toString(),"secret",row.secret()));return values;
        }
        public Map<String,Object> toQueuedColumns(Row row) {
            var values=new LinkedHashMap<>(toSourceColumns(row));
            values.put(EncryptedCodecSupport.QUEUE_FORMAT,EncryptedCodecSupport.QUEUE_VERSION);
            values.put(EncryptedCodecSupport.QUEUE_TENANT,"~T1:"+row.tenant());
            return values;
        }
        public Map<String,String> sourceColumnsFromQueued(String id,Map<String,String> queued) {
            if(crypto==null)throw EncryptedCodecSupport.unbound();
            try {
                if(queued.size()>130 || !EncryptedCodecSupport.QUEUE_VERSION.equals(queued.get(EncryptedCodecSupport.QUEUE_FORMAT))
                        || !Long.toString(Long.parseLong(id)).equals(id))throw EncryptedCodecSupport.invalidValue();
                EncryptedCodecSupport.rowId(id);
                String tenant=EncryptedCodecSupport.queuedTenant(queued.get(EncryptedCodecSupport.QUEUE_TENANT));
                if(queued.containsKey("id") && !id.equals(queued.get("id"))
                        || queued.containsKey("tenant_id") && !tenant.equals(queued.get("tenant_id")))
                    throw EncryptedCodecSupport.invalidValue();
                var source=new LinkedHashMap<String,String>();
                for(var entry:queued.entrySet()) {
                    String column=entry.getKey(),raw=entry.getValue();
                    if(column.equals(EncryptedCodecSupport.QUEUE_FORMAT) || column.equals(EncryptedCodecSupport.QUEUE_TENANT))continue;
                    switch(column) {
                        case "id","tenant_id" -> { }
                        case "secret" -> crypto.decrypt("initial",NS,id,"secret",raw);
                        case "entity_version" -> {
                            if(raw==null || !Long.toString(Long.parseLong(raw)).equals(raw) || Long.parseLong(raw)<1)
                                throw EncryptedCodecSupport.invalidValue();
                        }
                        default -> throw EncryptedCodecSupport.invalidValue();
                    }
                    // REDIS_AND_SOURCE authenticates the original ciphertext without re-encryption.
                    source.put(column,raw);
                }
                if(source.isEmpty())throw EncryptedCodecSupport.invalidValue();
                return source;
            } catch(CacheEncryptionException failure) { throw new CacheEncryptionException(failure.code()); }
            catch(RuntimeException failure) { throw EncryptedCodecSupport.invalidValue(); }
        }
        public Row fromColumns(Map<String,Object> values) {
            Long id=new java.math.BigDecimal(columnValue(values,"id").toString()).longValueExact();
            return new Row(id,(String)columnValue(values,"tenant_id"),crypto.decrypt("initial",NS,id.toString(),"secret",(String)columnValue(values,"secret")));
        }
        public Map<String,Object> toIndexColumns(Row row) {
            var values=new LinkedHashMap<>(toColumns(row));values.put("secret",indexQueryValue("secret",row.secret()));return values;
        }
        public Object indexQueryValue(String column,Object value){return column.equals("secret")
                ?crypto.blindIndex("initial",NS,"secret",(String)value):value;}
        public String encryptionIntentFingerprint(List<String> parts){return crypto.intentMac("initial",NS,"atomic-intent",parts);}
        public boolean directColumnAccess(){return true;}
        public Object entityColumnValue(Row row,String column){return switch(column){case "id"->row.id();case "tenant_id"->row.tenant();case "secret"->row.secret();default->null;};}
    }
    /** Physical EVAL dispatch counters only; the root still owns the final canonical Jedis adapter. */
    static final class NativeCommandCounts {
        final AtomicInteger closes=new AtomicInteger(),installs=new AtomicInteger(),births=new AtomicInteger();
        private void submitted(redis.clients.jedis.CommandArguments arguments) {
            if(!Arrays.equals(arguments.getCommand().getRaw(),redis.clients.jedis.Protocol.Command.EVAL.getRaw()))return;
            var iterator=arguments.iterator();assertTrue(iterator.hasNext());iterator.next();assertTrue(iterator.hasNext());
            byte[] bytes=iterator.next().getRaw();
            assertTrue(bytes.length<=1048576,"Fixture command observation must remain bounded");
            String script=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
            if(script.contains("local state='CLOSED'"))closes.incrementAndGet();
            if(script.contains("redis.call('SET',KEYS[3],ARGV[4])"))installs.incrementAndGet();
            if(script.contains("'schema','2','state','COLLECTING'"))births.incrementAndGet();
        }
        JedisPooled client(String host,int port) {
            var endpoint=new redis.clients.jedis.HostAndPort(host,port);
            var clientConfig=redis.clients.jedis.DefaultJedisClientConfig.builder()
                    .connectionTimeoutMillis(2000).socketTimeoutMillis(2000).build();
            var pool=new org.apache.commons.pool2.impl.GenericObjectPoolConfig<redis.clients.jedis.Connection>();
            pool.setMaxTotal(4);pool.setMaxIdle(4);pool.setJmxEnabled(false);pool.setFairness(true);
            pool.setMaxWait(java.time.Duration.ofSeconds(2));
            return new JedisPooled(new org.apache.commons.pool2.BasePooledObjectFactory<redis.clients.jedis.Connection>() {
                public redis.clients.jedis.Connection create(){return new CountedConnection(endpoint,clientConfig,NativeCommandCounts.this);}
                public org.apache.commons.pool2.PooledObject<redis.clients.jedis.Connection> wrap(redis.clients.jedis.Connection value){
                    return new org.apache.commons.pool2.impl.DefaultPooledObject<>(value);
                }
                @Override public void destroyObject(org.apache.commons.pool2.PooledObject<redis.clients.jedis.Connection> value){
                    value.getObject().setHandlingPool(null);value.getObject().close();
                }
                @Override public boolean validateObject(org.apache.commons.pool2.PooledObject<redis.clients.jedis.Connection> value){
                    return value.getObject().isConnected()&&!value.getObject().isBroken();
                }
            },pool);
        }
    }
    private static final class CountedConnection extends redis.clients.jedis.Connection {
        private NativeCommandCounts counts;
        CountedConnection(redis.clients.jedis.HostAndPort endpoint,redis.clients.jedis.JedisClientConfig config,NativeCommandCounts counts){
            super(endpoint,config);this.counts=counts;
        }
        @Override public void sendCommand(redis.clients.jedis.CommandArguments arguments){
            if(counts!=null)counts.submitted(arguments);
            super.sendCommand(arguments);
        }
    }
    static final class CountingSource implements DataSource {
        final DataSource delegate;final AtomicInteger calls=new AtomicInteger();
        CountingSource(DataSource delegate){this.delegate=delegate;}
        public Connection getConnection()throws SQLException{calls.incrementAndGet();return delegate.getConnection();}
        public Connection getConnection(String user,String password)throws SQLException{calls.incrementAndGet();return delegate.getConnection(user,password);}
        public java.io.PrintWriter getLogWriter()throws SQLException{return delegate.getLogWriter();}
        public void setLogWriter(java.io.PrintWriter out)throws SQLException{delegate.setLogWriter(out);}
        public int getLoginTimeout()throws SQLException{return delegate.getLoginTimeout();}
        public void setLoginTimeout(int seconds)throws SQLException{delegate.setLoginTimeout(seconds);}
        public java.util.logging.Logger getParentLogger()throws SQLFeatureNotSupportedException{return delegate.getParentLogger();}
        public <T>T unwrap(Class<T> type)throws SQLException{return delegate.unwrap(type);}
        public boolean isWrapperFor(Class<?> type)throws SQLException{return delegate.isWrapperFor(type);}
    }
}
