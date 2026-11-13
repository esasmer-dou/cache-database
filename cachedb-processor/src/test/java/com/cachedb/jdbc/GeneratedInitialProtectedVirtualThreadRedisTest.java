package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.redis.RedisTenantRuntimeControl.ClosedPair;
import com.reactor.cachedb.redis.JedisPooledOperations;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import jdk.jfr.*;
import jdk.jfr.consumer.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.*;

/** Real generated warm rows, SQL leases and native ACKs; the only delay is a real owned Redis command. */
@EnabledIfSystemProperty(named="cachedb.snapshot.providers",matches="true")
@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
@EnabledIfSystemProperty(named="cachedb.initial.isolatedSql",matches="true")
@Timeout(120)
class GeneratedInitialProtectedVirtualThreadRedisTest {
    @TempDir Path temp;
    @Name("com.reactor.cachedb.f3.ProtectedNativePhase") @StackTrace(true)
    static final class PhaseEvent extends Event {String phase;boolean virtual;boolean completed;}
    @Name("com.reactor.cachedb.f3.ProtectedNativeRedisPark") @StackTrace(true)
    static final class RedisParkEvent extends Event {boolean virtual;boolean physicalExit;long clientId;}

    @Test void closedReadFencesRetainCallbacksAndPhysicalRedisTicketsWithoutCachedbPins()throws Exception {
        assertTrue(FlightRecorder.isAvailable());
        var settings=Configuration.create(Path.of("../tools/ci/virtual-threads.jfc"));
        assertEquals("true",settings.getSettings().get("jdk.VirtualThreadPinned#enabled"));
        assertEquals("true",settings.getSettings().get("jdk.VirtualThreadPinned#stackTrace"));
        assertEquals("20 ms",settings.getSettings().get("jdk.VirtualThreadPinned#threshold"));
        try(var admin=CacheTenantContext.administrative();var f=new GeneratedInitialNativeFixture(temp,true)) {
            assertSame(f.redis,assertInstanceOf(JedisPooledOperations.class,f.database.redisOperations()).commands());
            assertSame(f.peerRedis,assertInstanceOf(JedisPooledOperations.class,f.peer.redisOperations()).commands());
            f.activate();assertNotSame(f.redis,f.peerRedis);assertNotEquals(f.primary.descriptor(f.ENTITY).runtimeId(),f.secondary.descriptor(f.ENTITY).runtimeId());
            var output=f.proof.getParent().resolve(f.run+"-protected.jfr");
            var report=f.proof.getParent().resolve(f.run+"-protected.json");
            var releaseA=new CountDownLatch(1);var releaseB=new CountDownLatch(1);
            var enteredA=new CountDownLatch(1);var enteredB=new CountDownLatch(1);
            var park=new ActualRedisPark(f);Lease lease=null;
            var workers=new ArrayList<Work<?>>();
            try(var recording=new Recording(settings)) {
                recording.enable(PhaseEvent.class).withThreshold(Duration.ZERO).withStackTrace();
                recording.enable(RedisParkEvent.class).withThreshold(Duration.ZERO).withStackTrace();
                recording.enable("jdk.SocketRead").withThreshold(Duration.ofMillis(20)).withStackTrace();
                recording.setMaxSize(64L*1048576);recording.setDuration(Duration.ofSeconds(30));recording.start();
                try {
                    var a=start("delivery-a",()->{
                        try(var audit=f.source.track()) {
                            f.primary.deliver(f.metadata,"a",r->r.findById(1L),row->{
                                assertEquals(f.row(1),row.orElseThrow());enteredA.countDown();await(releaseA);
                            });return null;
                        }
                    });workers.add(a);
                    var b=start("delivery-b",()->{
                        try(var audit=f.source.track()) {
                            f.secondary.deliver(f.metadata,"a",r->r.findById(3L),row->{
                                assertEquals(f.row(3),row.orElseThrow());enteredB.countDown();await(releaseB);
                            });return null;
                        }
                    });workers.add(b);assertTrue(enteredA.await(5,TimeUnit.SECONDS));assertTrue(enteredB.await(5,TimeUnit.SECONDS));
                    var io=start("capture-physical-io",()->{
                        try(var audit=f.source.track();
                                var ticket=f.producer.readFence().begin(f.generation.key())) {
                            // The real root ticket spans this exact canonical client's physical socket I/O.
                            // No adapter interception or simulated capture/ACK is involved.
                            ticket.read(()->{park.run();return null;});
                            fail("Closed physical read must never return");return null;
                        }
                    });workers.add(io);assertTrue(park.dispatched.await(5,TimeUnit.SECONDS));
                    assertBlocked(f,park.clientId.get());assertFalse(io.task.isDone());assertTrue(f.redis.getPool().getNumActive()>=1);
                    var saturated=start("capacity-busy",()->{
                        try(var audit=f.source.track()) {
                            var rejected=assertThrows(ProtectionException.class,()->f.primary.deliver(f.metadata,"a",r->r.findById(1L),row->fail("Capacity acceptance")));
                            assertEquals(ProtectionException.Code.BUSY,rejected.code());return null;
                        }
                    });workers.add(saturated);saturated.success();
                    var acquire=start("sql-authorized-close",()->{
                        try(var scope=CacheTenantContext.administrative()) {
                            var options=new PurgeOptions(UUID.randomUUID(),"a",ProtectionKey.hash("protected-vt:"+f.run),false,100,120,5000,
                                    PurgeScope.ACTIVE_STORES_RETAINING_SECURITY_METADATA);
                            return f.guard.acquireForTenant(f.binding,"a",options,false);
                        }
                    });workers.add(acquire);lease=acquire.success();final Lease actual=lease;
                    var close=start("seal-actual-closed",()->{
                        try(var scope=CacheTenantContext.administrative()) {
                            f.primary.barrier(f.ENTITY).close(actual,1);
                            f.primary.sealClosed(f.plan,f.approval,List.of(new ClosedPair(actual,1)));
                            f.secondary.sealClosed(f.peerPlan,f.approval,List.of(new ClosedPair(actual,1)));return null;
                        }
                    });workers.add(close);close.success();
                    assertEquals(2,f.producer.readFence().closeAndDrain(actual,1).active());
                    assertEquals(1,f.peerProducer.readFence().closeAndDrain(actual,1).active());
                    var first=round(f,actual,"drain-physical-and-callbacks",workers);assertEquals(TenantReadFence.State.DRAINING,first.state());assertEquals(0,first.acknowledged());
                    var poll=start("peer-held-poll",()->{try(var scope=CacheTenantContext.administrative()){return f.secondary.pollControl(1,1000);}});
                    workers.add(poll);assertEquals(0,poll.success().locallyAcknowledged());
                    assertBlocked(f,park.clientId.get());assertFalse(park.exited.get());assertFalse(io.task.isDone());
                    f.redis.rpush(park.key,"release");io.rejected();assertTrue(park.exited.get());assertEquals(0,f.redis.getPool().getNumActive());
                    assertEquals(1,f.producer.readFence().closeAndDrain(actual,1).active());
                    assertEquals(TenantReadFence.State.DRAINING,round(f,actual,"drain-callbacks-only",workers).state());
                    releaseA.countDown();a.rejected();
                    var one=round(f,actual,"drain-peer-callback",workers);assertEquals(TenantReadFence.State.DRAINING,one.state());assertEquals(1,one.acknowledged());
                    assertEquals(1,f.peerProducer.readFence().closeAndDrain(actual,1).active());
                    releaseB.countDown();b.rejected();
                    var peerAck=start("peer-physical-exit-ack",()->{try(var scope=CacheTenantContext.administrative()){return f.secondary.pollControl(1,1000);}});
                    workers.add(peerAck);assertEquals(1,peerAck.success().locallyAcknowledged());
                    var done=round(f,actual,"actual-two-root-consensus",workers);
                    assertEquals(TenantReadFence.State.CLOSED,done.state());assertEquals(2,done.registered());assertEquals(2,done.acknowledged());
                    f.primary.readFence(f.ENTITY).requireClosed(actual,1,f.plan.manifestToken(),done);
                    assertEquals(0,f.source.foreground.get());assertEquals(f.originalSource,f.sourceSnapshot());
                    f.evidence("PASS protectedActualRoots=2 separateRedisClients=2 actualGeneratedWarmRows=true SQLAuthorizedClosed=true"
                            +" heldCallbackTickets=2 physicalRedisReadTicket=1 canonicalAdapters=true saturatedRejected=true actualAck=2/2"
                            +" prematureAck=0 prematurePhysicalReturn=0 foregroundSqlBorrows=0 sourceUnchanged=true");
                }finally {
                    releaseA.countDown();releaseB.countDown();
                    if(park.dispatched.getCount()==0&&!park.exited.get())f.redis.rpush(park.key,"finally-release");
                    for(var work:workers)work.join();
                    recording.stop();recording.dump(output);
                    if(lease!=null)f.guard.release(lease);
                }
            }
            inspect(output,report);f.evidence("JFR_PASS file="+output.getFileName()+" pinThreshold=20ms actualRedisPark=true");
        }
    }
    static TenantReadFence.Evidence round(GeneratedInitialNativeFixture f,Lease lease,String phase,List<Work<?>> works)throws Exception {
        var work=start(phase,()->{try(var admin=CacheTenantContext.administrative()) {
            return f.primary.readFence(f.ENTITY).closeAndDrain(lease,1,f.plan.manifestToken(),"",128,1000);
        }});works.add(work);return work.success();
    }
    static void await(CountDownLatch latch){try{assertTrue(latch.await(10,TimeUnit.SECONDS),"Callback physical lifetime bound");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError("Callback interrupted",interrupted);}}
    record Work<T>(Thread thread,FutureTask<T> task) {
        T success()throws Exception{try{return task.get(5,TimeUnit.SECONDS);}finally{join();}}
        void rejected()throws Exception {
            try {var error=assertThrows(ExecutionException.class,()->task.get(5,TimeUnit.SECONDS));
                var protection=assertInstanceOf(ProtectionException.class,error.getCause());assertNull(protection.getCause());
                assertEquals(0,protection.getSuppressed().length);assertFalse(protection.toString().contains(GeneratedInitialNativeFixture.SECRET));
            }finally{join();}
        }
        void join()throws Exception{assertTrue(thread.join(Duration.ofSeconds(5)),"Physical virtual thread did not exit");}
    }
    static <T> Work<T> start(String phase,Callable<T> call) {
        var task=new FutureTask<T>(()->{var event=new PhaseEvent();event.phase=phase;event.virtual=Thread.currentThread().isVirtual();event.begin();
            assertTrue(event.virtual);try{T value=call.call();event.completed=true;return value;}finally{event.end();event.commit();}});
        return new Work<>(Thread.ofVirtual().name("cachedb-generated-"+phase).start(task),task);
    }
    static void assertBlocked(GeneratedInitialNativeFixture f,long client)throws Exception {
        assertTrue(client>0);boolean observed=false;
        for(int round=0;round<100;round++) {
            String list=clientRow(f.peerRedis,client);assertTrue(list.length()<16384);
            for(String row:list.split("\n"))if(row.startsWith("id="+client+" ")&&row.contains("cmd=blpop")&&row.contains("flags=b"))observed=true;
            if(observed)break;Thread.sleep(10);
        }assertTrue(observed,"Owned physical connection was not actually blocked on Redis");
    }
    static String clientRow(JedisPooled redis,long client)throws Exception {
        var connection=redis.getPool().borrowObject(Duration.ofMillis(1000));connection.setHandlingPool(null);
        int timeout=connection.getSoTimeout();boolean healthy=false;
        try {
            connection.setSoTimeout(timeout>0?Math.min(2000,timeout):2000);
            connection.sendCommand(new CommandArguments(Protocol.Command.CLIENT).add("LIST").add("ID").add(Long.toString(client)));
            Object raw=connection.getOne();assertInstanceOf(byte[].class,raw);healthy=true;
            return new String((byte[])raw,java.nio.charset.StandardCharsets.UTF_8);
        }finally {
            if(healthy){connection.setSoTimeout(timeout);redis.getPool().returnResource(connection);}
            else{connection.setBroken();redis.getPool().returnBrokenResource(connection);}
        }
    }
    static final class ActualRedisPark implements Runnable {
        final GeneratedInitialNativeFixture fixture;final String key;
        final CountDownLatch dispatched=new CountDownLatch(1);final AtomicBoolean exited=new AtomicBoolean();final AtomicLong clientId=new AtomicLong();
        ActualRedisPark(GeneratedInitialNativeFixture f){fixture=f;key=f.prefix+":owned-physical-probe";}
        public void run() {
            Connection connection=null;int original=0;boolean healthy=false;var event=new RedisParkEvent();event.virtual=Thread.currentThread().isVirtual();
            try {
                connection=fixture.redis.getPool().borrowObject(Duration.ofMillis(1000));connection.setHandlingPool(null);
                original=connection.getSoTimeout();connection.setSoTimeout(original>0?Math.min(2000,original):2000);
                connection.sendCommand(new CommandArguments(Protocol.Command.CLIENT).add("SETNAME").add(fixture.prefix+":physical"));assertNotNull(connection.getOne());
                connection.sendCommand(new CommandArguments(Protocol.Command.CLIENT).add("ID"));clientId.set(((Number)connection.getOne()).longValue());event.clientId=clientId.get();
                event.begin();connection.sendCommand(new CommandArguments(Protocol.Command.BLPOP).add(key).add("1.5"));dispatched.countDown();
                Object result=connection.getOne();assertNotNull(result,"Physical Redis command timed out before explicit release");healthy=true;
            }catch(Exception failure){throw new AssertionError("Owned Redis park failed",failure);}
            finally {
                if(connection!=null){if(healthy){connection.setSoTimeout(original);fixture.redis.getPool().returnResource(connection);}else{connection.setBroken();fixture.redis.getPool().returnBrokenResource(connection);}}
                exited.set(true);event.physicalExit=true;event.end();event.commit();
            }
        }
    }
    static void inspect(Path output,Path report)throws Exception {
        int events=0,pins=0,cachedbPins=0,loss=0,parks=0,sockets=0;var phases=new TreeSet<String>();boolean setting=false,enabled=false;
        long pinnedId=FlightRecorder.getFlightRecorder().getEventTypes().stream().filter(t->t.getName().equals("jdk.VirtualThreadPinned")).findFirst().orElseThrow().getId();
        try(var file=new RecordingFile(output)) {
            while(file.hasMoreEvents()) {
                assertTrue(++events<=10000);var event=file.readEvent();String name=event.getEventType().getName();
                if(name.equals("jdk.DataLoss"))loss++;
                if(name.equals("jdk.VirtualThreadPinned")){pins++;if(event.getStackTrace()!=null&&event.getStackTrace().getFrames().stream().anyMatch(frame->frame.getMethod().getType().getName().startsWith("com.reactor.cachedb")))cachedbPins++;}
                if(name.equals("com.reactor.cachedb.f3.ProtectedNativePhase")){assertTrue(event.getBoolean("virtual"));phases.add(event.getString("phase"));}
                if(name.equals("com.reactor.cachedb.f3.ProtectedNativeRedisPark")) {
                    assertTrue(event.getBoolean("virtual"));assertTrue(event.getBoolean("physicalExit"));assertTrue(event.getDuration().compareTo(Duration.ofMillis(20))>=0);
                    assertNotNull(event.getStackTrace());assertTrue(event.getStackTrace().getFrames().stream().anyMatch(frame->frame.getMethod().getType().getName().equals("com.reactor.cachedb.redis.RedisTenantReadFence$Ticket")
                            &&frame.getMethod().getName().equals("read")));
                    parks++;
                }
                if(name.equals("jdk.SocketRead")&&event.getThread()!=null&&event.getThread().isVirtual()
                        &&event.getThread().getJavaName().equals("cachedb-generated-capture-physical-io")
                        &&event.getDuration().compareTo(Duration.ofMillis(20))>=0&&event.getStackTrace()!=null
                        &&event.getStackTrace().getFrames().stream().anyMatch(frame->frame.getMethod().getType().getName().equals(ActualRedisPark.class.getName())))sockets++;
                if(name.equals("jdk.ActiveSetting")&&event.getLong("id")==pinnedId) {
                    if(event.getString("name").equals("threshold")&&event.getString("value").equals("20 ms"))setting=true;
                    if(event.getString("name").equals("enabled")&&event.getString("value").equals("true"))enabled=true;
                }
            }
        }
        Files.writeString(report,"{\"events\":"+events+",\"pins\":"+pins+",\"cachedbPins\":"+cachedbPins+",\"dataLoss\":"+loss
                +",\"actualRedisParks\":"+parks+",\"virtualSocketReads\":"+sockets+",\"pinEventEnabledObserved\":"+enabled
                +",\"threshold20msObserved\":"+setting+",\"phaseCount\":"+phases.size()+"}\n");
        assertEquals(0,loss);assertEquals(0,cachedbPins);assertEquals(1,parks);assertTrue(sockets>=1);assertTrue(setting);assertTrue(enabled);assertTrue(phases.size()>=10);
    }
}
