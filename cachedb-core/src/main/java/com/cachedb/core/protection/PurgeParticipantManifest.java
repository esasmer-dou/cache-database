package com.reactor.cachedb.core.protection;

import java.util.*;

/** Bounded explicit required stores for ONE entity-owned scope, not all tenant PII. */
public final class PurgeParticipantManifest {
    private final String entity;
    private final Map<String,PurgeParticipant> participants;
    private final Set<String> required;
    private final String fingerprint;
    public PurgeParticipantManifest(String entity,Set<String> required,Collection<PurgeParticipant> participants) {
        if(entity==null || entity.length()>128 || required==null || required.size()>64
                || participants==null || participants.size()>64)throw invalid();
        var registered=new TreeMap<String,PurgeParticipant>();
        for(var p:participants){
            var d=p.declaration();
            if(!d.entityNames().contains(entity) || registered.putIfAbsent(d.id(),p)!=null)throw invalid();
        }
        for(String id:required)if(id==null || !id.matches("[a-z0-9._-]{1,64}"))throw invalid();
        this.entity=entity;this.participants=Map.copyOf(registered);this.required=Set.copyOf(required);
        var manifest=new StringBuilder(entity);
        for(String id:new TreeSet<>(required)){
            var p=registered.get(id);manifest.append(':').append(id).append(':').append(p==null?"MISSING":p.declaration().ownershipFingerprint());
        }
        try{
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            fingerprint=java.util.HexFormat.of().formatHex(digest.digest(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable");}
    }
    public String entityName(){return entity;}
    public String fingerprint(){return fingerprint;}
    public Set<String> missing(){
        var missing=new TreeSet<>(required);missing.removeAll(participants.keySet());return Set.copyOf(missing);
    }
    public List<PurgeParticipant> requireAll(){
        if(!missing().isEmpty())throw new ProtectionException(ProtectionException.Code.INCOMPLETE_INVENTORY);
        return required.stream().sorted().map(participants::get).toList();
    }
    private static ProtectionException invalid(){return new ProtectionException(ProtectionException.Code.INVALID_REQUEST);}
}
