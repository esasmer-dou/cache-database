package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import java.util.*;

/** Post-commit completion/requeue is fenced in the same command, never a GET-then-mutate sequence. */
final class RedisGuardedCompaction {
    private static final String BODY="""
        if redis.call('HGET',KEYS[1],'state')~='OPEN' or redis.call('HGET',KEYS[1],'generation')~=ARGV[1] then return 0 end
        local runId=cachedb_version_run_id()
        cachedb_version_assert(KEYS[1],KEYS[2],KEYS[3],ARGV[1],runId)
        local pending=redis.call('GET',KEYS[4])
        if not pending and redis.call('EXISTS',KEYS[5])==0 then return 0 end
        if not cachedb_decimal(pending) or not cachedb_decimal(ARGV[6])
            or redis.call('HGET',KEYS[5],'version')~=pending
            or redis.call('HGET',KEYS[5],'tenantGeneration')~=ARGV[1]
            or redis.call('HGET',KEYS[5],'tenantToken')~=ARGV[2]
            or redis.call('HGET',KEYS[5],'generationScope')~=ARGV[3]
            or redis.call('HGET',KEYS[5],'namespace')~=ARGV[4]
            or redis.call('HGET',KEYS[5],'id')~=ARGV[5]
            then return redis.error_reply('PURGE_COMPACTION_MIGRATION_REQUIRED') end
        local allocated=redis.call('GET',KEYS[3])
        if cachedb_decimal_less(allocated,pending) then return redis.error_reply('PURGE_COMPACTION_VERSION_INVALID') end
        if cachedb_decimal_less(ARGV[6],pending) then
            if redis.call('HGET',KEYS[8],'state')~='OPEN'
                or redis.call('HGET',KEYS[8],'generation')~=ARGV[1]
                or redis.call('HGET',KEYS[8],'run_id')~=runId
                then return redis.error_reply('TENANT_QUEUE_GENERATION_REJECTED') end
            local kind=redis.call('TYPE',KEYS[6]).ok
            if kind~='none' and kind~='stream' then return redis.error_reply('PURGE_COMPACTION_LAYOUT_INVALID') end
            local entity=redis.call('HGET',KEYS[5],'entity')
            if not entity or #entity>128 then return redis.error_reply('PURGE_COMPACTION_MIGRATION_REQUIRED') end
            if redis.acl_check_cmd and not redis.acl_check_cmd('XADD',KEYS[6],'*','namespace',ARGV[4],'id',ARGV[5],
                'version',pending,'entity',entity,'tenantGeneration',ARGV[1],'tenantToken',ARGV[2],'generationScope',ARGV[3])
                then return redis.error_reply('PURGE_COMPACTION_ACL') end
            redis.call('XADD',KEYS[6],'*','namespace',ARGV[4],'id',ARGV[5],'version',pending,'entity',entity,
                'tenantGeneration',ARGV[1],'tenantToken',ARGV[2],'generationScope',ARGV[3])
            return 2
        end
        local payloadCount=redis.call('HGET',KEYS[7],'payloadCount')
        local pendingCount=redis.call('HGET',KEYS[7],'pendingCount')
        if not cachedb_decimal(payloadCount) or payloadCount=='0'
            or not cachedb_decimal(pendingCount) or pendingCount=='0'
            then return redis.error_reply('PURGE_COMPACTION_COUNTER_INVALID') end
        if redis.acl_check_cmd and (not redis.acl_check_cmd('DEL',KEYS[4],KEYS[5])
            or not redis.acl_check_cmd('HINCRBY',KEYS[7],'payloadCount','-1')
            or not redis.acl_check_cmd('HINCRBY',KEYS[7],'pendingCount','-1'))
            then return redis.error_reply('PURGE_COMPACTION_ACL') end
        redis.call('HINCRBY',KEYS[7],'payloadCount',-1)
        redis.call('HINCRBY',KEYS[7],'pendingCount',-1)
        redis.call('DEL',KEYS[4],KEYS[5])
        return 1
        """;
    static long complete(RedisClientOperations redis,RedisKeyStrategy keys,WriteBehindConfig config,
            QueuedWriteOperation operation,Generation stamp) {
        if(operation.atomicBatch() || operation.version()<1 || operation.version()>TenantVersionRange.MAX_VERSION
                || !ProtectionKey.hash(operation.redisNamespace()).equals(stamp.key().scopeToken()))
            throw new ProtectionException(ProtectionException.Code.MIGRATION_REQUIRED);
        String namespace=operation.redisNamespace();
        var barrier=new RedisGenerationBarrier(redis,keys,namespace);
        var allocator=new RedisTenantVersionAllocator(redis,keys,barrier,namespace);
        String stream=keys.compactionStreamKey(config.compactionStreamKey(),namespace,operation.id(),config.compactionShardCount());
        var mirror=new RedisQueueGenerationFence(redis,stream);
        var k=List.of(barrier.generationKey(stamp.key()),allocator.allocatorKey(stamp.key()),allocator.counterKey(stamp.key()),
                keys.compactionPendingKey(namespace,operation.id()),keys.compactionPayloadKey(namespace,operation.id()),stream,
                keys.compactionStatsKey(namespace),mirror.guardKey(stamp.key()));
        var a=List.of(Long.toString(stamp.value()),stamp.key().tenantToken(),stamp.key().scopeToken(),
                namespace,operation.id(),Long.toString(operation.version()));
        String script=RedisTenantVersionAllocator.library()+BODY;
        RedisProtectionArguments.validate(script,k,a);redis.requireSameSlot(k);
        return ((Number)redis.eval(script,k,a)).longValue();
    }
    private RedisGuardedCompaction(){}
}
