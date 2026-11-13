package com.reactor.cachedb.core.protection;

import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import java.util.*;

/** Explicit worker scope, not a process-wide identity cache or a business/trace field carrier. */
public final class TenantWriteStampContext {
    private static final ThreadLocal<Scope> CURRENT=new ThreadLocal<>();
    private TenantWriteStampContext(){}
    public static Scope restore(Collection<QueuedGenerationEnvelope> envelopes) {
        if(envelopes==null || envelopes.size()>10000)throw invalid();
        Map<QueuedGenerationEnvelope.Identity,Generation> entries=Map.of();
        for(var envelope:envelopes)if(envelope.protectedEnvelope()){
            if(entries.isEmpty())entries=new HashMap<>();
            for(var entry:envelope.stamps().entrySet()){
                Generation old=entries.putIfAbsent(entry.getKey(),entry.getValue());
                if(old!=null && !old.equals(entry.getValue()))throw invalid();
                if(entries.size()>128)throw invalid();
            }
        }
        var scope=new Scope(Thread.currentThread(),CURRENT.get(),Map.copyOf(entries));
        CURRENT.set(scope);return scope;
    }
    public static boolean hasStamps(){var scope=CURRENT.get();return scope!=null&&!scope.entries.isEmpty();}
    public static Generation current(QueuedWriteOperation operation){
        var scope=CURRENT.get();return scope==null||scope.entries.isEmpty()?null:scope.entries.get(QueuedGenerationEnvelope.identity(operation));
    }
    public static Generation require(QueuedWriteOperation operation){
        Generation value=current(operation);
        if(value==null)throw new ProtectionException(ProtectionException.Code.MIGRATION_REQUIRED);
        return value;
    }
    public static final class Scope implements AutoCloseable {
        private final Thread owner;private final Scope before;
        private final Map<QueuedGenerationEnvelope.Identity,Generation> entries;private boolean closed;
        private final int depth;
        private Scope(Thread owner,Scope before,Map<QueuedGenerationEnvelope.Identity,Generation> entries){
            this.owner=owner;this.before=before;this.entries=entries;depth=before==null?1:before.depth+1;
            if(depth>64)throw invalid();
        }
        public void close(){
            if(Thread.currentThread()!=owner || closed || CURRENT.get()!=this)throw invalid();
            closed=true;if(before==null)CURRENT.remove();else CURRENT.set(before);
        }
    }
    private static ProtectionException invalid(){return new ProtectionException(ProtectionException.Code.INVALID_REQUEST);}
}
