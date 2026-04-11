package com.reactor.cachedb.prodtest.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class ScaleLadderBenchmarkRunner {

    public ScaleLadderBenchmarkReport run(List<EcommerceScenarioProfile> profiles, List<Double> scaleFactors) throws Exception {
        return run("full-scale-50k-scale-ladder", "full-scale-50k-scale-ladder", profiles, scaleFactors);
    }

    public ScaleLadderBenchmarkReport run(
            String suiteName,
            String outputPrefix,
            List<EcommerceScenarioProfile> profiles,
            List<Double> scaleFactors
    ) throws Exception {
        FullScaleBenchmarkSuiteRunner suiteRunner = new FullScaleBenchmarkSuiteRunner();
        ArrayList<BenchmarkSuiteReport> suiteReports = new ArrayList<>(scaleFactors.size());
        for (double scaleFactor : scaleFactors) {
            suiteReports.add(suiteRunner.run(profiles, scaleFactor));
        }

        ScaleLadderBenchmarkReport report = new ScaleLadderBenchmarkReport(
                suiteName,
                List.copyOf(scaleFactors),
                suiteReports.size(),
                suiteReports.stream().mapToLong(BenchmarkSuiteReport::totalExecutedOperations).sum(),
                suiteReports.stream().mapToLong(BenchmarkSuiteReport::totalFailedOperations).sum(),
                suiteReports.stream().mapToDouble(BenchmarkSuiteReport::averageAchievedTransactionsPerSecond).max().orElse(0.0d),
                suiteReports.stream().mapToLong(BenchmarkSuiteReport::worstP99LatencyMicros).max().orElse(0L),
                suiteReports.stream().mapToLong(BenchmarkSuiteReport::worstWriteBehindBacklog).max().orElse(0L),
                suiteReports.stream().mapToLong(BenchmarkSuiteReport::worstRedisUsedMemoryBytes).max().orElse(0L),
                suiteReports.stream().mapToLong(BenchmarkSuiteReport::worstCompactionPendingCount).max().orElse(0L),
                suiteReports.stream().mapToLong(BenchmarkSuiteReport::totalRuntimeProfileSwitchCount).sum(),
                suiteReports.stream().mapToLong(BenchmarkSuiteReport::maxRuntimeProfileSwitchCount).max().orElse(0L),
                suiteReports.stream().allMatch(BenchmarkSuiteReport::allDrainsCompleted),
                List.copyOf(suiteReports)
        );
        writeReports(outputPrefix, report);
        return report;
    }

    private void writeReports(String outputPrefix, ScaleLadderBenchmarkReport report) throws IOException {
        Path reportDirectory = Path.of("target", "cachedb-prodtest-reports");
        Files.createDirectories(reportDirectory);
        Files.writeString(reportDirectory.resolve(outputPrefix + ".md"), toMarkdown(report));
        Files.writeString(reportDirectory.resolve(outputPrefix + ".json"), toJson(report));
    }

    private String toMarkdown(ScaleLadderBenchmarkReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(report.suiteName()).append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("- scaleFactors: `")
                .append(report.scaleFactors().stream().map(String::valueOf).collect(Collectors.joining(", ")))
                .append('`')
                .append(System.lineSeparator());
        builder.append("- runCount: `").append(report.runCount()).append('`').append(System.lineSeparator());
        builder.append("- totalExecutedOperations: `").append(report.totalExecutedOperations()).append('`').append(System.lineSeparator());
        builder.append("- totalFailedOperations: `").append(report.totalFailedOperations()).append('`').append(System.lineSeparator());
        builder.append("- bestAverageAchievedTransactionsPerSecond: `").append(report.bestAverageAchievedTransactionsPerSecond()).append('`').append(System.lineSeparator());
        builder.append("- worstP99LatencyMicros: `").append(report.worstP99LatencyMicros()).append('`').append(System.lineSeparator());
        builder.append("- worstWriteBehindBacklog: `").append(report.worstWriteBehindBacklog()).append('`').append(System.lineSeparator());
        builder.append("- worstRedisUsedMemoryBytes: `").append(report.worstRedisUsedMemoryBytes()).append('`').append(System.lineSeparator());
        builder.append("- worstCompactionPendingCount: `").append(report.worstCompactionPendingCount()).append('`').append(System.lineSeparator());
        builder.append("- totalRuntimeProfileSwitchCount: `").append(report.totalRuntimeProfileSwitchCount()).append('`').append(System.lineSeparator());
        builder.append("- maxRuntimeProfileSwitchCount: `").append(report.maxRuntimeProfileSwitchCount()).append('`').append(System.lineSeparator());
        builder.append("- allRunsDrained: `").append(report.allRunsDrained()).append('`').append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("| Scale | Scenarios | Avg Achieved TPS | Worst P99 us | Worst Backlog | Worst Redis Mem | Worst Pending | Total Switches | Max Switches | All Drained |")
                .append(System.lineSeparator());
        builder.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---|").append(System.lineSeparator());
        for (BenchmarkSuiteReport suiteReport : report.suiteReports()) {
            builder.append("| ").append(suiteReport.scaleFactor())
                    .append(" | ").append(suiteReport.scenarioCount())
                    .append(" | ").append(String.format("%.2f", suiteReport.averageAchievedTransactionsPerSecond()))
                    .append(" | ").append(suiteReport.worstP99LatencyMicros())
                    .append(" | ").append(suiteReport.worstWriteBehindBacklog())
                    .append(" | ").append(suiteReport.worstRedisUsedMemoryBytes())
                    .append(" | ").append(suiteReport.worstCompactionPendingCount())
                    .append(" | ").append(suiteReport.totalRuntimeProfileSwitchCount())
                    .append(" | ").append(suiteReport.maxRuntimeProfileSwitchCount())
                    .append(" | ").append(suiteReport.allDrainsCompleted())
                    .append(" |")
                    .append(System.lineSeparator());
        }
        return builder.toString();
    }

    private String toJson(ScaleLadderBenchmarkReport report) {
        String scales = report.scaleFactors().stream().map(String::valueOf).collect(Collectors.joining(","));
        String suites = report.suiteReports().stream()
                .map(this::suiteJson)
                .collect(Collectors.joining(","));
        return "{"
                + "\"suiteName\":\"" + escapeJson(report.suiteName()) + "\","
                + "\"scaleFactors\":[" + scales + "],"
                + "\"runCount\":" + report.runCount() + ","
                + "\"totalExecutedOperations\":" + report.totalExecutedOperations() + ","
                + "\"totalFailedOperations\":" + report.totalFailedOperations() + ","
                + "\"bestAverageAchievedTransactionsPerSecond\":" + report.bestAverageAchievedTransactionsPerSecond() + ","
                + "\"worstP99LatencyMicros\":" + report.worstP99LatencyMicros() + ","
                + "\"worstWriteBehindBacklog\":" + report.worstWriteBehindBacklog() + ","
                + "\"worstRedisUsedMemoryBytes\":" + report.worstRedisUsedMemoryBytes() + ","
                + "\"worstCompactionPendingCount\":" + report.worstCompactionPendingCount() + ","
                + "\"totalRuntimeProfileSwitchCount\":" + report.totalRuntimeProfileSwitchCount() + ","
                + "\"maxRuntimeProfileSwitchCount\":" + report.maxRuntimeProfileSwitchCount() + ","
                + "\"allRunsDrained\":" + report.allRunsDrained() + ","
                + "\"suiteReports\":[" + suites + "]"
                + "}";
    }

    private String suiteJson(BenchmarkSuiteReport report) {
        return "{"
                + "\"scaleFactor\":" + report.scaleFactor() + ","
                + "\"scenarioCount\":" + report.scenarioCount() + ","
                + "\"averageAchievedTransactionsPerSecond\":" + report.averageAchievedTransactionsPerSecond() + ","
                + "\"worstP99LatencyMicros\":" + report.worstP99LatencyMicros() + ","
                + "\"worstWriteBehindBacklog\":" + report.worstWriteBehindBacklog() + ","
                + "\"worstRedisUsedMemoryBytes\":" + report.worstRedisUsedMemoryBytes() + ","
                + "\"worstCompactionPendingCount\":" + report.worstCompactionPendingCount() + ","
                + "\"totalRuntimeProfileSwitchCount\":" + report.totalRuntimeProfileSwitchCount() + ","
                + "\"maxRuntimeProfileSwitchCount\":" + report.maxRuntimeProfileSwitchCount() + ","
                + "\"allDrainsCompleted\":" + report.allDrainsCompleted()
                + "}";
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
