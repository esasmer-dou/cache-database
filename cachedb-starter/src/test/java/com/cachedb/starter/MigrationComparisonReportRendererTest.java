package com.reactor.cachedb.starter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationComparisonReportRendererTest {

    @Test
    void shouldRenderSinglePageMarkdownReport() {
        MigrationComparisonRunner.Result comparison = MigrationComparisonFixtures.readyComparisonResult();

        MigrationComparisonReportRenderer.Report report = MigrationComparisonReportRenderer.render(comparison);

        assertTrue(report.fileName().contains("customer-orders"));
        assertTrue(report.fileName().endsWith(".md"));
        assertTrue(report.markdown().contains("# Migration Readiness Report"));
        assertTrue(report.markdown().contains("## Assessment"));
        assertTrue(report.markdown().contains("Ready for staged cutover rehearsal."));
        assertTrue(report.markdown().contains("## Latency Snapshot"));
        assertTrue(report.markdown().contains("## Sample Parity"));
    }
}
