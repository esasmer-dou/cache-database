package com.reactor.cachedb.core.protection;

import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease;
import java.util.*;

/**
 * Explicit entity-owned store contract, not automatic tenant-PII discovery.
 * Every mutating step must enforce the original generation and live fenced ownership atomically.
 */
public interface PurgeParticipant {
    enum State { MORE, VERIFIED, INCOMPLETE }
    enum Phase { CLOSE, DISCOVER, ERASE, VERIFY }
    record Declaration(String id,String ownershipFingerprint,Set<String> entityNames,boolean mixedOwnership) {
        public Declaration {
            if(id==null || !id.matches("[a-z0-9._-]{1,64}") || entityNames==null || entityNames.isEmpty() || entityNames.size()>64)
                throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
            ProtectionKey.token(ownershipFingerprint);entityNames=Set.copyOf(entityNames);
            for(String entity:entityNames)if(entity==null || entity.length()>128)throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
        }
    }
    record Request(Lease lease,long generation,Phase phase,String checkpoint,int maxItems,int maxBytes) {
        public Request {
            Objects.requireNonNull(lease);Objects.requireNonNull(phase);
            if(generation<1 || checkpoint==null || checkpoint.length()>1024 || maxItems<1 || maxItems>128 || maxBytes<1024 || maxBytes>1024*1024)
                throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
        }
    }
    record Result(State state,String checkpoint,long examined,long removed,String evidence) {
        public Result {
            Objects.requireNonNull(state);
            if(checkpoint==null || checkpoint.length()>1024 || examined<0 || examined>128 || removed<0 || removed>1048576)
                throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
            if(state==State.VERIFIED || evidence!=null && !evidence.isEmpty())ProtectionKey.token(evidence);
        }
    }
    Declaration declaration();
    /** Return INCOMPLETE for unknown ownership/partial mixed projections; never erase a foreign contribution. */
    Result step(Request request);
}
