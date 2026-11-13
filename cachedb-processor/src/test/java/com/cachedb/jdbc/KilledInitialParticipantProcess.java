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
import com.zaxxer.hikari.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import redis.clients.jedis.*;

/** Test-only fork entry point: every ACK/read comes from this actual root, never the controller. */
public final class KilledInitialParticipantProcess {
    static final String NS="generated_initial_rows",ENTITY="GeneratedInitialRow";
    static final String SECRET=GeneratedInitialNativeFixture.SECRET;
    private KilledInitialParticipantProcess(){}

    public static void main(String[] args)throws Exception {
        assertEquals(5,args.length);String mode=args[0],run=args[1],schema=args[2];Path classes=Path.of(args[3]),ipc=Path.of(args[4]);
        requireOwned(run,schema,ipc);assertTrue(Runtime.getRuntime().maxMemory()<=256L*1048576);
        assertEquals(1,Runtime.getRuntime().availableProcessors());
        assertFalse(Files.exists(Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token")),"No API token may be mounted");
        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            try{write(ipc.resolve(mode+"-shutdown-hook"),"executed\n");}catch(Exception ignored){}
        },"owned-child-shutdown-evidence"));
        System.out.println("CHILD_START mode="+mode+" pid="+ProcessHandle.current().pid()+" heapMax="+Runtime.getRuntime().maxMemory()
                +" processors="+Runtime.getRuntime().availableProcessors()+" redisPoolMax=4 jdbcPoolMax=2 timeoutsMillis=2000");System.out.flush();
        System.out.println("CHILD_VM_FLAGS "+java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());System.out.flush();
        Thread.ofPlatform().daemon().name("owned-child-deadline").start(()->{
            try{Thread.sleep(110_000);System.err.println("CHILD_DEADLINE_EXCEEDED");Runtime.getRuntime().halt(70);}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        });
        try(var pool=existingSource(schema);var admin=CacheTenantContext.administrative();var root=new Root(run,classes,pool)) {
            if(mode.equals("participant"))participant(root,ipc);
            else if(mode.equals("restart"))restart(root,ipc);
            else throw new AssertionError("Unknown child mode");
        }
    }

    static void participant(Root root,Path ipc)throws Exception {
        root.enroll(attachExistingNamespace(root,ipc,"participant"),null);
        var local=root.enrollment.descriptor(ENTITY);
        write(ipc.resolve("participant-enrolled"),local.runtimeId()+"\n"+root.plan.manifestToken()+"\n"+local.coverageToken()+"\n");
        var stop=new AtomicBoolean();var failure=new AtomicReference<Throwable>();
        Thread controller=Thread.ofPlatform().name("actual-child-control").start(()->{
            try(var admin=CacheTenantContext.administrative()) {
                boolean sealed=false,observed=false;long deadline=System.nanoTime()+100_000_000_000L;
                for(int round=0;!stop.get()&&round<10000&&System.nanoTime()<deadline;round++) {
                    if(!sealed&&Files.exists(ipc.resolve("initial-closure"))) {
                        var p=read(ipc.resolve("initial-closure"));
                        var approval=approval(root,p);
                        root.enrollment.sealClosed(root.plan,approval,List.of(new RedisTenantRuntimeControl.ClosedPair(lease(root,p),1)));
                        sealed=true;write(ipc.resolve("participant-sealed"),"actual-seal=true\n");
                    }
                    if(sealed)root.enrollment.pollControl(1,1000);
                    if(!observed&&Files.exists(ipc.resolve("kill-closure"))) {
                        var p=read(ipc.resolve("kill-closure"));var drain=root.producer.readFence().closeAndDrain(lease(root,p),1);
                        assertFalse(drain.drained());assertEquals(1,drain.active());
                        write(ipc.resolve("participant-active-closed"),"active="+drain.active()+"\ndrained="+drain.drained()+"\nruntime="+drain.runtimeId()+"\n");observed=true;
                    }
                    Thread.sleep(10);
                }
            }catch(Throwable unsafe){failure.set(unsafe);try{write(ipc.resolve("participant-control-failed"),unsafe.getClass().getName()+"\n");}catch(Exception ignored){}}
        });
        try(var input=new BufferedReader(new InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8))) {
            assertEquals("HOLD_ACTUAL_DELIVERY",input.readLine());
            if(failure.get()!=null)throw new AssertionError("Actual child control failed",failure.get());
            int before=root.source.calls.get();
            root.enrollment.deliver(root.metadata,"a",repository->repository.findById(1L),row->{
                assertEquals(root.row(1),row.orElseThrow());assertEquals(before,root.source.calls.get());
                try {
                    write(ipc.resolve("participant-callback-entered"),"runtime="+local.runtimeId()+"\nrowId=1\nforegroundSqlBorrows=0\n");
                    System.out.println("ACTUAL_CALLBACK_ENTERED runtime="+local.runtimeId()+" row=1 foregroundSqlBorrows=0");System.out.flush();
                    // Physical synchronous callback remains inside its real read ticket until SIGKILL/EOF.
                    if(input.readLine()==null)throw new AssertionError("Controller pipe ended before explicit SIGKILL");
                    throw new AssertionError("Controller must not release killed callback gracefully");
                }catch(IOException unavailable){throw new AssertionError("Owned child control pipe failed",unavailable);}
            });
        }finally {
            stop.set(true);assertTrue(controller.join(Duration.ofSeconds(3)),"Child control must physically exit");
        }
        throw new AssertionError("Killed participant must not reach a normal return");
    }

    static void restart(Root root,Path ipc)throws Exception {
        var producer=attachExistingNamespace(root,ipc,"restart");UUID fresh=producer.runtimeId();
        var prior=UUID.fromString(Files.readAllLines(ipc.resolve("participant-enrolled")).getFirst());assertNotEquals(prior,fresh);
        int before=root.source.calls.get();
        try(var tenant=CacheTenantContext.withTenant("a")) {
            var readFailure=assertThrows(ProtectionException.class,()->root.database.repository(root.metadata,root.unbound).findById(1L));safe(readFailure);
            var writeFailure=assertThrows(redis.clients.jedis.exceptions.JedisDataException.class,()->root.database.repository(root.metadata,root.unbound).save(root.row(1)));
            assertTrue(writeFailure.getMessage().contains("CDB_RUNTIME_NOT_APPROVED"));assertFalse(writeFailure.toString().contains(SECRET));
            assertNull(writeFailure.getCause());assertEquals(0,writeFailure.getSuppressed().length);
        }
        assertEquals(before,root.source.calls.get(),"Ordinary blocked routes must not borrow SQL");
        var namespace=root.database.prepareInitialNamespace(root.metadata,root.binding,root.guard,root.ids,1L,3L,2,65536,16384,1048576,2);
        var failure=assertThrows(ProtectionException.class,()->namespace.bootstrap(new JdbcNamespaceBootstrapGate(root.guard,
                Set.of(namespace.descriptor().namespaceFingerprint())),root.generation));
        safe(failure);assertEquals(ProtectionException.Code.MIGRATION_REQUIRED,failure.code());
        write(ipc.resolve("restart-rejected"),"runtime="+fresh+"\ndeadRuntime="+prior+"\nrawReadBlocked=true\nrawWriteBlocked=true\nbirthRejected="+failure.code()+"\nforegroundSqlBorrows=0\n");
        System.out.println("RESTART_REJECTED newRuntime="+fresh+" deadRuntime="+prior+" ordinaryRead=true ordinaryWrite=true birth=MIGRATION_REQUIRED");
    }

    static RedisTenantProducer attachExistingNamespace(Root root,Path ipc,String mode)throws Exception {
        // A peer attaches to the first root's consumed birth; it must never create another one.
        try(var namespace=root.database.prepareInitialNamespace(root.metadata,root.binding,root.guard,root.ids,1L,3L,2,65536,16384,1048576,2)) {
            var expected=namespace.descriptor();String incarnation;
            try(var connection=root.source.getConnection();var statement=connection.prepareStatement(
                    "SELECT roster_token,binding_token,mapping_token,incarnation_id,redis_run_id FROM cachedb_namespace_bootstrap")) {
                statement.setQueryTimeout(2);
                try(var rows=statement.executeQuery()) {
                    assertTrue(rows.next(),"Parent must commit the real namespace birth before starting a peer");
                    assertEquals(expected.rosterKeyToken(),rows.getString(1));
                    assertEquals(expected.bindingToken(),rows.getString(2));
                    assertEquals(expected.mappingToken(),rows.getString(3));
                    incarnation=UUID.fromString(rows.getString(4)).toString();
                    assertEquals(expected.redisRunId(),rows.getString(5),"Peer must use the Redis server of the consumed SQL birth");
                    assertFalse(rows.next());
                }
            }
            String roster=RedisTenantRuntimeControl.rosterKey(root.database.redisKeyStrategy(),NS);
            assertEquals("2",root.redis.hget(roster,"schema"),"Consumed SQL birth requires the existing parent Redis roster; never recreate it");
            assertEquals(expected.redisRunId(),root.redis.hget(roster,"run_id"));
            assertEquals("1",root.redis.hget(roster,"e:"+incarnation));
            var producer=root.database.configureTenantProducer(root.metadata,2);
            write(ipc.resolve(mode+"-existing-birth"),"sqlBirthVerified=true\nredisRosterVerified=true\nsecondBirthAttempted=false\nruntime="+producer.runtimeId()+"\n");
            return producer;
        }
    }

    static void safe(ProtectionException failure){assertNull(failure.getCause());assertEquals(0,failure.getSuppressed().length);assertFalse(failure.toString().contains(SECRET));}
    static RedisTenantRuntimeControl.ApprovedRoster approval(Root root,Properties p) {
        return new RedisTenantRuntimeControl.ApprovedRoster(p.getProperty("approval"),root.plan.manifestToken(),root.enrollment.descriptor(ENTITY).coverageToken(),
                List.of(UUID.fromString(p.getProperty("parent")),root.producer.runtimeId()),List.of(root.generation.key()));
    }
    static Lease lease(Root root,Properties p){return new Lease(root.generation.key(),UUID.fromString(p.getProperty("operation")),UUID.fromString(p.getProperty("owner")),Long.parseLong(p.getProperty("epoch")),Integer.parseInt(p.getProperty("seconds")));}
    static Properties read(Path path)throws IOException {
        assertTrue(Files.size(path)<=4096);var p=new Properties();try(var input=Files.newInputStream(path)){p.load(input);}assertTrue(p.size()<=8);return p;
    }
    static void write(Path path,String value)throws IOException {
        assertTrue(value.length()<=4096);Path temp=path.resolveSibling(path.getFileName()+".tmp");
        Files.writeString(temp,value,StandardOpenOption.CREATE_NEW);Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE);
    }
    static void requireOwned(String run,String schema,Path ipc){
        assertTrue(run.matches("[0-9a-f]{32}"));assertTrue(schema.matches("cachedb_v019_initial_[0-9a-f]{8}"));
        assertTrue(ipc.toAbsolutePath().normalize().toString().endsWith("killed-"+run));
    }
    static HikariDataSource existingSource(String schema) {
        assertTrue(schema.matches("cachedb_v019_initial_[0-9a-f]{8}"));
        var config=new HikariConfig();config.setJdbcUrl(GeneratedInitialSqlSandbox.setting("postgres","URL",null));
        config.setUsername(GeneratedInitialSqlSandbox.setting("postgres","USER",null));
        config.setPassword(GeneratedInitialSqlSandbox.setting("postgres","PASSWORD",null));
        config.addDataSourceProperty("connectTimeout","2");config.addDataSourceProperty("socketTimeout","2");
        config.setSchema(schema);config.setMaximumPoolSize(2);config.setMinimumIdle(0);config.setConnectionTimeout(2000);config.setValidationTimeout(1000);
        return new HikariDataSource(config);
    }

    static final class Root implements AutoCloseable {
        final String prefix,library;
        final EncryptionCompilerFixture.Loaded compiled;
        final EntityMetadata<Object,Long> metadata;
        final EntityCodec<Object> unbound,bound;
        final GeneratedInitialNativeFixture.CountingSource source;
        final JedisPooled redis;
        final BoundedRedisWire wire;
        final CacheDatabase database;
        final Binding binding;
        final JdbcTenantPurgeGuard guard;
        final Generation generation;
        final JdbcSourceOwnerOnboarding.JdbcIdCodec<Long> ids=JdbcSourceOwnerOnboarding.longIds();
        RedisTenantProducer producer;CacheTenantRuntimeEnrollment enrollment;RedisTenantPurgePlan plan;
        @SuppressWarnings("unchecked") Root(String run,Path classes,DataSource dataSource)throws Exception {
            assertTrue(run.matches("[0-9a-f]{32}"));prefix="killed_initial_"+run;library=prefix+"_functions";
            EncryptionCompilerFixture.Loaded openedCompiled=null;
            JedisPooled openedRedis=null;
            BoundedRedisWire openedWire=null;
            CacheDatabase openedDatabase=null;
            try {
                compiled=openedCompiled=EncryptionCompilerFixture.load(new EncryptionCompilerFixture.Compilation(true,null,classes,""));
                metadata=(EntityMetadata<Object,Long>)(EntityMetadata<?,?>)compiled.metadata();unbound=compiled.unbound();
                source=new GeneratedInitialNativeFixture.CountingSource(dataSource);
                String host=System.getProperty("cachedb.snapshot.redis.host","redis");int port=Integer.getInteger("cachedb.snapshot.redis.port",6379);
                var pool=new org.apache.commons.pool2.impl.GenericObjectPoolConfig<Connection>();pool.setMaxTotal(4);pool.setMaxIdle(4);pool.setMaxWait(Duration.ofMillis(1000));
                redis=openedRedis=new JedisPooled(new HostAndPort(host,port),DefaultJedisClientConfig.builder().connectionTimeoutMillis(2000).socketTimeoutMillis(2000).build(),pool);
                wire=openedWire=new BoundedRedisWire(new InetSocketAddress(host,port),null,null,null,null,0,2000,2000,1);
                var config=CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                        .readThrough(ReadThroughConfig.builder().mode(ReadThroughMode.REDIS_ONLY).build())
                        .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                        .redisGuardrail(RedisGuardrailConfig.builder().enabled(false).build())
                        .redisFunctions(RedisFunctionsConfig.builder().libraryName(library).upsertFunctionName(library+"_upsert")
                                .deleteFunctionName(library+"_delete").compactionCompleteFunctionName(library+"_complete").build())
                        .writeBehind(WriteBehindConfig.builder().enabled(false).streamKey(prefix+":write").deadLetterStreamKey(prefix+":dlq").compactionStreamKey(prefix+":compact").build()).build();
                var options=CacheRuntimeOptions.builder().keyProvider(EncryptionCompilerFixture.keys("k2",EncryptionCompilerFixture.key("k1",11),EncryptionCompilerFixture.key("k2",17))).build();
                database=openedDatabase=new CacheDatabase(redis,source,config,options);database.start();database.register(metadata,unbound,new CachePolicy(16,16,false,120,120));
                bound=(EntityCodec<Object>)database.entityRegistry().find(ENTITY).orElseThrow().codec();assertTrue(bound.encryptionBound());
                binding=new Binding(metadata.entityType(),NS,new SourcePlan(NS,"tenant_id","id"),RedisInitialSourceLayout.layoutToken(metadata,database.redisKeyStrategy(),config.queryIndex()),10000,128,1048576,true);
                guard=new JdbcTenantPurgeGuard(source,JdbcTenantPurgeDialect.POSTGRES,2,1,Set.of(binding.fingerprint()));generation=new Generation(binding.key("a"),1);
            } catch(Exception|Error failure) {
                GeneratedInitialSqlSandbox.closeAfterFailedConstruction(failure,true,null,
                        openedDatabase,openedWire,openedRedis,openedCompiled);
                throw failure;
            }
        }
        Object row(long id){return bound.fromLogicalColumns(Map.of("id",id,"tenant_id",id==2?"b":"a","secret",SECRET+id));}
        void enroll(RedisTenantProducer actual,InitialSourceHotWindow<Object,Long> window) {
            producer=actual;enrollment=database.prepareTenantProtection(2);
            if(window==null)window=new InitialSourceHotWindow<>(binding,metadata,bound,ids,ids.formatToken(),1L,3L,2,65536,16384,1048576,InitialSourceHotWindow.Profile.POINT_NO_QUOTA);
            var manifest=new PurgeParticipantManifest(ENTITY,Set.of("runtime-control-roster","initial-native-window","initial-source-journal"),
                    List.of(enrollment.controlParticipant(ENTITY),enrollment.initialSourceParticipant(metadata,window),new JdbcInitialBootstrapPurgeParticipant(guard,binding,ENTITY,2)));
            var routes=new LinkedHashMap<String,BoundedRedisWire>();var write=database.config().writeBehind();
            routes.put(write.streamKey(),wire);routes.put(write.deadLetterStreamKey(),wire);routes.put(write.compactionStreamKey(),wire);for(String stream:write.activeStreamKeys())routes.put(stream,wire);
            plan=new RedisTenantPurgePlan(binding,ENTITY,"entity_version",guard,new JdbcTenantVersionAllocator(guard,JdbcTenantPurgeDialect.POSTGRES,2),
                    new JdbcPurgeScanJournal(guard,JdbcTenantPurgeDialect.POSTGRES,2,128,10000,10*1048576),new JdbcPurgeFacadeJournal(guard,JdbcTenantPurgeDialect.POSTGRES,2,256,10*1048576),
                    new JdbcPurgeParticipantJournal(guard,2,1),wire,database.redisOperations(),database.redisKeyStrategy(),enrollment.barrier(ENTITY),enrollment.readFence(ENTITY),
                    List.of(enrollment.pointProfileTarget(ENTITY)),Set.of(NS),Map.of(),routes,manifest,event->{});
        }
        public void close()throws Exception {GeneratedInitialSqlSandbox.closeFixture(true,null,database,wire,redis,compiled);}
    }
}
