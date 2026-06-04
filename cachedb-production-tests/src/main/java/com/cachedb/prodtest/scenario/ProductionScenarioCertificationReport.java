package com.reactor.cachedb.prodtest.scenario;

import java.time.Instant;
import java.util.List;

public record ProductionScenarioCertificationReport(
        String certificationName,
        Instant recordedAt,
        List<CertificationGateResult> gates,
        boolean passed
) {
}
