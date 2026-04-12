package com.reactor.cachedb.starter;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

final class MigrationComparisonReportRenderer {

    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private MigrationComparisonReportRenderer() {
    }

    static Report render(MigrationComparisonRunner.Result comparison) {
        Objects.requireNonNull(comparison, "comparison");
        MigrationComparisonAssessment.Result assessment = comparison.assessment();
        String workload = sanitizeSegment(comparison.plan().request().workloadName());
        String timestamp = FILE_TIMESTAMP.format(comparison.completedAt());
        String fileName = workload + "-migration-report-" + timestamp + ".md";

        StringBuilder markdown = new StringBuilder();
        markdown.append("# Migration Readiness Report").append("\n\n");
        markdown.append("- Workload: `").append(safe(comparison.plan().request().workloadName())).append("`\n");
        markdown.append("- Generated at: `").append(comparison.completedAt()).append("`\n");
        markdown.append("- CacheDB route: `").append(safe(comparison.cacheRouteLabel())).append("`\n");
        markdown.append("- Recommended surface: ").append(safe(comparison.plan().recommendedSurface())).append("\n");
        markdown.append("- Summary projection: `").append(safe(comparison.plan().summaryProjectionName())).append("`\n");
        if (comparison.plan().rankedProjectionRequired()) {
            markdown.append("- Ranked projection: `").append(safe(comparison.plan().rankedProjectionName())).append("`\n");
        }
        markdown.append("\n");

        markdown.append("## Assessment\n\n");
        markdown.append("- Readiness: `").append(assessment.readiness().name()).append("`\n");
        markdown.append("- Decision: ").append(safe(assessment.decision())).append("\n");
        markdown.append("- Summary: ").append(safe(assessment.summary())).append("\n");
        markdown.append("- Parity status: `").append(assessment.parityStatus().name()).append("`\n");
        markdown.append("- Performance status: `").append(assessment.performanceStatus().name()).append("`\n");
        markdown.append("- Route status: `").append(assessment.routeStatus().name()).append("`\n");
        markdown.append("- Exact sample matches: `").append(assessment.exactMatchCount()).append(" / ").append(assessment.sampleCount()).append("`\n");
        markdown.append("- Avg latency ratio (CacheDB / PostgreSQL): `").append(formatRatio(assessment.averageLatencyRatio())).append("`\n");
        markdown.append("- p95 latency ratio (CacheDB / PostgreSQL): `").append(formatRatio(assessment.p95LatencyRatio())).append("`\n\n");

        appendList(markdown, "Strengths", assessment.strengths(), "No strong positive signal was recorded in this run.");
        appendList(markdown, "Cutover Blockers", assessment.blockers(), "No direct blocker was recorded in this run.");
        appendList(markdown, "Next Steps", assessment.nextSteps(), "No additional next step was generated.");

        markdown.append("## Latency Snapshot\n\n");
        markdown.append("| Surface | Avg latency | p95 | p99 | Avg rows/op |\n");
        markdown.append("| --- | ---: | ---: | ---: | ---: |\n");
        appendMetricsRow(markdown, "PostgreSQL baseline", comparison.baselineMetrics());
        appendMetricsRow(markdown, "CacheDB", comparison.cacheMetrics());
        markdown.append("\n");

        markdown.append("## Sample Parity\n\n");
        markdown.append("| Sample | Baseline rows | Cache rows | Exact match |\n");
        markdown.append("| --- | ---: | ---: | --- |\n");
        for (MigrationComparisonRunner.SampleComparison sample : comparison.sampleComparisons()) {
            markdown.append("| `").append(safe(sample.sampleLabel())).append("` | ")
                    .append(sample.baselineRowCount()).append(" | ")
                    .append(sample.cacheRowCount()).append(" | ")
                    .append(sample.exactMatch() ? "yes" : "no")
                    .append(" |\n");
        }
        markdown.append("\n");

        markdown.append("## Baseline SQL\n\n```sql\n")
                .append(safe(comparison.baselineSqlTemplate()))
                .append("\n```\n\n");

        if (comparison.warmResult() != null) {
            MigrationWarmRunner.Result warm = comparison.warmResult();
            markdown.append("## Warm Execution\n\n");
            markdown.append("- Dry run: `").append(warm.dryRun()).append("`\n");
            markdown.append("- Child rows hydrated: `").append(warm.childRowsHydrated()).append(" / ").append(warm.childRowsRead()).append("`\n");
            markdown.append("- Root rows hydrated: `").append(warm.rootRowsHydrated()).append(" / ").append(warm.rootRowsRead()).append("`\n");
            markdown.append("- Missing referenced root ids: `").append(warm.missingReferencedRootIds()).append("`\n");
            markdown.append("- Duration: `").append(warm.durationMillis()).append(" ms`\n\n");
        }

        appendList(markdown, "Planner Notes", comparison.notes(), "No extra planner note was recorded.");
        appendList(markdown, "Warnings", comparison.warnings(), "No warning was recorded.");

        return new Report(fileName, markdown.toString());
    }

    private static void appendMetricsRow(StringBuilder markdown, String label, MigrationComparisonRunner.Metrics metrics) {
        markdown.append("| ").append(label).append(" | `")
                .append(formatLatency(metrics.averageLatencyNanos())).append("` | `")
                .append(formatLatency(metrics.p95LatencyNanos())).append("` | `")
                .append(formatLatency(metrics.p99LatencyNanos())).append("` | `")
                .append(String.format(Locale.ROOT, "%.2f", metrics.averageRowsPerOperation())).append("` |\n");
    }

    private static void appendList(StringBuilder markdown, String title, java.util.List<String> items, String emptyMessage) {
        markdown.append("## ").append(title).append("\n\n");
        if (items == null || items.isEmpty()) {
            markdown.append("- ").append(emptyMessage).append("\n\n");
            return;
        }
        for (String item : items) {
            markdown.append("- ").append(safe(item)).append("\n");
        }
        markdown.append("\n");
    }

    private static String formatLatency(long nanos) {
        if (nanos >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.2f ms", nanos / 1_000_000.0d);
        }
        if (nanos >= 1_000L) {
            return String.format(Locale.ROOT, "%.2f µs", nanos / 1_000.0d);
        }
        return nanos + " ns";
    }

    private static String formatRatio(double value) {
        if (!Double.isFinite(value) || value <= 0.0d) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.2fx", value);
    }

    private static String sanitizeSegment(String value) {
        String normalized = value == null ? "workload" : value.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("(^-+|-+$)", "");
        return normalized.isBlank() ? "workload" : normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    record Report(String fileName, String markdown) {
    }
}
