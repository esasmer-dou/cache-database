package com.reactor.cachedb.starter;

public record StarterProfileSnapshot(
        String name,
        boolean adminEnabled,
        boolean dashboardEnabled,
        boolean writeBehindEnabled,
        boolean redisGuardrailsEnabled,
        String schemaBootstrapMode,
        boolean schemaAutoApplyOnStart,
        String note
) {
}
