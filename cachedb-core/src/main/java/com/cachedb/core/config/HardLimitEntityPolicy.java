package com.reactor.cachedb.core.config;

public record HardLimitEntityPolicy(
        String namespace,
        boolean shedPageCacheWrites,
        boolean shedReadThroughCache,
        boolean shedHotSetTracking,
        boolean shedQueryIndexWrites,
        boolean shedQueryIndexReads,
        boolean shedPlannerLearning,
        boolean autoRebuildIndexes
) {
    public boolean matches(String candidateNamespace) {
        return namespace != null
                && candidateNamespace != null
                && ("*".equals(namespace) || namespace.equals(candidateNamespace));
    }
}
