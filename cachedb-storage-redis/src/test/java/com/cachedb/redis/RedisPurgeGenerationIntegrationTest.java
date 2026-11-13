package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
@Timeout(60)
class RedisPurgeGenerationIntegrationTest {
    static QueuedWriteOperation operation(String ns,String id,long version){
        return new QueuedWriteOperation(OperationType.UPSERT,"Row","rows",ns,null,"id","entity_version",null,id,
                Map.of("id",id,"tenant","a"),version,Instant.EPOCH,null,null,0);
    }
    static List<String> fields(String ns,String token,long generation){
        return List.of("namespace",ns,"id","id","version","100","entity","Row","tenantGeneration",
                Long.toString(generation),"tenantToken",token,"generationScope",ProtectionKey.hash(ns));
    }
    static Map<String,String> body(List<String> fields){
        var result=new LinkedHashMap<String,String>();for(int i=0;i<fields.size();i+=2)result.put(fields.get(i),fields.get(i+1));
        return result;
    }
    @Test void closedAndReopenedGenerationCannotPublishOrRecreatePostCommitMarkers(){
        try(var f=new RedisGenerationBarrierTest.Fixture();var admin=CacheTenantContext.administrative()){
            var lease=f.lease(1);f.barrier.close(lease,1);var client=JedisPooledOperations.of(f.redis);
            var versions=new RedisTenantVersionAllocator(client,f.keys,f.barrier,f.ns);
            String stream=f.keys.namespacePrefix(f.ns)+":dlq";var mirror=new RedisQueueGenerationFence(client,stream);mirror.close(lease,1);
            var grant=new TenantVersionRange(f.key,1,1,1,100,100,UUID.randomUUID(),versions.runId(f.key),ProtectionKey.hash("inventory"));
            versions.install(grant);f.barrier.openPreparedGeneration(lease,1);mirror.open(lease,grant);
            String owner=f.keys.versionKey(f.ns,"id")+":tenant";f.redis.set(owner,"a");
            var stamp=new Generation(f.key,1);
            assertEquals("100",versions.mutate(stamp,owner,"a",f.keys.versionKey(f.ns,"id"),"-1",List.of(),List.of(),""));
            var op=operation(f.ns,"id",100);
            var envelope=new QueuedGenerationEnvelope(op,Map.of(QueuedGenerationEnvelope.identity(op),stamp));
            // Last range version MUST remain readable/markable after write capacity is exhausted.
            assertEquals(100,RedisGuardedDurability.advanceOne(client,f.keys,op,stamp));
            assertEquals("100",f.redis.get(f.keys.durabilityKey(f.ns,"id")));
            var fields=body(fields(f.ns,f.key.tenantToken(),1));
            RedisQueueGenerationFence.publish(client,stream,fields,envelope);assertEquals(1,f.redis.xlen(stream));
            assertThrows(RuntimeException.class,()->f.barrier.close(lease,1));
            var purge=f.lease(2);f.barrier.close(purge,1);mirror.close(purge,1);f.redis.del(f.keys.durabilityKey(f.ns,"id"));
            assertEquals(0,RedisGuardedDurability.advanceOne(client,f.keys,op,stamp));
            assertThrows(RuntimeException.class,()->RedisQueueGenerationFence.publish(client,stream,fields,envelope));
            f.barrier.installClosedGeneration(purge,2);mirror.close(purge,2);
            var next=new TenantVersionRange(f.key,2,purge.epoch(),2,101,200,UUID.randomUUID(),versions.runId(f.key),ProtectionKey.hash("inventory"));
            versions.install(next);f.barrier.openPreparedGeneration(purge,2);mirror.open(purge,next);
            assertEquals(0,RedisGuardedDurability.advanceOne(client,f.keys,op,stamp));
            assertNull(f.redis.get(f.keys.durabilityKey(f.ns,"id")));
            assertThrows(RuntimeException.class,()->RedisQueueGenerationFence.publish(client,stream,fields,envelope));
            assertEquals(1,f.redis.xlen(stream));
        }
    }
    @Test void compactionRequeuePreservesPayloadStampAndLateCompletionNeverRecreatesKeys(){
        try(var f=new RedisGenerationBarrierTest.Fixture();var admin=CacheTenantContext.administrative()){
            var lease=f.lease(1);f.barrier.close(lease,1);var client=JedisPooledOperations.of(f.redis);
            var keys=new RedisKeyStrategy("f3"){
                @Override public String compactionStreamKey(String base,String ns,Object id,int shards){return namespacePrefix(ns)+":notice";}
                @Override public String compactionStatsKey(String ns){return namespacePrefix(ns)+":stats";}
            };
            var versions=new RedisTenantVersionAllocator(client,keys,f.barrier,f.ns);
            var grant=new TenantVersionRange(f.key,1,1,1,100,101,UUID.randomUUID(),versions.runId(f.key),ProtectionKey.hash("inventory"));
            var cfg=WriteBehindConfig.defaults();String stream=keys.compactionStreamKey(cfg.compactionStreamKey(),f.ns,"id",cfg.compactionShardCount());
            var mirror=new RedisQueueGenerationFence(client,stream);mirror.close(lease,1);
            versions.install(grant);f.barrier.openPreparedGeneration(lease,1);mirror.open(lease,grant);
            var stamp=new Generation(f.key,1);String owner=keys.versionKey(f.ns,"id")+":tenant";f.redis.set(owner,"a");
            versions.mutate(stamp,owner,"a",keys.versionKey(f.ns,"id"),"-1",List.of(),List.of(),"");
            versions.mutate(stamp,owner,"a",keys.versionKey(f.ns,"id"),"-1",List.of(),List.of(),"");
            f.redis.set(keys.compactionPendingKey(f.ns,"id"),"101");
            var payload=body(fields(f.ns,f.key.tenantToken(),1));payload.put("version","101");
            f.redis.hset(keys.compactionPayloadKey(f.ns,"id"),payload);
            f.redis.hset(keys.compactionStatsKey(f.ns),Map.of("payloadCount","1","pendingCount","1"));
            assertEquals(2,RedisGuardedCompaction.complete(client,keys,cfg,operation(f.ns,"id",100),stamp));
            var notice=f.redis.xrange(stream,new redis.clients.jedis.StreamEntryID("0-0"),new redis.clients.jedis.StreamEntryID(Long.MAX_VALUE,Long.MAX_VALUE),1).get(0);
            assertEquals("1",notice.getFields().get("tenantGeneration"));assertEquals("101",notice.getFields().get("version"));
            // Completion at exhausted ceiling is safe and removes only its verified payload.
            assertEquals(1,RedisGuardedCompaction.complete(client,keys,cfg,operation(f.ns,"id",101),stamp));
            assertNull(f.redis.get(keys.compactionPendingKey(f.ns,"id")));assertEquals("0",f.redis.hget(keys.compactionStatsKey(f.ns),"pendingCount"));
            assertThrows(RuntimeException.class,()->f.barrier.close(lease,1));
            var purge=f.lease(2);f.barrier.close(purge,1);mirror.close(purge,1);f.redis.del(keys.compactionStatsKey(f.ns),stream);
            assertEquals(0,RedisGuardedCompaction.complete(client,keys,cfg,operation(f.ns,"id",101),stamp));
            assertFalse(f.redis.exists(stream));assertFalse(f.redis.exists(keys.compactionStatsKey(f.ns)));
            f.barrier.installClosedGeneration(purge,2);mirror.close(purge,2);
            var next=new TenantVersionRange(f.key,2,purge.epoch(),2,102,200,UUID.randomUUID(),versions.runId(f.key),ProtectionKey.hash("inventory"));
            versions.install(next);f.barrier.openPreparedGeneration(purge,2);mirror.open(purge,next);
            assertEquals(0,RedisGuardedCompaction.complete(client,keys,cfg,operation(f.ns,"id",101),stamp));
            assertFalse(f.redis.exists(keys.compactionPayloadKey(f.ns,"id")));
        }
    }
    @Test void closedCompactionErasureIsRedisOnlyCountsOnceAndKeepsOwnerUntilIdentityCleanup(){
        try(var f=new RedisGenerationBarrierTest.Fixture();var admin=CacheTenantContext.administrative()){
            var lease=f.lease(1);f.barrier.close(lease,1);var client=JedisPooledOperations.of(f.redis);
            var keys=new RedisKeyStrategy("f3"){
                @Override public String compactionStatsKey(String ns){return namespacePrefix(ns)+":stats";}
            };
            f.redis.set(keys.versionKey(f.ns,"id")+":tenant","a");f.redis.set(keys.compactionPendingKey(f.ns,"id"),"100");
            var payload=body(fields(f.ns,f.key.tenantToken(),1));payload.put("type","UPSERT");
            f.redis.hset(keys.compactionPayloadKey(f.ns,"id"),payload);
            f.redis.hset(keys.compactionStatsKey(f.ns),Map.of("payloadCount","2","pendingCount","2"));
            f.redis.set(keys.versionKey(f.ns,"peer")+":tenant","b");f.redis.set(keys.compactionPendingKey(f.ns,"peer"),"101");
            var peer=body(fields(f.ns,ProtectionKey.of(f.ns,"b").tenantToken(),1));peer.put("type","UPSERT");
            peer.put("id","peer");peer.put("version","101");f.redis.hset(keys.compactionPayloadKey(f.ns,"peer"),peer);
            var cleanup=new RedisCompactionPurgeCleanup(client,keys,f.barrier,f.ns);String request=ProtectionKey.hash("sealed compaction work");
            assertEquals(2,cleanup.erase(lease,1,"a","id",request));assertEquals(2,cleanup.erase(lease,1,"a","id",request));
            assertNull(f.redis.get(keys.compactionPendingKey(f.ns,"id")));assertFalse(f.redis.exists(keys.compactionPayloadKey(f.ns,"id")));
            assertEquals("a",f.redis.get(keys.versionKey(f.ns,"id")+":tenant"));assertEquals("1",f.redis.hget(keys.compactionStatsKey(f.ns),"pendingCount"));
            assertEquals("101",f.redis.get(keys.compactionPendingKey(f.ns,"peer")));
            cleanup.acknowledge(lease,1,request);
            assertThrows(ProtectionException.class,()->cleanup.erase(lease,1,"a","id",request));
            assertThrows(ProtectionException.class,()->cleanup.erase(lease,1,"a","peer",ProtectionKey.hash("foreign")));
            assertEquals("101",f.redis.get(keys.compactionPendingKey(f.ns,"peer")));assertEquals("1",f.redis.hget(keys.compactionStatsKey(f.ns),"payloadCount"));
        }
    }
    @Test void reconciliationAndArchiveCannotReintroduceClosedGenerationCopies(){
        try(var f=new RedisGenerationBarrierTest.Fixture();var admin=CacheTenantContext.administrative()){
            var lease=f.lease(1);f.barrier.close(lease,1);var client=JedisPooledOperations.of(f.redis);
            var versions=new RedisTenantVersionAllocator(client,f.keys,f.barrier,f.ns);
            var grant=new TenantVersionRange(f.key,1,1,1,100,200,UUID.randomUUID(),versions.runId(f.key),ProtectionKey.hash("inventory"));
            var cfg=com.reactor.cachedb.core.config.DeadLetterRecoveryConfig.builder()
                    .reconciliationStreamKey(f.keys.namespacePrefix(f.ns)+":reconciliation")
                    .archiveStreamKey(f.keys.namespacePrefix(f.ns)+":archive").build();
            var recon=new RedisQueueGenerationFence(client,cfg.reconciliationStreamKey());
            var archive=new RedisQueueGenerationFence(client,cfg.archiveStreamKey());
            recon.close(lease,1);archive.close(lease,1);versions.install(grant);
            f.barrier.openPreparedGeneration(lease,1);recon.open(lease,grant);archive.open(lease,grant);
            var op=operation(f.ns,"id",100);var original=new QueuedGenerationEnvelope(op,
                    Map.of(QueuedGenerationEnvelope.identity(op),new Generation(f.key,1)));
            var mapper=new RedisWriteOperationMapper();Map<String,String> body;
            try(var scope=TenantWriteStampContext.restore(List.of(original))){body=mapper.toBody(op);}
            RedisRecoveryStreams.publishReconciliation(client,cfg,Map.of("status","FAILED"),original);
            RedisRecoveryStreams.archiveResolved(client,cfg,body,"1-0","FAILED","",original);
            assertEquals(1,f.redis.xlen(cfg.reconciliationStreamKey()));assertEquals(1,f.redis.xlen(cfg.archiveStreamKey()));
            assertThrows(ProtectionException.class,()->RedisRecoveryStreams.archiveResolved(client,cfg,body,"1-0","FAILED",""));
            assertThrows(RuntimeException.class,()->f.barrier.close(lease,1));
            var purge=f.lease(2);f.barrier.close(purge,1);recon.close(purge,1);archive.close(purge,1);
            assertThrows(RuntimeException.class,()->RedisRecoveryStreams.publishReconciliation(client,cfg,Map.of("status","FAILED"),original));
            assertThrows(RuntimeException.class,()->RedisRecoveryStreams.archiveResolved(client,cfg,body,"1-0","FAILED","",original));
            assertEquals(1,f.redis.xlen(cfg.reconciliationStreamKey()));assertEquals(1,f.redis.xlen(cfg.archiveStreamKey()));
        }
    }
    @Test void nativeStreamCleanupIsByteBoundedResumableAndDoesNotDeleteForeignOrMixedEntries(){
        try(var f=new RedisGenerationBarrierTest.Fixture();var admin=CacheTenantContext.administrative();
                var wire=new BoundedRedisWire(new InetSocketAddress(System.getProperty("cachedb.snapshot.redis.host","127.0.0.1"),
                    Integer.getInteger("cachedb.snapshot.redis.port",16383)),null,null,null,null,0,3000,5000,1)){
            var lease=f.lease(1);f.barrier.close(lease,1);var client=JedisPooledOperations.of(f.redis);
            String stream=f.keys.namespacePrefix(f.ns)+":queue";var mirror=new RedisQueueGenerationFence(client,stream);mirror.close(lease,1);
            var xp=redis.clients.jedis.params.XAddParams.xAddParams();
            var ownId=f.redis.xadd(stream,xp,body(fields(f.ns,f.key.tenantToken(),1)));
            var foreignId=f.redis.xadd(stream,xp,body(fields(f.ns,ProtectionKey.of(f.ns,"b").tenantToken(),1)));
            var legacyId=f.redis.xadd(stream,xp,Map.of("namespace",f.ns,"id","legacy"));
            var mixed=new LinkedHashMap<String,String>();mixed.put("batchCount","2");
            for(var field:body(fields(f.ns,f.key.tenantToken(),1)).entrySet())mixed.put("b0:"+field.getKey(),field.getValue());
            for(var field:body(fields(f.ns,ProtectionKey.of(f.ns,"b").tenantToken(),1)).entrySet())mixed.put("b1:"+field.getKey(),field.getValue());
            var mixedId=f.redis.xadd(stream,xp,mixed);
            var cleanup=new RedisTenantQueueEraser(client,wire,stream,f.ns);var snapshot=cleanup.snapshot(lease,1);
            var frame=cleanup.scan(lease,1,snapshot,"0-0");assertTrue(frame.finished());assertEquals(4,frame.entries().size());
            var resumed=RedisTenantQueueEraser.decodePending(frame.encoded());assertEquals(frame.entries(),resumed);
            var own=resumed.stream().filter(e->e.id().equals(ownId.toString())).findFirst().orElseThrow();
            var foreign=resumed.stream().filter(e->e.id().equals(foreignId.toString())).findFirst().orElseThrow();
            var legacy=resumed.stream().filter(e->e.id().equals(legacyId.toString())).findFirst().orElseThrow();
            var atomic=resumed.stream().filter(e->e.id().equals(mixedId.toString())).findFirst().orElseThrow();
            assertEquals(RedisTenantQueueEraser.Ownership.FOREIGN,cleanup.ownership(foreign,f.key,1));
            assertEquals(RedisTenantQueueEraser.Ownership.INCOMPLETE,cleanup.ownership(legacy,f.key,1));
            assertEquals(RedisTenantQueueEraser.Ownership.INCOMPLETE,cleanup.ownership(atomic,f.key,1));
            String request=ProtectionKey.hash("stream work offset");
            assertEquals(1,cleanup.erase(lease,1,own,request));assertEquals(1,cleanup.erase(lease,1,own,request));
            assertThrows(ProtectionException.class,()->cleanup.erase(lease,1,foreign,ProtectionKey.hash("foreign")));
            cleanup.acknowledgeResult(lease,1,request);
            assertThrows(RuntimeException.class,()->cleanup.erase(lease,1,own,request)); // No fabricated zero after result loss.
            assertEquals(3,f.redis.xlen(stream));
            // Foreign activity after the captured upper bound cannot make the scan unbounded.
            f.redis.xadd(stream,xp,body(fields(f.ns,ProtectionKey.of(f.ns,"b").tenantToken(),1)));
            assertTrue(cleanup.scan(lease,1,snapshot,snapshot.upper()).entries().isEmpty());
        }
    }
}
