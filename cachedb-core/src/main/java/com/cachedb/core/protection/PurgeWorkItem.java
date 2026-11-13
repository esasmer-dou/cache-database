package com.reactor.cachedb.core.protection;

import java.util.Arrays;
public record PurgeWorkItem(String token,Kind kind,byte[] locator) {
    public enum Kind { ID, KEY, ENTRY }
    public PurgeWorkItem {
        ProtectionKey.token(token);
        if(kind==null || locator==null || locator.length<1 || locator.length>1024*1024
                || kind!=Kind.ENTRY && locator.length>1024)throw new IllegalArgumentException("Invalid purge work item");
        locator=locator.clone();
    }
    @Override public byte[] locator(){return locator.clone();}
    @Override public boolean equals(Object other){return other instanceof PurgeWorkItem w && token.equals(w.token)&&kind==w.kind&&Arrays.equals(locator,w.locator);}
    @Override public int hashCode(){return 31*(31*token.hashCode()+kind.hashCode())+Arrays.hashCode(locator);}
    @Override public String toString(){return "PurgeWorkItem[redacted]";}
}
