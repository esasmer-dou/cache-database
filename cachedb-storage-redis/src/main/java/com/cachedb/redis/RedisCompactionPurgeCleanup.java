package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.util.*;

/** Redis-only compaction cleanup BEFORE the identity owner marker is erased. No SQL DELETE/enqueue. */
public final class RedisCompactionPurgeCleanup {
    private final RedisClientOperations redis;private final RedisKeyStrategy keys;
    private final RedisGenerationBarrier barrier;private final String namespace;
    public RedisCompactionPurgeCleanup(RedisClientOperations redis,RedisKeyStrategy keys,RedisGenerationBarrier barrier,String namespace){
        this.redis=Objects.requireNonNull(redis);this.keys=Objects.requireNonNull(keys);
        this.barrier=Objects.requireNonNull(barrier);this.namespace=Objects.requireNonNull(namespace);
        RedisProtectionArguments.layout(barrier,keys,namespace);
    }
    public long erase(Lease lease,long generation,String tenant,String id,String request){
        validate(lease,tenant,id,request);
        var k=new ArrayList<>(barrier.fencedKeys(lease));k.add(resultKey(lease));
        k.add(keys.versionKey(namespace,id)+":tenant");k.add(keys.compactionPendingKey(namespace,id));
        k.add(keys.compactionPayloadKey(namespace,id));k.add(keys.compactionStatsKey(namespace));
        var a=new ArrayList<>(barrier.fencedArgs(lease,generation));
        a.add(request);a.add(tenant);a.add(id);a.add(lease.key().scopeToken());a.add(lease.key().tenantToken());a.add(namespace);a.add(lease.key().objectToken(id));
        String script=RedisTenantVersionAllocator.library()+RedisGenerationBarrier.closedCheckLua()+"""
            local prior=redis.call('HGET',KEYS[3],'request')
            if prior then
                if prior~=ARGV[6] or redis.call('HGET',KEYS[3],'identity')~=ARGV[12]
                    then return redis.error_reply('PURGE_COMPACTION_RESULT_PENDING') end
                return tonumber(redis.call('HGET',KEYS[3],'removed'))
            end
            if redis.call('HGET',KEYS[1],'compaction_ack')==ARGV[6] then return redis.error_reply('PURGE_COMPACTION_RESULT_ACKNOWLEDGED') end
            if redis.call('GET',KEYS[4])~=ARGV[7] or redis.call('PTTL',KEYS[4])~=-1
                then return redis.error_reply('PURGE_COMPACTION_OWNER_INCOMPLETE') end
            local pending=redis.call('GET',KEYS[5])
            local exists=redis.call('EXISTS',KEYS[6])
            local removed=0
            if pending or exists~=0 then
                if not cachedb_decimal(pending) or exists~=1
                    or redis.call('HGET',KEYS[6],'namespace')~=ARGV[11]
                    or redis.call('HGET',KEYS[6],'id')~=ARGV[8]
                    or redis.call('HGET',KEYS[6],'version')~=pending
                    or redis.call('HGET',KEYS[6],'generationScope')~=ARGV[9]
                    or redis.call('HGET',KEYS[6],'tenantToken')~=ARGV[10]
                    or redis.call('HEXISTS',KEYS[6],'batchCount')~=0
                    then return redis.error_reply('PURGE_COMPACTION_INCOMPLETE') end
                local original=redis.call('HGET',KEYS[6],'tenantGeneration')
                if not original or not string.match(original,'^[1-9][0-9]*$') or #original>19
                    or (#original==19 and original>'9223372036854775807')
                    or (#original>#ARGV[1] or (#original==#ARGV[1] and original>ARGV[1]))
                    then return redis.error_reply('PURGE_COMPACTION_GENERATION_INCOMPLETE') end
                local kind=redis.call('HGET',KEYS[6],'type')
                if kind~='UPSERT' and kind~='DELETE' then return redis.error_reply('PURGE_COMPACTION_TYPE_INCOMPLETE') end
                local payloads=redis.call('HGET',KEYS[7],'payloadCount')
                local pendings=redis.call('HGET',KEYS[7],'pendingCount')
                if not cachedb_decimal(payloads) or payloads=='0' or not cachedb_decimal(pendings) or pendings=='0'
                    then return redis.error_reply('PURGE_COMPACTION_COUNTER_INCOMPLETE') end
                removed=2
            end
            if not redis.acl_check_cmd
                or not redis.acl_check_cmd('HSET',KEYS[3],'request',ARGV[6],'identity',ARGV[12],'removed',tostring(removed))
                or (removed~=0 and (not redis.acl_check_cmd('DEL',KEYS[5],KEYS[6])
                    or not redis.acl_check_cmd('HINCRBY',KEYS[7],'payloadCount','-1')
                    or not redis.acl_check_cmd('HINCRBY',KEYS[7],'pendingCount','-1')))
                then return redis.error_reply('PURGE_COMPACTION_ACL') end
            redis.call('HSET',KEYS[3],'request',ARGV[6],'identity',ARGV[12],'removed',tostring(removed))
            if removed~=0 then
                redis.call('HINCRBY',KEYS[7],'payloadCount',-1);redis.call('HINCRBY',KEYS[7],'pendingCount',-1)
                redis.call('DEL',KEYS[5],KEYS[6])
            end
            return removed
            """;
        return ((Number)call(script,k,a)).longValue();
    }
    /** Call only AFTER request/result and consumed work position committed together in SQL. */
    public void acknowledge(Lease lease,long generation,String request){
        admin();ProtectionKey.token(request);
        var k=new ArrayList<>(barrier.fencedKeys(lease));k.add(resultKey(lease));
        var a=new ArrayList<>(barrier.fencedArgs(lease,generation));a.add(request);
        call(RedisGenerationBarrier.closedCheckLua()+"""
            if redis.call('HGET',KEYS[3],'request')==ARGV[6] then
                if not redis.acl_check_cmd('HSET',KEYS[1],'compaction_ack',ARGV[6])
                    or not redis.acl_check_cmd('DEL',KEYS[3]) then return redis.error_reply('PURGE_COMPACTION_ACL') end
                redis.call('HSET',KEYS[1],'compaction_ack',ARGV[6]);redis.call('DEL',KEYS[3]);return 1
            end
            return 0
            """,k,a);
    }
    private String resultKey(Lease lease){return barrier.generationKey(lease.key())+":compaction-result";}
    private void validate(Lease lease,String tenant,String id,String request){
        admin();ProtectionKey.token(request);
        if(!barrier.key(tenant).equals(lease.key())||id==null||!id.matches("[A-Za-z0-9._-]{1,1024}"))
            throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
    }
    private Object call(String script,List<String> keys,List<String> args){
        RedisProtectionArguments.validate(script,keys,args);redis.requireSameSlot(keys);
        try{return redis.eval(script,keys,args);}
        catch(ProtectionException safe){throw safe;}
        catch(RuntimeException failure){throw new ProtectionException(ProtectionException.Code.INCOMPLETE_INVENTORY);}
    }
    private static void admin(){if(!CacheTenantContext.isAdministrative())throw new ProtectionException(ProtectionException.Code.ADMIN_REQUIRED);}
}
