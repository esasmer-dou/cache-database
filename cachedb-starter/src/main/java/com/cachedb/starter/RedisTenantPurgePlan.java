package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.jdbc.*;
import com.reactor.cachedb.redis.*;
import java.util.*;

/** Explicit entity-owned routes. Transport/pools are application-owned and closed by their runtime. */
public final class RedisTenantPurgePlan {
    public record QueueRoute(String token,String stream,RedisQueueGenerationFence fence,RedisTenantQueueEraser eraser) {
        public QueueRoute { ProtectionKey.token(token);Objects.requireNonNull(fence);Objects.requireNonNull(eraser); }
        @Override public String toString(){return "PurgeQueueRoute[redacted]";}
    }
    final Binding binding;
    final String entityName,versionColumn;
    final JdbcTenantPurgeGuard guard;
    final JdbcTenantVersionAllocator allocator;
    final JdbcPurgeScanJournal inventory;
    final JdbcPurgeFacadeJournal journal;
    final JdbcPurgeParticipantJournal participants;
    final BoundedRedisWire wire;
    final RedisClientOperations redis;
    final RedisKeyStrategy keys;
    final RedisGenerationBarrier barrier;
    final TenantReadFence readFence;
    final List<RedisPurgeTarget> targets;
    final List<QueueRoute> queues;
    final Set<String> namespaces;
    final Map<String,RedisNearCacheCoordinator.Store> localL1;
    final PurgeParticipantManifest manifest;
    final String fingerprint;
    final CachePurgeAuditListener audit;

    public RedisTenantPurgePlan(Binding binding,String entityName,String sourceVersionColumn,
            JdbcTenantPurgeGuard guard,JdbcTenantVersionAllocator allocator,JdbcPurgeScanJournal inventory,
            JdbcPurgeFacadeJournal journal,JdbcPurgeParticipantJournal participants,
            BoundedRedisWire wire,RedisClientOperations redis,RedisKeyStrategy keys,RedisGenerationBarrier barrier,
            TenantReadFence readFence,List<RedisPurgeTarget> targets,Set<String> requiredNamespaces,
            Map<String,RedisNearCacheCoordinator.Store> localL1,
            Map<String,BoundedRedisWire> requiredQueuePrimaries,PurgeParticipantManifest manifest,
            CachePurgeAuditListener audit){
        this.binding=Objects.requireNonNull(binding);this.entityName=Objects.requireNonNull(entityName);
        if(!binding.tenantPurgeEnabled()||entityName.length()>128||sourceVersionColumn==null
                ||!sourceVersionColumn.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"))throw invalid();
        this.versionColumn=sourceVersionColumn;
        this.guard=Objects.requireNonNull(guard);this.allocator=Objects.requireNonNull(allocator);
        this.inventory=Objects.requireNonNull(inventory);this.journal=Objects.requireNonNull(journal);
        this.participants=Objects.requireNonNull(participants);this.wire=Objects.requireNonNull(wire);
        this.redis=Objects.requireNonNull(redis);this.keys=Objects.requireNonNull(keys);this.barrier=Objects.requireNonNull(barrier);
        this.readFence=Objects.requireNonNull(readFence);this.manifest=Objects.requireNonNull(manifest);this.audit=Objects.requireNonNull(audit);
        if(targets==null||targets.isEmpty()||targets.size()>8||requiredNamespaces==null
                ||requiredNamespaces.size()>8||localL1==null||localL1.size()>8||requiredQueuePrimaries==null
                ||requiredQueuePrimaries.isEmpty()||requiredQueuePrimaries.size()>128)throw invalid();
        this.targets=List.copyOf(targets);this.namespaces=Set.copyOf(requiredNamespaces);this.localL1=Map.copyOf(localL1);
        if(!entityName.equals(manifest.entityName())||!targets.getFirst().namespace().equals(binding.scope())
                ||!RedisIdentityPurgeParticipant.layoutFingerprint(targets).equals(binding.layoutToken())
                ||!targets.stream().map(RedisPurgeTarget::namespace).collect(java.util.stream.Collectors.toSet()).equals(namespaces))
            throw invalid();
        var routeList=new ArrayList<QueueRoute>();
        requiredQueuePrimaries.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->{
            // Complete route set includes the normal stream, retry, archive/outbox, recovery and DLQ streams.
            String stream=e.getKey();if(stream==null||stream.length()>1024)throw invalid();
            routeList.add(new QueueRoute(ProtectionKey.hash(stream),stream,new RedisQueueGenerationFence(redis,stream),
                    new RedisTenantQueueEraser(redis,Objects.requireNonNull(e.getValue()),stream,binding.scope())));
        });
        this.queues=List.copyOf(routeList);
        var signature=new StringBuilder(binding.fingerprint()).append(':').append(manifest.fingerprint()).append(':').append(versionColumn);
        for(var route:queues)signature.append(':').append(route.token());
        try{fingerprint=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(signature.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable");}
    }
    public Class<?> entityType(){return binding.entityType();}
    public String scope(){return binding.scope();}
    public String manifestToken(){return fingerprint;}
    private static ProtectionException invalid(){return new ProtectionException(ProtectionException.Code.INVALID_REQUEST);}
}
