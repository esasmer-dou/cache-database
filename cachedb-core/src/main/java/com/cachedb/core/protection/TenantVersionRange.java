package com.reactor.cachedb.core.protection;

import java.util.Objects;
import java.util.UUID;

/** One bounded durably reserved range. Unused numbers are burned, never recycled. */
public final class TenantVersionRange {
    public static final long MAX_VERSION=9_007_199_254_740_991L;
    public static final int MAX_RANGE=1_000_000;
    private final ProtectionKey key;
    private final long generation,controlEpoch,reservationEpoch,first,last;
    private final UUID request;
    private final String runId,inventory;
    public TenantVersionRange(ProtectionKey key,long generation,long controlEpoch,long reservationEpoch,
            long first,long last,UUID request,String runId,String inventory) {
        if(generation<1 || controlEpoch<1 || reservationEpoch<1 || first<1 || last<first
                || last>MAX_VERSION || last-first>=MAX_RANGE || runId==null || !runId.matches("[0-9a-f]{40}"))
            throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
        this.key=Objects.requireNonNull(key);this.generation=generation;this.controlEpoch=controlEpoch;
        this.reservationEpoch=reservationEpoch;this.first=first;this.last=last;this.request=Objects.requireNonNull(request);
        this.runId=runId;this.inventory=ProtectionKey.token(inventory);
    }
    public ProtectionKey key(){return key;} public long generation(){return generation;}
    public long controlEpoch(){return controlEpoch;} public long reservationEpoch(){return reservationEpoch;}
    public long first(){return first;} public long last(){return last;} public UUID request(){return request;}
    public String runId(){return runId;} public String inventory(){return inventory;}
    @Override public String toString(){return "TenantVersionRange[redacted]";}
}
