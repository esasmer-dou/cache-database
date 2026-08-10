package com.reactor.cachedb.spring.boot;

/** Compile-time generated scheduling metadata for one warm-plan method. */
public record CacheScheduledWarmDescriptor(
        String declaringType,
        String methodName,
        String name,
        String cron,
        String zone,
        String fixedDelayString,
        String fixedRateString,
        String initialDelayString,
        String enabledString,
        CacheScheduledWarmMode mode,
        String lockAtMostForString,
        String lockWaitTimeoutString,
        String lockRetryIntervalString,
        String minimumIntervalString,
        boolean reconcileHotSet,
        String reconcileMaxRowsPerRunString,
        String reconcileScanCountString
) {
    public CacheScheduledWarmDescriptor {
        declaringType = requireText(declaringType, "declaringType");
        methodName = requireText(methodName, "methodName");
        name = normalize(name);
        cron = normalize(cron);
        zone = normalize(zone);
        fixedDelayString = normalize(fixedDelayString);
        fixedRateString = normalize(fixedRateString);
        initialDelayString = normalize(initialDelayString);
        enabledString = normalize(enabledString);
        mode = mode == null ? CacheScheduledWarmMode.ENTITY_AND_PROJECTIONS : mode;
        lockAtMostForString = normalize(lockAtMostForString);
        lockWaitTimeoutString = normalize(lockWaitTimeoutString);
        lockRetryIntervalString = normalize(lockRetryIntervalString);
        minimumIntervalString = normalize(minimumIntervalString);
        reconcileMaxRowsPerRunString = normalize(reconcileMaxRowsPerRunString);
        reconcileScanCountString = normalize(reconcileScanCountString);
    }

    public String defaultJobName() {
        return declaringType + "#" + methodName;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
