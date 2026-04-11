package com.reactor.cachedb.prodtest.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

public final class RankedProjectionBenchmarkMain {

    private static final String DEFAULT_OUTPUT_PREFIX = "ranked-projection-comparison";
    private static final String REPORT_DIR_PROPERTY = "cachedb.prod.reportDir";

    private RankedProjectionBenchmarkMain() {
    }

    public static void main(String[] args) throws Exception {
        RankedProjectionBenchmarkRunner runner = new RankedProjectionBenchmarkRunner();
        RankedProjectionBenchmarkReport report = runner.run();
        writeReports(resolveReportDirectory(), DEFAULT_OUTPUT_PREFIX, report);
    }

    static void writeReports(Path reportDirectory, String outputPrefix, RankedProjectionBenchmarkReport report) throws IOException {
        Files.createDirectories(reportDirectory);
        Files.writeString(reportDirectory.resolve(outputPrefix + ".md"), toMarkdown(report));
        Files.writeString(reportDirectory.resolve(outputPrefix + ".json"), toJson(report));
    }

    static String toMarkdown(RankedProjectionBenchmarkReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(report.benchmarkName()).append(System.lineSeparator()).append(System.lineSeparator());
        builder.append(report.disclaimer()).append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("- warmupIterations: `").append(report.warmupIterations()).append('`').append(System.lineSeparator());
        builder.append("- measuredIterationsPerShape: `").append(report.measuredIterationsPerShape()).append('`').append(System.lineSeparator());
        builder.append("- datasetSize: `").append(report.datasetSize()).append('`').append(System.lineSeparator());
        builder.append("- topWindowSize: `").append(report.topWindowSize()).append('`').append(System.lineSeparator());
        builder.append("- fastestAverageShape: `").append(report.fastestAverageShape()).append('`').append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("| Shape | Avg ns | p95 ns | p99 ns | Objects/op |").append(System.lineSeparator());
        builder.append("|---|---:|---:|---:|---:|").append(System.lineSeparator());
        for (RankedProjectionBenchmarkReport.ShapeReport shapeReport : report.shapeReports()) {
            builder.append("| ").append(shapeReport.label())
                    .append(" | ").append(shapeReport.averageLatencyNanos())
                    .append(" | ").append(shapeReport.p95LatencyNanos())
                    .append(" | ").append(shapeReport.p99LatencyNanos())
                    .append(" | ").append(shapeReport.materializedObjectsPerOperation())
                    .append(" |")
                    .append(System.lineSeparator());
        }
        return builder.toString();
    }

    static String toJson(RankedProjectionBenchmarkReport report) {
        String shapeReports = report.shapeReports().stream()
                .map(RankedProjectionBenchmarkMain::shapeJson)
                .collect(Collectors.joining(","));
        return "{"
                + "\"benchmarkName\":\"" + escapeJson(report.benchmarkName()) + "\","
                + "\"warmupIterations\":" + report.warmupIterations() + ","
                + "\"measuredIterationsPerShape\":" + report.measuredIterationsPerShape() + ","
                + "\"datasetSize\":" + report.datasetSize() + ","
                + "\"topWindowSize\":" + report.topWindowSize() + ","
                + "\"disclaimer\":\"" + escapeJson(report.disclaimer()) + "\","
                + "\"fastestAverageShape\":\"" + escapeJson(report.fastestAverageShape()) + "\","
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

    private static String shapeJson(RankedProjectionBenchmarkReport.ShapeReport report) {
        return "{"
                + "\"shapeName\":\"" + escapeJson(report.shapeName()) + "\","
                + "\"label\":\"" + escapeJson(report.label()) + "\","
                + "\"materializedObjectsPerOperation\":" + report.materializedObjectsPerOperation() + ","
                + "\"averageLatencyNanos\":" + report.averageLatencyNanos() + ","
                + "\"p95LatencyNanos\":" + report.p95LatencyNanos() + ","
                + "\"p99LatencyNanos\":" + report.p99LatencyNanos()
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
