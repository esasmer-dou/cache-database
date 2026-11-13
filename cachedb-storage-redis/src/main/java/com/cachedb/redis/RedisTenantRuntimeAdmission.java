package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.protection.ProtectionException;
import java.util.List;
import java.util.UUID;

/** Standard-root namespace incarnation. No TTL, retirement, replacement or background discovery. */
final class RedisTenantRuntimeAdmission {
    final UUID runtimeId;
    final String key;

    RedisTenantRuntimeAdmission(RedisClientOperations redis, RedisKeyStrategy keys, String namespace) {
        this(redis, keys, namespace, UUID.randomUUID());
    }
    RedisTenantRuntimeAdmission(RedisClientOperations redis, RedisKeyStrategy keys, String namespace, UUID runtimeId) {
        this.runtimeId = java.util.Objects.requireNonNull(runtimeId);
        key = RedisTenantRuntimeControl.rosterKey(keys,namespace);
        try {
            redis.requireSameSlot(List.of(key));
            Object result = redis.eval(RedisTenantVersionAllocator.library() + """
                if redis.call('TYPE',KEYS[1]).ok=='none' then
                    return -1
                end
                local enrolled,required,pairs,state=cachedb_runtime_counts(KEYS[1])
                -- HKEYS is bounded by the validated 329-field maximum, never a keyspace scan.
                local es,ms,ps=0,0,0
                for _,field in ipairs(redis.call('HKEYS',KEYS[1])) do
                    local prefix=string.sub(field,1,2)
                    if prefix=='e:' or prefix=='m:' or prefix=='p:' then
                        local length=prefix=='p:' and 66 or 38
                        if #field~=length or redis.call('HGET',KEYS[1],field)~='1' then error('CDB_RUNTIME_INCOMPLETE') end
                        if prefix=='e:' then es=es+1 elseif prefix=='m:' then
                            ms=ms+1
                            if redis.call('HGET',KEYS[1],'e:'..string.sub(field,3))~='1' then error('CDB_RUNTIME_INCOMPLETE') end
                        else ps=ps+1 end
                    elseif field~='schema' and field~='state' and field~='enrolled' and field~='required'
                        and field~='pairs' and field~='run_id' and field~='approval' and field~='manifest'
                        and field~='coverage' then error('CDB_RUNTIME_INCOMPLETE') end
                end
                if es~=enrolled or ms~=required or ps~=pairs then error('CDB_RUNTIME_INCOMPLETE') end
                if redis.call('HGET',KEYS[1],'e:'..ARGV[1])=='1' then return 1 end
                -- Invalidate a previous seal atomically BEFORE any late join can publish a producer.
                if state~='COLLECTING' then redis.call('HSET',KEYS[1],'state','REVIEW_REQUIRED') end
                if enrolled==128 then return 0 end
                redis.call('HSET',KEYS[1],'e:'..ARGV[1],'1','enrolled',tostring(enrolled+1))
                return 1
                """, List.of(key), List.of(runtimeId.toString()));
            if (result instanceof Number n && n.longValue() == -1)
                throw new ProtectionException(ProtectionException.Code.MIGRATION_REQUIRED);
            if (!(result instanceof Number n) || n.intValue() != 1) throw rejected();
        } catch (ProtectionException known) { throw known; }
        catch (RuntimeException unsafe) { throw rejected(); }
    }

    String prelude(int index) {
        return "cachedb_runtime_admit(KEYS[" + index + "],'" + runtimeId + "')\n";
    }
    static ProtectionException rejected() {
        return new ProtectionException(ProtectionException.Code.INCOMPLETE_INVENTORY);
    }
}
