package com.reactor.cachedb.spring.boot;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, shareable checkpoint payload for resumable distributed jobs. */
public record CacheDistributedJobProgress(
        String phase,
        int attempt,
        Integer percent,
        String message,
        Map<String, String> attributes
) {
    public static final int MAX_ATTRIBUTES = 16;

    public CacheDistributedJobProgress {
        if (phase == null || !phase.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")) {
            throw new IllegalArgumentException("phase must be a safe value up to 64 characters");
        }
        phase = phase.trim();
        if (attempt <= 0) {
            throw new IllegalArgumentException("attempt must be greater than zero");
        }
        if (percent != null && (percent < 0 || percent > 100)) {
            throw new IllegalArgumentException("percent must be between 0 and 100");
        }
        message = message == null ? "" : message.trim();
        if (message.length() > 512) {
            throw new IllegalArgumentException("message must not exceed 512 characters");
        }
        attributes = validateAttributes(attributes);
    }

    public static CacheDistributedJobProgress phase(String phase, int attempt) {
        return new CacheDistributedJobProgress(phase, attempt, null, "", Map.of());
    }

    public static CacheDistributedJobProgress completed(int attempt) {
        return new CacheDistributedJobProgress("COMPLETED", attempt, 100, "", Map.of());
    }

    public CacheDistributedJobProgress withAttribute(String key, String value) {
        LinkedHashMap<String, String> updated = new LinkedHashMap<>(attributes);
        updated.put(key, value);
        return new CacheDistributedJobProgress(phase, attempt, percent, message, updated);
    }

    private static Map<String, String> validateAttributes(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        if (values.size() > MAX_ATTRIBUTES) {
            throw new IllegalArgumentException("attributes must not exceed " + MAX_ATTRIBUTES + " entries");
        }
        LinkedHashMap<String, String> copy = new LinkedHashMap<>(values.size());
        values.forEach((key, value) -> {
            if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")) {
                throw new IllegalArgumentException("attribute keys must be safe values up to 64 characters");
            }
            if (value == null || value.length() > 256) {
                throw new IllegalArgumentException("attribute values must not be null or exceed 256 characters");
            }
            copy.put(key, value);
        });
        return Map.copyOf(copy);
    }
}
