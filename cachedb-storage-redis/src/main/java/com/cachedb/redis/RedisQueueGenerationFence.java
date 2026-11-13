package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.util.*;

/** Same-stream-slot admission; a Java GET of the entity namespace guard is NOT a stream fence. */
public final class RedisQueueGenerationFence {
    private final RedisClientOperations redis;private final String stream;
    public RedisQueueGenerationFence(RedisClientOperations redis,String stream){
        if(stream==null || stream.isBlank() || stream.length()>8192)throw invalid();this.redis=Objects.requireNonNull(redis);this.stream=stream;
    }
    public String guardKey(ProtectionKey key){return stream+":protection:"+key.scopeToken()+":"+key.tenantToken();}
    public String leaseKey(ProtectionKey key){return guardKey(key)+":lease";}
    public void close(Lease lease,long generation) {
        admin();if(generation<1)throw invalid();
        var k=List.of(stream,guardKey(lease.key()),leaseKey(lease.key()));
        var a=List.of(Long.toString(generation),Long.toString(lease.epoch()),lease.operationId().toString(),
                lease.owner().toString(),Integer.toString(lease.seconds()*1000));
        redis.requireSameSlot(k);
        redis.eval(RedisTenantVersionAllocator.library()+"""
            local function less(a,b)if #a~=#b then return #a<#b end return a<b end
            local old=redis.call('HGET',KEYS[2],'generation') or '0'
            local epoch=redis.call('HGET',KEYS[2],'epoch') or '0'
            if redis.call('HGET',KEYS[2],'e:operation')==ARGV[3]
                then return redis.error_reply('PURGE_RECOVERY_REQUIRED') end
            if less(ARGV[1],old) or less(ARGV[2],epoch) then return redis.error_reply('PURGE_QUEUE_FENCE_LOST') end
            if epoch==ARGV[2] and redis.call('HGET',KEYS[2],'owner')
                and (redis.call('HGET',KEYS[2],'owner')~=ARGV[4] or redis.call('HGET',KEYS[2],'operation')~=ARGV[3])
                then return redis.error_reply('PURGE_QUEUE_FENCE_LOST') end
            local runId=cachedb_version_run_id()
            if redis.call('HGET',KEYS[2],'run_id')~=runId and epoch==ARGV[2] then return redis.error_reply('PURGE_RECOVERY_REQUIRED') end
            redis.call('HSET',KEYS[2],'generation',ARGV[1],'epoch',ARGV[2],'operation',ARGV[3],'owner',ARGV[4],'state','CLOSED','run_id',runId)
            redis.call('SET',KEYS[3],ARGV[3]..':'..ARGV[4]..':'..ARGV[2],'PX',ARGV[5])
            return 1
            """,k,a);
    }
    /** Invoke only after committed durable OPEN and fresh grant preparation. No foreground SQL. */
    public void open(Lease lease,TenantVersionRange range){
        admin();if(!range.key().equals(lease.key())||range.controlEpoch()!=lease.epoch())throw invalid();
        redis.eval(RedisTenantVersionAllocator.library()+"""
            if redis.call('HGET',KEYS[1],'e:to')==ARGV[1] then return redis.error_reply('PURGE_RECOVERY_REQUIRED') end
            if redis.call('HGET',KEYS[1],'generation')~=ARGV[1] or redis.call('HGET',KEYS[1],'epoch')~=ARGV[2]
                or redis.call('HGET',KEYS[1],'operation')~=ARGV[3] or redis.call('HGET',KEYS[1],'owner')~=ARGV[4]
                or redis.call('HGET',KEYS[1],'run_id')~=cachedb_version_run_id()
                or redis.call('GET',KEYS[2])~=ARGV[3]..':'..ARGV[4]..':'..ARGV[2] or redis.call('PTTL',KEYS[2])<=0
                then return redis.error_reply('PURGE_QUEUE_FENCE_LOST') end
            if redis.call('HEXISTS',KEYS[1],'cleanup_request')==1 then return redis.error_reply('PURGE_QUEUE_RESULT_PENDING') end
            redis.call('HSET',KEYS[1],'state','OPEN');return 1
            """,List.of(guardKey(lease.key()),leaseKey(lease.key())),List.of(Long.toString(range.generation()),
                    Long.toString(lease.epoch()),lease.operationId().toString(),lease.owner().toString()));
    }
    public void prepareEmptyActivation(Lease lease, EmptyTenantActivation.Checkpoint checkpoint) {
        admin();
        RedisEmptyActivation.execute(redis, List.of(guardKey(lease.key()), leaseKey(lease.key())),
                "queue-prepare", checkpoint, lease);
    }
    public void openEmptyActivation(Lease lease, EmptyTenantActivation.Checkpoint checkpoint) {
        admin();
        RedisEmptyActivation.execute(redis, List.of(guardKey(lease.key()), leaseKey(lease.key())),
                "queue-open", checkpoint, lease);
    }
    /** Original stamps only. Missing mirrors fail closed; mixed batches are admitted/rejected together. */
    public static String publish(RedisClientOperations redis,String stream,Map<String,String> fields,
            QueuedGenerationEnvelope envelope){
        if(!envelope.protectedEnvelope())throw new ProtectionException(ProtectionException.Code.MIGRATION_REQUIRED);
        var generations=new TreeMap<ProtectionKey,Long>();
        for(var stamp:envelope.stamps().values()){
            Long old=generations.putIfAbsent(stamp.key(),stamp.value());
            if(old!=null && old.longValue()!=stamp.value())throw invalid();
        }
        return publishStamps(redis,stream,fields,generations);
    }
    static String publishStamps(RedisClientOperations redis,String stream,Map<String,String> fields,
            SortedMap<ProtectionKey,Long> generations){
        if(generations.isEmpty()||generations.size()>128||fields.size()>32768)throw invalid();
        var fence=new RedisQueueGenerationFence(redis,stream);
        var k=new ArrayList<String>();var a=new ArrayList<String>();a.add(Integer.toString(generations.size()));
        for(var entry:generations.entrySet()){k.add(fence.guardKey(entry.getKey()));a.add(Long.toString(entry.getValue()));}
        k.add(stream);
        for(var field:fields.entrySet()){a.add(field.getKey());a.add(field.getValue());}
        String script=RedisTenantVersionAllocator.library()+"""
            local runId=cachedb_version_run_id()
            local n=tonumber(ARGV[1])
            for i=1,n do
                if redis.call('HGET',KEYS[i],'state')~='OPEN' or redis.call('HGET',KEYS[i],'generation')~=ARGV[i+1]
                    or redis.call('HGET',KEYS[i],'run_id')~=runId
                    then return redis.error_reply('TENANT_QUEUE_GENERATION_REJECTED') end
            end
            local fields={};for i=n+2,#ARGV do fields[#fields+1]=ARGV[i] end
            return redis.call('XADD',KEYS[n+1],'*',unpack(fields))
            """;
        RedisProtectionArguments.validate(script,k,a);redis.requireSameSlot(k);
        return (String)redis.eval(script,k,a);
    }
    private static void admin(){if(!CacheTenantContext.isAdministrative())throw new ProtectionException(ProtectionException.Code.ADMIN_REQUIRED);}
    private static ProtectionException invalid(){return new ProtectionException(ProtectionException.Code.INVALID_REQUEST);}
}
