package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.util.*;

/** Native range installation and same-command allocation/mutation. Foreground uses Redis only. */
public final class RedisTenantVersionAllocator {
    private static final String LIBRARY=new RedisFunctionLibrarySource().renderVersionAllocator();
    private final RedisClientOperations redis;
    private final RedisGenerationBarrier barrier;
    private final RedisKeyStrategy keys;
    private final String namespace;
    public RedisTenantVersionAllocator(RedisClientOperations redis,RedisKeyStrategy keys,RedisGenerationBarrier barrier,String namespace) {
        this.redis=Objects.requireNonNull(redis);this.keys=Objects.requireNonNull(keys);
        this.barrier=Objects.requireNonNull(barrier);this.namespace=Objects.requireNonNull(namespace);
        RedisProtectionArguments.layout(barrier,keys,namespace);
    }
    public static String library(){return LIBRARY;}
    public String allocatorKey(ProtectionKey key){
        barrier.generationKey(key);return keys.namespacePrefix(namespace)+":security:version:"+key.tenantToken();
    }
    public String counterKey(ProtectionKey key){return allocatorKey(key)+":next";}
    public String runId(ProtectionKey key){return (String)redis.eval(library()+"return cachedb_version_run_id()",List.of(barrier.generationKey(key)),List.of());}
    public void install(TenantVersionRange range) {
        if(!CacheTenantContext.isAdministrative())throw new ProtectionException(ProtectionException.Code.ADMIN_REQUIRED);
        var k=List.of(barrier.generationKey(range.key()),allocatorKey(range.key()),counterKey(range.key()));
        redis.eval(library()+INSTALL,k,rangeArguments(range));
    }
    void installClosed(TenantVersionRange range,TenantPurgeProtocol.Lease lease,RedisTenantRuntimeControl control) {
        if(!range.key().equals(lease.key()) || range.controlEpoch()!=lease.epoch())
            throw new ProtectionException(ProtectionException.Code.GENERATION_MISMATCH);
        Object result=control.initialClosed(lease,range.generation(),INSTALL,
                List.of(barrier.generationKey(range.key()),allocatorKey(range.key()),counterKey(range.key())),rangeArguments(range));
        if(!(result instanceof Number n) || n.longValue()<0 || n.longValue()>1)
            throw new ProtectionException(ProtectionException.Code.RECOVERY_REQUIRED);
    }
    private static List<String> rangeArguments(TenantVersionRange range) {
        return List.of(Long.toString(range.generation()),Long.toString(range.controlEpoch()),Long.toString(range.reservationEpoch()),
                Long.toString(range.first()-1),Long.toString(range.last()),range.request().toString(),range.runId(),range.inventory());
    }
    private static final String INSTALL="""
        if redis.call('HGET',KEYS[1],'e:to')==ARGV[1] and redis.call('HGET',KEYS[1],'state')~='OPEN'
            then return redis.error_reply('CDB_VERSION_RECOVERY_REQUIRED') end
            local function less(a,b) if #a~=#b then return #a<#b end return a<b end
            if redis.call('HGET',KEYS[1],'generation')~=ARGV[1] or redis.call('HGET',KEYS[1],'epoch')~=ARGV[2]
                or (redis.call('HGET',KEYS[1],'state')~='OPEN' and redis.call('HGET',KEYS[1],'state')~='CLOSED')
                or cachedb_version_run_id()~=ARGV[7] then return redis.error_reply('CDB_VERSION_RECOVERY_REQUIRED') end
            local previous=redis.call('HGET',KEYS[2],'reservation_epoch')
            if previous and less(ARGV[3],previous) then return redis.error_reply('CDB_VERSION_STALE_RANGE') end
            if previous==ARGV[3] then
                if redis.call('HGET',KEYS[2],'request')~=ARGV[6] or redis.call('HGET',KEYS[2],'floor')~=ARGV[4]
                    or redis.call('HGET',KEYS[2],'ceiling')~=ARGV[5] or redis.call('HGET',KEYS[2],'generation')~=ARGV[1]
                    or redis.call('HGET',KEYS[2],'run_id')~=ARGV[7] then return redis.error_reply('CDB_VERSION_STALE_RANGE') end
                -- Idempotent install NEVER rewinds a consumed counter.
                cachedb_version_assert(KEYS[1],KEYS[2],KEYS[3],ARGV[1],ARGV[7]);return 0
            end
            local priorCeiling=redis.call('HGET',KEYS[2],'ceiling')
            if priorCeiling and less(ARGV[4],priorCeiling) then return redis.error_reply('CDB_VERSION_STALE_RANGE') end
            redis.call('HSET',KEYS[2],'generation',ARGV[1],'control_epoch',ARGV[2],'reservation_epoch',ARGV[3],
                'floor',ARGV[4],'ceiling',ARGV[5],'request',ARGV[6],'run_id',ARGV[7],'inventory',ARGV[8])
            redis.call('PERSIST',KEYS[2]);redis.call('SET',KEYS[3],ARGV[4]);return 1
            """;
    /** Body is an explicit trusted producer template; version is an exact decimal local variable. */
    public String mutate(Generation stamp,String ownerKey,String expectedOwner,String versionKey,String expectedVersion,
            List<String> mutationKeys,List<String> mutationArgs,String body) {
        if(body==null || body.length()>131072 || mutationKeys.size()>4096 || mutationArgs.size()>8192
                || expectedOwner==null || expectedOwner.length()>256
                || !("-1".equals(expectedVersion) || expectedVersion!=null&&expectedVersion.matches("0|[1-9][0-9]{0,15}")))
            throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
        var k=new ArrayList<>(List.of(barrier.generationKey(stamp.key()),allocatorKey(stamp.key()),counterKey(stamp.key()),ownerKey,versionKey));
        k.addAll(mutationKeys);var a=new ArrayList<>(List.of(Long.toString(stamp.value()),expectedOwner,expectedVersion));a.addAll(mutationArgs);
        String program=library()+"""
            if redis.call('GET',KEYS[4])~=ARGV[2] then return redis.error_reply('CacheDB tenant access denied') end
            local current=redis.call('GET',KEYS[5]) or '0'
            if ARGV[3]~='-1' and current~=ARGV[3] then return redis.error_reply('CDB_VERSION_CAS_CONFLICT') end
            local version=cachedb_version_next(KEYS[1],KEYS[2],KEYS[3],ARGV[1],current)
            redis.call('SET',KEYS[5],version)
            """+body+"\nreturn version";
        RedisProtectionArguments.validate(program,k,a);
        return (String)redis.eval(program,k,a);
    }
}
