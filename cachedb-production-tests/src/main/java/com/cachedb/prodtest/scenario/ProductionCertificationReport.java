package com.reactor.cachedb.prodtest.scenario;

import java.time.Instant;
import java.util.List;

public record ProductionCertificationReport(
        String certificationName,
        Instant recordedAt,
        ScenarioReport benchmarkReport,
        RestartRecoveryCertificationResult restartRecovery,
        CrashReplayChaosSuiteReport crashReplayChaos,
        FaultInjectionSuiteReport faultInjection,
        List<CertificationGateResult> gates,
        boolean passed
) {
}
