package com.reactor.cachedb.core.config;

public record MutationConfig(int maxPatchAttempts, boolean coldPatchEnabled) {
    public MutationConfig {
        if (maxPatchAttempts < 1 || maxPatchAttempts > 16)
            throw new IllegalArgumentException("maxPatchAttempts must be 1..16");
    }
    public static MutationConfig defaults() { return new MutationConfig(4, true); }
}
