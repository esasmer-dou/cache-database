package com.reactor.cachedb.prodtest.scenario;

import java.time.Instant;
import java.util.List;

public record ProductionGateReport(
        String gateName,
        Instant recordedAt,
        ProductionGateStatus overallStatus,
        List<ProductionGateCheck> checks,
        String summary
) {
}
