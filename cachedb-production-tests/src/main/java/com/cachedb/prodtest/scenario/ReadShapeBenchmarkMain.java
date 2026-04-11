package com.reactor.cachedb.prodtest.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Collectors;

public final class ReadShapeBenchmarkMain {

    private static final String DEFAULT_OUTPUT_PREFIX = "relation-read-shape-comparison";
    private static final String REPORT_DIR_PROPERTY = "cachedb.prod.reportDir";

    private ReadShapeBenchmarkMain() {
    }

    public static void main(String[] args) throws Exception {
        ReadShapeBenchmarkRunner runner = new ReadShapeBenchmarkRunner();
        ReadShapeBenchmarkReport report = runner.run();
        writeReports(resolveReportDirectory(), DEFAULT_OUTPUT_PREFIX, report);
    }

    static void writeReports(String outputPrefix, ReadShapeBenchmarkReport report) throws IOException {
        writeReports(Path.of("target", "cachedb-prodtest-reports"), outputPrefix, report);
    }

    static void writeReports(Path reportDirectory, String outputPrefix, ReadShapeBenchmarkReport report) throws IOException {
        Files.createDirectories(reportDirectory);
        Files.writeString(reportDirectory.resolve(outputPrefix + ".md"), toMarkdown(report));
        Files.writeString(reportDirectory.resolve(outputPrefix + ".json"), toJson(report));
    }

    static String toMarkdown(ReadShapeBenchmarkReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(report.benchmarkName()).append(System.lineSeparator()).append(System.lineSeparator());
        builder.append(report.disclaimer()).append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("- warmupIterations: `").append(report.warmupIterations()).append('`').append(System.lineSeparator());
        builder.append("- measuredIterationsPerShape: `").append(report.measuredIterationsPerShape()).append('`').append(System.lineSeparator());
        builder.append("- ordersPerRead: `").append(report.ordersPerRead()).append('`').append(System.lineSeparator());
        builder.append("- fullLinesPerOrder: `").append(report.fullLinesPerOrder()).append('`').append(System.lineSeparator());
        builder.append("- previewLinesPerOrder: `").append(report.previewLinesPerOrder()).append('`').append(System.lineSeparator());
        builder.append("- fastestAverageShape: `").append(report.fastestAverageShape()).append('`').append(System.lineSeparator());
        builder.append("- maxAverageDriftVsFastestPercent: `")
                .append(String.format(Locale.US, "%.2f", report.maxAverageSpreadPercent()))
                .append("%`")
                .append(System.lineSeparator())
                .append(System.lineSeparator());
        builder.append("| Shape | Avg ns | p95 ns | p99 ns | Objects/op | Lines/op | Avg vs fastest |").append(System.lineSeparator());
        builder.append("|---|---:|---:|---:|---:|---:|---:|").append(System.lineSeparator());
        for (ReadShapeBenchmarkReport.ShapeReport shapeReport : report.shapeReports()) {
            builder.append("| ").append(shapeReport.label())
                    .append(" | ").append(shapeReport.averageLatencyNanos())
                    .append(" | ").append(shapeReport.p95LatencyNanos())
                    .append(" | ").append(shapeReport.p99LatencyNanos())
                    .append(" | ").append(shapeReport.estimatedObjectCountPerOperation())
                    .append(" | ").append(shapeReport.materializedLineViewsPerOperation())
                    .append(" | ").append(String.format(Locale.US, "%.2f%%", shapeReport.averageLatencyVsFastestPercent()))
                    .append(" |")
                    .append(System.lineSeparator());
        }
        builder.append(System.lineSeparator());
        for (ReadShapeBenchmarkReport.ShapeReport shapeReport : report.shapeReports()) {
            builder.append("## ").append(shapeReport.label()).append(System.lineSeparator()).append(System.lineSeparator());
            builder.append(shapeReport.positioning()).append(System.lineSeparator()).append(System.lineSeparator());
            builder.append("- materializedOrdersPerOperation: `").append(shapeReport.materializedOrdersPerOperation()).append('`').append(System.lineSeparator());
            builder.append("- materializedLineViewsPerOperation: `").append(shapeReport.materializedLineViewsPerOperation()).append('`').append(System.lineSeparator());
            builder.append("- estimatedObjectCountPerOperation: `").append(shapeReport.estimatedObjectCountPerOperation()).append('`').append(System.lineSeparator());
            builder.append("- averageLatencyNanos: `").append(shapeReport.averageLatencyNanos()).append('`').append(System.lineSeparator());
            builder.append("- p95LatencyNanos: `").append(shapeReport.p95LatencyNanos()).append('`').append(System.lineSeparator());
            builder.append("- p99LatencyNanos: `").append(shapeReport.p99LatencyNanos()).append('`').append(System.lineSeparator());
            builder.append("- averageLatencyVsFastestPercent: `")
                    .append(String.format(Locale.US, "%.2f", shapeReport.averageLatencyVsFastestPercent()))
                    .append("%`")
                    .append(System.lineSeparator())
                    .append(System.lineSeparator());
        }
        return builder.toString();
    }

    static String toJson(ReadShapeBenchmarkReport report) {
        String shapeReports = report.shapeReports().stream()
                .map(ReadShapeBenchmarkMain::shapeJson)
                .collect(Collectors.joining(","));
        return "{"
                + "\"benchmarkName\":\"" + escapeJson(report.benchmarkName()) + "\","
                + "\"warmupIterations\":" + report.warmupIterations() + ","
                + "\"measuredIterationsPerShape\":" + report.measuredIterationsPerShape() + ","
                + "\"ordersPerRead\":" + report.ordersPerRead() + ","
                + "\"fullLinesPerOrder\":" + report.fullLinesPerOrder() + ","
                + "\"previewLinesPerOrder\":" + report.previewLinesPerOrder() + ","
                + "\"disclaimer\":\"" + escapeJson(report.disclaimer()) + "\","
                + "\"fastestAverageShape\":\"" + escapeJson(report.fastestAverageShape()) + "\","
                + "\"maxAverageSpreadPercent\":" + report.maxAverageSpreadPercent() + ","
                + "\"shapeReports\":[" + shapeReports + "]"
                + "}";
    }

    private static Path resolveReportDirectory() {
        String configured = System.getProperty(REPORT_DIR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return Path.of("target", "cachedb-prodtest-reports");
        }
        return Path.of(configured);
    }

    private static String shapeJson(ReadShapeBenchmarkReport.ShapeReport report) {
        return "{"
                + "\"shapeName\":\"" + escapeJson(report.shapeName()) + "\","
                + "\"label\":\"" + escapeJson(report.label()) + "\","
                + "\"positioning\":\"" + escapeJson(report.positioning()) + "\","
                + "\"materializedOrdersPerOperation\":" + report.materializedOrdersPerOperation() + ","
                + "\"materializedLineViewsPerOperation\":" + report.materializedLineViewsPerOperation() + ","
                + "\"estimatedObjectCountPerOperation\":" + report.estimatedObjectCountPerOperation() + ","
                + "\"totalLatencyNanos\":" + report.totalLatencyNanos() + ","
                + "\"averageLatencyNanos\":" + report.averageLatencyNanos() + ","
                + "\"p95LatencyNanos\":" + report.p95LatencyNanos() + ","
                + "\"p99LatencyNanos\":" + report.p99LatencyNanos() + ","
                + "\"averageLatencyVsFastestPercent\":" + report.averageLatencyVsFastestPercent()
                + "}";
    }

    private static String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
