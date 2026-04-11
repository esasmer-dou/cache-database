package com.reactor.cachedb.starter;

import java.time.Instant;

public record ProductionReportSnapshot(
        String reportKey,
        String fileName,
        String absolutePath,
        Instant lastModifiedAt,
        String status,
        String headline
) {
}
