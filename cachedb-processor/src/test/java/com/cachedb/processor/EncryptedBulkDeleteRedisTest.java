package com.reactor.cachedb.processor;

import static com.reactor.cachedb.processor.EncryptionCompilerFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.api.*;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.crypto.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.page.*;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.queue.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.redis.*;
import com.reactor.cachedb.starter.CacheDatabase;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.IntConsumer;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.*;
import redis.clients.jedis.params.ScanParams;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
@Timeout(60)
class EncryptedBulkDeleteRedisTest {
    @TempDir Path temp;
    static final String TENANT="tenant-a";
    static final class CountingKeys implements CacheKeyProvider {
        final CacheKeyProvider delegate=keys("k1",key("k1",1));final AtomicInteger lookups=new AtomicInteger();
        public KeyMaterial current(String ring){lookups.incrementAndGet();return delegate.current(ring);}
        public Optional<KeyMaterial> byId(String ring,String id){lookups.incrementAndGet();return delegate.byId(ring,id);}
    }
    static final class Client implements RedisClientOperations {
        final RedisClientOperations delegate;final AtomicInteger bulkCalls=new AtomicInteger();
        volatile IntConsumer beforeBulk=ignored->{};
        Client(RedisClientOperations delegate){this.delegate=delegate;}
        public UnifiedJedis commands(){return delegate.commands();}
        public JedisPooled pooledClient(){return delegate.pooledClient();}
        public boolean clustered(){return delegate.clustered();}
        public int physicalPoolCapacity(){return delegate.physicalPoolCapacity();}
        public AbstractPipeline pipeline(){return delegate.pipeline();}
        public Object memoryInfo(String key){return delegate.memoryInfo(key);}
        public Object serverInfo(String section,String key){return delegate.serverInfo(section,key);}
        public String functionLoad(String code){return delegate.functionLoad(code);}
        public String functionLoadReplace(String code){return delegate.functionLoadReplace(code);}
        public Object eval(String script,List<String> names,List<String> args){return delegate.eval(script,names,args);}
        public Object fcall(String name,List<String> names,List<String> args){
            if(name.endsWith("_bulk"))beforeBulk.accept(bulkCalls.incrementAndGet());
            return delegate.fcall(name,names,args);
        }
        public redis.clients.jedis.resps.ScanResult<String> scan(String cursor,ScanParams params){return delegate.scan(cursor,params);}
        public long delete(String... names){return delegate.delete(names);}
        public void close(){delegate.close();}
    }
    static final class Sql implements DataSource {
        final JdbcDataSource delegate=new JdbcDataSource();final AtomicInteger connections=new AtomicInteger();
        Sql(){delegate.setURL("jdbc:h2:mem:"+UUID.randomUUID());}
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
    static RedisClientOperations standalone(){return JedisPooledOperations.of(new JedisPooled(
            System.getProperty("cachedb.snapshot.redis.host","127.0.0.1"),Integer.getInteger("cachedb.snapshot.redis.port",16383)));}
    static Loaded generated(Path temp)throws Exception{
        var sources=new LinkedHashMap<>(recordSources(true));
        sources.replaceAll((name,text)->text.replace("EncryptionScope.REDIS_AND_SOURCE","EncryptionScope.REDIS_ONLY"));
        return load(compile(temp,sources));
    }
    static final class Fixture implements AutoCloseable {
        final Loaded generated;final Client redis;final Sql sql=new Sql();final CountingKeys provider=new CountingKeys();
        final String prefix;final RedisKeyStrategy keys;final CacheDatabase database;
        final String library="f2_encrypted_"+UUID.randomUUID().toString().replace("-","");
        final EntityMetadata<Object,Object> metadata;final EntityCodec<Object> codec;final EntityRepository<Object,Object> repository;
        final AtomicInteger owners=new AtomicInteger();final CacheDatabaseConfig config;
        Fixture(Path temp,RedisClientOperations client,boolean compaction)throws Exception{
            redis=new Client(client);
            prefix="f2-encrypted-"+(client.clustered()?"{"+UUID.randomUUID()+"}":UUID.randomUUID());
            try{
                generated=generated(temp);metadata=generated.metadata();
                var writes=WriteBehindConfig.builder().streamKey(prefix+":writes").compactionStreamKey(prefix+":compact")
                        .deadLetterStreamKey(prefix+":dlq").compactionShardCount(1)
                        .dedicatedWriteConsumerGroupEnabled(compaction).durableCompactionEnabled(compaction).build();
                config=CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                        .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                        .redisFunctions(RedisFunctionsConfig.builder().libraryName(library)
                                .upsertFunctionName(library+"_upsert").deleteFunctionName(library+"_delete")
                                .compactionCompleteFunctionName(library+"_complete").build())
                        .writeBehind(writes).build();
                keys=client.clustered()?new ClusterHashTagKeyStrategy(config.keyspace()):new RedisKeyStrategy(prefix);
                database=new CacheDatabase(redis,sql,config,CacheRuntimeOptions.builder().keyProvider(provider).build());
                class NoOwners implements EntityByIdLoader<Object,Object>,EntityOwnershipLoader<Object>{
                    public Optional<Object> load(Object id){throw new AssertionError("No foreground source fallback");}
                    public Map<Object,String> loadTenantOwners(Collection<Object> ids){owners.incrementAndGet();throw new AssertionError("No foreground owner bootstrap");}
                }
                var policy=CachePolicy.builder().hotEntityLimit(1024).build();
                database.register(metadata,generated.unbound(),policy,null,null,new NoOwners(),null);
                codec=database.runtimeCodec(metadata,generated.unbound());
                repository=database.repository(metadata,generated.unbound(),policy);
                assertFalse(metadata.tenantPurgeEnabled());
                assertTrue(metadata.encryptedColumns().values().stream().noneMatch(EncryptedColumnDescriptor::encryptsSource));
            }catch(Exception|Error failure){
                try{close();}catch(Exception|Error cleanupFailure){failure.addSuppressed(cleanupFailure);}
                throw failure;
            }
        }
        List<Object> ids(int count){return java.util.stream.LongStream.rangeClosed(1,count).mapToObj(id->(Object)id).toList();}
        void seed(int count){
            var template=codec.toColumns(generated.entity());var rows=new ArrayList<Object>();
            for(Object id:ids(count)){
                var fields=new LinkedHashMap<>(template);fields.put(metadata.idColumn(),id);fields.put(metadata.tenantFieldName(),TENANT);
                rows.add(codec.fromLogicalColumns(fields));
                redis.commands().set(owner(id),TENANT);
            }
            repository.saveAll(rows);
        }
        String owner(Object id){return keys.versionKey(metadata.redisNamespace(),id)+":tenant";}
        boolean exists(Object id){return redis.commands().exists(keys.entityKey(metadata.redisNamespace(),id));}
        String version(Object id){return redis.commands().get(keys.versionKey(metadata.redisNamespace(),id));}
        void unchanged(int count){
            assertEquals(0,redis.bulkCalls.get());assertEquals(0,owners.get());
            for(Object id:ids(count)){assertTrue(exists(id));assertEquals("1",version(id));assertFalse(redis.commands().exists(keys.tombstoneKey(metadata.redisNamespace(),id)));}
        }
        public void close()throws Exception{
            // Runtime stops before owned Redis cleanup; transport and generated classes close last.
            try(generated;redis;Cleanup cleanup=this::cleanupOwnedFixture;database){}
        }
        private interface Cleanup extends AutoCloseable {
            @Override void close();
        }
        private void cleanupOwnedFixture(){
            var failures=new ArrayList<RuntimeException>();
            try{
                String cursor="0";boolean done=false;
                for(int i=0;i<128;i++){
                    var page=redis.scan(cursor,new ScanParams().match(prefix+":*").count(500));
                    if(!page.getResult().isEmpty())redis.delete(page.getResult().toArray(String[]::new));
                    cursor=page.getCursor();if("0".equals(cursor)){done=true;break;}
                }
                if(!done)throw new IllegalStateException("Bounded fixture cleanup incomplete");
            }catch(RuntimeException failure){failures.add(failure);}
            try{
                var commands=redis.commands();
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
                try{redis.delete(key);}
                catch(RuntimeException failure){failures.add(failure);}
            }
            if(!failures.isEmpty()){
                var failure=new IllegalStateException("Owned encrypted fixture cleanup failed",failures.getFirst());
                for(int i=1;i<failures.size();i++)failure.addSuppressed(failures.get(i));
                throw failure;
            }
        }
    }
    static void safe(CacheEncryptionException failure){
        assertNull(failure.getCause());assertEquals(0,failure.getSuppressed().length);
        assertFalse(failure.toString().contains(SECRET));assertFalse(failure.toString().contains(HIDDEN));
    }
    static void positive(Path temp,RedisClientOperations client,boolean compaction)throws Exception{
        try(var f=new Fixture(temp,client,compaction);var tenant=CacheTenantContext.withTenant(TENANT)){
            f.seed(257);int sql=f.sql.connections.get(),lookups=f.provider.lookups.get();
            String token=f.redis.commands().hget(f.keys.indexMetaKey(f.metadata.redisNamespace(),1L),"contact");
            assertTrue(token.startsWith("~B1:"));
            String blindKey=f.keys.indexExactKey(f.metadata.redisNamespace(),"contact",java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertTrue(f.redis.commands().sismember(blindKey,"1"));
            var receipts=f.repository.bulkDelete(f.ids(257));
            assertEquals(257,receipts.size());assertEquals(3,f.redis.bulkCalls.get());assertEquals(0,f.owners.get());
            assertEquals(sql,f.sql.connections.get());assertEquals(lookups,f.provider.lookups.get(),"No field crypto/key lookup for an identity-only DELETE");
            for(var receipt:receipts){
                assertEquals(OperationType.DELETE,receipt.operationType());assertEquals(2,receipt.version());
                assertFalse(f.exists(receipt.id()));assertEquals("2",f.redis.commands().get(f.keys.tombstoneKey(f.metadata.redisNamespace(),receipt.id())));
                assertFalse(f.redis.commands().exists(f.keys.indexMetaKey(f.metadata.redisNamespace(),receipt.id())));
            }
            assertEquals(0,f.redis.commands().scard(blindKey),"Blind-index membership must be removed, not merely filtered by missing payload");
            assertEquals(0,f.redis.commands().scard(f.keys.indexAllKey(f.metadata.redisNamespace())));
            var mapper=new RedisWriteOperationMapper(com.reactor.cachedb.core.trace.TraceContexts.threadLocal(),f.database.entityRegistry());
            var deletes=new ArrayList<QueuedWriteOperation>();
            if(compaction){
                for(Object id:f.ids(257)){
                    var body=f.redis.commands().hgetAll(f.keys.compactionPayloadKey(f.metadata.redisNamespace(),id));
                    assertEquals("DELETE",body.get("type"));assertFalse(body.toString().contains(SECRET));assertFalse(body.toString().contains(HIDDEN));
                    deletes.add(mapper.fromBody(body));
                }
            }else{
                for(var entry:f.redis.commands().xrange(f.config.writeBehind().streamKey(),new StreamEntryID("0-0"),new StreamEntryID("9999999999999-0"),1000)){
                    var body=entry.getFields();
                    if(!"DELETE".equals(body.get("type")))continue;
                    assertFalse(body.keySet().stream().anyMatch(key->key.equals("col:contact")||key.equals("col:amount")||key.equals("col:hidden")));
                    deletes.add(mapper.fromBody(body));
                }
            }
            assertEquals(257,deletes.size());
            assertEquals(257,EncryptedQueuedWrites.sourceColumns(f.database.entityRegistry(),deletes).size(),
                    "Actual Redis DELETE entries must pass registered JDBC queue admission without field decryption");
        }
    }
    @Test void generatedRedisOnlyMappingDeletesInChunksAndEmitsRealReceipts()throws Exception{positive(temp,standalone(),false);}
    @Test void generatedRedisOnlyCompactionDeleteKeepsTheExistingSafeQueueContract()throws Exception{positive(temp,standalone(),true);}
    @Test void coldPayloadWithKnownOwnerAndVersionDeletesWithoutSourceOrFieldCrypto()throws Exception{
        try(var f=new Fixture(temp,standalone(),false);var tenant=CacheTenantContext.withTenant(TENANT)){
            f.seed(1);f.redis.commands().del(f.keys.entityKey(f.metadata.redisNamespace(),1L));
            int sql=f.sql.connections.get(),lookups=f.provider.lookups.get();
            assertEquals(2,f.repository.bulkDelete(List.of(1L)).get(0).version());
            assertEquals(sql,f.sql.connections.get());assertEquals(lookups,f.provider.lookups.get());assertEquals(0,f.owners.get());
        }
    }
    @Test void allMissingAndForeignOwnersAreRejectedBeforeAnyDelete()throws Exception{
        try(var f=new Fixture(temp,standalone(),false);var tenant=CacheTenantContext.withTenant(TENANT)){
            f.seed(3);int sql=f.sql.connections.get();
            f.redis.commands().del(f.owner(3L));
            assertThrows(com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException.class,()->f.repository.bulkDelete(f.ids(3)));f.unchanged(3);
            f.redis.commands().set(f.owner(3L),"tenant-b");
            assertThrows(com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException.class,()->f.repository.bulkDelete(f.ids(3)));f.unchanged(3);
            assertEquals(sql,f.sql.connections.get());
        }
    }
    @Test void nonCanonicalTrailingIdAndUnknownVersionFailBeforeMutation()throws Exception{
        try(var f=new Fixture(temp,standalone(),false);var tenant=CacheTenantContext.withTenant(TENANT)){
            f.seed(2);safe(assertThrows(CacheEncryptionException.class,()->f.repository.bulkDelete(List.of(1L,"02"))));f.unchanged(2);
            f.redis.commands().del(f.keys.versionKey(f.metadata.redisNamespace(),2L));
            safe(assertThrows(CacheEncryptionException.class,()->f.repository.bulkDelete(f.ids(2))));
            assertEquals(0,f.redis.bulkCalls.get());assertTrue(f.exists(1L));assertTrue(f.exists(2L));assertEquals("1",f.version(1L));
        }
    }
    @Test void ownerRaceIsAtomicallyRejectedWithoutFakeRollbackReceipts()throws Exception{
        try(var f=new Fixture(temp,standalone(),false);var tenant=CacheTenantContext.withTenant(TENANT)){
            f.seed(2);f.redis.beforeBulk=call->f.redis.commands().set(f.owner(1L),"tenant-b");
            var failure=assertThrows(BulkDeleteFailureException.class,()->f.repository.bulkDelete(f.ids(2)));
            assertTrue(failure.accepted().isEmpty());assertEquals(List.of(1L),failure.rejected());
            assertTrue(failure.uncertain().isEmpty());assertEquals(1,failure.notAttempted());
            assertTrue(f.exists(1L));assertTrue(f.exists(2L));assertEquals("1",f.version(1L));assertEquals("1",f.version(2L));
            assertEquals(1,f.redis.bulkCalls.get());assertEquals(0,f.owners.get());
        }
    }
    @Test void laterChunkOwnerRaceRetainsOnlyRealEarlierAcceptance()throws Exception{
        try(var f=new Fixture(temp,standalone(),false);var tenant=CacheTenantContext.withTenant(TENANT)){
            f.seed(129);int sql=f.sql.connections.get();
            f.redis.beforeBulk=call->{if(call==2)f.redis.commands().set(f.owner(129L),"tenant-b");};
            var failure=assertThrows(BulkDeleteFailureException.class,()->f.repository.bulkDelete(f.ids(129)));
            assertEquals(128,failure.accepted().size());assertEquals(List.of(129L),failure.rejected());
            assertEquals(0,failure.notAttempted());assertTrue(failure.uncertain().isEmpty());
            for(long id=1;id<=128;id++){assertFalse(f.exists(id));assertEquals("2",f.version(id));}
            assertTrue(f.exists(129L));assertEquals("1",f.version(129L));
            assertEquals(sql,f.sql.connections.get());assertEquals(0,f.owners.get());assertEquals(2,f.redis.bulkCalls.get());
        }
    }
    @Test void validWatcherReceivesExitOnlyAfterEncryptedBeforeImageValidation()throws Exception{
        try(var f=new Fixture(temp,standalone(),false);var tenant=CacheTenantContext.withTenant(TENANT)){
            f.seed(1);var events=new java.util.concurrent.LinkedBlockingQueue<com.reactor.cachedb.core.watch.CacheChangeEvent<Object,Object>>();
            try(var watch=f.repository.registerWatcher(QuerySpec.where(QueryFilter.eq("status","OPEN")),events::add)){
                int sql=f.sql.connections.get();var receipts=f.repository.bulkDelete(List.of(1L));
                var event=events.poll(5,java.util.concurrent.TimeUnit.SECONDS);
                assertNotNull(event);assertEquals(com.reactor.cachedb.core.watch.CacheChangeType.EXITED,event.changeType());
                assertEquals(1L,event.id());assertEquals(2,receipts.get(0).version());assertFalse(f.exists(1L));
                assertEquals(sql,f.sql.connections.get());assertEquals(0,f.owners.get());
            }
        }
    }
    @Test void malformedBlindMetadataAndForeignHotOwnerCannotMutateAnyRow()throws Exception{
        try(var f=new Fixture(temp,standalone(),false);var tenant=CacheTenantContext.withTenant(TENANT)){
            f.seed(2);
            String meta=f.keys.indexMetaKey(f.metadata.redisNamespace(),2L);
            var original=f.redis.commands().hgetAll(meta);
            f.redis.commands().hset(meta,"contact",SECRET);
            safe(assertThrows(CacheEncryptionException.class,()->f.repository.bulkDelete(f.ids(2))));f.unchanged(2);
            f.redis.commands().del(meta);f.redis.commands().hset(meta,original);
            f.redis.commands().set(f.keys.tenantHotOwnerKey(f.metadata.redisNamespace(),2L),"foreign-set\nforeign-bytes\n9");
            safe(assertThrows(CacheEncryptionException.class,()->f.repository.bulkDelete(f.ids(2))));f.unchanged(2);
        }
    }
    @Test void watcherBeforeImageIdentityMismatchFailsBeforeFirstMutation()throws Exception{
        try(var f=new Fixture(temp,standalone(),false);var tenant=CacheTenantContext.withTenant(TENANT)){
            f.seed(2);
            try(var watch=f.repository.registerWatcher(QuerySpec.where(QueryFilter.eq("status","OPEN")),event->{})){
                String first=f.redis.commands().get(f.keys.entityKey(f.metadata.redisNamespace(),1L));
                f.redis.commands().set(f.keys.entityKey(f.metadata.redisNamespace(),2L),first);
                safe(assertThrows(CacheEncryptionException.class,()->f.repository.bulkDelete(f.ids(2))));f.unchanged(2);
            }
        }
    }
}
