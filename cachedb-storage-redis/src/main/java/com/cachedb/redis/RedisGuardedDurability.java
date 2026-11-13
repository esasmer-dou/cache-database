package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import java.util.*;

/** Post-commit markers cannot recreate erased state from a closed/previous generation. */
final class RedisGuardedDurability {
    static Set<QueuedGenerationEnvelope.Identity> advance(RedisClientOperations redis,RedisKeyStrategy keys,
            Collection<QueuedGenerationEnvelope> envelopes) {
        var groups=new LinkedHashMap<Generation,List<QueuedWriteOperation>>();int count=0;
        for(var envelope:envelopes){
            var members=envelope.operation().atomicBatch()?envelope.operation().batchOperations():List.of(envelope.operation());
            for(var member:members){
                var stamp=envelope.generation(member);if(stamp==null)continue;
                if(++count>128)throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
                groups.computeIfAbsent(stamp,x->new ArrayList<>()).add(member);
            }
        }
        var advanced=new HashSet<QueuedGenerationEnvelope.Identity>();
        for(var group:groups.entrySet()){
            Generation stamp=group.getKey();var members=group.getValue();String ns=members.get(0).redisNamespace();
            var barrier=new RedisGenerationBarrier(redis,keys,ns);
            var allocator=new RedisTenantVersionAllocator(redis,keys,barrier,ns);
            var k=new ArrayList<>(List.of(barrier.generationKey(stamp.key()),allocator.allocatorKey(stamp.key()),allocator.counterKey(stamp.key())));
            var a=new ArrayList<>(List.of(Long.toString(stamp.value())));
            for(var member:members){
                if(member.version()<1 || member.version()>TenantVersionRange.MAX_VERSION)throw new ProtectionException(ProtectionException.Code.VERSION_LIMIT);
                k.add(keys.durabilityKey(ns,member.id()));a.add(Long.toString(member.version()));
            }
            String script=RedisTenantVersionAllocator.library()+"""
                if redis.call('HGET',KEYS[1],'state')~='OPEN' or redis.call('HGET',KEYS[1],'generation')~=ARGV[1] then return 0 end
                cachedb_version_assert(KEYS[1],KEYS[2],KEYS[3],ARGV[1],cachedb_version_run_id())
                for i=4,#KEYS do
                    local current=redis.call('GET',KEYS[i]) or '0'
                    if not cachedb_decimal(current) then return redis.error_reply('CDB_VERSION_LIMIT') end
                end
                for i=4,#KEYS do
                    local candidate=ARGV[i-2];local current=redis.call('GET',KEYS[i]) or '0'
                    if cachedb_decimal_less(current,candidate) then redis.call('SET',KEYS[i],candidate) end
                end
                return 1
                """;
            RedisProtectionArguments.validate(script,k,a);redis.requireSameSlot(k);
            if(((Number)redis.eval(script,k,a)).longValue()==1)for(var member:members)advanced.add(QueuedGenerationEnvelope.identity(member));
        }
        return Set.copyOf(advanced);
    }
    static long advanceOne(RedisClientOperations redis,RedisKeyStrategy keys,QueuedWriteOperation op,Generation stamp) {
        return advance(redis,keys,List.of(new QueuedGenerationEnvelope(op,Map.of(QueuedGenerationEnvelope.identity(op),stamp)))).isEmpty()?0:op.version();
    }
    private RedisGuardedDurability(){}
}
