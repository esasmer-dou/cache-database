package com.reactor.cachedb.prodtest.scenario;

public record RestartRecoveryCertificationResult(
        boolean persistedBeforeRestart,
        boolean recoveredAfterRestart,
        boolean queryIndexRebuildSuccessful,
        String healthStatus,
        String note
) {
}
