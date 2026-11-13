package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease;
import com.reactor.cachedb.jdbc.*;
import com.reactor.cachedb.redis.*;
import java.util.*;

/**
 * Native identity participant + durable checkpoint integration, not an automatic universal purge.
 * The surrounding coordinator owns closure/discovery, queue cleanup, source erasure and completion.
 */
public final class RedisIdentityPurgeRuntime {
    private final RedisIdentityPurgeParticipant participant;
    private final JdbcPurgeParticipantJournal checkpoints;
    public RedisIdentityPurgeRuntime(BoundedRedisWire wire,RedisClientOperations redis,RedisKeyStrategy keys,
            RedisGenerationBarrier barrier,String namespace,String tenant,String entityName,String expectedLayout,
            Set<String> requiredNamespaces,List<RedisPurgeTarget> targets,JdbcPurgeScanJournal discovery,
            JdbcPurgeParticipantJournal checkpoints,RedisIdentityPurgeParticipant.Closure closure,
            Map<String,RedisNearCacheCoordinator.Store> localL1){
        this.checkpoints=Objects.requireNonNull(checkpoints);
        var inventory=new RedisIdentityPurgeParticipant.Inventory(){
            public boolean sealed(Lease lease){return discovery.discoveryComplete(lease);}
            public List<PurgeWorkItem> page(Lease lease,String after,int limit,int bytes){
                return discovery.page(lease,PurgeWorkItem.Kind.ID,after,limit,bytes);
            }
        };
        participant=new RedisIdentityPurgeParticipant(wire,redis,keys,barrier,namespace,tenant,entityName,expectedLayout,
                requiredNamespaces,targets,inventory,Objects.requireNonNull(closure),localL1);
    }
    /** Every call renews SQL ownership and re-attests live Redis closure, including previously VERIFIED phases. */
    public JdbcPurgeParticipantJournal.Applied step(Lease lease,long generation,PurgeParticipant.Phase phase,int maxBytes){
        return checkpoints.step(lease,generation,participant,phase,maxBytes);
    }
    public PurgeParticipant.Declaration declaration(){return participant.declaration();}
}
