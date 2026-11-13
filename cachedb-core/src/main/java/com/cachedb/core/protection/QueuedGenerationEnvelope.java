package com.reactor.cachedb.core.protection;

import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation;
import java.util.*;

/** Immutable bounded original stamps; public queue record arity remains unchanged. */
public final class QueuedGenerationEnvelope {
    public record Identity(String namespace,String id,long version) {
        public Identity {
            if(namespace==null || namespace.length()>128 || id==null || id.length()>1024 || version<0)
                throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
        }
        @Override public String toString(){return "QueuedIdentity[redacted]";}
    }
    private final QueuedWriteOperation operation;
    private final Map<Identity,Generation> stamps;
    public QueuedGenerationEnvelope(QueuedWriteOperation operation,Map<Identity,Generation> stamps) {
        this.operation=Objects.requireNonNull(operation);
        if(stamps==null || stamps.size()>128)throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
        this.stamps=Map.copyOf(stamps);
        for(var entry:this.stamps.entrySet()){
            if(!ProtectionKey.hash(entry.getKey().namespace()).equals(entry.getValue().key().scopeToken()))
                throw new ProtectionException(ProtectionException.Code.GENERATION_MISMATCH);
        }
        var expected=new HashSet<Identity>();
        var members=operation.atomicBatch()?operation.batchOperations():List.of(operation);
        for(var member:members)expected.add(identity(member));
        if(!expected.containsAll(stamps.keySet()))throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
    }
    public static Identity identity(QueuedWriteOperation operation){
        return new Identity(operation.redisNamespace(),operation.id(),operation.version());
    }
    public QueuedWriteOperation operation(){return operation;}
    public Map<Identity,Generation> stamps(){return stamps;}
    public boolean protectedEnvelope(){return !stamps.isEmpty();}
    public Generation generation(QueuedWriteOperation member){return stamps.get(identity(member));}
    @Override public String toString(){return "QueuedGenerationEnvelope[redacted]";}
}
