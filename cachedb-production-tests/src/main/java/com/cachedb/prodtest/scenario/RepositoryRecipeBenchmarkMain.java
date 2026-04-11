package com.reactor.cachedb.prodtest.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Collectors;

public final class RepositoryRecipeBenchmarkMain {

    private static final String DEFAULT_OUTPUT_PREFIX = "repository-recipe-comparison";
    private static final String REPORT_DIR_PROPERTY = "cachedb.prod.reportDir";

    private RepositoryRecipeBenchmarkMain() {
    }

    public static void main(String[] args) throws Exception {
        RepositoryRecipeBenchmarkRunner runner = new RepositoryRecipeBenchmarkRunner();
        RepositoryRecipeBenchmarkReport report = runner.run();
        writeReports(resolveReportDirectory(), DEFAULT_OUTPUT_PREFIX, report);
    }

    static void writeReports(String outputPrefix, RepositoryRecipeBenchmarkReport report) throws IOException {
        writeReports(Path.of("target", "cachedb-prodtest-reports"), outputPrefix, report);
    }

    static void writeReports(Path reportDirectory, String outputPrefix, RepositoryRecipeBenchmarkReport report) throws IOException {
        Files.createDirectories(reportDirectory);
        Files.writeString(reportDirectory.resolve(outputPrefix + ".md"), toMarkdown(report));
        Files.writeString(reportDirectory.resolve(outputPrefix + ".json"), toJson(report));
    }

    static String toMarkdown(RepositoryRecipeBenchmarkReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(report.benchmarkName()).append(System.lineSeparator()).append(System.lineSeparator());
        builder.append(report.disclaimer()).append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("- warmupIterations: `").append(report.warmupIterations()).append('`').append(System.lineSeparator());
        builder.append("- measuredIterationsPerOperation: `").append(report.measuredIterationsPerOperation()).append('`').append(System.lineSeparator());
        builder.append("- fastestAverageMode: `").append(report.fastestAverageMode()).append('`').append(System.lineSeparator());
        builder.append("- fastestP95Mode: `").append(report.fastestP95Mode()).append('`').append(System.lineSeparator());
        builder.append("- maxAverageDriftVsMinimalPercent: `")
                .append(String.format(Locale.US, "%.2f", report.maxAverageSpreadPercent()))
                .append("%`")
                .append(System.lineSeparator());
        builder.append("- comparedOperations: `")
                .append(String.join(", ", report.comparedOperations()))
                .append('`')
                .append(System.lineSeparator())
                .append(System.lineSeparator());
        builder.append("| Mode | Avg ns | p95 ns | p99 ns | Avg vs minimal |").append(System.lineSeparator());
        builder.append("|---|---:|---:|---:|---:|").append(System.lineSeparator());
        for (RepositoryRecipeModeReport modeReport : report.modeReports()) {
            builder.append("| ").append(modeReport.label())
                    .append(" | ").append(modeReport.averageLatencyNanos())
                    .append(" | ").append(modeReport.p95LatencyNanos())
                    .append(" | ").append(modeReport.p99LatencyNanos())
                    .append(" | ").append(String.format(Locale.US, "%.2f%%", modeReport.averageLatencyVsMinimalPercent()))
                    .append(" |")
                    .append(System.lineSeparator());
        }
        builder.append(System.lineSeparator());
        for (RepositoryRecipeModeReport modeReport : report.modeReports()) {
            builder.append("## ").append(modeReport.label()).append(System.lineSeparator()).append(System.lineSeparator());
            builder.append(modeReport.positioning()).append(System.lineSeparator()).append(System.lineSeparator());
            builder.append("| Operation | Category | Avg ns | p95 ns | p99 ns |").append(System.lineSeparator());
            builder.append("|---|---|---:|---:|---:|").append(System.lineSeparator());
            for (RepositoryRecipeOperationReport operationReport : modeReport.operationReports()) {
                builder.append("| ").append(operationReport.operationName())
                        .append(" | ").append(operationReport.category())
                        .append(" | ").append(operationReport.averageLatencyNanos())
                        .append(" | ").append(operationReport.p95LatencyNanos())
                        .append(" | ").append(operationReport.p99LatencyNanos())
                        .append(" |")
                        .append(System.lineSeparator());
            }
            builder.append(System.lineSeparator());
        }
        return builder.toString();
    }

    static String toJson(RepositoryRecipeBenchmarkReport report) {
        String comparedOperations = report.comparedOperations().stream()
                .map(operation -> "\"" + escapeJson(operation) + "\"")
                .collect(Collectors.joining(","));
        String modeReports = report.modeReports().stream()
                .map(RepositoryRecipeBenchmarkMain::modeJson)
                .collect(Collectors.joining(","));
        return "{"
                + "\"benchmarkName\":\"" + escapeJson(report.benchmarkName()) + "\","
                + "\"warmupIterations\":" + report.warmupIterations() + ","
                + "\"measuredIterationsPerOperation\":" + report.measuredIterationsPerOperation() + ","
                + "\"disclaimer\":\"" + escapeJson(report.disclaimer()) + "\","
                + "\"fastestAverageMode\":\"" + escapeJson(report.fastestAverageMode()) + "\","
                + "\"fastestP95Mode\":\"" + escapeJson(report.fastestP95Mode()) + "\","
                + "\"maxAverageSpreadPercent\":" + report.maxAverageSpreadPercent() + ","
                + "\"comparedOperations\":[" + comparedOperations + "],"
                + "\"modeReports\":[" + modeReports + "]"
                + "}";
    }

    private static Path resolveReportDirectory() {
        String configured = System.getProperty(REPORT_DIR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return Path.of("target", "cachedb-prodtest-reports");
        }
        return Path.of(configured);
    }

    private static String modeJson(RepositoryRecipeModeReport report) {
        String operationReports = report.operationReports().stream()
                .map(RepositoryRecipeBenchmarkMain::operationJson)
                .collect(Collectors.joining(","));
        return "{"
                + "\"mode\":\"" + report.mode().name() + "\","
                + "\"label\":\"" + escapeJson(report.label()) + "\","
                + "\"positioning\":\"" + escapeJson(report.positioning()) + "\","
                + "\"measuredOperations\":" + report.measuredOperations() + ","
                + "\"totalLatencyNanos\":" + report.totalLatencyNanos() + ","
                + "\"averageLatencyNanos\":" + report.averageLatencyNanos() + ","
                + "\"p95LatencyNanos\":" + report.p95LatencyNanos() + ","
                + "\"p99LatencyNanos\":" + report.p99LatencyNanos() + ","
                + "\"averageLatencyVsMinimalPercent\":" + report.averageLatencyVsMinimalPercent() + ","
                + "\"operationReports\":[" + operationReports + "]"
                + "}";
    }

    private static String operationJson(RepositoryRecipeOperationReport report) {
        return "{"
                + "\"operationName\":\"" + escapeJson(report.operationName()) + "\","
                + "\"category\":\"" + escapeJson(report.category()) + "\","
                + "\"measuredOperations\":" + report.measuredOperations() + ","
                + "\"totalLatencyNanos\":" + report.totalLatencyNanos() + ","
                + "\"averageLatencyNanos\":" + report.averageLatencyNanos() + ","
                + "\"p95LatencyNanos\":" + report.p95LatencyNanos() + ","
                + "\"p99LatencyNanos\":" + report.p99LatencyNanos()
                + "}";
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
