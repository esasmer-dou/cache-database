package com.reactor.cachedb.prodtest.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class FullScaleBenchmarkSuiteRunner {

    public BenchmarkSuiteReport run(List<EcommerceScenarioProfile> profiles, double scaleFactor) throws Exception {
        EcommerceProductionScenarioRunner runner = new EcommerceProductionScenarioRunner();
        ArrayList<ScenarioReport> reports = new ArrayList<>(profiles.size());
        for (EcommerceScenarioProfile profile : profiles) {
            reports.add(runner.run(profile, scaleFactor));
        }

        BenchmarkSuiteReport suiteReport = new BenchmarkSuiteReport(
                "full-scale-50k-suite",
                scaleFactor,
                reports.size(),
                reports.stream().mapToLong(ScenarioReport::executedOperations).sum(),
                reports.stream().mapToLong(ScenarioReport::readOperations).sum(),
                reports.stream().mapToLong(ScenarioReport::writeOperations).sum(),
                reports.stream().mapToLong(ScenarioReport::failedOperations).sum(),
                reports.stream().mapToDouble(ScenarioReport::achievedTransactionsPerSecond).average().orElse(0.0d),
                reports.stream().mapToLong(ScenarioReport::p95LatencyMicros).max().orElse(0L),
                reports.stream().mapToLong(ScenarioReport::p99LatencyMicros).max().orElse(0L),
                reports.stream().mapToLong(ScenarioReport::writeBehindStreamLength).max().orElse(0L),
                reports.stream().mapToLong(ScenarioReport::redisUsedMemoryBytes).max().orElse(0L),
                reports.stream().mapToLong(ScenarioReport::compactionPendingCount).max().orElse(0L),
                reports.stream().mapToLong(ScenarioReport::runtimeProfileSwitchCount).sum(),
                reports.stream().mapToLong(ScenarioReport::runtimeProfileSwitchCount).max().orElse(0L),
                reports.stream().mapToLong(ScenarioReport::drainMillis).max().orElse(0L),
                reports.stream().allMatch(ScenarioReport::drainCompleted),
                reports.stream()
                        .filter(report -> !"UP".equalsIgnoreCase(report.finalHealthStatus()))
                        .map(report -> report.scenarioName() + ":" + report.finalHealthStatus())
                        .toList(),
                List.copyOf(reports)
        );

        writeReports(suiteReport);
        return suiteReport;
    }

    private void writeReports(BenchmarkSuiteReport suiteReport) throws IOException {
        Path reportDirectory = Path.of("target", "cachedb-prodtest-reports");
        Files.createDirectories(reportDirectory);
        Files.writeString(reportDirectory.resolve("full-scale-50k-suite.md"), toMarkdown(suiteReport));
        Files.writeString(reportDirectory.resolve("full-scale-50k-suite.json"), toJson(suiteReport));
    }

    private String toMarkdown(BenchmarkSuiteReport suiteReport) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(suiteReport.suiteName()).append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("- scaleFactor: `").append(suiteReport.scaleFactor()).append('`').append(System.lineSeparator());
        builder.append("- scenarioCount: `").append(suiteReport.scenarioCount()).append('`').append(System.lineSeparator());
        builder.append("- totalExecutedOperations: `").append(suiteReport.totalExecutedOperations()).append('`').append(System.lineSeparator());
        builder.append("- totalReadOperations: `").append(suiteReport.totalReadOperations()).append('`').append(System.lineSeparator());
        builder.append("- totalWriteOperations: `").append(suiteReport.totalWriteOperations()).append('`').append(System.lineSeparator());
        builder.append("- totalFailedOperations: `").append(suiteReport.totalFailedOperations()).append('`').append(System.lineSeparator());
        builder.append("- averageAchievedTransactionsPerSecond: `").append(suiteReport.averageAchievedTransactionsPerSecond()).append('`').append(System.lineSeparator());
        builder.append("- worstP95LatencyMicros: `").append(suiteReport.worstP95LatencyMicros()).append('`').append(System.lineSeparator());
        builder.append("- worstP99LatencyMicros: `").append(suiteReport.worstP99LatencyMicros()).append('`').append(System.lineSeparator());
        builder.append("- worstWriteBehindBacklog: `").append(suiteReport.worstWriteBehindBacklog()).append('`').append(System.lineSeparator());
        builder.append("- worstRedisUsedMemoryBytes: `").append(suiteReport.worstRedisUsedMemoryBytes()).append('`').append(System.lineSeparator());
        builder.append("- worstCompactionPendingCount: `").append(suiteReport.worstCompactionPendingCount()).append('`').append(System.lineSeparator());
        builder.append("- totalRuntimeProfileSwitchCount: `").append(suiteReport.totalRuntimeProfileSwitchCount()).append('`').append(System.lineSeparator());
        builder.append("- maxRuntimeProfileSwitchCount: `").append(suiteReport.maxRuntimeProfileSwitchCount()).append('`').append(System.lineSeparator());
        builder.append("- worstDrainMillis: `").append(suiteReport.worstDrainMillis()).append('`').append(System.lineSeparator());
        builder.append("- allDrainsCompleted: `").append(suiteReport.allDrainsCompleted()).append('`').append(System.lineSeparator());
        builder.append("- degradedOrDownScenarios: `")
                .append(String.join(", ", suiteReport.degradedOrDownScenarios()))
                .append('`')
                .append(System.lineSeparator())
                .append(System.lineSeparator());
        builder.append("| Scenario | Kind | Achieved TPS | P95 us | P99 us | Backlog | Redis Mem | Pending | Switches | Final Profile | Drain ms | Health |")
                .append(System.lineSeparator());
        builder.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---|---:|---|").append(System.lineSeparator());
        for (ScenarioReport report : suiteReport.scenarioReports()) {
            builder.append("| `").append(report.scenarioName()).append("` | `").append(report.kind()).append("` | ")
                    .append(String.format("%.2f", report.achievedTransactionsPerSecond()))
                    .append(" | ").append(report.p95LatencyMicros())
                    .append(" | ").append(report.p99LatencyMicros())
                    .append(" | ").append(report.writeBehindStreamLength())
                    .append(" | ").append(report.redisUsedMemoryBytes())
                    .append(" | ").append(report.compactionPendingCount())
                    .append(" | ").append(report.runtimeProfileSwitchCount())
                    .append(" | `").append(report.finalRuntimeProfile()).append("`")
                    .append(" | ").append(report.drainMillis())
                    .append(" | `").append(report.finalHealthStatus()).append("` |")
                    .append(System.lineSeparator());
        }
        return builder.toString();
    }

    private String toJson(BenchmarkSuiteReport suiteReport) {
        String scenarios = suiteReport.scenarioReports().stream()
                .map(this::scenarioJson)
                .collect(Collectors.joining(","));
        String degraded = suiteReport.degradedOrDownScenarios().stream()
                .map(value -> "\"" + escapeJson(value) + "\"")
                .collect(Collectors.joining(","));
        return "{"
                + "\"suiteName\":\"" + escapeJson(suiteReport.suiteName()) + "\","
                + "\"scaleFactor\":" + suiteReport.scaleFactor() + ","
                + "\"scenarioCount\":" + suiteReport.scenarioCount() + ","
                + "\"totalExecutedOperations\":" + suiteReport.totalExecutedOperations() + ","
                + "\"totalReadOperations\":" + suiteReport.totalReadOperations() + ","
                + "\"totalWriteOperations\":" + suiteReport.totalWriteOperations() + ","
                + "\"totalFailedOperations\":" + suiteReport.totalFailedOperations() + ","
                + "\"averageAchievedTransactionsPerSecond\":" + suiteReport.averageAchievedTransactionsPerSecond() + ","
                + "\"worstP95LatencyMicros\":" + suiteReport.worstP95LatencyMicros() + ","
                + "\"worstP99LatencyMicros\":" + suiteReport.worstP99LatencyMicros() + ","
                + "\"worstWriteBehindBacklog\":" + suiteReport.worstWriteBehindBacklog() + ","
                + "\"worstRedisUsedMemoryBytes\":" + suiteReport.worstRedisUsedMemoryBytes() + ","
                + "\"worstCompactionPendingCount\":" + suiteReport.worstCompactionPendingCount() + ","
                + "\"totalRuntimeProfileSwitchCount\":" + suiteReport.totalRuntimeProfileSwitchCount() + ","
                + "\"maxRuntimeProfileSwitchCount\":" + suiteReport.maxRuntimeProfileSwitchCount() + ","
                + "\"worstDrainMillis\":" + suiteReport.worstDrainMillis() + ","
                + "\"allDrainsCompleted\":" + suiteReport.allDrainsCompleted() + ","
                + "\"degradedOrDownScenarios\":[" + degraded + "],"
                + "\"scenarioReports\":[" + scenarios + "]"
                + "}";
    }

    private String scenarioJson(ScenarioReport report) {
        return "{"
                + "\"scenarioName\":\"" + escapeJson(report.scenarioName()) + "\","
                + "\"kind\":\"" + report.kind().name() + "\","
                + "\"achievedTransactionsPerSecond\":" + report.achievedTransactionsPerSecond() + ","
                + "\"p95LatencyMicros\":" + report.p95LatencyMicros() + ","
                + "\"p99LatencyMicros\":" + report.p99LatencyMicros() + ","
                + "\"writeBehindStreamLength\":" + report.writeBehindStreamLength() + ","
                + "\"redisUsedMemoryBytes\":" + report.redisUsedMemoryBytes() + ","
                + "\"compactionPendingCount\":" + report.compactionPendingCount() + ","
                + "\"runtimeProfileSwitchCount\":" + report.runtimeProfileSwitchCount() + ","
                + "\"finalRuntimeProfile\":\"" + escapeJson(report.finalRuntimeProfile()) + "\","
                + "\"runtimeProfileTimeline\":[" + report.runtimeProfileTimeline().stream().map(value -> "\"" + escapeJson(value) + "\"").collect(Collectors.joining(",")) + "],"
                + "\"redisPressureLevel\":\"" + escapeJson(report.redisPressureLevel()) + "\","
                + "\"drainMillis\":" + report.drainMillis() + ","
                + "\"finalHealthStatus\":\"" + escapeJson(report.finalHealthStatus()) + "\""
                + "}";
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
