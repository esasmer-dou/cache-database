package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.config.RedisFunctionsConfig;
import org.junit.jupiter.api.Test;

class RedisRetentionScriptsTest {
    @Test void mutationEmbedsTheExplicitSharedProducerWithoutSourceParsingOrInjection() {
        var config=RedisFunctionsConfig.defaults();
        String body=new RedisFunctionLibrarySource().renderDeleteBody(config);
        String script=RedisRetentionScripts.mutation(config);
        assertEquals(new RedisFunctionLibrarySource().renderVersionAllocator()+"\n"+body+"\n"+RedisRetentionScripts.read("mutate"),script);
        assertTrue(script.contains("local function cachedb_delete("));
        assertTrue(script.contains("local version=cachedb_delete(dk,x.args,allocatorRunId)"));
        assertFalse(script.contains("redis.register_function"));
        assertTrue(script.contains("RETENTION_LEASE_LOST"));
    }
    @Test void generationIsComparedBeforeAnyDeletionAndEvictionPublicationIsConditional() {
        String script=RedisRetentionScripts.read("mutate");
        assertTrue(script.indexOf("~='OPEN'")<script.indexOf("redis.call('DEL',KEYS[x.k[1]])"));
        assertTrue(script.contains("'generation')~=x.gen"));
        assertTrue(script.contains("str(x.k[1])==x.payload"));
        assertTrue(script.contains("if x.channel~='' then redis.call('PUBLISH',x.channel,x.message) end"));
        assertThrows(IllegalArgumentException.class,()->RedisRetentionScripts.mutation(
                RedisFunctionsConfig.builder().sourceOverride("bad").build()));
    }
}
