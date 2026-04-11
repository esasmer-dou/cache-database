package com.reactor.cachedb.core.queue;

public record RedisRuntimeProfileSnapshot(
        String activeProfile,
        String lastObservedPressureLevel,
        long switchCount,
        long lastSwitchedAtEpochMillis,
        long normalPressureSamples,
        long warnPressureSamples,
        long criticalPressureSamples
) {
}
