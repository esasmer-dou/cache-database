package com.reactor.cachedb.redis;

import java.util.*;

/** Export from native descriptors, not an independently guessed page/projection key convention. */
public final class RedisPurgeRouteLayout {
    private RedisPurgeRouteLayout(){}
    public static Map<String,RedisPurgeScanner.Kind> pages(RedisPurgeTarget target){
        var result=new LinkedHashMap<String,RedisPurgeScanner.Kind>();
        for(var page:target.pages)result.put(target.indexes.keys.pageKey(target.namespace(),page.number()),
                page.kind()==RedisPurgeTarget.PageKind.LIST?RedisPurgeScanner.Kind.LIST:RedisPurgeScanner.Kind.ZSET);
        return Map.copyOf(result);
    }
    public static String tenantColumn(RedisPurgeTarget target){return target.tenantColumn;}
    public static boolean identityPreserving(RedisPurgeTarget target){return target.identityPreserving;}
}
