package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.InitialTenantBootstrap.*;
import java.util.*;

/** Bounded data codec only. Encoding ACTIVE is not observation, authorization or a database write. */
public final class InitialBootstrapJournalCodec {
    public static final int MAX_BYTES=InitialBootstrapState.MAX_BYTES;
    private InitialBootstrapJournalCodec() {}
    public record Frame(UUID operation,long generation,long revision,long probes,long warmed,long residentBytes,
            String plan,String manifest,String operator,String runId,String roster,String inventory,String runtimeProof,
            String cursor,String windowDigest,int pages,int verifiedPages,Phase phase,Activation activation,boolean finalizing) {
        @Override public String toString(){return "InitialBootstrapFrame[redacted]";}
    }
    public static Frame decode(byte[] bytes,ProtectionKey key) {
        var s=InitialBootstrapState.decode(bytes,key);
        return new Frame(s.operation,s.generation,s.revision,s.probes,s.warmed,s.residentBytes,s.plan,s.manifest,
                s.operator,s.runId,s.roster,s.inventory,s.runtimeProof,s.cursor,s.windowDigest,s.pages,s.verifiedPages,
                s.phase,s.activation,s.finalizing);
    }
    public static byte[] encode(Frame f,ProtectionKey key) {
        Objects.requireNonNull(f);Objects.requireNonNull(key);var s=new InitialBootstrapState();
        int units=0;
        for(String value:new String[]{f.plan(),f.manifest(),f.operator(),f.runId(),f.roster(),f.inventory(),
                f.runtimeProof(),f.cursor(),f.windowDigest()}) {
            if(value!=null && (value.length()>1024 || (units+=value.length())>4096))
                throw new ProtectionException(ProtectionException.Code.INVALID_PROGRESS);
        }
        s.operation=f.operation();s.generation=f.generation();s.revision=f.revision();s.probes=f.probes();
        s.warmed=f.warmed();s.residentBytes=f.residentBytes();s.plan=f.plan();s.manifest=f.manifest();
        s.operator=f.operator();s.runId=f.runId();s.roster=f.roster();s.inventory=f.inventory();
        s.runtimeProof=f.runtimeProof();s.cursor=f.cursor();s.windowDigest=f.windowDigest();s.pages=f.pages();
        s.verifiedPages=f.verifiedPages();s.phase=f.phase();s.activation=f.activation();s.finalizing=f.finalizing();
        byte[] bytes=s.encode();var checked=InitialBootstrapState.decode(bytes,key);
        if(f.activation()!=null && !activationFields(f.activation()).equals(activationFields(checked.activation)))
            throw new ProtectionException(ProtectionException.Code.INVALID_PROGRESS);
        return bytes;
    }
    /** All17 immutable activation/window/grant values; never a native authority receipt. */
    public static List<String> activationFields(Activation a) {
        Objects.requireNonNull(a);var r=a.range();
        return List.of(a.token(),a.planToken(),a.manifestToken(),a.inventoryToken(),Long.toString(a.window().rows()),
                a.window().digest(),a.runtimeProof(),r.key().scopeToken(),r.key().tenantToken(),Long.toString(r.generation()),
                Long.toString(r.controlEpoch()),Long.toString(r.reservationEpoch()),Long.toString(r.first()),
                Long.toString(r.last()),r.request().toString(),r.runId(),r.inventory());
    }
}
