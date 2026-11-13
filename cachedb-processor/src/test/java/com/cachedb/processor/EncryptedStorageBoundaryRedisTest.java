package com.reactor.cachedb.processor;

import static com.reactor.cachedb.processor.EncryptionCompilerFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.registry.DefaultEntityRegistry;
import com.reactor.cachedb.redis.*;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.resps.StreamEntry;

/** Real repository/index/worker storage boundaries, not manually populated fake stores. */
@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
class EncryptedStorageBoundaryRedisTest {
    @TempDir Path temp;

    @Test @Timeout(40) void entityStreamIndexAndDlqNeverContainProtectedPlaintext() throws Exception { verify(false); }
    @Test @Timeout(40) void compactionStreamAndDlqNeverContainProtectedPlaintext() throws Exception { verify(true); }

    @Test @Timeout(40) void functionCompactionHashStoresOnlyProtectedQueuedColumns() throws Exception {
        String prefix="f3-function-"+UUID.randomUUID();
        try(var fixture=load(compile(temp,recordSources(false)));var redis=new JedisPooled(
                System.getProperty("cachedb.snapshot.redis.host","127.0.0.1"),Integer.getInteger("cachedb.snapshot.redis.port",16383))) {
            var codec=fixture.codec();var metadata=fixture.metadata();var keys=new RedisKeyStrategy(prefix);
            var config=WriteBehindConfig.builder().dedicatedWriteConsumerGroupEnabled(true).durableCompactionEnabled(true)
                    .compactionShardCount(1).streamKey(prefix+":stream").compactionStreamKey(prefix+":compaction").build();
            var executor=new RedisFunctionExecutor(redis,RedisFunctionsConfig.defaults(),RedisGuardrailConfig.defaults(),config,new RedisFunctionArgsMapper());
            try {
                var operation=new com.reactor.cachedb.core.model.WriteOperation<>(com.reactor.cachedb.core.model.OperationType.UPSERT,
                        metadata,1L,codec.toQueuedColumns(fixture.entity()),codec.toRedisValue(fixture.entity()),0L,java.time.Instant.EPOCH);
                assertEquals(1L,executor.upsert(keys.entityKey(metadata.redisNamespace(),1L),keys.versionKey(metadata.redisNamespace(),1L),
                        keys.tombstoneKey(metadata.redisNamespace(),1L),config.streamKey(),keys.compactionPayloadKey(metadata.redisNamespace(),1L),
                        keys.compactionPendingKey(metadata.redisNamespace(),1L),config.compactionStreamKey(),keys.compactionStatsKey(metadata.redisNamespace()),
                        operation,CachePolicy.defaults(),true));
                var snapshot=redis.hgetAll(keys.compactionPayloadKey(metadata.redisNamespace(),1L));
                assertFalse(snapshot.isEmpty());
                absent(snapshot);
                var decoded=new RedisWriteOperationMapper().fromBody(snapshot);
                var sql=codec.sourceColumnsFromQueued(decoded.id(),decoded.columns());
                assertEquals(SECRET,sql.get("contact"));
                assertTrue(sql.get("amount").startsWith("~E1:"));
                inspectAll(redis,prefix);
            } finally {cleanup(redis,prefix);}
        }
    }

    private void verify(boolean compaction) throws Exception {
        String prefix="f3-protection-"+UUID.randomUUID();
        String host=System.getProperty("cachedb.snapshot.redis.host","127.0.0.1");
        int port=Integer.getInteger("cachedb.snapshot.redis.port",16383);
        try(var fixture=load(compile(temp,recordSources(false)));var redis=new JedisPooled(host,port)) {
            var codec=fixture.codec();
            var metadata=fixture.metadata();
            var keys=new RedisKeyStrategy(prefix);
            var config=WriteBehindConfig.builder().enabled(true).workerThreads(1).flushGroupParallelism(1)
                    .dedicatedWriteConsumerGroupEnabled(compaction).durableCompactionEnabled(compaction).compactionShardCount(1)
                    .streamKey(prefix+":stream").compactionStreamKey(prefix+":compaction").deadLetterStreamKey(prefix+":dlq")
                    .consumerGroup("f3-group").compactionConsumerGroup("f3-group").batchSize(10)
                    .blockTimeoutMillis(100).idleSleepMillis(10).maxFlushRetries(1).retryBackoffMillis(5).build();
            var registry=new DefaultEntityRegistry(ResourceLimits.defaults());
            registry.register(metadata,codec,CachePolicy.builder().hotEntityLimit(10).build(),null,null);
            var operations=JedisPooledOperations.of(redis);
            try {
                repository(redis, fixture, config, keys).save(fixture.entity());
                String entity=redis.get(keys.entityKey(metadata.redisNamespace(),1L));
                assertNotNull(entity);
                absent(entity);
                assertEquals(fixture.entity(),codec.fromRedisValue(entity));
                assertTrue(redis.xlen(config.activeStreamKey())>0);
                absent(streamFields(redis,config.activeStreamKey()));
                var manager=new RedisQueryIndexManager<>(redis,metadata,codec,keys,null,
                        QueryIndexConfig.builder().prefixIndexEnabled(true).textIndexEnabled(true).build(),
                        RelationConfig.defaults(),new QueryEvaluator(),null);
                manager.reindex(fixture.entity());
                var indexMeta=redis.hgetAll(keys.indexMetaKey(metadata.redisNamespace(),1L));
                assertFalse(indexMeta.isEmpty());
                absent(indexMeta);
                assertEquals(List.of("1"),manager.resolveCandidateIds(QuerySpec.where(QueryFilter.eq("contact",SECRET))));
                assertFalse(redis.exists(keys.indexSortKey(metadata.redisNamespace(),"contact")));
                assertFalse(redis.exists(keys.indexExactKey(metadata.redisNamespace(),"contact",encoded(SECRET))));
                assertFalse(redis.exists(keys.indexPrefixKey(metadata.redisNamespace(),"contact",encoded("~"))));
                redis.del(keys.indexMetaKey(metadata.redisNamespace(),1L));
                manager.reindexBatchWithColumns(Map.of(1L,fixture.entity()),Map.of(1L,codec.toColumns(fixture.entity())));
                absent(redis.hgetAll(keys.indexMetaKey(metadata.redisNamespace(),1L)));
                assertThrows(RuntimeException.class,()->manager.resolveCandidateIds(QuerySpec.where(QueryFilter.ne("contact",SECRET))));
                inspectAll(redis,prefix);
                AtomicInteger sourceConversions=new AtomicInteger();
                AtomicReference<Throwable> contractFailure=new AtomicReference<>();
                com.reactor.cachedb.core.queue.WriteBehindFlusher flusher=operation->{
                    try {
                        var members=operation.atomicBatch()?operation.batchOperations():List.of(operation);
                        for(var member:members){
                            absent(member.columns());
                            var sql=codec.sourceColumnsFromQueued(member.id(),member.columns());
                            assertEquals(SECRET,sql.get("contact"));
                            assertTrue(sql.get("amount").startsWith("~E1:"));
                            sourceConversions.incrementAndGet();
                        }
                    } catch(Throwable failure) {
                        contractFailure.set(failure);
                        throw new SQLException("queue contract failure","22000");
                    }
                    // Simulate a driver echo after scope conversion; diagnostics must not retain it.
                    throw new SQLException("fake SQL echoed "+SECRET,"22000");
                };
                try(var worker=new RedisWriteBehindWorker(operations,flusher,config,new RedisWriteOperationMapper(),keys)) {
                    worker.start();
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                    while(redis.xlen(config.deadLetterStreamKey())==0 && System.nanoTime()<deadline)Thread.sleep(20);
                    assertNull(contractFailure.get());
                    assertTrue(sourceConversions.get()>0);
                    assertEquals(1L,redis.xlen(config.deadLetterStreamKey()));
                    absent(streamFields(redis,config.deadLetterStreamKey()));
                    inspectAll(redis,prefix);
                }
            } finally { cleanup(redis,prefix); }
        }
    }

    @Test @Timeout(40) void manualAutomaticReplayAndArchiveNeverCopyProviderPlaintext() throws Exception {
        String prefix="f3-replay-"+UUID.randomUUID();
        try(var fixture=load(compile(temp,recordSources(false)));var redis=new JedisPooled(
                System.getProperty("cachedb.snapshot.redis.host","127.0.0.1"),Integer.getInteger("cachedb.snapshot.redis.port",16383))){
            var registry=new DefaultEntityRegistry(ResourceLimits.defaults(),keys("k1",key("k1",1)));
            registry.register(fixture.metadata(),fixture.unbound(),CachePolicy.defaults(),null,null);
            var mapper=new RedisWriteOperationMapper(com.reactor.cachedb.core.trace.TraceContexts.threadLocal(),registry);
            var codec=fixture.codec();var metadata=fixture.metadata();var keyStrategy=new RedisKeyStrategy(prefix);
            var operation=new com.reactor.cachedb.core.model.WriteOperation<>(com.reactor.cachedb.core.model.OperationType.UPSERT,
                    metadata,1L,codec.toQueuedColumns(fixture.entity()),codec.toRedisValue(fixture.entity()),1L,java.time.Instant.EPOCH);
            var fields=new LinkedHashMap<>(mapper.toBody(operation));
            fields.put("deadLetterReason","java.sql.SQLException");fields.put("deadLetterMessage","redacted");
            fields.put("deadLetterAttempts","1");
            String dlq=prefix+":dlq";
            var recovery=DeadLetterRecoveryConfig.builder().enabled(true).workerThreads(1).blockTimeoutMillis(100)
                    .idleSleepMillis(5).maxReplayRetries(1).replayBackoffMillis(5).consumerGroup(prefix+":replay")
                    .reconciliationStreamKey(prefix+":reconciliation").archiveStreamKey(prefix+":archive")
                    .archiveResolvedEntries(true).build();
            var reject=new java.util.concurrent.atomic.AtomicBoolean(true);
            var conversions=new AtomicInteger();
            com.reactor.cachedb.core.queue.WriteBehindFlusher flusher=queued->{
                absent(queued.columns());
                var sql=codec.sourceColumnsFromQueued(queued.id(),queued.columns());
                assertEquals(SECRET,sql.get("contact"));assertTrue(sql.get("amount").startsWith("~E1:"));
                conversions.incrementAndGet();
                if(reject.get())throw new SQLException("driver echoed "+SECRET,"40001",17,new SQLException(HIDDEN));
            };
            try {
                var first=redis.xadd(dlq,XAddParams.xAddParams(),fields);
                redis.xgroupCreate(dlq,recovery.consumerGroup(),new StreamEntryID("0-0"),true);
                var management=new RedisDeadLetterManagement(redis,flusher,recovery,mapper,keyStrategy,dlq);
                assertFalse(management.replay(first.toString(),"review").applied());
                absent(streamFields(redis,recovery.reconciliationStreamKey()));
                management.close(first.toString(),"review");
                assertEquals(1L,redis.xlen(recovery.archiveStreamKey()));
                absent(streamFields(redis,recovery.archiveStreamKey()));
                var secondFields=new LinkedHashMap<>(fields);secondFields.put("version","2");
                var second=redis.xadd(dlq,XAddParams.xAddParams(),secondFields);
                try(var worker=new RedisDeadLetterRecoveryWorker(redis,flusher,recovery,mapper,keyStrategy,dlq)){
                    worker.start();
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                    while(worker.snapshot().failedCount()==0 && System.nanoTime()<deadline)Thread.sleep(20);
                    assertTrue(worker.snapshot().failedCount()>0);
                    absent(worker.snapshot());absent(streamFields(redis,recovery.reconciliationStreamKey()));
                    reject.set(false);
                    var thirdFields=new LinkedHashMap<>(fields);thirdFields.put("version","3");
                    redis.xadd(dlq,XAddParams.xAddParams(),thirdFields);
                    deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                    while(worker.snapshot().replayedCount()==0 && System.nanoTime()<deadline)Thread.sleep(20);
                    assertTrue(worker.snapshot().replayedCount()>0);absent(worker.snapshot());
                }
                management.close(second.toString(),"review");
                assertTrue(conversions.get()>=4);
                assertTrue(redis.xlen(recovery.archiveStreamKey())>=3);
                inspectAll(redis,prefix);
            } finally {cleanup(redis,prefix);}
        }
    }

    @Test @Timeout(40) void protectedAtomicLostReplyRetryAcrossAesRotationKeepsPublishedCiphertextAndIntent() throws Exception {
        String prefix="f3-atomic-"+UUID.randomUUID();
        try(var fixture=load(compile(temp,recordSources(false)));var redis=new JedisPooled(
                System.getProperty("cachedb.snapshot.redis.host","127.0.0.1"),Integer.getInteger("cachedb.snapshot.redis.port",16383))){
            var keys=new RedisKeyStrategy(prefix);
            var write=WriteBehindConfig.builder().enabled(true).streamKey(prefix+":stream").build();
            var guard=RedisGuardrailConfig.builder().enabled(false).build();
            var oldRegistry=new DefaultEntityRegistry(ResourceLimits.defaults(),keys("k1",key("k1",1)));
            oldRegistry.register(fixture.metadata(),fixture.unbound(),CachePolicy.defaults(),null,null);
            registerPlainAtomic(oldRegistry);
            var currentRegistry=new DefaultEntityRegistry(ResourceLimits.defaults(),keys("k2",key("k1",1),key("k2",2)));
            currentRegistry.register(fixture.metadata(),fixture.unbound(),CachePolicy.defaults(),null,null);
            registerPlainAtomic(currentRegistry);
            var operations=JedisPooledOperations.of(redis);
            var lose=new java.util.concurrent.atomic.AtomicBoolean(true);
            var firstArguments=new AtomicReference<List<String>>();
            var lostReply=(RedisClientOperations)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{RedisClientOperations.class},(proxy,method,args)->{
                        Object result;
                        try {result=method.invoke(operations,args);}
                        catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                        if(method.getName().equals("eval") && lose.compareAndSet(true,false)){
                            @SuppressWarnings("unchecked") var arguments=(List<String>)args[2];
                            firstArguments.set(List.copyOf(arguments));
                            throw new JedisConnectionException("Simulated lost acceptance reply");
                        }
                        return result;
                    });
            try {
                var first=new RedisAtomicWriteBatch(lostReply,oldRegistry,keys,write,guard,null,"rotate-retry");
                first.put(new PlainAtomicRow("plain","ordinary"));first.put(fixture.entity(),0);
                assertThrows(JedisConnectionException.class,first::commit);
                assertEquals(1L,redis.xlen(write.streamKey()));
                var published=streamFields(redis,write.streamKey());
                String payload=redis.get(keys.entityKey(fixture.metadata().redisNamespace(),1L));
                String fingerprint=redis.hget(write.streamKey()+":batch:rotate-retry","fingerprint");
                assertTrue(fingerprint.startsWith("~I1:"));assertEquals(firstArguments.get().get(0),fingerprint);
                var retry=new RedisAtomicWriteBatch(operations,currentRegistry,keys,write,guard,null,"rotate-retry");
                retry.put(new PlainAtomicRow("plain","ordinary"));retry.put(fixture.entity(),0);
                var receipt=retry.commit();assertEquals(List.of(1L,1L),receipt.versions());
                assertEquals(1L,redis.xlen(write.streamKey()));assertEquals(published,streamFields(redis,write.streamKey()));
                assertEquals(payload,redis.get(keys.entityKey(fixture.metadata().redisNamespace(),1L)));
                var rotated=fixture.unbound().bindEncryption(keys("k2",key("k1",1),key("k2",2)));
                assertNotEquals(payload,rotated.toRedisValue(fixture.entity()));
                assertEquals(fixture.entity(),rotated.fromRedisValue(payload));
                var changed=rotated.toColumns(fixture.entity());changed.put("contact",SECRET+"changed");
                var conflict=new RedisAtomicWriteBatch(operations,currentRegistry,keys,write,guard,null,"rotate-retry");
                conflict.put(new PlainAtomicRow("plain","ordinary"));conflict.put(rotated.fromLogicalColumns(changed),0);
                assertThrows(JedisDataException.class,conflict::commit);
                var casConflict=new RedisAtomicWriteBatch(operations,currentRegistry,keys,write,guard,null,"rotate-retry");
                casConflict.put(new PlainAtomicRow("plain","ordinary"));casConflict.put(fixture.entity(),1);
                assertThrows(JedisDataException.class,casConflict::commit);
                var plainConflict=new RedisAtomicWriteBatch(operations,currentRegistry,keys,write,guard,null,"rotate-retry");
                plainConflict.put(new PlainAtomicRow("plain","changed"));plainConflict.put(fixture.entity(),0);
                assertThrows(JedisDataException.class,plainConflict::commit);
                assertEquals(fingerprint,redis.hget(write.streamKey()+":batch:rotate-retry","fingerprint"));
                assertEquals(published,streamFields(redis,write.streamKey()));inspectAll(redis,prefix);
            } finally {cleanup(redis,prefix);}
        }
    }

    @Test @Timeout(40) void protectedAtomicPatchAndDeleteRetryNewBuildersWithoutBindingDerivedState() throws Exception {
        String prefix="f3-atomic-patch-"+UUID.randomUUID();
        try(var fixture=load(compile(temp,recordSources(false)));var redis=new JedisPooled(
                System.getProperty("cachedb.snapshot.redis.host","127.0.0.1"),Integer.getInteger("cachedb.snapshot.redis.port",16383))){
            var keys=new RedisKeyStrategy(prefix);var operations=JedisPooledOperations.of(redis);
            var write=WriteBehindConfig.builder().enabled(true).streamKey(prefix+":stream").build();
            var guard=RedisGuardrailConfig.builder().enabled(false).build();
            var registry=new DefaultEntityRegistry(ResourceLimits.defaults(),keys("k1",key("k1",1)));
            registry.register(fixture.metadata(),fixture.unbound(),CachePolicy.defaults(),null,null);
            var rotatedRegistry=new DefaultEntityRegistry(ResourceLimits.defaults(),keys("k2",key("k1",1),key("k2",2)));
            rotatedRegistry.register(fixture.metadata(),fixture.unbound(),CachePolicy.defaults(),null,null);
            try {
                var seed=new RedisAtomicWriteBatch(operations,registry,keys,write,guard,null,"seed");seed.put(fixture.entity());seed.commit();
                var patch=new RedisAtomicWriteBatch(operations,registry,keys,write,guard,null,"patch");
                patch.patch(fixture.metadata().entityType(),1L,Map.of("contact","cachedb:__NULL__"));
                var accepted=patch.commit();var published=streamFields(redis,write.streamKey());
                String payload=redis.get(keys.entityKey(fixture.metadata().redisNamespace(),1L));
                var retry=new RedisAtomicWriteBatch(operations,rotatedRegistry,keys,write,guard,null,"patch");
                retry.patch(fixture.metadata().entityType(),1L,Map.of("contact","cachedb:__NULL__"));
                assertEquals(accepted,retry.commit());assertEquals(published,streamFields(redis,write.streamKey()));
                assertEquals(payload,redis.get(keys.entityKey(fixture.metadata().redisNamespace(),1L)));
                var changed=new RedisAtomicWriteBatch(operations,rotatedRegistry,keys,write,guard,null,"patch");
                changed.patch(fixture.metadata().entityType(),1L,Map.of("contact","__NULL__"));
                assertThrows(JedisDataException.class,changed::commit);
                var mapper=new RedisWriteOperationMapper(com.reactor.cachedb.core.trace.TraceContexts.threadLocal(),rotatedRegistry);
                for(var fields:published){
                    var original=mapper.fromBody(fields);
                    var sql=com.reactor.cachedb.core.crypto.EncryptedQueuedWrites.sourceColumns(rotatedRegistry,List.of(original)).get(0);
                    assertTrue(sql.atomicBatch());assertFalse(original.batchOperations().get(0).columns().toString().contains(SECRET));
                    if(sql.batchOperations().get(0).type()==com.reactor.cachedb.core.model.OperationType.PATCH)
                        assertEquals("cachedb:__NULL__",sql.batchOperations().get(0).columns().get("contact"));
                    else assertEquals(SECRET,sql.batchOperations().get(0).columns().get("contact"));
                }
                var delete=new RedisAtomicWriteBatch(operations,registry,keys,write,guard,null,"delete");
                delete.delete(fixture.metadata().entityType(),1L);var deleted=delete.commit();
                var deleteRetry=new RedisAtomicWriteBatch(operations,rotatedRegistry,keys,write,guard,null,"delete");
                deleteRetry.delete(fixture.metadata().entityType(),1L);assertEquals(deleted,deleteRetry.commit());
                assertFalse(redis.exists(keys.entityKey(fixture.metadata().redisNamespace(),1L)));
                assertEquals(3L,redis.xlen(write.streamKey()));inspectAll(redis,prefix);
            } finally {cleanup(redis,prefix);}
        }
    }

    record PlainAtomicRow(String id,String note) {}
    private static void registerPlainAtomic(DefaultEntityRegistry registry) {
        var metadata=new com.reactor.cachedb.core.model.EntityMetadata<PlainAtomicRow,String>(){
            public String entityName(){return "PlainAtomicRow";}
            public String tableName(){return "plain_rows";}
            public String redisNamespace(){return "plain_rows";}
            public String idColumn(){return "id";}
            public Class<PlainAtomicRow> entityType(){return PlainAtomicRow.class;}
            public java.util.function.Function<PlainAtomicRow,String> idAccessor(){return PlainAtomicRow::id;}
            public List<String> columns(){return List.of("id","note");}
            public List<com.reactor.cachedb.core.model.RelationDefinition> relations(){return List.of();}
        };
        var codec=new com.reactor.cachedb.core.model.EntityCodec<PlainAtomicRow>(){
            public String toRedisValue(PlainAtomicRow row){return com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec.encode(Map.of("id",row.id(),"note",row.note()));}
            public PlainAtomicRow fromRedisValue(String value){var map=com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec.decode(value);return new PlainAtomicRow(map.get("id"),map.get("note"));}
            public Map<String,Object> toColumns(PlainAtomicRow row){return Map.of("id",row.id(),"note",row.note());}
        };
        registry.register(metadata,codec,CachePolicy.defaults(),null,null);
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static RedisEntityRepository<Object,Object> repository(JedisPooled redis,Loaded fixture,WriteBehindConfig write,RedisKeyStrategy keys) {
        return repository(redis,fixture,write,keys,null);
    }

    private static RedisEntityRepository<Object,Object> repository(JedisPooled redis,Loaded fixture,WriteBehindConfig write,RedisKeyStrategy keys,
            com.reactor.cachedb.core.page.EntityByIdLoader<Object,Object> byId) {
        return repository(redis,fixture,write,keys,byId,null,ReadThroughConfig.defaults());
    }

    private static RedisEntityRepository<Object,Object> repository(JedisPooled redis,Loaded fixture,WriteBehindConfig write,RedisKeyStrategy keys,
            com.reactor.cachedb.core.page.EntityByIdLoader<Object,Object> byId,
            com.reactor.cachedb.core.page.EntityQueryLoader<Object> queryLoader,ReadThroughConfig readThrough) {
        var limits=RedisGuardrailConfig.builder().enabled(false).build();
        var query=QueryIndexConfig.builder().prefixIndexEnabled(true).textIndexEnabled(true).build();
        var config=CacheDatabaseConfig.builder().writeBehind(write).redisGuardrail(limits).queryIndex(query).readThrough(readThrough).build();
        var options=com.reactor.cachedb.core.config.CacheRuntimeOptions.builder()
                .keyProvider(keys("k1",key("k1",1))).build();
        var registry=new DefaultEntityRegistry(ResourceLimits.defaults(),options.keyProvider());
        var policy=com.reactor.cachedb.core.cache.CachePolicy.builder().hotEntityLimit(10).build();
        registry.register(fixture.metadata(),fixture.unbound(),policy,null,null,byId,queryLoader);
        var operations=JedisPooledOperations.of(redis);
        var mapper=new RedisWriteOperationMapper(com.reactor.cachedb.core.trace.TraceContexts.threadLocal(),registry);
        var queue=new RedisWriteBehindQueue(redis,write.streamKey(),write.compactionStreamKey(),mapper,128,write,limits,keys);
        var functions=new RedisFunctionExecutor(redis,RedisFunctionsConfig.defaults(),limits,write,new RedisFunctionArgsMapper());
        var producer=new RedisProducerGuard(redis,limits,write,keys,write.streamKey()+":diagnostics",100);
        var session=new com.reactor.cachedb.starter.DefaultCacheSession(operations,operations,config,registry,queue,keys,
                functions,producer,new RedisHotSetManager(redis,keys),new QueryEvaluator(),null,null,null);
        return (RedisEntityRepository<Object,Object>)session.repository(fixture.metadata(),fixture.unbound(),policy);
    }

    @Test @Timeout(40) void ordinarySaveAllPatchAndWarmUseProtectedWireColumnsWithoutSqlBootstrap() throws Exception {
        String prefix="f3-repository-"+UUID.randomUUID();
        try(var fixture=load(compile(temp,recordSources(false)));var redis=new JedisPooled(
                System.getProperty("cachedb.snapshot.redis.host","127.0.0.1"),Integer.getInteger("cachedb.snapshot.redis.port",16383))){
            var keys=new RedisKeyStrategy(prefix);
            var write=WriteBehindConfig.builder().enabled(true).streamKey(prefix+":stream").compactionStreamKey(prefix+":compaction").build();
            var codec=fixture.codec();var metadata=fixture.metadata();
            try {
                var repository=repository(redis,fixture,write,keys);
                var second=codec.toColumns(fixture.entity());second.put("id",2L);
                repository.saveAll(List.of(fixture.entity(),codec.fromLogicalColumns(second)));
                assertEquals(2L,redis.xlen(write.streamKey()));
                var eq=QueryFilter.eq("contact",SECRET);
                var logicalQuery=QuerySpec.where(eq).and(QueryFilter.ne("status","CLOSED")).limitTo(9);
                var selected=repository.query(logicalQuery);
                assertEquals(2,selected.size());assertEquals(SECRET,eq.value());
                for(var row:selected)assertTrue(new QueryEvaluator().matches(codec.toColumns(row),logicalQuery.rootGroup()));
                assertTrue(codec.toIndexColumns(fixture.entity()).get("contact").toString().startsWith("~B1:"));
                repository.patchWithReceipt(1L,Collections.singletonMap("contact","cachedb:__NULL__"));
                var decoded=codec.toColumns(repository.findById(1L).orElseThrow());
                assertEquals("cachedb:__NULL__",decoded.get("contact"));
                assertEquals(1,repository.query(logicalQuery).size());assertEquals(SECRET,eq.value());
                var mapper=new RedisWriteOperationMapper();
                for(var fields:streamFields(redis,write.streamKey())){
                    var queued=mapper.fromBody(fields);
                    var sql=codec.sourceColumnsFromQueued(queued.id(),queued.columns());
                    if(queued.type()==com.reactor.cachedb.core.model.OperationType.PATCH)assertEquals("cachedb:__NULL__",sql.get("contact"));
                    else {assertEquals(SECRET,sql.get("contact"));assertTrue(sql.get("amount").startsWith("~E1:"));}
                }
                var third=codec.toColumns(fixture.entity());third.put("id",3L);
                long queuedBefore=redis.xlen(write.streamKey());
                repository.hydrateWarm(codec.fromLogicalColumns(third),5L);
                assertEquals(queuedBefore,redis.xlen(write.streamKey()));
                assertNotNull(redis.get(keys.entityKey(metadata.redisNamespace(),3L)));
                assertThrows(RuntimeException.class,()->repository.patchWithReceipt(77L,Map.of("contact",SECRET)));
                assertThrows(RuntimeException.class,()->repository.aggregateBy("contact",
                        new com.reactor.cachedb.core.query.AggregateSpec(com.reactor.cachedb.core.query.AggregateFunction.COUNT,null,com.reactor.cachedb.core.query.QueryGroup.and())));
                inspectAll(redis,prefix);
            } finally {cleanup(redis,prefix);}
        }
    }

    @Test @Timeout(40) void encryptedTenantWritesNeverBootstrapOwnershipFromSql() throws Exception {
        String prefix="f3-tenant-"+UUID.randomUUID();var sqlCalls=new AtomicInteger();
        class ForbiddenSource implements com.reactor.cachedb.core.page.EntityByIdLoader<Object,Object>,com.reactor.cachedb.core.page.EntityOwnershipLoader<Object>{
            public Optional<Object> load(Object id){sqlCalls.incrementAndGet();throw new AssertionError("Foreground SQL entity load");}
            public Map<Object,String> loadTenantOwners(Collection<Object> ids){sqlCalls.incrementAndGet();throw new AssertionError("Foreground SQL ownership lookup");}
        }
        try(var fixture=load(compile(temp,recordSources(true)));var redis=new JedisPooled(
                System.getProperty("cachedb.snapshot.redis.host","127.0.0.1"),Integer.getInteger("cachedb.snapshot.redis.port",16383));
                var tenant=com.reactor.cachedb.core.tenant.CacheTenantContext.withTenant("tenant-a")){
            var keys=new RedisKeyStrategy(prefix);
            var write=WriteBehindConfig.builder().enabled(true).streamKey(prefix+":stream").compactionStreamKey(prefix+":compaction").build();
            try {
                var repository=repository(redis,fixture,write,keys,new ForbiddenSource());
                assertThrows(RuntimeException.class,()->repository.save(fixture.entity()));
                assertThrows(RuntimeException.class,()->repository.saveAll(List.of(fixture.entity())));
                assertThrows(RuntimeException.class,()->repository.patchWithReceipt(1L,Map.of("contact",SECRET)));
                assertThrows(RuntimeException.class,()->repository.deleteById(1L));
                assertEquals(0,sqlCalls.get());assertEquals(0L,redis.xlen(write.streamKey()));
                redis.set(keys.versionKey(fixture.metadata().redisNamespace(),1L)+":tenant","tenant-a");
                repository.save(fixture.entity());
                repository.patchWithReceipt(1L,Map.of("contact","cachedb:__NULL__"));
                assertEquals(0,sqlCalls.get());
                var mapper=new RedisWriteOperationMapper();var codec=fixture.codec();
                for(var fields:streamFields(redis,write.streamKey())){
                    var queued=mapper.fromBody(fields);
                    assertTrue(queued.columns().containsKey(com.reactor.cachedb.core.crypto.EncryptedCodecSupport.QUEUE_TENANT));
                    var sql=codec.sourceColumnsFromQueued(queued.id(),queued.columns());
                    if(queued.type()==com.reactor.cachedb.core.model.OperationType.PATCH)assertEquals("cachedb:__NULL__",sql.get("contact"));
                }
                inspectAll(redis,prefix);
            } finally {cleanup(redis,prefix);}
        }
    }


    @Test @Timeout(60) void coldPatchReceiptUsesProtectedAssignmentsAndRealJdbcScopeConversion() throws Exception {
        for(boolean scoped:List.of(false,true)){
            String prefix="f3-cold-"+UUID.randomUUID();
            try(var fixture=load(compile(temp,recordSources(scoped)));var redis=new JedisPooled(
                    System.getProperty("cachedb.snapshot.redis.host","127.0.0.1"),Integer.getInteger("cachedb.snapshot.redis.port",16383));
                    var tenant=com.reactor.cachedb.core.tenant.CacheTenantContext.withTenant("tenant-a")){
                var keys=new RedisKeyStrategy(prefix);var codec=fixture.codec();var metadata=fixture.metadata();
                var write=WriteBehindConfig.builder().enabled(true).dedicatedWriteConsumerGroupEnabled(scoped)
                        .durableCompactionEnabled(scoped).compactionShardCount(1).streamKey(prefix+":stream")
                        .compactionStreamKey(prefix+":compaction").build();
                var registry=new DefaultEntityRegistry(ResourceLimits.defaults(),keys("k1",key("k1",1)));
                registry.register(metadata,fixture.unbound(),CachePolicy.defaults(),null,null);
                var calls=new AtomicInteger();var source=new ForbiddenVersionedSource(calls);
                var database=new org.h2.jdbcx.JdbcDataSource();
                database.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
                try(var c=database.getConnection();var ddl=c.createStatement()){
                    ddl.execute("CREATE TABLE protected_rows(id BIGINT PRIMARY KEY,tenant_id VARCHAR(256),status VARCHAR(32),contact VARCHAR(65536),amount VARCHAR(65536),hidden VARCHAR(65536),entity_version BIGINT)");
                    var initial=codec.toSourceColumns(fixture.entity());
                    try(var insert=c.prepareStatement("INSERT INTO protected_rows(id,tenant_id,status,contact,amount,hidden,entity_version) VALUES(?,?,?,?,?,?,1)")){
                        int index=1;for(String column:metadata.columns())insert.setObject(index++,initial.get(column));
                        insert.executeUpdate();
                    }
                }
                try {
                    redis.set(keys.versionKey(metadata.redisNamespace(),1L),"1");
                    if(scoped)redis.set(keys.versionKey(metadata.redisNamespace(),1L)+":tenant","tenant-a");
                    var repository=repository(redis,fixture,write,keys,source);
                    var mapper=new RedisWriteOperationMapper(com.reactor.cachedb.core.trace.TraceContexts.threadLocal(),registry);
                    long version=1;
                    for(String value:Arrays.asList(SECRET,"cachedb:__NULL__","__NULL__","~P:N",null)){
                        var assignments=new LinkedHashMap<String,Object>();
                        assignments.put("contact",value);assignments.put("amount",new java.math.BigDecimal("98765.4321"));
                        var receipt=repository.patchWithReceipt(1L,assignments);
                        assertNull(receipt.entity());assertEquals(++version,receipt.version());
                        assertFalse(redis.exists(keys.entityKey(metadata.redisNamespace(),1L)));
                        var fields=streamFields(redis,write.activeStreamKey()).get((int)version-2);
                        absent(fields);var queued=mapper.fromBody(fields);var before=new LinkedHashMap<>(queued.columns());
                        assertEquals(com.reactor.cachedb.core.model.OperationType.PATCH,queued.type());
                        if(value!=null)assertTrue(queued.columns().get("contact").startsWith("~E1:"));
                        else assertNull(queued.columns().get("contact"));
                        assertTrue(queued.columns().get("amount").startsWith("~E1:"));
                        var sql=com.reactor.cachedb.core.crypto.EncryptedQueuedWrites.sourceColumns(registry,List.of(queued)).get(0);
                        assertEquals(value,sql.columns().get("contact"));assertTrue(sql.columns().get("amount").startsWith("~E1:"));
                        assertEquals(before,queued.columns());assertThrows(UnsupportedOperationException.class,()->sql.columns().put("contact","forbidden"));
                        assertFalse(sql.columns().containsKey("hidden"));assertEquals(0,calls.get());
                        try(var c=database.getConnection();var update=c.prepareStatement("UPDATE protected_rows SET contact=?,amount=?,entity_version=? WHERE id=?")){
                            update.setObject(1,com.reactor.cachedb.jdbc.JdbcWriteBehindSupport.convertValue(sql.columns().get("contact"),"java.lang.String"));
                            update.setObject(2,com.reactor.cachedb.jdbc.JdbcWriteBehindSupport.convertValue(sql.columns().get("amount"),"java.lang.String"));
                            update.setLong(3,sql.version());update.setLong(4,1);assertEquals(1,update.executeUpdate());
                            try(var read=c.prepareStatement("SELECT id,tenant_id,status,contact,amount,hidden FROM protected_rows WHERE id=1");
                                    var rows=read.executeQuery()){
                                assertTrue(rows.next());assertEquals(value,rows.getString("contact"));assertTrue(rows.getString("amount").startsWith("~E1:"));
                                var columns=new LinkedHashMap<String,Object>();for(String column:metadata.columns())columns.put(column,rows.getObject(column));
                                var logical=codec.toColumns(codec.fromColumns(columns));
                                assertEquals(value,logical.get("contact"));assertEquals(assignments.get("amount"),logical.get("amount"));
                                assertEquals(HIDDEN,logical.get("hidden"));
                            }
                        }
                    }
                    long accepted=redis.xlen(write.activeStreamKey());
                    assertThrows(IllegalStateException.class,()->repository.patch(1L,Map.of("contact",SECRET)));
                    var invalid=assertThrows(IllegalArgumentException.class,()->repository.patchWithReceipt(1L,Map.of(SECRET,SECRET)));
                    assertFalse(invalid.toString().contains(SECRET));assertNull(invalid.getCause());
                    assertThrows(com.reactor.cachedb.core.crypto.CacheEncryptionException.class,()->
                            repository.patchWithReceipt(1L,Map.of("amount","invalid-"+SECRET)));
                    redis.del(keys.versionKey(metadata.redisNamespace(),1L));
                    var missing=assertThrows(IllegalStateException.class,()->repository.patchWithReceipt(1L,Map.of("contact",SECRET)));
                    assertFalse(missing.toString().contains(SECRET));assertEquals(accepted,redis.xlen(write.activeStreamKey()));assertEquals(0,calls.get());
                    inspectAll(redis,prefix);
                } finally {cleanup(redis,prefix);}
            }
        }
    }

    private static final class ForbiddenVersionedSource implements com.reactor.cachedb.core.page.VersionedEntitySourceLoader<Object,Object> {
        private final AtomicInteger calls;
        ForbiddenVersionedSource(AtomicInteger calls){this.calls=calls;}
        private AssertionError forbidden(){calls.incrementAndGet();return new AssertionError("Foreground source access is forbidden");}
        public Optional<com.reactor.cachedb.core.page.VersionedEntity<Object>> loadVersionedById(Object id){throw forbidden();}
        public java.util.OptionalLong loadVersionById(Object id){throw forbidden();}
        public List<com.reactor.cachedb.core.page.VersionedEntity<Object>> loadVersionedPage(com.reactor.cachedb.core.cache.PageWindow page){throw forbidden();}
        public List<com.reactor.cachedb.core.page.VersionedEntity<Object>> loadVersionedQuery(QuerySpec query){throw forbidden();}
    }


    @Test @Timeout(40) void actualRepositorySourceFallbackKeepsLogicalRedisOnlyAndRejectsCiphertextPredicatesBeforeLoader() throws Exception {
        String prefix="f3-source-fallback-"+UUID.randomUUID();
        try(var fixture=load(compile(temp,recordSources(false)));var redis=new JedisPooled(
                System.getProperty("cachedb.snapshot.redis.host","127.0.0.1"),Integer.getInteger("cachedb.snapshot.redis.port",16383))){
            var calls=new AtomicInteger();var observed=new AtomicReference<QuerySpec>();
            com.reactor.cachedb.core.page.EntityQueryLoader<Object> loader=query->{
                calls.incrementAndGet();observed.set(query);return List.of(fixture.entity());
            };
            var read=ReadThroughConfig.builder().mode(ReadThroughMode.READ_THROUGH_QUERY).hydrateLoadedEntities(false).build();
            var keys=new RedisKeyStrategy(prefix);var write=WriteBehindConfig.builder().enabled(true).streamKey(prefix+":stream").build();
            try {
                var repository=repository(redis,fixture,write,keys,null,loader,read);
                var eq=QueryFilter.eq("contact",SECRET);var logical=QuerySpec.where(eq).limitTo(9);
                assertEquals(List.of(fixture.entity()),repository.query(logical));assertEquals(1,calls.get());
                assertEquals(9,observed.get().limit());
                assertEquals(SECRET,observed.get().filters().get(0).value());assertEquals(SECRET,eq.value());
                assertEquals(0L,redis.xlen(write.streamKey()));
                var rejected=assertThrows(com.reactor.cachedb.core.crypto.CacheEncryptionException.class,()->
                        repository.query(QuerySpec.where(QueryFilter.eq("amount",new java.math.BigDecimal("98765.4321"))).limitTo(9)));
                assertEquals(com.reactor.cachedb.core.crypto.CacheEncryptionException.Code.UNSUPPORTED_SOURCE_OPERATION,rejected.code());
                assertNull(rejected.getCause());assertFalse(rejected.toString().contains("98765.4321"));assertEquals(1,calls.get());
                assertThrows(com.reactor.cachedb.core.crypto.CacheEncryptionException.class,()->
                        repository.query(logical.orderBy(QuerySort.asc("contact"))));
                assertEquals(1,calls.get());inspectAll(redis,prefix);
            } finally {cleanup(redis,prefix);}
        }
    }

    private static void absent(Object stored) {
        String text=String.valueOf(stored);
        assertFalse(text.contains(SECRET));
        assertFalse(text.contains(HIDDEN));
        assertFalse(text.contains("98765.4321"));
    }

    private static List<String> boundedKeys(JedisPooled redis,String prefix) {
        String cursor="0";
        List<String> keys=new ArrayList<>();
        for(int round=0;round<256;round++) {
            var scan=redis.scan(cursor,new ScanParams().match(prefix+":*").count(256));
            cursor=scan.getCursor();keys.addAll(scan.getResult());
            assertTrue(keys.size()<=512);
            if(cursor.equals("0"))return keys;
        }
        throw new AssertionError("Bounded fixture key traversal did not finish");
    }

    private static void inspectAll(JedisPooled redis,String prefix) {
        for(String key:boundedKeys(redis,prefix)) {
            absent(key);
            switch(redis.type(key)) {
                case "string"->absent(redis.get(key));
                case "hash"->absent(redis.hgetAll(key));
                case "stream"->absent(streamFields(redis,key));
                case "set"->{assertTrue(redis.scard(key)<=128);absent(redis.smembers(key));}
                case "zset"->{assertTrue(redis.zcard(key)<=128);absent(redis.zrange(key,0,127));}
                case "none"->{ }
                default->throw new AssertionError("Unexpected fixture Redis type");
            }
        }
    }

    private static void cleanup(JedisPooled redis,String prefix) {
        for(String key:boundedKeys(redis,prefix))redis.unlink(key);
    }

    private static List<Map<String,String>> streamFields(JedisPooled redis,String key) {
        assertTrue(redis.xlen(key)<=20);
        return redis.xrange(key,new StreamEntryID("0-0"),new StreamEntryID("9999999999999-0"),20)
                .stream().map(StreamEntry::getFields).toList();
    }
}
