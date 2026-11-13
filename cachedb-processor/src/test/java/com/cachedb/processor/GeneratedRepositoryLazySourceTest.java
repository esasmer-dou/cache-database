package com.reactor.cachedb.processor;

import static com.reactor.cachedb.processor.EncryptionCompilerFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.api.*;
import com.reactor.cachedb.core.cache.*;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.crypto.CacheEncryptionException;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.repository.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.core.testing.InMemoryCacheDatabase;
import com.reactor.cachedb.jdbc.NativeProducerInitialFixture;
import com.reactor.cachedb.redis.*;
import com.reactor.cachedb.starter.CacheDatabase;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;

@Timeout(60)
class GeneratedRepositoryLazySourceTest {
    @TempDir Path temp;

    private static Map<String,String> sources(boolean encrypted,boolean purge,boolean explicitSql){
        var sources=new HashMap<>(recordSources(encrypted));
        sources.compute("sample/ProtectedRow.java",(path,row)->{
            if(!encrypted)row=row.replace("@CacheEncrypted(blindIndex=true)","")
                    .replace("@CacheEncrypted(scope=EncryptionScope.REDIS_AND_SOURCE,blindIndex=true)","")
                    .replace("@CacheEncrypted","");
            return purge?row.replace("@CacheEntity(","@CacheEntity(tenantPurgeEnabled=true, "):row;
        });
        sources.put("sample/Rows.java","""
                package sample;
                import com.reactor.cachedb.annotations.*;
                import com.reactor.cachedb.core.repository.*;
                @CacheRepository(entity=ProtectedRow.class,springBean=false)
                public interface Rows extends CacheDbRepository<ProtectedRow,Long>{
                    @HotRoute("hot") @CacheRouteQuery(fixedLimit=9,predicates=@CachePredicate(field="status",constants="OPEN"))
                    HotWindow<ProtectedRow> hot();
                    %s
                }
                """.formatted(explicitSql?"""
                        @SourceRoute("archive") @CacheRouteQuery(fixedLimit=9)
                        SourceWindow<ProtectedRow> archive();
                        @SourceSql(value="SELECT id,tenant_id,status,contact,amount,hidden FROM protected_rows",maxRows=9)
                        SourceWindow<ProtectedRow> audit();
                        """:""));
        return sources;
    }

    @SuppressWarnings("unchecked")
    private static CacheDbRepository<Object,Long> generated(Loaded fixture,CacheSession session)throws Exception{
        // Test-only generated-class loading, matching the existing processor fixture harness.
        return (CacheDbRepository<Object,Long>)fixture.loader().loadClass("sample.RowsCacheDbImplementation")
                .getConstructor(CacheSession.class).newInstance(session);
    }

    @Test void bindingsAreLazySingleFlightCachedAndUseEachRequestsTenant()throws Exception{
        try(var fixture=load(compile(temp,sources(false,false,true)))){
            var session=new CountingSession(fixture.entity());
            var repository=generated(fixture,session);
            var hot=repository.getClass().getMethod("hot");
            var audit=repository.getClass().getMethod("audit");
            var archive=repository.getClass().getMethod("archive");
            assertFalse(repository.findHotById(1L).isHit());
            assertTrue(((HotWindow<?>)hot.invoke(repository)).items().isEmpty());
            assertEquals(0,session.sourceBindings.get());
            assertEquals(0,session.sqlBindings.get());
            try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
                var start=new CountDownLatch(1);
                var pending=new ArrayList<Future<?>>();
                for(int i=0;i<64;i++){
                    String tenant="caller-"+i;
                    pending.add(workers.submit(()->{
                        assertTrue(start.await(5,TimeUnit.SECONDS));
                        try(var scope=CacheTenantContext.withTenant(tenant)){
                            assertSame(fixture.entity(),repository.findSourceById(1L).orElseThrow());
                            assertEquals(List.of(fixture.entity()),((SourceWindow<?>)audit.invoke(repository)).items());
                        }
                        return null;
                    }));
                }
                start.countDown();
                for(var future:pending)future.get(10,TimeUnit.SECONDS);
            }
            assertEquals(1,session.sourceBindings.get());
            assertEquals(1,session.sqlBindings.get());
            assertEquals(64,session.sourceTenants.size());
            assertEquals(64,session.sqlTenants.size());
            for(int i=0;i<64;i++){
                assertTrue(session.sourceTenants.contains("caller-"+i));
                assertTrue(session.sqlTenants.contains("caller-"+i));
            }
            try(var tenant=CacheTenantContext.withTenant("later")){
                assertEquals(List.of(fixture.entity()),((SourceWindow<?>)archive.invoke(repository)).items());
                assertEquals(List.of(fixture.entity()),((SourceWindow<?>)audit.invoke(repository)).items());
            }
            assertTrue(session.sourceTenants.contains("later"));
            assertTrue(session.sqlTenants.contains("later"));
            assertEquals(1,session.sourceBindings.get());
            assertEquals(1,session.sqlBindings.get());
        }
    }

    @Test void failedSourceInitializationIsNotCachedAndReleasesItsLock()throws Exception{
        try(var fixture=load(compile(temp,sources(false,false,true)))){
            var session=new CountingSession(fixture.entity());
            session.failSource=true;session.failSql=true;
            var repository=generated(fixture,session);
            var audit=repository.getClass().getMethod("audit");
            assertEquals(0,session.sourceBindings.get());
            assertEquals(0,session.sqlBindings.get());
            assertSame(session.failure,assertThrows(IllegalStateException.class,()->repository.findSourceById(1L)));
            assertSame(session.failure,assertThrows(InvocationTargetException.class,()->audit.invoke(repository)).getCause());
            session.failSource=false;session.failSql=false;
            var worker=Executors.newSingleThreadExecutor(Thread.ofVirtual().inheritInheritableThreadLocals(false).factory());
            try{
                worker.submit(()->{
                    assertSame(fixture.entity(),repository.findSourceById(1L).orElseThrow());
                    assertEquals(List.of(fixture.entity()),((SourceWindow<?>)audit.invoke(repository)).items());
                    return null;
                }).get(5,TimeUnit.SECONDS);
            }finally{
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(1,TimeUnit.SECONDS),"Failed initialization must release the binding lock");
            }
            repository.findSourceById(1L);audit.invoke(repository);
            assertEquals(2,session.sourceBindings.get());
            assertEquals(2,session.sqlBindings.get());
        }
    }

    @Test @EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
    void encryptedAndProtectedGeneratedHotBindingsNeverAcquireJdbcAndSourceGuardsRemainStrict()throws Exception{
        for(boolean purge:List.of(false,true)){
            var compilation=compile(temp,sources(true,purge,false));
            assertTrue(compilation.success(),compilation.diagnostics());
            assertFalse(compilation.source("sample/RowsCacheDbImplementation.java").contains("sourceSqlRepository"));
            String prefix="lazy_source_"+UUID.randomUUID().toString().replace("-","");
            String library=prefix+"_functions";
            var functions=RedisFunctionsConfig.builder().libraryName(library).upsertFunctionName(library+"_upsert")
                    .deleteFunctionName(library+"_delete").compactionCompleteFunctionName(library+"_complete").build();
            var config=CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                    .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build()).redisFunctions(functions)
                    .redisGuardrail(RedisGuardrailConfig.builder().enabled(false).build())
                    .writeBehind(WriteBehindConfig.builder().enabled(false).streamKey(prefix+":stream")
                            .deadLetterStreamKey(prefix+":dlq").compactionStreamKey(prefix+":compaction").build()).build();
            var options=CacheRuntimeOptions.builder().keyProvider(keys("k1",key("k1",1))).build();
            var source=new ProtectedProducerRootRedisTest.NoSql();
            try(var fixture=load(compilation);
                    var initial=purge?NativeProducerInitialFixture.create(fixture.metadata(),source):null;
                    var redis=new JedisPooled(
                    System.getProperty("cachedb.snapshot.redis.host","127.0.0.1"),
                    Integer.getInteger("cachedb.snapshot.redis.port",16383))){
                try(var database=new CacheDatabase(redis,initial==null?source:initial,config,options)){
                    database.start();
                    var metadata=fixture.metadata();
                    var policy=CachePolicy.builder().hotEntityLimit(16).build();
                    database.register(metadata,fixture.unbound(),policy);
                    if(purge){
                        initial.bootstrap(database,metadata,"tenant-a",8);
                    }
                    var repository=generated(fixture,database); // No tenant is captured during construction.
                    assertEquals(0,source.calls.get());
                    redis.set(database.redisKeyStrategy().versionKey(metadata.redisNamespace(),1L)+":tenant","tenant-a");
                    try(var tenant=CacheTenantContext.withTenant("tenant-a")){
                        repository.save(fixture.entity());
                        assertEquals(fixture.entity(),repository.findHotById(1L).value());
                        var hot=repository.getClass().getMethod("hot");
                        if(purge){
                            assertInstanceOf(ProtectionException.class,assertThrows(InvocationTargetException.class,
                                    ()->hot.invoke(repository)).getCause());
                            assertEquals(ProtectionException.Code.UNWIRED_PROTOCOL,assertThrows(ProtectionException.class,
                                    ()->repository.findSourceById(1L)).code());
                        }else{
                            assertEquals(List.of(fixture.entity()),((HotWindow<?>)hot.invoke(repository)).items());
                            var missing=assertThrows(IllegalStateException.class,()->repository.findSourceById(1L));
                            assertEquals("No durable source by-id loader is registered for "+metadata.entityName(),missing.getMessage());
                            assertNull(missing.getCause());
                            assertEquals(0,source.calls.get());
                        }
                        var opaque=assertThrows(CacheEncryptionException.class,()->database.sourceSqlRepository(fixture.unbound()));
                        assertEquals(CacheEncryptionException.Code.UNSUPPORTED_SOURCE_OPERATION,opaque.code());
                        assertEquals(0,source.calls.get());
                    }
                }finally{
                    String cursor="0";int pages=0;
                    do{
                        if(++pages>128)throw new AssertionError("Fixture cleanup bound");
                        var page=redis.scan(cursor,new ScanParams().match(prefix+":*").count(128));cursor=page.getCursor();
                        if(!page.getResult().isEmpty())redis.del(page.getResult().toArray(String[]::new));
                    }while(!"0".equals(cursor));
                    redis.functionDelete(library);redis.del("cachedb:function-library:"+library+":marker");
                }
            }
        }
    }

    private static final class CountingSession implements CacheSession{
        final InMemoryCacheDatabase memory=new InMemoryCacheDatabase();
        final Object row;
        final AtomicInteger sourceBindings=new AtomicInteger(),sqlBindings=new AtomicInteger();
        final Queue<String> sourceTenants=new ConcurrentLinkedQueue<>(),sqlTenants=new ConcurrentLinkedQueue<>();
        final IllegalStateException failure=new IllegalStateException("Source binding unavailable");
        boolean failSource,failSql;
        CountingSession(Object row){this.row=row;}
        public <T,ID> EntityRepository<T,ID> repository(EntityMetadata<T,ID> metadata,EntityCodec<T> codec){return memory.repository(metadata,codec);}
        public <T,ID> EntityRepository<T,ID> repository(EntityMetadata<T,ID> metadata,EntityCodec<T> codec,CachePolicy policy){return memory.repository(metadata,codec,policy);}
        @SuppressWarnings("unchecked") private <T>T row(){return (T)row;}
        private static String tenant(){return CacheTenantContext.currentTenant().orElse("none");}
        public <T,ID> SourceRepository<T,ID> sourceRepository(EntityMetadata<T,ID> metadata,EntityCodec<T> codec){
            sourceBindings.incrementAndGet();if(failSource)throw failure;
            return new SourceRepository<>(){
                public Optional<T> findById(ID id){sourceTenants.add(tenant());return Optional.of(row());}
                public List<T> findPage(PageWindow page){sourceTenants.add(tenant());T value=CountingSession.this.row();return List.of(value);}
                public List<T> query(com.reactor.cachedb.core.query.QuerySpec query){sourceTenants.add(tenant());T value=CountingSession.this.row();return List.of(value);}
            };
        }
        public <T> SourceSqlRepository<T> sourceSqlRepository(EntityCodec<T> codec){
            sqlBindings.incrementAndGet();if(failSql)throw failure;
            return query->{sqlTenants.add(tenant());assertEquals(9,query.maxRows());T value=row();return List.of(value);};
        }
    }
}
