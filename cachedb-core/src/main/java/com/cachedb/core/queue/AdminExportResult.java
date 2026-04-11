package com.reactor.cachedb.core.queue;

public record AdminExportResult(
        String reportName,
        AdminExportFormat format,
        String contentType,
        String content
) {
}
