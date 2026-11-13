package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease;
import java.util.*;

/** Numeric maximum over complete persisted pending scan chunks, not a hot-set maximum. */
public final class RedisVersionInventory {
    private final RedisClientOperations redis;private final RedisKeyStrategy keys;
    private final RedisGenerationBarrier barrier;private final String namespace;
    public RedisVersionInventory(RedisClientOperations redis,RedisKeyStrategy keys,RedisGenerationBarrier barrier,String namespace){
        this.redis=Objects.requireNonNull(redis);this.keys=Objects.requireNonNull(keys);this.barrier=Objects.requireNonNull(barrier);this.namespace=namespace;
        RedisProtectionArguments.layout(barrier,keys,namespace);
    }
    public long maximum(Lease lease,long generation,RedisPurgeScanner.Kind kind,List<String> pending) {
        if(pending.size()>128)throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
        var k=new ArrayList<>(barrier.fencedKeys(lease));var a=new ArrayList<>(barrier.fencedArgs(lease,generation));
        var modes=new ArrayList<String>();
        for(String item:pending) {
            String key=null,mode="S";
            if(kind==RedisPurgeScanner.Kind.SET || kind==RedisPurgeScanner.Kind.ZSET){
                if(!item.matches("[A-Za-z0-9._-]{1,1024}"))throw new ProtectionException(ProtectionException.Code.MIGRATION_REQUIRED);
                key=keys.versionKey(namespace,item);mode="V";
            } else {
                String vp=keys.versionKey(namespace,"");
                if(item.startsWith(vp)){key=item.endsWith(":tenant")?item.substring(0,item.length()-7):item;mode="V";}
                else if(item.startsWith(keys.tombstoneKey(namespace,"")) || item.startsWith(keys.durabilityKey(namespace,""))
                        || item.startsWith(keys.compactionPendingKey(namespace,"")))key=item;
                else if(item.startsWith(keys.compactionPayloadKey(namespace,""))){key=item;mode="H";}
            }
            if(key!=null){if(key.length()>8192)throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);k.add(key);modes.add(mode);}
        }
        a.add(Integer.toString(modes.size()));a.addAll(modes);
        String result=(String)redis.eval(RedisTenantVersionAllocator.library()+RedisGenerationBarrier.closedCheckLua()+"""
            local maximum='0';local n=tonumber(ARGV[6])
            for i=1,n do
                local key=KEYS[2+i];local mode=ARGV[6+i];local value=nil
                if mode=='H' then
                    if redis.call('HSTRLEN',key,'version')>16 then return redis.error_reply('CDB_VERSION_LIMIT') end
                    value=redis.call('HGET',key,'version')
                else
                    if redis.call('STRLEN',key)>16 then return redis.error_reply('CDB_VERSION_LIMIT') end
                    if mode=='V' and redis.call('PTTL',key)~=-1 then return redis.error_reply('CDB_VERSION_MIGRATION_REQUIRED') end
                    value=redis.call('GET',key)
                end
                if value then
                    if not cachedb_decimal(value) then return redis.error_reply('CDB_VERSION_LIMIT') end
                    if cachedb_decimal_less(maximum,value) then maximum=value end
                elseif mode=='V' then return redis.error_reply('CDB_VERSION_MIGRATION_REQUIRED') end
            end
            return maximum
            """,k,a);
        return Long.parseLong(result);
    }
}
