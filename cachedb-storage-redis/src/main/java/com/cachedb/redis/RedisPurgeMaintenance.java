package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease;
import java.util.*;

/** Pair-fenced cleanup of exact native quota controls and temporary native identity receipts. */
public final class RedisPurgeMaintenance {
    private final RedisClientOperations redis;private final RedisKeyStrategy keys;private final RedisGenerationBarrier barrier;
    public RedisPurgeMaintenance(RedisClientOperations redis,RedisKeyStrategy keys,RedisGenerationBarrier barrier){
        this.redis=Objects.requireNonNull(redis);this.keys=Objects.requireNonNull(keys);this.barrier=Objects.requireNonNull(barrier);
    }
    public long residual(Lease lease,long generation,String tenant,String key,List<RedisPurgeTarget> targets,String request){
        if(!barrier.key(tenant).equals(lease.key())||targets.size()>8)throw incomplete();
        boolean allowed=false;String mode="";
        for(var target:targets){
            String column=target.tenantColumn;if(column==null)continue;
            String set=keys.tenantHotSetKey(target.namespace(),column,tenant);
            if(key.equals(keys.tenantHotPayloadBytesKey(target.namespace(),column,tenant))){allowed=true;mode="BYTES";}
            if(key.equals(RedisPageCacheManager.tenantAdmissionLockKey(set))){allowed=true;mode="LOCK";}
        }
        if(!allowed)throw incomplete();ProtectionKey.token(request);
        var k=new ArrayList<>(barrier.fencedKeys(lease));k.add(barrier.generationKey(lease.key())+":residual-result");k.add(key);
        var a=new ArrayList<>(barrier.fencedArgs(lease,generation));a.add(mode);a.add(request);
        String script=RedisGenerationBarrier.closedCheckLua()+"""
            local previous=redis.call('HGET',KEYS[3],'request')
            if previous then
                if previous~=ARGV[7] then return redis.error_reply('PURGE_RESIDUAL_RESULT_PENDING') end
                return tonumber(redis.call('HGET',KEYS[3],'removed'))
            end
            local t=redis.call('TYPE',KEYS[4]).ok
            if t=='none' then
                redis.call('HSET',KEYS[3],'request',ARGV[7],'removed','0');return 0
            end
            if t~='string' or redis.call('STRLEN',KEYS[4])>1024 then return redis.error_reply('PURGE_QUOTA_LAYOUT') end
            if ARGV[6]=='BYTES' and redis.call('GET',KEYS[4])~='0' then return redis.error_reply('PURGE_QUOTA_NOT_EMPTY') end
            if not redis.acl_check_cmd or not redis.acl_check_cmd('DEL',KEYS[4]) then return redis.error_reply('PURGE_QUOTA_ACL') end
            if not redis.acl_check_cmd('HSET',KEYS[3],'request',ARGV[7],'removed','1') then return redis.error_reply('PURGE_RESIDUAL_ACL') end
            redis.call('HSET',KEYS[3],'request',ARGV[7],'removed','1')
            redis.call('DEL',KEYS[4]);return 1
            """;
        RedisProtectionArguments.validate(script,k,a);redis.requireSameSlot(k);
        return ((Number)redis.eval(script,k,a)).longValue();
    }
    public void acknowledgeResidual(Lease lease,long generation,String request){
        ProtectionKey.token(request);
        var k=new ArrayList<>(barrier.fencedKeys(lease));k.add(barrier.generationKey(lease.key())+":residual-result");
        var a=new ArrayList<>(barrier.fencedArgs(lease,generation));a.add(request);redis.requireSameSlot(k);
        redis.eval(RedisGenerationBarrier.closedCheckLua()+"""
            if redis.call('HGET',KEYS[3],'request')==ARGV[6] then return redis.call('DEL',KEYS[3]) end
            return 0
            """,k,a);
    }
    public void collectReceipt(Lease lease,long generation,String exactReceipt){
        var k=new ArrayList<>(barrier.fencedKeys(lease));k.add(exactReceipt);
        var a=barrier.fencedArgs(lease,generation);redis.requireSameSlot(k);
        // exactReceipt comes ONLY from RedisIdentityPurgeParticipant.receiptKey for the sealed work item.
        redis.eval(RedisGenerationBarrier.closedCheckLua()+"""
            if not redis.acl_check_cmd or not redis.acl_check_cmd('DEL',KEYS[3]) then return redis.error_reply('PURGE_RECEIPT_ACL') end
            return redis.call('DEL',KEYS[3])
            """,k,a);
    }
    private static ProtectionException incomplete(){return new ProtectionException(ProtectionException.Code.INCOMPLETE_INVENTORY);}
}
