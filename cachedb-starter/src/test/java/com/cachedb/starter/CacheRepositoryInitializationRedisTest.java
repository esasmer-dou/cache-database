package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.redis.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;

/** Actual unprotected root construction, without private reflection or an alternative repository cache. */
@EnabledIfSystemProperty(named="cachedb.snapshot.redis", matches="true")
@Timeout(60)
class CacheRepositoryInitializationRedisTest {
    private static final CachePolicy POLICY = CachePolicy.defaults();
    record Row(String id, String category) {}
    private static final EntityCodec<Row> CODEC = new EntityCodec<>() {
        public String toRedisValue(Row row) { return row.id() + "|" + row.category(); }
        public Row fromRedisValue(String value) { var parts = value.split("\\|", -1); return new Row(parts[0], parts[1]); }
        public Map<String,Object> toColumns(Row row) { return Map.of("id",row.id(),"category",row.category()); }
    };
    record Metadata(String entityName, String redisNamespace, boolean tenantPurgeEnabled) implements EntityMetadata<Row,String> {
        Metadata(String name) { this(name, name.toLowerCase(Locale.ROOT), false); }
        public String tableName() { return redisNamespace; }
        public String idColumn() { return "id"; }
        public Class<Row> entityType() { return Row.class; }
        public Function<Row,String> idAccessor() { return Row::id; }
        public List<String> columns() { return List.of("id","category"); }
        public Set<String> indexedColumns() { return Set.of("id","category"); }
        public Map<String,List<String>> compositeExactIndexes() { return Map.of("category_id",List.of("category","id")); }
        public Map<String,String> columnTypes() { return Map.of("id","java.lang.String","category","java.lang.String"); }
        public List<RelationDefinition> relations() { return List.of(); }
    }

    @Test void concurrentSameKeyPublishesExactlyOneRepositoryAndCachedHitsPerformNoIo() throws Exception {
        try (var f = new Fixture()) {
            var metadata = f.register("SameKey"); var gate = f.block(metadata);
            var ready = new CountDownLatch(16); var tasks = new ArrayList<Task<EntityRepository<Row,String>>>();
            try {
                for (int i=0;i<16;i++) tasks.add(new Task<>(() -> { ready.countDown(); return f.repository(metadata,POLICY); }));
                assertTrue(ready.await(5,TimeUnit.SECONDS)); assertTrue(gate.entered.await(5,TimeUnit.SECONDS));
                assertEquals(1,gate.calls.get()); assertTrue(tasks.stream().noneMatch(task -> task.future.isDone()));
                gate.release.countDown();
                var repository = tasks.getFirst().get();
                for (var task : tasks) assertSame(repository,task.get());
                assertEquals(1,gate.calls.get());
                int commands=f.operations.commands.get(), evals=f.operations.evals.get();
                for(int i=0;i<128;i++) assertSame(repository,f.repository(metadata,POLICY));
                assertEquals(commands,f.operations.commands.get()); assertEquals(evals,f.operations.evals.get());
            } finally {
                gate.release.countDown(); for(var task:tasks)task.close();
            }
            assertEquals(0,f.source.calls.get());
        }
    }

    @Test void initializedHitDoesNotWaitBehindSameStripeCustomPolicyConstruction() throws Exception {
        try (var f = new Fixture()) {
            var metadata=f.register("PolicyKey"); var first=f.repository(metadata,POLICY);
            // The historical CachePolicy hash deliberately excludes extension fields; equality does not.
            var custom=new CachePolicy(POLICY.hotEntityLimit(),POLICY.pageSize(),POLICY.lruEvictionEnabled(),
                    POLICY.entityTtlSeconds(),POLICY.pageTtlSeconds(),POLICY.hotPolicy(),
                    POLICY.accessDebounceMillis()+1,POLICY.recentTouchSlots(),POLICY.nearCachePolicy(),POLICY.compressionThresholdBytes());
            assertEquals(POLICY.hashCode(),custom.hashCode()); assertNotEquals(POLICY,custom);
            f.client.del(f.database.redisKeyStrategy().compositeIndexSignatureKey(metadata.redisNamespace()));
            var gate=f.block(metadata);
            try(var cold=new Task<>(() -> f.repository(metadata,custom))) {
                try {
                    assertTrue(gate.entered.await(5,TimeUnit.SECONDS));
                    try(var hit=new Task<>(() -> f.repository(metadata,POLICY))) { assertSame(first,hit.get()); }
                    assertFalse(cold.future.isDone()); assertEquals(1,gate.calls.get());
                } finally {gate.release.countDown();}
                var second=cold.get(); assertNotSame(first,second);
                assertSame(second,f.repository(metadata,custom)); assertSame(first,f.repository(metadata,POLICY));
            }
        }
    }

    @Test void differentStripesInitializeWhileAnotherPhysicalConstructorIsStillRunning() throws Exception {
        try(var f=new Fixture()) {
            var first=f.register("ParallelA"); Metadata second=null;
            for(int i=0;i<64;i++) {
                var candidate=new Metadata("ParallelB"+i);
                if(stripe(candidate.entityName())!=stripe(first.entityName())) {second=f.register(candidate.entityName());break;}
            }
            assertNotNull(second); final Metadata independent=second; var gate=f.block(first);
            try(var blocked=new Task<>(() -> f.repository(first,POLICY))) {
                try {
                    assertTrue(gate.entered.await(5,TimeUnit.SECONDS));
                    try(var other=new Task<>(() -> f.repository(independent,POLICY))) {
                        assertSame(other.get(),f.repository(independent,POLICY));
                    }
                    assertFalse(blocked.future.isDone()); assertEquals(1,gate.calls.get());
                } finally {gate.release.countDown();}
                assertSame(blocked.get(),f.repository(first,POLICY));
            }
        }
    }

    @Test void runtimeExceptionAndErrorDoNotPublishOrRetainAConstructionLock() throws Exception {
        for(boolean fatal:List.of(false,true)) try(var f=new Fixture()) {
            var metadata=f.register("RetryKey");
            Throwable expected=fatal?new AssertionError("Fixture construction failed"):new IllegalStateException("Fixture construction failed");
            f.operations.failure.set(expected);
            try(var failed=new Task<>(() -> f.repository(metadata,POLICY))) {
                assertSame(expected,assertThrows(ExecutionException.class,failed::get).getCause());
            }
            assertEquals(1,f.operations.evals.get());
            // Another virtual thread must be able to acquire the released stripe and retry the SAME key.
            try(var retry=new Task<>(() -> f.repository(metadata,POLICY))) {
                var created=retry.get(); assertSame(created,f.repository(metadata,POLICY));
            }
            assertEquals(2,f.operations.evals.get()); assertEquals(0,f.source.calls.get());
        }
    }

    @Test void cacheHitsStillValidateMetadataAndUseTheRegisteredCodec() {
        try(var f=new Fixture()) {
            var metadata=f.register("ValidatedHit"); var repository=f.repository(metadata,POLICY);
            int commands=f.operations.commands.get(), evals=f.operations.evals.get();
            assertThrows(IllegalArgumentException.class,() -> f.database.repository(
                    new Metadata(metadata.entityName(),"foreign_namespace",false),CODEC,POLICY));
            assertThrows(IllegalArgumentException.class,() -> f.database.repository(
                    new Metadata(metadata.entityName(),metadata.redisNamespace(),true),CODEC,POLICY));
            var bound=f.database.runtimeCodec(metadata,CODEC);
            EntityCodec<Row> ignored=new EntityCodec<>() {
                public String toRedisValue(Row row){throw new AssertionError("Unregistered codec used");}
                public Row fromRedisValue(String value){throw new AssertionError("Unregistered codec used");}
                public Map<String,Object> toColumns(Row row){throw new AssertionError("Unregistered codec used");}
            };
            assertSame(repository,f.database.repository(metadata,ignored,POLICY));
            assertSame(bound,f.database.runtimeCodec(metadata,ignored));
            assertEquals(commands,f.operations.commands.get()); assertEquals(evals,f.operations.evals.get());
        }
    }

    @Test void sameKeyRedisCallbackRecursionFailsPredictablyAndAnotherThreadCanRetry() throws Exception {
        try(var f=new Fixture()) {
            var metadata=f.register("RecursiveKey");
            f.operations.reenter.set(() -> f.repository(metadata,POLICY));
            try(var first=new Task<>(() -> f.repository(metadata,POLICY))) {
                var cause=assertThrows(ExecutionException.class,first::get).getCause();
                var rejected=assertInstanceOf(IllegalStateException.class,cause);
                assertEquals("Recursive repository initialization",rejected.getMessage());
            }
            assertEquals(1,f.operations.evals.get());
            try(var retry=new Task<>(() -> f.repository(metadata,POLICY))) {
                var repository=retry.get();assertSame(repository,f.repository(metadata,POLICY));
            }
            assertEquals(2,f.operations.evals.get());assertEquals(0,f.source.calls.get());
        }
    }

    @Test void acyclicNestedSameStripeAndDifferentPolicyPreserveBothRepositoryIdentities() throws Exception {
        try(var f=new Fixture()) {
            var metadata=f.register("NestedPolicy");var custom=customPolicy();
            assertNotEquals(POLICY,custom);assertEquals(POLICY.hashCode(),custom.hashCode());
            var nested=new AtomicReference<EntityRepository<Row,String>>();
            f.operations.reenter.set(() -> nested.set(f.repository(metadata,custom)));
            try(var task=new Task<>(() -> f.repository(metadata,POLICY))) {
                var outer=task.get();assertNotNull(nested.get());assertNotSame(outer,nested.get());
                assertSame(outer,f.repository(metadata,POLICY));assertSame(nested.get(),f.repository(metadata,custom));
            }
            assertEquals(2,f.operations.evals.get());assertEquals(0,f.source.calls.get());
        }
    }

    @Test void acyclicNestedDifferentFreeStripeAndAlreadyCachedDependencyRemainSupported() throws Exception {
        try(var f=new Fixture()) {
            var outer=f.register("NestedA");var inner=f.register(otherStripeName(outer.entityName()));
            var cached=f.register("NestedCached");var dependency=f.repository(cached,POLICY);
            var nested=new AtomicReference<EntityRepository<Row,String>>();
            f.operations.reenter.set(() -> {
                assertSame(dependency,f.repository(cached,POLICY));
                nested.set(f.repository(inner,POLICY));
            });
            try(var task=new Task<>(() -> f.repository(outer,POLICY))) {
                assertSame(task.get(),f.repository(outer,POLICY));assertSame(nested.get(),f.repository(inner,POLICY));
            }
            assertEquals(3,f.operations.evals.get());assertEquals(0,f.source.calls.get());
        }
    }

    @Test void exactSameKeyAncestorStillRejectsAfterAnAllowedDifferentKeyNestedMiss() throws Exception {
        try(var f=new Fixture()) {
            var a=f.register("AncestorA");var b=f.register(otherStripeName(a.entityName()));
            f.operations.reenter.set(() -> {
                f.operations.reenter.set(() -> f.repository(a,POLICY));
                f.repository(b,POLICY);
            });
            try(var task=new Task<>(() -> {
                var rejected=assertThrows(IllegalStateException.class,() -> f.repository(a,POLICY));
                assertEquals("Recursive repository initialization",rejected.getMessage());
                // Both ancestor frames and both stripe locks must have cleared on this exact thread.
                f.operations.reenter.set(() -> f.repository(b,POLICY));
                return f.repository(a,POLICY);
            })) {assertSame(task.get(),f.repository(a,POLICY));}
            assertEquals(4,f.operations.evals.get());assertEquals(0,f.source.calls.get());
        }
    }

    @Test void oppositeThreadNestedStripeCycleRejectsWithoutWaitingAndBothKeysCanRetry() throws Exception {
        try(var f=new Fixture()) {
            var a=f.register("CycleA");var b=f.register(otherStripeName(a.entityName()));
            var entered=new CountDownLatch(2);var attempted=new CountDownLatch(2);
            java.util.function.Consumer<Metadata> enter=other -> {
                entered.countDown();await(entered);
                IllegalStateException rejected;
                try {
                    rejected=assertThrows(IllegalStateException.class,() -> f.repository(other,POLICY));
                    assertEquals("Nested repository initialization is contended",rejected.getMessage());
                }finally{attempted.countDown();await(attempted);}
                throw rejected;
            };
            f.operations.callbacks=Map.of(f.signature(a),() -> enter.accept(b),f.signature(b),() -> enter.accept(a));
            try(var left=new Task<>(() -> f.repository(a,POLICY));var right=new Task<>(() -> f.repository(b,POLICY))) {
                for(var task:List.of(left,right)) {
                    var cause=assertThrows(ExecutionException.class,task::get).getCause();
                    assertEquals("Nested repository initialization is contended",assertInstanceOf(IllegalStateException.class,cause).getMessage());
                }
            }finally{f.operations.callbacks=Map.of();}
            try(var left=new Task<>(() -> f.repository(a,POLICY));var right=new Task<>(() -> f.repository(b,POLICY))) {
                assertSame(left.get(),f.repository(a,POLICY));assertSame(right.get(),f.repository(b,POLICY));
            }
            assertEquals(4,f.operations.evals.get());assertEquals(0,f.source.calls.get());
        }
    }

    @Test void boundedFrameCapacityRejectsBeforeIoThenAllSlotsAreReusableOnTheSameThread() throws Exception {
        try(var f=new Fixture()) {
            var chain=new ArrayList<Metadata>();int wanted=stripe("DepthSeed");
            for(int i=0;i<16384 && chain.size()<65;i++) {
                String name="Depth"+i;if(stripe(name)==wanted)chain.add(f.register(name));
            }
            assertEquals(65,chain.size());
            class Descend implements Runnable {
                final int limit;int next=1;
                Descend(int limit){this.limit=limit;}
                public void run(){if(next<limit){var child=chain.get(next++);f.operations.reenter.set(this);f.repository(child,POLICY);}}
            }
            try(var task=new Task<>(() -> {
                f.operations.reenter.set(new Descend(65));
                var rejected=assertThrows(IllegalStateException.class,() -> f.repository(chain.getFirst(),POLICY));
                assertEquals("Repository initialization capacity exhausted",rejected.getMessage());
                assertEquals(64,f.operations.evals.get());
                f.operations.reenter.set(new Descend(64));
                return f.repository(chain.getFirst(),POLICY);
            })) {assertSame(task.get(),f.repository(chain.getFirst(),POLICY));}
            assertEquals(128,f.operations.evals.get());
            try(var retry=new Task<>(() -> f.repository(chain.getLast(),POLICY))) {
                assertSame(retry.get(),f.repository(chain.getLast(),POLICY));
            }
            assertEquals(129,f.operations.evals.get());assertEquals(0,f.source.calls.get());
        }
    }

    private static CachePolicy customPolicy() {
        return new CachePolicy(POLICY.hotEntityLimit(),POLICY.pageSize(),POLICY.lruEvictionEnabled(),
                POLICY.entityTtlSeconds(),POLICY.pageTtlSeconds(),POLICY.hotPolicy(),POLICY.accessDebounceMillis()+1,
                POLICY.recentTouchSlots(),POLICY.nearCachePolicy(),POLICY.compressionThresholdBytes());
    }

    private static String otherStripeName(String name) {
        for(int i=0;i<128;i++){String other=name+"Other"+i;if(stripe(other)!=stripe(name))return other;}
        throw new AssertionError("Fixture could not select a different stripe");
    }

    private static void await(CountDownLatch latch) {
        try {if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("Nested constructor fixture deadline");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError("Fixture interrupted");}
    }

    // Same value-tuple hashing as the actual private cache key, without reflective access to production state.
    private record Key(String entityName, CachePolicy cachePolicy) {}
    private static int stripe(String name) {int hash=new Key(name,POLICY).hashCode();return (hash^(hash>>>16))&63;}
    private static final class Task<T> implements AutoCloseable {
        final FutureTask<T> future; final Thread thread;
        Task(Callable<T> action) {future=new FutureTask<>(action);thread=Thread.ofVirtual().name("cachedb-init-proof").start(future);}
        T get() throws Exception {return future.get(5,TimeUnit.SECONDS);}
        public void close() throws Exception {
            if(!thread.join(Duration.ofSeconds(5))) throw new AssertionError("Physical construction thread did not exit");
        }
    }
    private static final class Gate {
        final String signature; final AtomicInteger calls=new AtomicInteger();
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        Gate(String signature){this.signature=signature;}
    }
    static final class Operations implements RedisClientOperations {
        final JedisPooled client; final AtomicInteger commands=new AtomicInteger(),evals=new AtomicInteger();
        final AtomicReference<Throwable> failure=new AtomicReference<>(); volatile Gate gate;
        final AtomicReference<Runnable> reenter=new AtomicReference<>();
        volatile Map<String,Runnable> callbacks=Map.of();
        Operations(JedisPooled client){this.client=client;}
        public UnifiedJedis commands(){commands.incrementAndGet();return client;}
        public JedisPooled pooledClient(){return client;}
        public int physicalPoolCapacity(){return client.getPool().getMaxTotal();}
        public Object eval(String script,List<String> keys,List<String> args) {
            evals.incrementAndGet();
            var callback=reenter.getAndSet(null);if(callback!=null)callback.run();
            var keyed=keys.isEmpty()?null:callbacks.get(keys.getFirst());if(keyed!=null)keyed.run();
            Throwable injected=failure.getAndSet(null);
            if(injected instanceof Error fatal)throw fatal;
            if(injected instanceof RuntimeException runtime)throw runtime;
            var waiting=gate;
            if(waiting!=null && !keys.isEmpty() && keys.getFirst().equals(waiting.signature)) {
                waiting.calls.incrementAndGet();waiting.entered.countDown();
                try {if(!waiting.release.await(5,TimeUnit.SECONDS))throw new AssertionError("Construction fixture release timeout");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError("Fixture interrupted");}
            }
            return client.eval(script,keys,args);
        }
    }
    static final class Fixture implements AutoCloseable {
        final String prefix="repository_init_"+UUID.randomUUID().toString().replace("-","");
        final JedisPooled client=new JedisPooled(System.getProperty("cachedb.snapshot.redis.host","127.0.0.1"),
                Integer.getInteger("cachedb.snapshot.redis.port",16383));
        final Operations operations=new Operations(client); final NoSql source=new NoSql(); final CacheDatabase database;
        Fixture() {
            var config=CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                    .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                    .redisFunctions(RedisFunctionsConfig.builder().enabled(false).build())
                    .redisGuardrail(RedisGuardrailConfig.builder().enabled(false).build())
                    .writeBehind(WriteBehindConfig.builder().enabled(false).build()).build();
            database=new CacheDatabase(operations,source,config);database.start();
        }
        Metadata register(String name){var metadata=new Metadata(name);database.register(metadata,CODEC,POLICY);return metadata;}
        EntityRepository<Row,String> repository(Metadata metadata,CachePolicy policy){return database.repository(metadata,CODEC,policy);}
        String signature(Metadata metadata){return database.redisKeyStrategy().compositeIndexSignatureKey(metadata.redisNamespace());}
        Gate block(Metadata metadata){var gate=new Gate(database.redisKeyStrategy().compositeIndexSignatureKey(metadata.redisNamespace()));operations.gate=gate;return gate;}
        public void close() {
            try {database.close();}
            finally {
                try {
                    var owned=new HashSet<String>();String cursor="0";int pages=0;
                    do {
                        if(++pages>1000)throw new AssertionError("Fixture cleanup page bound");
                        var page=client.scan(cursor,new redis.clients.jedis.params.ScanParams().match(prefix+":*").count(128));
                        owned.addAll(page.getResult());if(owned.size()>10000)throw new AssertionError("Fixture cleanup key bound");
                        cursor=page.getCursor();
                    }while(!cursor.equals("0"));
                    if(!owned.isEmpty())client.del(owned.toArray(String[]::new));
                }finally{client.close();}
            }
        }
    }
    static final class NoSql implements DataSource {
        final AtomicInteger calls=new AtomicInteger();
        public Connection getConnection(){calls.incrementAndGet();throw new AssertionError("No source SQL in initialization proof");}
        public Connection getConnection(String username,String password){return getConnection();}
        public java.io.PrintWriter getLogWriter(){return null;}
        public void setLogWriter(java.io.PrintWriter writer){}
        public void setLoginTimeout(int seconds){}
        public int getLoginTimeout(){return 1;}
        public java.util.logging.Logger getParentLogger(){return java.util.logging.Logger.getLogger("initialization-test");}
        public <T>T unwrap(Class<T> type)throws SQLException{throw new SQLException("Not a wrapper");}
        public boolean isWrapperFor(Class<?> type){return false;}
    }
}
