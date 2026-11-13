package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.protection.ProtectionException;
import java.util.List;

/** Input accounting without allocating an encoded copy for each untrusted string. */
final class RedisProtectionArguments {
    private RedisProtectionArguments() {}
    static void layout(RedisGenerationBarrier barrier,RedisKeyStrategy keys,String namespace) {
        var probe=com.reactor.cachedb.core.protection.ProtectionKey.of(namespace,"layout-probe");
        String actual=barrier.generationKey(probe);
        if(!actual.equals(keys.namespacePrefix(namespace)+":generation:"+probe.tenantToken()))invalid();
        var roles=List.of(keys.entityKey(namespace,"probe"),keys.versionKey(namespace,"probe"),
                keys.tombstoneKey(namespace,"probe"),keys.durabilityKey(namespace,"probe"),
                keys.indexMetaKey(namespace,"probe"),keys.tenantHotOwnerKey(namespace,"probe"),
                keys.compactionPayloadKey(namespace,"probe"),keys.compactionPendingKey(namespace,"probe"));
        if(new java.util.HashSet<>(roles).size()!=roles.size())invalid();
    }
    static void validate(String script,List<String> keys,List<String> args) {
        validate(script,keys,args,262144);
    }
    static void validateInitial(String script,List<String> keys,List<String> args) {
        validate(script,keys,args,1048576);
    }
    private static void validate(String script,List<String> keys,List<String> args,int argumentLimit) {
        if(keys==null || args==null || keys.size()>4096 || args.size()>8192)invalid();
        long bytes=utf8(script,262144)+64L;
        for(String key:keys)bytes+=utf8(key,8192)+16L;
        for(String arg:args)bytes+=utf8(arg,argumentLimit)+16L;
        if(bytes>8*1024*1024)invalid();
    }
    private static int utf8(String value,int limit) {
        if(value==null || value.length()>limit)invalid();
        int bytes=0;
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(c<0x80)bytes++;
            else if(c<0x800)bytes+=2;
            else if(Character.isHighSurrogate(c)){
                if(++i>=value.length() || !Character.isLowSurrogate(value.charAt(i)))invalid();
                bytes+=4;
            } else if(Character.isLowSurrogate(c)){invalid();}
            else bytes+=3;
            if(bytes>limit)invalid();
        }
        return bytes;
    }
    private static void invalid(){throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);}
}
