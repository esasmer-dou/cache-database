package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.util.*;

/** Namespace+tenant Redis barrier; durable authority lives in JdbcTenantPurgeGuard. */
public final class RedisGenerationBarrier {
    private final RedisClientOperations redis;
    private final RedisKeyStrategy keys;
    private final String namespace;
    private final RedisTenantRuntimeAdmission admission;
    private static final String ORDER="""
        local function less(a,b)
          if #a~=#b then return #a<#b end
          return a<b
        end
        """;
    private static final String CHECK="""
        local currentRun=string.match(redis.call('INFO','SERVER'),'run_id:([0-9a-f]+)')
        if not currentRun or redis.call('HGET',KEYS[1],'run_id')~=currentRun then return redis.error_reply('PURGE_RECOVERY_REQUIRED') end
        if redis.call('HGET',KEYS[1],'state')~='CLOSED'
          or redis.call('HGET',KEYS[1],'generation')~=ARGV[1]
          or redis.call('HGET',KEYS[1],'operation')~=ARGV[2]
          or redis.call('HGET',KEYS[1],'owner')~=ARGV[3]
          or redis.call('HGET',KEYS[1],'epoch')~=ARGV[4]
          or redis.call('PTTL',KEYS[2])<=0 or redis.call('GET',KEYS[2])~=ARGV[5]
        then return redis.error_reply('PURGE_FENCE_LOST') end
        """;
    public RedisGenerationBarrier(RedisClientOperations redis,RedisKeyStrategy keys,String namespace) {
        this(redis, keys, namespace, null);
    }
    private RedisGenerationBarrier(RedisClientOperations redis,RedisKeyStrategy keys,String namespace,
            RedisTenantRuntimeAdmission admission) {
        this.redis=Objects.requireNonNull(redis);this.keys=Objects.requireNonNull(keys);
        if(namespace==null || namespace.length()>128)throw new IllegalArgumentException("Invalid namespace");
        this.namespace=namespace;
        this.admission=admission;
    }
    /** Mandatory standard-root enrollment, BEFORE the producer is published to application code. */
    public static RedisGenerationBarrier enrolled(RedisClientOperations redis,RedisKeyStrategy keys,String namespace) {
        return enrolled(redis, keys, namespace, UUID.randomUUID());
    }
    /** Existing roster only; a missing roster is never created by this path. */
    public static RedisGenerationBarrier enrolled(RedisClientOperations redis,RedisKeyStrategy keys,String namespace,UUID incarnation) {
        // Validate layout before registration. Standalone custom adapters retain their explicit separate contract.
        new RedisGenerationBarrier(redis, keys, namespace);
        return new RedisGenerationBarrier(redis, keys, namespace, new RedisTenantRuntimeAdmission(redis, keys, namespace, incarnation));
    }
    RedisTenantRuntimeAdmission admission() { return admission; }
    boolean belongsToRuntime(RedisClientOperations redis, RedisKeyStrategy keys) {
        return this.redis == redis && this.keys == keys;
    }

    void requireFunctionFrame(RedisClientOperations redis, RedisKeyStrategy keys,
            com.reactor.cachedb.core.config.WriteBehindConfig writeBehind,
            com.reactor.cachedb.core.model.WriteOperation<?, ?> operation, List<String> routed) {
        String scope = operation.metadata().redisNamespace();
        Object id = operation.id();
        boolean patch = operation.type() == com.reactor.cachedb.core.model.OperationType.PATCH;
        if (!belongsToRuntime(redis, keys) || !namespace.equals(scope))
            throw new ProtectionException(ProtectionException.Code.UNWIRED_PROTOCOL);
        String compaction = this.keys.compactionStreamKey(writeBehind.compactionStreamKey(),
                scope, id, writeBehind.compactionShardCount());
        String stream = patch && writeBehind.compactionActive()
                ? compaction : this.keys.writeStreamKey(writeBehind.streamKey(), scope);
        if (routed.size() != (patch ? 9 : 8)
                || !this.keys.entityKey(scope, id).equals(routed.get(0))
                || !this.keys.versionKey(scope, id).equals(routed.get(1))
                || !this.keys.tombstoneKey(scope, id).equals(routed.get(2))
                || !stream.equals(routed.get(3))
                || !this.keys.compactionPayloadKey(scope, id).equals(routed.get(4))
                || !this.keys.compactionPendingKey(scope, id).equals(routed.get(5))
                || !compaction.equals(routed.get(6))
                || !this.keys.compactionStatsKey(scope).equals(routed.get(7))
                || patch && !this.keys.indexDegradedKey(scope).equals(routed.get(8)))
            throw new ProtectionException(ProtectionException.Code.UNWIRED_PROTOCOL);
    }

    public ProtectionKey key(String tenant){return ProtectionKey.of(namespace,tenant);}
    public String generationKey(ProtectionKey key) {
        if(!key.scopeToken().equals(ProtectionKey.hash(namespace)))throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
        return keys.namespacePrefix(namespace)+":generation:"+key.tenantToken();
    }
    public String versionAllocatorKey(ProtectionKey key) {
        generationKey(key);
        return keys.namespacePrefix(namespace)+":security:version:"+key.tenantToken();
    }
    public String leaseKey(ProtectionKey key){return generationKey(key)+":purge-lease";}
    public String token(Lease lease){return lease.operationId()+":"+lease.owner()+":"+lease.epoch();}
    public List<String> fencedKeys(Lease lease){return List.of(generationKey(lease.key()),leaseKey(lease.key()));}
    public List<String> fencedArgs(Lease lease,long generation) {
        if(generation<=0)throw new IllegalArgumentException("Invalid generation");
        return List.of(Long.toString(generation),lease.operationId().toString(),lease.owner().toString(),
                Long.toString(lease.epoch()),token(lease));
    }
    public static String closedCheckLua(){return CHECK;}
    public void close(Lease lease,long generation){install(lease,generation,false);}
    public void installClosedGeneration(Lease lease,long generation){install(lease,generation,true);}
    private void install(Lease lease,long generation,boolean activation) {
        admin(); if(generation<=0)throw new IllegalArgumentException("Invalid generation");
        var args=new ArrayList<>(fencedArgs(lease,generation));
        args.add(Integer.toString(lease.seconds()*1000));args.add(activation?"1":"0");
        redis.eval(ORDER+"""
            local old=redis.call('HGET',KEYS[1],'generation')
            local epoch=redis.call('HGET',KEYS[1],'epoch') or '0'
            if redis.call('HGET',KEYS[1],'e:operation')==ARGV[2]
                then return redis.error_reply('PURGE_RECOVERY_REQUIRED') end
            if old and less(ARGV[1],old) or less(ARGV[4],epoch) then return redis.error_reply('PURGE_FENCE_LOST') end
            if epoch==ARGV[4] and redis.call('HGET',KEYS[1],'owner') and
                (redis.call('HGET',KEYS[1],'owner')~=ARGV[3] or redis.call('HGET',KEYS[1],'operation')~=ARGV[2])
                then return redis.error_reply('PURGE_FENCE_LOST') end
            local currentRun=string.match(redis.call('INFO','SERVER'),'run_id:([0-9a-f]+)')
            local priorRun=redis.call('HGET',KEYS[1],'run_id')
            if old and priorRun~=currentRun and epoch==ARGV[4] then return redis.error_reply('PURGE_RECOVERY_REQUIRED') end
            if redis.call('HSTRLEN',KEYS[1],'i:finalize_epoch')>16 then return redis.error_reply('PURGE_RECOVERY_REQUIRED') end
            local finalizing=redis.call('HGET',KEYS[1],'i:finalize_epoch')
            if finalizing and (not string.match(finalizing,'^[1-9]%d*$') or #finalizing>16
                or #finalizing==16 and finalizing>'9007199254740991')
                then return redis.error_reply('PURGE_RECOVERY_REQUIRED') end
            if finalizing and redis.call('HGET',KEYS[1],'state')=='OPEN' and not less(finalizing,ARGV[4])
                and not (epoch==ARGV[4] and old==ARGV[1] and ARGV[7]=='1')
                then return redis.error_reply('PURGE_FENCE_LOST') end
            if epoch==ARGV[4] and redis.call('HGET',KEYS[1],'state')=='OPEN'
                and (ARGV[7]~='1' or old~=ARGV[1]) then return redis.error_reply('PURGE_FENCE_LOST') end
            local state='CLOSED'
            if ARGV[7]=='1' and old==ARGV[1] and redis.call('HGET',KEYS[1],'state')=='OPEN'
                and redis.call('HGET',KEYS[1],'operation')==ARGV[2] then state='OPEN' end
            redis.call('HSET',KEYS[1],'generation',ARGV[1],'state',state,'operation',ARGV[2],'owner',ARGV[3],'epoch',ARGV[4],'run_id',currentRun)
            redis.call('SET',KEYS[2],ARGV[5],'PX',ARGV[6])
            return 1
            """,fencedKeys(lease),args);
    }
    public void assertClosed(Lease lease,long generation) {
        admin();redis.eval(CHECK+"return 1",fencedKeys(lease),fencedArgs(lease,generation));
    }
    /** Adopts only this immutable intent. OPEN recovery preserves allocator state and consumed versions. */
    public String prepareEmptyActivation(Lease lease, EmptyTenantActivation.Checkpoint checkpoint) {
        return (String) RedisEmptyActivation.execute(redis, emptyKeys(lease.key()), "prepare", checkpoint, lease);
    }
    public EmptyTenantActivation.Receipt activateEmpty(Lease lease, EmptyTenantActivation.Checkpoint checkpoint) {
        return RedisEmptyActivation.receipt(RedisEmptyActivation.execute(redis, emptyKeys(lease.key()),
                "activate", checkpoint, lease), checkpoint);
    }
    public EmptyTenantActivation.Receipt observeEmptyActivation(EmptyTenantActivation.Checkpoint checkpoint) {
        return RedisEmptyActivation.receipt(RedisEmptyActivation.execute(redis, emptyKeys(checkpoint.identity().key()),
                "observe", checkpoint, null), checkpoint);
    }
    private List<String> emptyKeys(ProtectionKey key) {
        String allocator = versionAllocatorKey(key);
        return List.of(generationKey(key), leaseKey(key), allocator, allocator + ":next");
    }
    /** Invoke only AFTER durable SQL OPEN(g+1) commit. */
    public void openPreparedGeneration(Lease lease,long generation) {
        admin();
        var openedKeys=new ArrayList<>(fencedKeys(lease));
        String allocator=keys.namespacePrefix(namespace)+":security:version:"+lease.key().tenantToken();
        openedKeys.add(allocator);openedKeys.add(allocator+":next");
        redis.eval(RedisTenantVersionAllocator.library()+"""
            if redis.call('HGET',KEYS[1],'e:to')==ARGV[1] then return redis.error_reply('PURGE_RECOVERY_REQUIRED') end
            if redis.call('HGET',KEYS[1],'generation')~=ARGV[1]
              or redis.call('HGET',KEYS[1],'operation')~=ARGV[2]
              or redis.call('HGET',KEYS[1],'owner')~=ARGV[3]
              or redis.call('HGET',KEYS[1],'epoch')~=ARGV[4]
              or redis.call('GET',KEYS[2])~=ARGV[5] or redis.call('PTTL',KEYS[2])<=0
              then return redis.error_reply('PURGE_FENCE_LOST') end
            cachedb_version_assert(KEYS[1],KEYS[3],KEYS[4],ARGV[1],cachedb_version_run_id())
            redis.call('HSET',KEYS[1],'state','OPEN'); return 1
            """,openedKeys,fencedArgs(lease,generation));
    }
    /** Revoke matching maintenance ownership only; never open admission. */
    public void release(Lease lease) {
        admin();redis.eval("""
            if redis.call('HGET',KEYS[1],'epoch')==ARGV[4] and redis.call('HGET',KEYS[1],'owner')==ARGV[3]
               and redis.call('GET',KEYS[2])==ARGV[5] then return redis.call('DEL',KEYS[2]) end
            return 0
            """,fencedKeys(lease),fencedArgs(lease,1));
    }
    /** Capture BEFORE an asynchronous source read; reuse this stamp for its delayed fill. */
    public Generation captureOpen(ProtectionKey key) {
        String allocator=keys.namespacePrefix(namespace)+":security:version:"+key.tenantToken();
        var capturedKeys=new ArrayList<>(List.of(generationKey(key),allocator,allocator+":next"));
        String membership="";
        if(admission!=null) {
            capturedKeys.add(admission.key);
            membership=admission.prelude(capturedKeys.size());
        }
        Object value=redis.eval(RedisTenantVersionAllocator.library()+membership+"""
            if redis.call('HGET',KEYS[1],'state')~='OPEN' then return redis.error_reply('TENANT_GENERATION_REJECTED') end
            local generation=redis.call('HGET',KEYS[1],'generation')
            cachedb_version_assert(KEYS[1],KEYS[2],KEYS[3],generation,cachedb_version_run_id())
            return generation
            """,capturedKeys,List.of());
        try{return new Generation(key,Long.parseLong((String)value));}
        catch(RuntimeException invalid){throw new ProtectionException(ProtectionException.Code.GENERATION_MISMATCH);}
    }
    /** Unknown legacy events are rejected; NEVER manufacture a stamp from the current generation at dequeue. */
    public Object evalStamped(TenantGenerationEnvelope<?> original,ProtectionKey expected,String ownerKey,String expectedOwner,
            String body,List<String> mutationKeys,List<String> mutationArgs) {
        if(original==null)throw new ProtectionException(ProtectionException.Code.MIGRATION_REQUIRED);
        if(!original.generation().key().equals(expected))throw new ProtectionException(ProtectionException.Code.GENERATION_MISMATCH);
        return evalOpen(original.generation(),ownerKey,expectedOwner,body,mutationKeys,mutationArgs);
    }
    /** Atomic generation+existing-owner prelude for an ordinary Redis mutation; NO SQL lookup. */
    public Object evalOpen(Generation generation,String ownerKey,String expectedOwner,String body,
            List<String> mutationKeys,List<String> mutationArgs) {
        if(body==null || body.length()>131072 || mutationKeys.size()>4096 || mutationArgs.size()>8192
                || expectedOwner==null || expectedOwner.length()>256)throw new IllegalArgumentException("Invalid guarded mutation");
        var allKeys=new ArrayList<String>(List.of(generationKey(generation.key()),ownerKey));allKeys.addAll(mutationKeys);
        String allocator=keys.namespacePrefix(namespace)+":security:version:"+generation.key().tenantToken();
        allKeys.add(allocator);allKeys.add(allocator+":next");
        var args=new ArrayList<String>(List.of(Long.toString(generation.value()),expectedOwner));args.addAll(mutationArgs);
        String membership="";
        if(admission!=null) {
            allKeys.add(admission.key);
            // Preserve the publication body's existing KEYS indices and allocator tail contract.
            membership=admission.prelude(allKeys.size())+"table.remove(KEYS)\n";
        }
        String program=RedisTenantVersionAllocator.library()+membership+"""
            if redis.call('HGET',KEYS[1],'state')~='OPEN' or redis.call('HGET',KEYS[1],'generation')~=ARGV[1]
               or redis.call('GET',KEYS[2])~=ARGV[2] then return redis.error_reply('TENANT_GENERATION_REJECTED') end
            cachedb_version_assert(KEYS[1],KEYS[#KEYS-1],KEYS[#KEYS],ARGV[1],cachedb_version_run_id())
            """+body;
        RedisProtectionArguments.validate(program,allKeys,args);
        return redis.eval(program,allKeys,args);
    }
    private static void admin(){if(!CacheTenantContext.isAdministrative())throw new ProtectionException(ProtectionException.Code.ADMIN_REQUIRED);}
}
