package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import jdk.jfr.*;
import jdk.jfr.consumer.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Standalone current-main J0 qualification: no F3 enrollment class or function library is required. */
@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
@Timeout(60)
class CacheRepositoryInitializationVirtualThreadRedisTest {
    @Name("com.reactor.cachedb.j0.RepositoryConstructorRedisIO")
    @StackTrace(true)
    static final class ConstructionIO extends Event {
        String phase; boolean virtual; boolean constructor; boolean completed;
    }

    @Test void actualUnprotectedRootConstructorsParkOnRedisWithoutPinningCarriers() throws Exception {
        assertTrue(FlightRecorder.isAvailable());
        var settings=Configuration.create(Path.of(System.getProperty("cachedb.j0.jfr.settings","../tools/ci/virtual-threads.jfc")));
        assertEquals("true",settings.getSettings().get("jdk.VirtualThreadPinned#enabled"));
        assertEquals("true",settings.getSettings().get("jdk.VirtualThreadPinned#stackTrace"));
        assertEquals("20 ms",settings.getSettings().get("jdk.VirtualThreadPinned#threshold"));
        Path directory=Path.of(System.getProperty("cachedb.j0.jfr.output","target/repository-initialization-jfr"));
        Files.createDirectories(directory);Path output=directory.resolve("unprotected-construction.jfr");
        var parked=new AtomicInteger();
        try(var recording=new Recording(settings)) {
            recording.enable(ConstructionIO.class).withThreshold(Duration.ZERO).withStackTrace();
            recording.setMaxSize(32L*1024*1024);recording.setDuration(Duration.ofSeconds(20));recording.start();
            for(String phase:List.of("unprotected-a","unprotected-b")) {
                try(var f=new CacheRepositoryInitializationRedisTest.Fixture()) {
                    var metadata=f.register("UnprotectedConstructor");
                    f.operations.reenter.set(() -> {
                        var event=new ConstructionIO();event.phase=phase;event.virtual=Thread.currentThread().isVirtual();
                        event.constructor=StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                                frame.getClassName().equals(DefaultCacheSession.class.getName()) && frame.getMethodName().equals("createRepository")));
                        assertTrue(event.virtual);assertTrue(event.constructor);event.begin();
                        try {
                            // Real bounded Redis socket parking; no shared server pause or synthetic pin event.
                            assertNull(f.client.blpop(0.08d,f.prefix+":empty-jfr-probe"));
                            parked.incrementAndGet();event.completed=true;
                        }finally{event.end();event.commit();}
                    });
                    var task=new FutureTask<>(() -> {
                        assertTrue(Thread.currentThread().isVirtual());
                        var repository=f.repository(metadata,com.reactor.cachedb.core.cache.CachePolicy.defaults());
                        assertSame(repository,f.repository(metadata,com.reactor.cachedb.core.cache.CachePolicy.defaults()));return repository;
                    });
                    var thread=Thread.ofVirtual().name("cachedb-j0-"+phase).start(task);
                    try{assertNotNull(task.get(5,TimeUnit.SECONDS));}
                    finally{if(!thread.join(Duration.ofSeconds(5)))throw new AssertionError("Physical JFR constructor did not exit");}
                    assertEquals(0,f.source.calls.get());
                }
            }
            assertEquals(2,parked.get());recording.stop();recording.dump(output);
        }
        int pins=0,cachedbPins=0,losses=0,count=0;var phases=new TreeSet<String>();
        try(var file=new RecordingFile(output)) {
            while(file.hasMoreEvents()) {
                if(++count>10000)throw new AssertionError("JFR event limit");
                var event=file.readEvent();String name=event.getEventType().getName();
                if(name.equals("jdk.DataLoss"))losses++;
                else if(name.equals("jdk.VirtualThreadPinned")) {
                    pins++;assertNotNull(event.getStackTrace());
                    if(event.getStackTrace().getFrames().stream().anyMatch(frame ->
                            frame.getMethod().getType().getName().startsWith("com.reactor.cachedb.")))cachedbPins++;
                }else if(name.equals("com.reactor.cachedb.j0.RepositoryConstructorRedisIO")) {
                    assertTrue(event.getBoolean("virtual"));assertTrue(event.getThread().isVirtual());
                    assertTrue(event.getBoolean("constructor"));assertTrue(event.getBoolean("completed"));
                    assertTrue(phases.add(event.getString("phase")));assertNotNull(event.getStackTrace());
                    assertTrue(event.getStackTrace().getFrames().stream().anyMatch(frame ->
                            frame.getMethod().getType().getName().equals(DefaultCacheSession.class.getName())
                                    &&frame.getMethod().getName().equals("createRepository")));
                }
            }
        }
        assertEquals(Set.of("unprotected-a","unprotected-b"),phases);assertEquals(0,losses);
        Files.writeString(directory.resolve("unprotected-construction.json"),"{\"virtualConstructors\":2,\"realRedisParkingCalls\":2,"
                +"\"pinThresholdMillis\":20,\"pins\":"+pins+",\"cachedbPins\":"+cachedbPins+",\"dataLoss\":"+losses+"}\n");
        System.out.println("J0_UNPROTECTED_CONSTRUCTION phases="+phases+" realRedisParkingCalls=2 pins="+pins+" cachedbPins="+cachedbPins+" dataLoss="+losses);
        assertEquals(0,cachedbPins,"Actual CacheDB constructor carrier pinning; see JFR");
    }
}
