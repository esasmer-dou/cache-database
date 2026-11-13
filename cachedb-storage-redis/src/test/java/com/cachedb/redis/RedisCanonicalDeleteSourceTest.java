package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.config.RedisFunctionsConfig;
import org.junit.jupiter.api.Test;

class RedisCanonicalDeleteSourceTest {
    @Test void normalProducerAndRetentionUseTheExactExplicitResource() {
        var renderer=new RedisFunctionLibrarySource();
        var config=RedisFunctionsConfig.defaults();
        String body=renderer.renderDeleteBody(config);
        String normal=renderer.render(config);
        assertTrue(normal.contains(body));
        assertEquals(1,normal.split("local function cachedb_delete\\(", -1).length-1);
        assertTrue(normal.contains("return cachedb_delete(keys, args)"));
        assertFalse(normal.contains(RedisFunctionLibrarySource.DELETE_BODY_TOKEN));
        assertTrue(body.contains("args[22]"));
        assertTrue(body.contains("generation ~= nil"));
        int generationGuard = body.indexOf("CacheDB generation closed or changed");
        var legacyIncrement = java.util.regex.Pattern.compile("redis\\.call\\('INCR',\\s*versionKey\\)").matcher(body);
        assertTrue(generationGuard >= 0, "Canonical DELETE must reject a closed or changed generation");
        assertTrue(legacyIncrement.find(), "Canonical DELETE must retain the unprotected version increment");
        assertTrue(generationGuard < legacyIncrement.start(), "Generation rejection must precede version mutation");
        int protectedAllocation = body.indexOf("cachedb_version_next(");
        assertTrue(protectedAllocation >= 0, "Canonical DELETE must retain protected version allocation");
        assertTrue(generationGuard < protectedAllocation, "Generation rejection must precede protected allocation");
    }
    @Test void customWholeProgramsRetainTheirOrdinaryRenderContractButCannotBeExtracted() {
        var renderer=new RedisFunctionLibrarySource();
        var custom=RedisFunctionsConfig.builder().sourceOverride("custom-__LIBRARY_NAME__").build();
        assertEquals("custom-" + custom.libraryName(),renderer.render(custom));
        assertThrows(IllegalArgumentException.class,()->renderer.renderDeleteBody(custom));
        assertThrows(IllegalArgumentException.class,()->renderer.renderDeleteBody(
                RedisFunctionsConfig.builder().templateResourcePath("/custom.lua").build()));
        assertThrows(IllegalArgumentException.class,()->renderer.renderDeleteBody(
                RedisFunctionsConfig.builder().enabled(false).build()));
    }
}
