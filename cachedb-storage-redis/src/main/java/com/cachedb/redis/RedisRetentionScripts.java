package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.RedisFunctionsConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class RedisRetentionScripts {
    static String read(String name) {
        String path="/com/reactor/cachedb/redis/retention/"+name+".lua";
        try (var input=RedisRetentionScripts.class.getResourceAsStream(path)) {
            if (input==null) throw new IllegalArgumentException("Missing retention script");
            byte[] bytes=input.readNBytes(131073);
            if (bytes.length>131072) throw new IllegalArgumentException("Oversized retention script");
            return new String(bytes,StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Unable to read retention script"); }
    }
    static String mutation(RedisFunctionsConfig config) {
        var source=new RedisFunctionLibrarySource();
        return source.renderVersionAllocator()+"\n"+source.renderDeleteBody(config)+"\n"+read("mutate");
    }
    private RedisRetentionScripts() {}
}
