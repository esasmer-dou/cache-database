package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.api.*;
import com.reactor.cachedb.core.cache.*;
import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.page.*;
import com.reactor.cachedb.core.projection.*;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.core.watch.*;
import com.reactor.cachedb.redis.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;
import redis.clients.jedis.params.ScanParams;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
@Timeout(60)
class RedisBulkDeleteRedisTest {
    record Row(String id,String tenant,String value) {}
    static final EntityCodec<Row> CODEC=new EntityCodec<>(){
        public String toRedisValue(Row row){return LengthPrefixedPayloadCodec.encode(Map.of("id",row.id(),"tenant_id",row.tenant(),"value_text",row.value()));}
        public Row fromRedisValue(String payload){var p=LengthPrefixedPayloadCodec.decode(payload);return new Row(p.get("id"),p.get("tenant_id"),p.get("value_text"));}
        public Map<String,Object> toColumns(Row row){return Map.of("id",row.id(),"tenant_id",row.tenant(),"value_text",row.value());}
    };
    static EntityMetadata<Row,String> metadata(String table){
        return new EntityMetadata<>(){
            public String entityName(){return "F2BulkRow";}
            public String tableName(){return table;}
            public String redisNamespace(){return "f2bulk";}
            public String idColumn(){return "id";}
            public Class<Row> entityType(){return Row.class;}
            public Function<Row,String> idAccessor(){return Row::id;}
            public List<String> columns(){return List.of("id","tenant_id","value_text");}
            public Map<String,String> columnTypes(){return Map.of("id","java.lang.String","tenant_id","java.lang.String","value_text","java.lang.String");}
            public String tenantFieldName(){return "tenant_id";}
            public String extractTenantId(Row row){return row.tenant();}
            public List<RelationDefinition> relations(){return List.of();}
        };
    }
    static RedisClientOperations standalone(){
        return JedisPooledOperations.of(new JedisPooled(System.getProperty("cachedb.snapshot.redis.host","127.0.0.1"),
                Integer.getInteger("cachedb.snapshot.redis.port",16383)));
    }
    static final class Counted implements RedisClientOperations,RedisTrackingConnectionProvider {
        final RedisClientOperations delegate;
        final AtomicInteger bulkCalls=new AtomicInteger(),singleDeletes=new AtomicInteger();
        volatile IntConsumer beforeBulk=ignored->{};
        volatile boolean dropAfterAcceptance;
        volatile Runnable afterBulk=()->{};
        Counted(RedisClientOperations client){delegate=client;}
        public UnifiedJedis commands(){return delegate.commands();}
        public JedisPooled pooledClient(){return delegate.pooledClient();}
        public boolean clustered(){return delegate.clustered();}
        public int physicalPoolCapacity(){return delegate.physicalPoolCapacity();}
        private RedisTrackingConnectionProvider trackingProvider(){
            if(delegate instanceof RedisTrackingConnectionProvider provider)return provider;
            throw new IllegalStateException("Wrapped client does not provide dedicated invalidation connections");
        }
        public List<RedisTrackingNode> trackingNodes(int maximumNodes){return trackingProvider().trackingNodes(maximumNodes);}
        public boolean trackingClustered(){return trackingProvider().trackingClustered();}
        public AbstractPipeline pipeline(){return delegate.pipeline();}
        public Object memoryInfo(String key){return delegate.memoryInfo(key);}
        public Object serverInfo(String section,String key){return delegate.serverInfo(section,key);}
        public String functionLoad(String code){return delegate.functionLoad(code);}
        public String functionLoadReplace(String code){return delegate.functionLoadReplace(code);}
        public Object eval(String script,List<String> keys,List<String> args){return delegate.eval(script,keys,args);}
        public Object fcall(String name,List<String> keys,List<String> args){
            if(name.endsWith("_bulk")){
                beforeBulk.accept(bulkCalls.incrementAndGet());
                Object result=delegate.fcall(name,keys,args);
                afterBulk.run();
                if(dropAfterAcceptance)throw new redis.clients.jedis.exceptions.JedisConnectionException("Injected lost reply after real acceptance");
                return result;
            }
            if(name.toLowerCase(Locale.ROOT).contains("delete"))singleDeletes.incrementAndGet();
            return delegate.fcall(name,keys,args);
        }
        public redis.clients.jedis.resps.ScanResult<String> scan(String cursor,ScanParams params){return delegate.scan(cursor,params);}
        public long delete(String...keys){return delegate.delete(keys);}
        public void close(){delegate.close();}
    }
    static final class CountedSource implements DataSource {
        final DataSource delegate;final AtomicInteger connections=new AtomicInteger();
        CountedSource(DataSource source){delegate=source;}
        public java.sql.Connection getConnection()throws java.sql.SQLException{connections.incrementAndGet();return delegate.getConnection();}
        public java.sql.Connection getConnection(String user,String password)throws java.sql.SQLException{connections.incrementAndGet();return delegate.getConnection(user,password);}
        public java.io.PrintWriter getLogWriter()throws java.sql.SQLException{return delegate.getLogWriter();}
        public void setLogWriter(java.io.PrintWriter writer)throws java.sql.SQLException{delegate.setLogWriter(writer);}
        public void setLoginTimeout(int seconds)throws java.sql.SQLException{delegate.setLoginTimeout(seconds);}
        public int getLoginTimeout()throws java.sql.SQLException{return delegate.getLoginTimeout();}
        public java.util.logging.Logger getParentLogger()throws java.sql.SQLFeatureNotSupportedException{return delegate.getParentLogger();}
        public <T>T unwrap(Class<T> type)throws java.sql.SQLException{return delegate.unwrap(type);}
        public boolean isWrapperFor(Class<?> type)throws java.sql.SQLException{return delegate.isWrapperFor(type);}
    }
    static final class Fixture implements AutoCloseable {
        final Counted client;final CountedSource source;final String prefix;
        final String library="f2_bulk_"+UUID.randomUUID().toString().replace("-","");
        final RedisKeyStrategy keys;final EntityMetadata<Row,String> metadata;final CachePolicy policy;
        final CacheDatabase database;final EntityRepository<Row,String> repository;
        final AtomicInteger ownerSourceCalls=new AtomicInteger();
        final AtomicReference<Runnable> decodeHook=new AtomicReference<>();
        Fixture(RedisClientOperations client,boolean near)throws Exception{this(client,near,null,"f2_bulk_rows");}
        Fixture(RedisClientOperations raw,boolean near,DataSource provided,String table)throws Exception{
            client=new Counted(raw);prefix="f2bulk-"+(raw.clustered()?"{"+UUID.randomUUID()+"}":UUID.randomUUID());
            try{
                var sql=new JdbcDataSource();sql.setURL("jdbc:h2:mem:"+UUID.randomUUID());
                source=new CountedSource(provided==null?sql:provided);
                metadata=metadata(table);
                policy=CachePolicy.builder().hotEntityLimit(2000)
                        .nearCachePolicy(near?new NearCachePolicy(64,10,1048576):NearCachePolicy.disabled()).build();
                var config=CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                        .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                        .redisFunctions(RedisFunctionsConfig.builder().libraryName(library)
                                .upsertFunctionName(library+"_upsert").deleteFunctionName(library+"_delete")
                                .compactionCompleteFunctionName(library+"_complete").build())
                        .queryIndex(QueryIndexConfig.builder().prefixIndexEnabled(true).build())
                        .projectionRefresh(ProjectionRefreshConfig.builder().streamKey(prefix+":projection")
                                .deadLetterStreamKey(prefix+":projection-dlq").batchSize(1).blockTimeoutMillis(50)
                                .idleSleepMillis(5).claimIdleMillis(5).claimBatchSize(16).maxAttempts(1).build())
                        .writeBehind(WriteBehindConfig.builder().streamKey(prefix+":writes").compactionStreamKey(prefix+":compact")
                                .deadLetterStreamKey(prefix+":dead").build()).build();
                keys=raw.clustered()?new ClusterHashTagKeyStrategy(config.keyspace()):new RedisKeyStrategy(prefix);
                database=new CacheDatabase(client,source,config,CacheRuntimeOptions.builder()
                        .nearCacheInvalidation(NearCacheInvalidationConfig.builder().mode(NearCacheInvalidationMode.PUBSUB).build()).build());
                var codec=new EntityCodec<Row>(){
                    public String toRedisValue(Row row){return CODEC.toRedisValue(row);}
                    public Row fromRedisValue(String payload){var hook=decodeHook.getAndSet(null);if(hook!=null)hook.run();return CODEC.fromRedisValue(payload);}
                    public Map<String,Object> toColumns(Row row){return CODEC.toColumns(row);}
                };
                class NoSqlOwners implements EntityByIdLoader<Row,String>,EntityOwnershipLoader<String>{
                    public Optional<Row> load(String id){throw new AssertionError("No foreground source read");}
                    public Map<String,String> loadTenantOwners(Collection<String> ids){ownerSourceCalls.incrementAndGet();throw new AssertionError("No foreground owner SQL/bootstrap");}
                }
                database.register(metadata,codec,policy,null,null,new NoSqlOwners(),null);
                repository=database.repository(metadata,codec,policy);
            }catch(Exception|Error failure){
                try{close();}catch(Exception|Error cleanupFailure){failure.addSuppressed(cleanupFailure);}
                throw failure;
            }
        }
        void seed(int count){
            var rows=new ArrayList<Row>();
            for(int i=1;i<=count;i++){
                String id=Integer.toString(i);client.commands().set(keys.versionKey(metadata.redisNamespace(),id)+":tenant","a");
                rows.add(new Row(id,"a","value-"+id));
            }
            try(var tenant=CacheTenantContext.withTenant("a")){repository.saveAll(rows);}
        }
        List<String> ids(int count){return java.util.stream.IntStream.rangeClosed(1,count).mapToObj(Integer::toString).toList();}
        boolean exists(String id){return client.commands().exists(keys.entityKey(metadata.redisNamespace(),id));}
        public void close(){
            // Runtime stops before owned Redis cleanup; the transport closes last.
            try(client;Cleanup cleanup=this::cleanupOwnedFixture;database){}
        }
        private interface Cleanup extends AutoCloseable {
            @Override void close();
        }
        private void cleanupOwnedFixture(){
            var failures=new ArrayList<RuntimeException>();
            try{
                String cursor="0";boolean done=false;
                for(int i=0;i<128;i++){
                    var page=client.scan(cursor,new ScanParams().match(prefix+":*").count(500));
                    if(!page.getResult().isEmpty())client.delete(page.getResult().toArray(String[]::new));
                    cursor=page.getCursor();if("0".equals(cursor)){done=true;break;}
                }
                if(!done)throw new IllegalStateException("Bounded bulk fixture cleanup did not finish");
            }catch(RuntimeException failure){failures.add(failure);}
            try{
                var commands=client.commands();
                if(commands instanceof JedisCluster cluster){
                    for(var pool:cluster.getClusterNodes().values()){
                        try(var node=new Jedis(pool.getResource())){
                            if(node.info("replication").contains("role:master")&&!node.functionList(library).isEmpty())
                                node.functionDelete(library);
                        }catch(RuntimeException failure){failures.add(failure);}
                    }
                }else if(!commands.functionList(library).isEmpty())commands.functionDelete(library);
            }catch(RuntimeException failure){failures.add(failure);}
            String marker="cachedb:function-library:"+library+":marker";
            for(String key:List.of(marker,marker+":lease")){
                try{client.delete(key);}
                catch(RuntimeException failure){failures.add(failure);}
            }
            if(!failures.isEmpty()){
                var failure=new IllegalStateException("Owned bulk fixture cleanup failed",failures.getFirst());
                for(int i=1;i<failures.size();i++)failure.addSuppressed(failures.get(i));
                throw failure;
            }
        }
    }
    static void boundedDeleteContract(RedisClientOperations client)throws Exception{
        try(var f=new Fixture(client,false);var tenant=CacheTenantContext.withTenant("a")){
            f.seed(257);int before=f.source.connections.get();
            var receipts=f.repository.bulkDelete(f.ids(257));
            assertEquals(257,receipts.size());assertEquals(3,f.client.bulkCalls.get());assertEquals(0,f.client.singleDeletes.get());
            assertEquals(before,f.source.connections.get());assertEquals(0,f.ownerSourceCalls.get());
            for(var receipt:receipts){
                assertEquals(OperationType.DELETE,receipt.operationType());assertTrue(receipt.version()>1);
                assertFalse(f.exists(receipt.id()));
                assertEquals(Long.toString(receipt.version()),f.client.commands().get(f.keys.tombstoneKey("f2bulk",receipt.id())));
                assertFalse(f.client.commands().exists(f.keys.indexMetaKey("f2bulk",receipt.id())));
            }
            assertEquals(0,f.repository.count(QuerySpec.allOf()));
        }
    }
    @Test void chunksHaveReceiptsIndexesAndZeroForegroundSql(){assertDoesNotThrow(()->boundedDeleteContract(standalone()));}
    @Test void allInputAndOwnersAreValidatedBeforeFirstDelete()throws Exception{
        try(var f=new Fixture(standalone(),false);var tenant=CacheTenantContext.withTenant("a")){
            f.seed(3);f.client.commands().set(f.keys.versionKey("f2bulk","3")+":tenant","b");
            assertThrows(com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException.class,()->f.repository.bulkDelete(f.ids(3)));
            assertThrows(IllegalArgumentException.class,()->f.repository.bulkDelete(Collections.nCopies(10_001,"1")));
            assertEquals(0,f.client.bulkCalls.get());assertTrue(f.exists("1"));assertTrue(f.exists("2"));
            f.client.commands().del(f.keys.versionKey("f2bulk","3")+":tenant");
            assertThrows(com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException.class,()->f.repository.bulkDelete(f.ids(3)));
            assertEquals(0,f.client.bulkCalls.get());assertEquals(0,f.ownerSourceCalls.get());
        }
    }
    @Test void ownerRaceReturnsPriorReceiptsAndNeverDeletesTheForeignOrNotAttemptedRows()throws Exception{
        try(var f=new Fixture(standalone(),false);var tenant=CacheTenantContext.withTenant("a")){
            f.seed(257);f.client.beforeBulk=call->{if(call==2)f.client.commands().set(f.keys.versionKey("f2bulk","129")+":tenant","b");};
            var failure=assertThrows(BulkDeleteFailureException.class,()->f.repository.bulkDelete(f.ids(257)));
            assertEquals(128,failure.accepted().size());assertEquals(List.of("129"),failure.rejected());
            assertTrue(failure.uncertain().isEmpty());assertEquals(128,failure.notAttempted());
            assertFalse(f.exists("1"));assertTrue(f.exists("129"));assertTrue(f.exists("130"));assertTrue(f.exists("257"));
            assertEquals(2,f.client.bulkCalls.get());assertEquals(0,f.ownerSourceCalls.get());
        }
    }
    @Test void lostReplyIsUncertainAndIsNeverRetriedOrReplayedAsAnAtomicBatch()throws Exception{
        try(var f=new Fixture(standalone(),false);var tenant=CacheTenantContext.withTenant("a")){
            f.seed(2);f.client.dropAfterAcceptance=true;
            var failure=assertThrows(BulkDeleteFailureException.class,()->f.repository.bulkDelete(f.ids(2)));
            assertTrue(failure.accepted().isEmpty());assertEquals(List.of("1","2"),failure.uncertain());
            assertEquals(1,f.client.bulkCalls.get());assertFalse(f.exists("1"));assertFalse(f.exists("2"));
            assertEquals(0,f.client.singleDeletes.get());
        }
    }
    @Test void l1WatchersAndSynchronousProjectionFollowAcceptedDeletes()throws Exception{
        try(var f=new Fixture(standalone(),true);var tenant=CacheTenantContext.withTenant("a")){
            var near=f.database.nearCacheCoordinator();
            var nativeProvider=assertInstanceOf(RedisTrackingConnectionProvider.class,f.client.delegate);
            var countedProvider=assertInstanceOf(RedisTrackingConnectionProvider.class,f.client);
            var nativeNodes=nativeProvider.trackingNodes(64);
            var countedNodes=countedProvider.trackingNodes(64);
            assertFalse(nativeNodes.isEmpty());
            assertEquals(nativeNodes,countedNodes);
            assertSame(nativeNodes.getFirst().factory(),countedNodes.getFirst().factory(),"Preserve the actual endpoint/credentials/DB connection factory");
            assertEquals(nativeProvider.trackingClustered(),countedProvider.trackingClustered());
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!near.connected()&&System.nanoTime()<end)Thread.sleep(2);
            assertTrue(near.connected(),()->"Real subscription must be ready before save: "+near.invalidationFailure());
            assertEquals(NearCacheInvalidationMode.PUBSUB,near.invalidationMode());
            var projection=EntityProjection.<Row,Row,String>of("view",new ProjectionCodec<>(){
                public String toRedisValue(Row row){return CODEC.toRedisValue(row);}
                public Row fromRedisValue(String payload){return CODEC.fromRedisValue(payload);}
            },Row::id,f.metadata.columns(),CODEC::toColumns,Function.identity());
            var projected=f.repository.projected(projection);
            long published=near.invalidationsReceived();f.seed(1);
            end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(near.invalidationsReceived()<published+1&&System.nanoTime()<end)Thread.sleep(2);
            assertTrue(near.invalidationsReceived()>=published+1,"Observe the seed invalidation before warming L1");
            assertTrue(f.repository.findById("1").isPresent());
            var store=near.register("f2bulk",f.policy.nearCachePolicy());assertNotNull(store.get("1"));
            assertTrue(projected.findById("1").isPresent());
            var events=new LinkedBlockingQueue<CacheChangeEvent<Row,String>>();
            try(var watch=f.repository.registerWatcher(QuerySpec.where(QueryFilter.eq("value_text","value-1")),events::add)){
                var receipts=f.repository.bulkDelete(List.of("1"));assertEquals(1,receipts.size());
                var event=events.poll(5,TimeUnit.SECONDS);assertNotNull(event);assertEquals(CacheChangeType.EXITED,event.changeType());
                assertNull(store.get("1"));assertTrue(projected.findById("1").isEmpty());
                try(var causal=com.reactor.cachedb.core.causal.CacheCausalContext.withReceipt("f2bulk",receipts.get(0))){
                    assertTrue(f.repository.findById("1").isEmpty());
                }
                assertEquals(0,f.ownerSourceCalls.get());
            }
        }
    }
    @Test void asyncRefreshBindingUsesBoundedSynchronousBulkCleanupWithoutPerIdQueueEvents()throws Exception{
        try(var f=new Fixture(standalone(),false);var tenant=CacheTenantContext.withTenant("a")){
            var projection=EntityProjection.<Row,Row,String>of("async_view",new ProjectionCodec<>(){
                public String toRedisValue(Row row){return CODEC.toRedisValue(row);}
                public Row fromRedisValue(String payload){return CODEC.fromRedisValue(payload);}
            },Row::id,f.metadata.columns(),CODEC::toColumns,Function.identity()).asyncRefresh();
            f.repository.projected(projection);f.seed(257);
            var pm=new ProjectionEntityMetadata<Row,String>(f.metadata,projection);
            String projectionKey=f.keys.entityKey(pm.redisNamespace(),"1");
            f.client.commands().set(projectionKey,CODEC.toRedisValue(new Row("1","a","old")));
            String queue=f.database.config().projectionRefresh().streamKey();
            long before=f.client.commands().xlen(queue);int sql=f.source.connections.get();
            var receipts=f.repository.bulkDelete(f.ids(257));
            assertEquals(257,receipts.size());assertEquals(3,f.client.bulkCalls.get());
            assertEquals(before,f.client.commands().xlen(queue),"Bulk cleanup must not enqueue 257 point DELETE jobs");
            assertEquals(sql,f.source.connections.get());assertEquals(0,f.ownerSourceCalls.get());
            assertFalse(f.client.commands().exists(projectionKey));
        }
    }
    @Test void lateProjectionCleanupCannotEraseARecreatedParentOrItsNewProjection()throws Exception{
        try(var f=new Fixture(standalone(),false);var tenant=CacheTenantContext.withTenant("a")){
            var projection=EntityProjection.<Row,Row,String>of("view",new ProjectionCodec<>(){
                public String toRedisValue(Row row){return CODEC.toRedisValue(row);}
                public Row fromRedisValue(String payload){return CODEC.fromRedisValue(payload);}
            },Row::id,f.metadata.columns(),CODEC::toColumns,Function.identity());
            var projected=f.repository.projected(projection);f.seed(1);
            String projectionKey=f.keys.entityKey("f2bulk:projection:view","1");
            assertTrue(projected.findById("1").isPresent());
            var accepted=new AtomicReference<Object>();
            // Invoke the normal bulk FCALL, then let a real newer save win before cleanup starts.
            f.client.beforeBulk=ignored->{};
            // A separate post-acceptance callback is injected by the counting transport below.
            f.client.afterBulk=()->{
                f.client.afterBulk=()->{};
                f.repository.save(new Row("1","a","new"));
                accepted.set(f.client.commands().get(projectionKey));
            };
            f.repository.bulkDelete(List.of("1"));
            assertNotNull(accepted.get());
            assertEquals("new",projected.findById("1").orElseThrow().value());
            assertEquals(accepted.get(),f.client.commands().get(projectionKey));
        }
    }
}
