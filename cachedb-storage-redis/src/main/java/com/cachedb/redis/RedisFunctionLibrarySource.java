package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.RedisFunctionsConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class RedisFunctionLibrarySource {

    public String render(RedisFunctionsConfig config) {
        String source = config.sourceOverride();
        if (source == null || source.isBlank()) {
            source = readClasspathResource(config.templateResourcePath());
        }

        return source
                .replace("__LIBRARY_NAME__", config.libraryName())
                .replace("__UPSERT_FUNCTION__", config.upsertFunctionName())
                .replace("__DELETE_FUNCTION__", config.deleteFunctionName())
                .replace("__COMPACTION_COMPLETE_FUNCTION__", config.compactionCompleteFunctionName());
    }

    private String readClasspathResource(String resourcePath) {
        try (InputStream inputStream = getClass().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("Redis function template not found: " + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read Redis function template: " + resourcePath, exception);
        }
    }
}
