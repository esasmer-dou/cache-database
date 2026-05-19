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
        assertTrue(report.markdown().contains("## Cutover Action Plan"));
        assertTrue(report.markdown().contains("Full cutover rehearsal"));
        assertTrue(report.markdown().contains("## Full Conversion Coverage Plan"));
        assertTrue(report.markdown().contains("This report is not a whole-system conversion certificate."));
        assertTrue(report.markdown().contains("Reject unclassified routes."));
        assertTrue(report.markdown().contains("## Latency Snapshot"));
        assertTrue(report.markdown().contains("## Sample Parity"));
    }
}
