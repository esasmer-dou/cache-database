package com.reactor.cachedb.core.protection;

import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.registry.EntityBinding;
import java.util.Collection;

/** Explicit entity-owned namespace/source scope, never a tenant-wide PII promise. */
public final class PurgeEntityScope {
    private PurgeEntityScope(){}
    public static void validate(EntityMetadata<?,?> requested,Collection<EntityBinding<?,?>> registered,
            PurgeParticipantManifest manifest,boolean includeSource){
        if(!requested.tenantPurgeEnabled() || !requested.entityName().equals(manifest.entityName()))throw invalid();
        manifest.requireAll();int count=0;boolean found=false;
        for(var binding:registered){
            if(++count>10000)throw invalid();var metadata=binding.metadata();
            if(metadata.entityName().equals(requested.entityName())){found=true;continue;}
            if(metadata.redisNamespace().equals(requested.redisNamespace())
                    || includeSource && metadata.tableName().equalsIgnoreCase(requested.tableName()))throw invalid();
        }
        if(!found)throw invalid();
        // Unregistered external stores are not discovered: the manifest must declare every required store.
    }
    private static ProtectionException invalid(){return new ProtectionException(ProtectionException.Code.INCOMPLETE_INVENTORY);}
}
