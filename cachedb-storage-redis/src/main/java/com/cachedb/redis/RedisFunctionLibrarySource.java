package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.RedisFunctionsConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class RedisFunctionLibrarySource {
    public static final String UPSERT_BODY_TOKEN = "__CACHEDB_UPSERT_BODY__";
    public static final String UPSERT_BODY_RESOURCE = "/functions/cachedb-upsert-body.lua";
    public static final String DELETE_BODY_TOKEN = "__CACHEDB_DELETE_BODY__";
    public static final String VERSION_BODY_TOKEN = "__CACHEDB_VERSION_ALLOCATOR__";
    public static final String VERSION_BODY_RESOURCE = "/functions/cachedb-version-allocator.lua";
    public static final String DELETE_BODY_RESOURCE = "/functions/cachedb-delete-body.lua";
    private static final String DEFAULT_TEMPLATE = "/functions/cachedb-functions.lua";

    public String render(RedisFunctionsConfig config) {
        Objects.requireNonNull(config);
        String source = config.sourceOverride();
        if (source == null || source.isBlank()) source = readClasspathResource(config.templateResourcePath());
        return source.replace(VERSION_BODY_TOKEN, renderVersionAllocator())
                .replace(UPSERT_BODY_TOKEN, readClasspathResource(UPSERT_BODY_RESOURCE))
                .replace(DELETE_BODY_TOKEN, readClasspathResource(DELETE_BODY_RESOURCE))
                .replace("__LIBRARY_NAME__", config.libraryName())
                .replace("__UPSERT_FUNCTION__", config.upsertFunctionName())
                .replace("__DELETE_FUNCTION__", config.deleteFunctionName())
                .replace("__COMPACTION_COMPLETE_FUNCTION__", config.compactionCompleteFunctionName());
    }

    public String renderVersionAllocator() {
        return readClasspathResource(VERSION_BODY_RESOURCE)
                + "\n" + readClasspathResource("/functions/cachedb-runtime-admission.lua");
    }

    /** Explicit shared resource contract, never extracts code from a Lua program. */
    public String renderDeleteBody(RedisFunctionsConfig config) {
        Objects.requireNonNull(config);
        if (!config.enabled() || !DEFAULT_TEMPLATE.equals(config.templateResourcePath())
                || config.sourceOverride() != null && !config.sourceOverride().isBlank())
            throw new IllegalArgumentException("Native retention DELETE requires the canonical function template");
        return readClasspathResource(DELETE_BODY_RESOURCE);
    }

    private String readClasspathResource(String resourcePath) {
        try (InputStream inputStream = getClass().getResourceAsStream(resourcePath)) {
            if (inputStream == null) throw new IllegalStateException("Redis function resource unavailable");
            byte[] bytes = inputStream.readNBytes(262145);
            if (bytes.length > 262144) throw new IllegalStateException("Redis function resource exceeds limit");
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read Redis function resource");
        }
    }
}
