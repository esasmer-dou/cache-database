package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.config.RedisFunctionsConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RedisFunctionsVersionContractTest {
    static final String LEGACY_RESOURCE = "/functions/cachedb-functions-v0181-3a212461.lua";
    static final String LEGACY_SHA256 =
            "0368c05267cf5a23156bb48ce51199d812ad326b31a53fcb63c9010b19b6f445";

    @Test
    void defaultsUseDisjointVersionedLibraryAndFunctionNames() {
        var config = RedisFunctionsConfig.defaults();
        assertEquals("cachedb_v019", config.libraryName());
        assertEquals("entity_upsert_v019", config.upsertFunctionName());
        assertEquals("entity_delete_v019", config.deleteFunctionName());
        assertEquals("compaction_complete_v019", config.compactionCompleteFunctionName());
        assertEquals("0.19.0", config.libraryVersion());
        assertEquals(config, RedisFunctionsConfig.builder().build());
        var oldNames = Set.of("cachedb", "entity_upsert", "entity_delete", "compaction_complete");
        for (var name : Set.of(config.libraryName(), config.upsertFunctionName(),
                config.deleteFunctionName(), config.compactionCompleteFunctionName(),
                config.deleteFunctionName() + "_bulk")) {
            assertFalse(oldNames.contains(name), name);
        }
        assertTrue(config.enabled());
        assertTrue(config.autoLoadLibrary());
        assertTrue(config.replaceLibraryOnLoad());
        assertTrue(config.strictLoading());
    }

    @Test
    void tenArgumentConstructorKeepsExplicitNamesButUsesNewVersionFallback() {
        var defaults = RedisFunctionsConfig.defaults();
        var config = new RedisFunctionsConfig(true, true, true, true,
                "custom_library", "custom_upsert", "custom_delete", "custom_complete",
                defaults.templateResourcePath(), null);
        assertEquals("0.19.0", config.libraryVersion());
        assertEquals("custom_library", config.libraryName());
        assertEquals("custom_upsert", config.upsertFunctionName());
        assertEquals("custom_delete", config.deleteFunctionName());
        assertEquals("custom_complete", config.compactionCompleteFunctionName());
    }

    @Test
    void elevenArgumentConstructorStillAllowsAnExplicitLegacyVersionPin() {
        var config = new RedisFunctionsConfig(true, true, true, true,
                "pinned_library", "pinned_upsert", "pinned_delete", "pinned_complete",
                RedisFunctionsConfig.defaults().templateResourcePath(), legacySource(), "0.18.0");
        assertEquals("0.18.0", config.libraryVersion());
        assertEquals(legacySource(), config.sourceOverride());
    }

    @Test
    void historicalFixtureIsTheExactCommittedLegacyTemplate() {
        String source = legacySource();
        assertEquals(LEGACY_SHA256, sha256(source));
        assertEquals(11052, source.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(source.startsWith("#!lua name=__LIBRARY_NAME__\n"));
        assertTrue(source.contains("redis.register_function('__UPSERT_FUNCTION__'"));
        assertTrue(source.contains("redis.register_function('__DELETE_FUNCTION__'"));
        assertTrue(source.contains("redis.register_function('__COMPACTION_COMPLETE_FUNCTION__'"));
        assertFalse(source.contains("__CACHEDB_DELETE_BODY__"));
        assertFalse(source.contains("__CACHEDB_VERSION_ALLOCATOR__"));
    }

    static String legacySource() {
        try (var input = RedisFunctionsVersionContractTest.class.getResourceAsStream(LEGACY_RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing historical Redis Function fixture");
            byte[] bytes = input.readNBytes(262145);
            if (bytes.length > 262144) throw new IllegalStateException("Historical fixture exceeds bound");
            String source = new String(bytes, StandardCharsets.UTF_8);
            if (!LEGACY_SHA256.equals(sha256(source)))
                throw new IllegalStateException("Historical Redis Function fixture changed");
            return source;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read historical Redis Function fixture", failure);
        }
    }

    static String sha256(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
