package com.reactor.cachedb.prodtest.scenario;

public record GuardrailProfileSummary(
        String profileName,
        int scenarioCount,
        double averageBalanceScore,
        double averageAchievedTransactionsPerSecond,
        long worstWriteBehindBacklog,
        long worstRedisUsedMemoryBytes,
        long worstCompactionPendingCount,
        long degradedOrDownRuns
) {
}
