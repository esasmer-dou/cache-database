package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.processor.EncryptionCompilerFixture;
import com.reactor.cachedb.redis.RedisTenantRuntimeControl;
import com.reactor.cachedb.starter.CacheInitialNamespace;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static com.reactor.cachedb.jdbc.KilledInitialParticipantProcess.*;

@EnabledIfSystemProperty(named="cachedb.snapshot.providers",matches="true")
@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
@EnabledIfSystemProperty(named="cachedb.initial.isolatedSql",matches="true")
@Timeout(120)
class KilledInitialParticipantRedisTest {
    @TempDir Path temp;
    @Test void sigkillOfAnActiveEnrolledJvmRetainsMissingAckAndRejectsAutomaticRestartApproval()throws Exception {
        assertEquals("Linux",System.getProperty("os.name"));assertTrue(Runtime.getRuntime().maxMemory()<=512L*1048576);
        assertEquals(1,Runtime.getRuntime().availableProcessors());assertTrue(Files.isExecutable(Path.of("/bin/bash")));
        try(var admin=CacheTenantContext.administrative();var f=new Fixture(temp)) {
            Child original=null,replacement=null;Lease lease=null;
            Throwable primary=null;
            try {
                original=f.start("participant");await(f.ipc.resolve("participant-enrolled"),original,10);
                var member=Files.readAllLines(f.ipc.resolve("participant-enrolled"));assertEquals(3,member.size());UUID dead=UUID.fromString(member.get(0));
                assertNotEquals(dead,f.root.producer.runtimeId());assertEquals(f.root.plan.manifestToken(),member.get(1));
                assertEquals(f.root.enrollment.descriptor(ENTITY).coverageToken(),member.get(2));
                var approval=new RedisTenantRuntimeControl.ApprovedRoster(ProtectionKey.hash("killed-peer-review:"+f.run),f.root.plan.manifestToken(),member.get(2),
                        List.of(f.root.producer.runtimeId(),dead),List.of(f.root.generation.key()));
                var service=f.namespace.createBootstrap(f.root.enrollment,f.root.plan,approval,64,Set.of(f.namespace.window().fingerprint()));
                var request=new InitialTenantBootstrap.Request(f.root.generation,"a",UUID.randomUUID(),ProtectionKey.hash("killed-peer-operator:"+f.run),120);
                var relayFailure=new AtomicReference<Throwable>();
                Thread relay=Thread.ofPlatform().name("owned-initial-lease-relay").start(()->{
                    try(var relayAdmin=CacheTenantContext.administrative()) {
                        long deadline=System.nanoTime()+5_000_000_000L;
                        for(int probe=0;probe<500&&System.nanoTime()<deadline;probe++) {
                            var pending=f.namespace.pendingClosure();
                            if(pending.isPresent()){write(f.ipc.resolve("initial-closure"),message(pending.orElseThrow().lease(),approval.approvalToken(),f.root.producer.runtimeId()));return;}
                            Thread.sleep(10);
                        }throw new AssertionError("Actual SQL INITIAL lease relay exhausted");
                    }catch(Throwable failure){relayFailure.set(failure);}
                });
                try {
                    boolean active=false;
                    for(int call=0;call<512;call++) {
                        if(relayFailure.get()!=null)throw new AssertionError("Real lease relay failed",relayFailure.get());
                        var progress=service.step(request);f.evidence("PROGRESS call="+call+" phase="+progress.phase());
                        assertNotEquals(InitialTenantBootstrap.Phase.INCOMPLETE,progress.phase());
                        if(progress.phase()==InitialTenantBootstrap.Phase.ACTIVE){assertEquals(2,progress.warmedRows());active=true;break;}
                    }assertTrue(active,"Actual bounded INITIAL must reach ACTIVE");
                }finally{assertTrue(relay.join(Duration.ofSeconds(3)));}
                if(relayFailure.get()!=null)throw new AssertionError("Real lease relay failed",relayFailure.get());
                await(f.ipc.resolve("participant-sealed"),original,2);
                String roster=f.root.enrollment.controlStoreKey(ENTITY);
                assertEquals("SEALED",f.root.redis.hget(roster,"state"));assertEquals("2",f.root.redis.hget(roster,"enrolled"));assertEquals("2",f.root.redis.hget(roster,"required"));
                assertEquals("OPEN",f.root.redis.hget(f.root.enrollment.barrier(ENTITY).generationKey(f.root.generation.key()),"state"));
                for(long id:List.of(1L,3L)) {
                    String payload=f.root.redis.get(f.root.database.redisKeyStrategy().entityKey(NS,id));assertNotNull(payload);
                    assertTrue(payload.contains("~E1:k2:"));assertFalse(payload.contains(SECRET));
                }
                assertEquals(f.originalSource,f.source());String birth=f.birth();var resident=f.resident();
                original.command("HOLD_ACTUAL_DELIVERY");await(f.ipc.resolve("participant-callback-entered"),original,5);
                assertTrue(original.process.isAlive());assertFalse(Files.exists(f.ipc.resolve("participant-control-failed")));
                var options=new PurgeOptions(UUID.randomUUID(),"a",ProtectionKey.hash("kill-close:"+f.run),false,100,120,5000,PurgeScope.ACTIVE_STORES_RETAINING_SECURITY_METADATA);
                lease=f.root.guard.acquireForTenant(f.root.binding,"a",options,false);
                f.root.enrollment.barrier(ENTITY).close(lease,1);
                f.root.enrollment.sealClosed(f.root.plan,approval,List.of(new RedisTenantRuntimeControl.ClosedPair(lease,1)));
                var before=f.round(lease);assertEquals(TenantReadFence.State.DRAINING,before.state());assertEquals(2,before.registered());assertEquals(1,before.acknowledged());
                write(f.ipc.resolve("kill-closure"),message(lease,approval.approvalToken(),f.root.producer.runtimeId()));
                await(f.ipc.resolve("participant-active-closed"),original,5);
                var physical=read(f.ipc.resolve("participant-active-closed"));assertEquals("1",physical.getProperty("active"));assertEquals("false",physical.getProperty("drained"));assertEquals(dead.toString(),physical.getProperty("runtime"));
                assertTrue(original.process.isAlive());assertFalse(Files.exists(f.ipc.resolve("participant-shutdown-hook")));
                f.assertSqlClosed();
                long pid=original.process.pid();f.evidence("SIGKILL_DISPATCH pid="+pid+" realCallbackActive=1 actualSqlClosed=true actualAck=1/2");
                original.terminationRequested=true;
                // Bash sends the real signal without requiring procps in the test image.
                var killer=new ProcessBuilder("/bin/bash","-c","kill -KILL -- \"$1\"","cachedb-test-kill",Long.toString(pid)).redirectOutput(f.ipc.resolve("kill.stdout").toFile()).redirectError(f.ipc.resolve("kill.stderr").toFile()).start();
                assertTrue(killer.waitFor(2,TimeUnit.SECONDS));assertEquals(0,killer.exitValue());
                assertTrue(original.process.waitFor(5,TimeUnit.SECONDS));assertEquals(137,original.process.exitValue(),"Linux SIGKILL must be proven, not graceful shutdown");
                original.terminationConfirmed=true;
                assertFalse(original.process.isAlive());assertFalse(Files.exists(f.ipc.resolve("participant-shutdown-hook")));
                write(f.ipc.resolve("participant-death"),"pid="+pid+"\nexit=137\nsignal=SIGKILL\nshutdownHook=false\n");
                for(int probe=0;probe<3;probe++) {
                    var after=f.round(lease);assertEquals(TenantReadFence.State.DRAINING,after.state());assertEquals(2,after.registered());assertEquals(1,after.acknowledged());
                    assertEquals("1",f.root.redis.hget(roster,"e:"+dead));assertEquals("1",f.root.redis.hget(roster,"m:"+dead));
                    assertEquals("SEALED",f.root.redis.hget(roster,"state"));assertEquals("2",f.root.redis.hget(roster,"enrolled"));Thread.sleep(25);
                }
                assertEquals(birth,f.birth());assertEquals(resident,f.resident());assertEquals(f.originalSource,f.source());f.assertSqlClosed();
                replacement=f.start("restart");await(f.ipc.resolve("restart-rejected"),replacement,10);
                assertTrue(replacement.process.waitFor(5,TimeUnit.SECONDS));assertEquals(0,replacement.process.exitValue());
                var rejected=read(f.ipc.resolve("restart-rejected"));UUID restarted=UUID.fromString(rejected.getProperty("runtime"));
                assertNotEquals(dead,restarted);assertNotEquals(f.root.producer.runtimeId(),restarted);
                assertEquals("true",rejected.getProperty("rawReadBlocked"));assertEquals("true",rejected.getProperty("rawWriteBlocked"));
                assertEquals("MIGRATION_REQUIRED",rejected.getProperty("birthRejected"));
                assertEquals("REVIEW_REQUIRED",f.root.redis.hget(roster,"state"));assertEquals("3",f.root.redis.hget(roster,"enrolled"));assertEquals("2",f.root.redis.hget(roster,"required"));
                for(UUID runtime:List.of(dead,f.root.producer.runtimeId(),restarted))assertEquals("1",f.root.redis.hget(roster,"e:"+runtime));
                assertEquals("1",f.root.redis.hget(roster,"m:"+dead));assertNull(f.root.redis.hget(roster,"m:"+restarted));
                final Lease actual=lease;safe(assertThrows(ProtectionException.class,()->f.root.enrollment.readFence(ENTITY).requireClosed(actual,1,f.root.plan.manifestToken(),before)));
                assertEquals(birth,f.birth(),"Consumed namespace birth must not change");assertEquals(resident,f.resident());assertEquals(f.originalSource,f.source());f.assertSqlClosed();
                try(var tenant=CacheTenantContext.withTenant("a")) {
                    safe(assertThrows(ProtectionException.class,()->f.root.database.repository(f.root.metadata,f.root.unbound).findById(1L)));
                }
                for(var stream:List.of(f.root.database.config().writeBehind().streamKey(),f.root.database.config().writeBehind().deadLetterStreamKey(),f.root.database.config().writeBehind().compactionStreamKey()))assertEquals(0,f.root.redis.xlen(stream));
                f.evidence("PASS actualJvms=parent+child childExit=137 childSignal=SIGKILL shutdownHook=false actualGeneratedInitial=ACTIVE warmed=2"
                        +" callbackActiveAtKill=1 nativeAckAfterDeath=1/2 deadRosterRetained=true restartedNewIncarnation=true enrolled=3 approved=2"
                        +" automaticReadWriteRejected=true birthReuseRejectedBeforeDispatch=true sourceUnchanged=true ciphertextVersionsUnchanged=true sqlGuard=CLOSED"
                        +" deadRuntime="+dead+" restartedRuntime="+restarted);
            }catch(Exception|Error failure){primary=failure;throw failure;
            }finally {
                Throwable cleanup=null;
                try{if(original!=null)original.finish();}catch(Exception|Error failure){cleanup=append(cleanup,failure);}
                try{if(replacement!=null)replacement.finish();}catch(Exception|Error failure){cleanup=append(cleanup,failure);}
                try{if(lease!=null)f.root.guard.release(lease);}catch(Exception|Error failure){cleanup=append(cleanup,failure);}
                if(cleanup!=null){if(primary!=null)primary.addSuppressed(cleanup);else rethrow(cleanup);}
            }
        }
    }

    static String message(Lease lease,String approval,UUID parent){return "operation="+lease.operationId()+"\nowner="+lease.owner()+"\nepoch="+lease.epoch()+"\nseconds="+lease.seconds()+"\napproval="+approval+"\nparent="+parent+"\n";}
    static void await(Path path,Child child,int seconds)throws Exception {
        long deadline=System.nanoTime()+seconds*1_000_000_000L;
        for(int probe=0;probe<seconds*100&&System.nanoTime()<deadline;probe++) {
            if(Files.exists(path))return;
            assertTrue(child.process.isAlive(),()->"Owned child exited before required actual phase: "+path.getFileName()
                    +" exit="+child.process.exitValue()+" stdout="+child.ipc.resolve(child.mode+".stdout")
                    +" stderr="+child.ipc.resolve(child.mode+".stderr"));Thread.sleep(10);
        }fail("Bounded child phase not reached: "+path.getFileName());
    }
    static Throwable append(Throwable original,Throwable cleanup){if(original==null)return cleanup;if(original!=cleanup)original.addSuppressed(cleanup);return original;}
    static void rethrow(Throwable failure)throws Exception{if(failure instanceof Exception checked)throw checked;if(failure instanceof Error fatal)throw fatal;throw new AssertionError(failure);}
    static final class Child {
        final Process process;final BufferedWriter input;final Path ipc;final String mode;
        boolean terminationRequested,terminationConfirmed;
        Child(Process process,Path ipc,String mode){this.process=process;this.ipc=ipc;this.mode=mode;input=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),java.nio.charset.StandardCharsets.UTF_8));}
        void command(String line)throws IOException{input.write(line);input.newLine();input.flush();}
        void finish()throws Exception {
            Throwable failure=null;
            if(process.isAlive()) {
                try{write(ipc.resolve(mode+"-failure-cleanup"),"cleanupOnly=true\npid="+process.pid()+"\n");}
                catch(Exception|Error cleanup){failure=append(failure,cleanup);}
                try{terminationRequested=true;process.destroyForcibly();assertTrue(process.waitFor(5,TimeUnit.SECONDS));terminationConfirmed=true;}
                catch(InterruptedException cleanup){Thread.currentThread().interrupt();failure=append(failure,cleanup);}
                catch(Exception|Error cleanup){failure=append(failure,cleanup);}
            }
            // EOF must never replace the forced death that this fixture is intended to prove.
            if(!process.isAlive())try{input.close();}catch(IOException closedPipe){if(!terminationRequested||!terminationConfirmed)failure=append(failure,closedPipe);}
            catch(RuntimeException|Error cleanup){failure=append(failure,cleanup);}
            try{process.getInputStream().close();}catch(Exception|Error cleanup){failure=append(failure,cleanup);}
            try{process.getErrorStream().close();}catch(Exception|Error cleanup){failure=append(failure,cleanup);}
            for(String extension:List.of(".stdout",".stderr"))try{assertTrue(Files.size(ipc.resolve(mode+extension))<=1048576);}
            catch(Exception|Error cleanup){failure=append(failure,cleanup);}
            if(failure!=null)rethrow(failure);
        }
    }
    static final class Fixture implements AutoCloseable {
        final String run=UUID.randomUUID().toString().replace("-","");
        final Path ipc=Path.of("target","generated-initial-proofs","killed-"+run).toAbsolutePath().normalize();
        final GeneratedInitialSqlSandbox sandbox;
        final Root root;
        final CacheInitialNamespace<Object,Long> namespace;
        final Map<Long,String> originalSource;
        Fixture(Path temp)throws Exception {
            Files.createDirectories(ipc);
            var result=EncryptionCompilerFixture.compile(temp,Map.of("sample/GeneratedInitialRow.java","""
                    package sample;
                    import com.reactor.cachedb.annotations.*;
                    @CacheEntity(table="generated_initial_rows",redisNamespace="generated_initial_rows",tenantPurgeEnabled=true,indexedColumns={"tenant_id","secret"})
                    public record GeneratedInitialRow(@CacheId(column="id") Long id,@CacheTenantId @CacheColumn("tenant_id") String tenant,
                        @CacheEncrypted(scope=EncryptionScope.REDIS_AND_SOURCE,blindIndex=true) @CacheColumn("secret") String secret) {}
                    ""","sample/Scenario.java","""
                    package sample;
                    public final class Scenario implements java.util.function.Supplier<Object[]> {
                        public Object[] get(){return new Object[]{new GeneratedInitialRow(1L,"a","GENERATED_INITIAL_PRIVATE_1"),GeneratedInitialRowCacheBinding.METADATA,GeneratedInitialRowCacheBinding.CODEC};}
                    }
                    """));assertTrue(result.success(),result.diagnostics());copy(result.classes(),ipc.resolve("classes"));copy(result.generated(),ipc.resolve("generated"));
            GeneratedInitialSqlSandbox openedSandbox=null;
            Root openedRoot=null;
            try {
                sandbox=openedSandbox=new GeneratedInitialSqlSandbox("postgres");root=openedRoot=new Root(run,ipc.resolve("classes"),sandbox.source);
                try(var c=root.source.getConnection();var q=c.createStatement()) {
                    q.setQueryTimeout(5);q.executeUpdate("CREATE TABLE native_fixture_owner(run_token VARCHAR(32) NOT NULL PRIMARY KEY)");
                    q.executeUpdate("INSERT INTO native_fixture_owner(run_token) VALUES ('"+run+"')");
                    q.executeUpdate("CREATE TABLE generated_initial_rows(id DECIMAL(19,0) NOT NULL PRIMARY KEY,tenant_id VARCHAR(64) NOT NULL,secret VARCHAR(1024),entity_version DECIMAL(19,0) NOT NULL)");
                    q.executeUpdate("CREATE INDEX generated_tenant_idx ON generated_initial_rows(tenant_id,id)");q.executeUpdate("CREATE INDEX generated_version_idx ON generated_initial_rows(tenant_id,entity_version)");
                }
                var old=root.unbound.bindEncryption(EncryptionCompilerFixture.keys("k1",EncryptionCompilerFixture.key("k1",11),EncryptionCompilerFixture.key("k2",17)));
                try(var c=root.source.getConnection();var q=c.prepareStatement("INSERT INTO generated_initial_rows(id,tenant_id,secret,entity_version) VALUES (?,?,?,?)")) {
                    q.setQueryTimeout(2);for(long id:List.of(1L,2L,3L,99L)) {
                        q.setLong(1,id);q.setString(2,id==2?"b":"a");q.setString(3,old.toSourceColumns(root.row(id)).get("secret").toString());q.setLong(4,id==1?7:id==3?8:id==99?9000:18000);q.addBatch();
                    }assertEquals(4,q.executeBatch().length);
                }
                originalSource=source();root.guard.provisionClosed(root.generation);
                namespace=root.database.prepareInitialNamespace(root.metadata,root.binding,root.guard,root.ids,1L,3L,2,65536,16384,1048576,2);
                root.enroll(namespace.bootstrap(new JdbcNamespaceBootstrapGate(root.guard,Set.of(namespace.descriptor().namespaceFingerprint())),root.generation),namespace.window());
                evidence("OWNED_FIXTURE scope="+sandbox.name+" redisPrefix="+root.prefix+" library="+root.library+" run="+run+" parentPid="+ProcessHandle.current().pid());
            } catch(Exception|Error failure) {
                GeneratedInitialSqlSandbox.closeAfterFailedConstruction(failure,false,openedSandbox,openedRoot);
                throw failure;
            }
        }
        Child start(String mode)throws Exception {
            var command=List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xms32m","-Xmx256m","-XX:ActiveProcessorCount=1","-XX:+ExitOnOutOfMemoryError",
                    "-Dcachedb.snapshot.redis.host="+System.getProperty("cachedb.snapshot.redis.host","redis"),
                    "-Dcachedb.snapshot.redis.port="+Integer.getInteger("cachedb.snapshot.redis.port",6379),
                    "-cp",System.getProperty("java.class.path"),KilledInitialParticipantProcess.class.getName(),mode,run,sandbox.name,ipc.resolve("classes").toString(),ipc.toString());
            var builder=new ProcessBuilder(command).redirectOutput(ipc.resolve(mode+".stdout").toFile()).redirectError(ipc.resolve(mode+".stderr").toFile());
            builder.environment().keySet().removeIf(key->key.startsWith("KUBERNETES_")||Set.of("JAVA_TOOL_OPTIONS","_JAVA_OPTIONS","JDK_JAVA_OPTIONS").contains(key));
            write(ipc.resolve(mode+"-resource-flags"),"java="+command.getFirst()+"\n-Xms32m\n-Xmx256m\n-XX:ActiveProcessorCount=1\n-XX:+ExitOnOutOfMemoryError\nparentHeapMax="+Runtime.getRuntime().maxMemory()+"\nparentCpu="+Runtime.getRuntime().availableProcessors()+"\nmaxLiveTestChildren=1\n");
            Process child=builder.start();evidence("CHILD_STARTED mode="+mode+" pid="+child.pid()+" maxHeap=256MiB cpu=1");return new Child(child,ipc,mode);
        }
        TenantReadFence.Evidence round(Lease lease){return root.enrollment.readFence(ENTITY).closeAndDrain(lease,1,root.plan.manifestToken(),"",128,1000);}
        Map<Long,String> source()throws SQLException {
            var rows=new LinkedHashMap<Long,String>();try(var c=root.source.getConnection();var q=c.prepareStatement("SELECT id,tenant_id,secret,entity_version FROM generated_initial_rows ORDER BY id")) {
                q.setQueryTimeout(2);try(var r=q.executeQuery()){while(r.next())rows.put(r.getLong(1),r.getString(2)+"|"+r.getString(3)+"|"+r.getLong(4));}
            }return Map.copyOf(rows);
        }
        String birth()throws SQLException {
            try(var c=root.source.getConnection();var q=c.prepareStatement("SELECT roster_token,binding_token,mapping_token,incarnation_id,redis_run_id FROM cachedb_namespace_bootstrap")) {
                q.setQueryTimeout(2);try(var r=q.executeQuery()){assertTrue(r.next());var value=new StringBuilder();for(int i=1;i<=5;i++)value.append(r.getString(i)).append(':');assertFalse(r.next());return value.toString();}
            }
        }
        Map<String,String> resident(){var values=new LinkedHashMap<String,String>();var keys=root.database.redisKeyStrategy();for(long id:List.of(1L,3L)){values.put(keys.entityKey(NS,id),root.redis.get(keys.entityKey(NS,id)));values.put(keys.versionKey(NS,id),root.redis.get(keys.versionKey(NS,id)));values.put(keys.versionKey(NS,id)+":tenant",root.redis.get(keys.versionKey(NS,id)+":tenant"));}return Map.copyOf(values);}
        void assertSqlClosed()throws SQLException {
            try(var c=root.source.getConnection();var q=c.prepareStatement("SELECT gate_state FROM cachedb_tenant_guard WHERE scope_token=? AND tenant_token=?")) {
                q.setQueryTimeout(2);q.setString(1,root.generation.key().scopeToken());q.setString(2,root.generation.key().tenantToken());try(var r=q.executeQuery()){assertTrue(r.next());assertEquals("CLOSED",r.getString(1));assertFalse(r.next());}
            }
        }
        void evidence(String message)throws IOException {Files.writeString(ipc.resolve("controller.txt"),message+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);System.out.println("KILLED_INITIAL "+message);}
        static void copy(Path from,Path to)throws IOException {try(var walk=Files.walk(from)){var files=walk.filter(Files::isRegularFile).limit(65).toList();assertTrue(files.size()<=64);long total=0;for(var file:files){total+=Files.size(file);assertTrue(total<=8*1048576);Path target=to.resolve(from.relativize(file));Files.createDirectories(target.getParent());Files.copy(file,target);}}}
        public void close()throws Exception{GeneratedInitialSqlSandbox.closeFixture(false,sandbox,root);}
    }
}
