package com.reactor.cachedb.prodtest.scenario;

import java.time.Instant;
import java.util.List;

public record ProductionGateLadderReport(
        String ladderName,
        Instant recordedAt,
        boolean passed,
        int profileCount,
        List<ProductionGateLadderResult> results
) {
}
