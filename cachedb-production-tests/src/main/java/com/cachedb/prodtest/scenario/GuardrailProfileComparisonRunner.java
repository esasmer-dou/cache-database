package com.reactor.cachedb.prodtest.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class GuardrailProfileComparisonRunner {

    public GuardrailProfileComparisonReport run(
            String suiteName,
            String outputPrefix,
            List<EcommerceScenarioProfile> profiles,
            double scaleFactor,
            List<String> degradeProfiles
    ) throws Exception {
        EcommerceProductionScenarioRunner runner = new EcommerceProductionScenarioRunner();
        String previousForcedProfile = System.getProperty(EcommerceProductionScenarioRunner.FORCE_DEGRADE_PROFILE_PROPERTY);
        ArrayList<GuardrailScenarioComparisonReport> scenarioComparisons = new ArrayList<>(profiles.size());
        try {
            for (EcommerceScenarioProfile scenarioProfile : profiles) {
                ArrayList<ScenarioReport> rawReports = new ArrayList<>(degradeProfiles.size());
                for (String degradeProfile : degradeProfiles) {
                    System.setProperty(EcommerceProductionScenarioRunner.FORCE_DEGRADE_PROFILE_PROPERTY, degradeProfile);
                    String reportFilePrefix = scenarioProfile.name() + "-" + degradeProfile.toLowerCase(Locale.ROOT);
                    rawReports.add(runner.run(scenarioProfile, scaleFactor, reportFilePrefix));
                }
                scenarioComparisons.add(buildScenarioComparison(scenarioProfile, rawReports));
            }
        } finally {
            if (previousForcedProfile == null || previousForcedProfile.isBlank()) {
                System.clearProperty(EcommerceProductionScenarioRunner.FORCE_DEGRADE_PROFILE_PROPERTY);
            } else {
                System.setProperty(EcommerceProductionScenarioRunner.FORCE_DEGRADE_PROFILE_PROPERTY, previousForcedProfile);
            }
        }

        List<GuardrailProfileSummary> profileSummaries = buildProfileSummaries(scenarioComparisons, degradeProfiles);
        GuardrailProfileSummary bestSummary = profileSummaries.stream()
                .max(Comparator.comparingDouble(GuardrailProfileSummary::averageBalanceScore))
                .orElse(new GuardrailProfileSummary("N/A", 0, 0.0d, 0.0d, 0L, 0L, 0L, 0L));

        GuardrailProfileComparisonReport report = new GuardrailProfileComparisonReport(
                suiteName,
                scaleFactor,
                List.copyOf(degradeProfiles),
                scenarioComparisons.size(),
                scenarioComparisons.size() * degradeProfiles.size(),
                bestSummary.averageBalanceScore(),
                bestSummary.profileName(),
                scenarioComparisons.stream()
                        .flatMap(comparison -> comparison.profileRuns().stream())
                        .map(GuardrailProfileRunReport::scenarioReport)
                        .mapToLong(ScenarioReport::writeBehindStreamLength)
                        .max()
                        .orElse(0L),
                scenarioComparisons.stream()
                        .flatMap(comparison -> comparison.profileRuns().stream())
                        .map(GuardrailProfileRunReport::scenarioReport)
                        .mapToLong(ScenarioReport::redisUsedMemoryBytes)
                        .max()
                        .orElse(0L),
                scenarioComparisons.stream()
                        .flatMap(comparison -> comparison.profileRuns().stream())
                        .map(GuardrailProfileRunReport::scenarioReport)
                        .mapToLong(ScenarioReport::compactionPendingCount)
                        .max()
                        .orElse(0L),
                List.copyOf(profileSummaries),
                List.copyOf(scenarioComparisons)
        );
        writeReports(outputPrefix, report);
        return report;
    }

    private GuardrailScenarioComparisonReport buildScenarioComparison(
            EcommerceScenarioProfile scenarioProfile,
            List<ScenarioReport> rawReports
    ) {
        double maxTps = rawReports.stream()
                .mapToDouble(ScenarioReport::achievedTransactionsPerSecond)
                .max()
                .orElse(1.0d);
        long minBacklog = rawReports.stream()
                .mapToLong(ScenarioReport::writeBehindStreamLength)
                .min()
                .orElse(1L);
        long minMemory = rawReports.stream()
                .mapToLong(ScenarioReport::redisUsedMemoryBytes)
                .filter(value -> value > 0L)
                .min()
                .orElse(1L);
        long minP99 = rawReports.stream()
                .mapToLong(ScenarioReport::p99LatencyMicros)
                .filter(value -> value > 0L)
                .min()
                .orElse(1L);

        ArrayList<GuardrailProfileRunReport> profileRuns = new ArrayList<>(rawReports.size());
        for (ScenarioReport report : rawReports) {
            double throughputRatio = safeDivide(report.achievedTransactionsPerSecond(), maxTps);
            double backlogRatio = safeDivide(minBacklog, Math.max(1L, report.writeBehindStreamLength()));
            double memoryRatio = safeDivide(minMemory, Math.max(1L, report.redisUsedMemoryBytes()));
            double p99Ratio = safeDivide(minP99, Math.max(1L, report.p99LatencyMicros()));
            double pressureFactor = switch (report.redisPressureLevel().toUpperCase(Locale.ROOT)) {
                case "CRITICAL" -> 0.40d;
                case "WARN" -> 0.70d;
                default -> 1.0d;
            };
            double drainFactor = report.drainCompleted() ? 1.0d : 0.55d;
            double healthFactor = "UP".equalsIgnoreCase(report.finalHealthStatus()) ? 1.0d : 0.75d;
            double balanceScore = ((throughputRatio * 0.45d)
                    + (backlogRatio * 0.20d)
                    + (memoryRatio * 0.15d)
                    + (p99Ratio * 0.10d)
                    + (drainFactor * 0.10d)) * pressureFactor * healthFactor;
            profileRuns.add(new GuardrailProfileRunReport(
                    report.degradeProfile(),
                    report,
                    throughputRatio,
                    backlogRatio,
                    memoryRatio,
                    p99Ratio,
                    pressureFactor,
                    balanceScore
            ));
        }

        GuardrailProfileRunReport bestBalance = profileRuns.stream()
                .max(Comparator.comparingDouble(GuardrailProfileRunReport::balanceScore))
                .orElseThrow();
        GuardrailProfileRunReport bestThroughput = profileRuns.stream()
                .max(Comparator.comparingDouble(run -> run.scenarioReport().achievedTransactionsPerSecond()))
                .orElseThrow();
        GuardrailProfileRunReport lowestBacklog = profileRuns.stream()
                .min(Comparator.comparingLong(run -> run.scenarioReport().writeBehindStreamLength()))
                .orElseThrow();
        GuardrailProfileRunReport lowestMemory = profileRuns.stream()
                .min(Comparator.comparingLong(run -> run.scenarioReport().redisUsedMemoryBytes()))
                .orElseThrow();

        return new GuardrailScenarioComparisonReport(
                scenarioProfile.name(),
                scenarioProfile.kind(),
                bestBalance.profileName(),
                bestThroughput.profileName(),
                lowestBacklog.profileName(),
                lowestMemory.profileName(),
                List.copyOf(profileRuns)
        );
    }

    private List<GuardrailProfileSummary> buildProfileSummaries(
            List<GuardrailScenarioComparisonReport> scenarioComparisons,
            List<String> degradeProfiles
    ) {
        Map<String, List<GuardrailProfileRunReport>> runsByProfile = new LinkedHashMap<>();
        for (String profileName : degradeProfiles) {
            runsByProfile.put(profileName.toUpperCase(Locale.ROOT), new ArrayList<>());
        }
        for (GuardrailScenarioComparisonReport comparison : scenarioComparisons) {
            for (GuardrailProfileRunReport run : comparison.profileRuns()) {
                runsByProfile.computeIfAbsent(run.profileName(), ignored -> new ArrayList<>()).add(run);
            }
        }
        ArrayList<GuardrailProfileSummary> summaries = new ArrayList<>(runsByProfile.size());
        for (Map.Entry<String, List<GuardrailProfileRunReport>> entry : runsByProfile.entrySet()) {
            List<GuardrailProfileRunReport> runs = entry.getValue();
            summaries.add(new GuardrailProfileSummary(
                    entry.getKey(),
                    runs.size(),
                    runs.stream().mapToDouble(GuardrailProfileRunReport::balanceScore).average().orElse(0.0d),
                    runs.stream().mapToDouble(run -> run.scenarioReport().achievedTransactionsPerSecond()).average().orElse(0.0d),
                    runs.stream().map(GuardrailProfileRunReport::scenarioReport).mapToLong(ScenarioReport::writeBehindStreamLength).max().orElse(0L),
                    runs.stream().map(GuardrailProfileRunReport::scenarioReport).mapToLong(ScenarioReport::redisUsedMemoryBytes).max().orElse(0L),
                    runs.stream().map(GuardrailProfileRunReport::scenarioReport).mapToLong(ScenarioReport::compactionPendingCount).max().orElse(0L),
                    runs.stream()
                            .map(GuardrailProfileRunReport::scenarioReport)
                            .filter(report -> !"UP".equalsIgnoreCase(report.finalHealthStatus()))
                            .count()
            ));
        }
        return List.copyOf(summaries);
    }

    private void writeReports(String outputPrefix, GuardrailProfileComparisonReport report) throws IOException {
        Path reportDirectory = Path.of("target", "cachedb-prodtest-reports");
        Files.createDirectories(reportDirectory);
        Files.writeString(reportDirectory.resolve(outputPrefix + ".md"), toMarkdown(report));
        Files.writeString(reportDirectory.resolve(outputPrefix + ".json"), toJson(report));
    }

    private String toMarkdown(GuardrailProfileComparisonReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(report.suiteName()).append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("- scaleFactor: `").append(report.scaleFactor()).append('`').append(System.lineSeparator());
        builder.append("- comparedProfiles: `").append(String.join(", ", report.comparedProfiles())).append('`').append(System.lineSeparator());
        builder.append("- scenarioCount: `").append(report.scenarioCount()).append('`').append(System.lineSeparator());
        builder.append("- totalRuns: `").append(report.totalRuns()).append('`').append(System.lineSeparator());
        builder.append("- bestOverallProfile: `").append(report.bestOverallProfile()).append('`').append(System.lineSeparator());
        builder.append("- highestAverageBalanceScore: `").append(format(report.highestAverageBalanceScore())).append('`').append(System.lineSeparator());
        builder.append("- worstWriteBehindBacklog: `").append(report.worstWriteBehindBacklog()).append('`').append(System.lineSeparator());
        builder.append("- worstRedisUsedMemoryBytes: `").append(report.worstRedisUsedMemoryBytes()).append('`').append(System.lineSeparator());
        builder.append("- worstCompactionPendingCount: `").append(report.worstCompactionPendingCount()).append('`').append(System.lineSeparator()).append(System.lineSeparator());

        builder.append("## Profile Summary").append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("| Profile | Avg Balance Score | Avg TPS | Worst Backlog | Worst Redis Mem | Worst Pending | Degraded/Down Runs |").append(System.lineSeparator());
        builder.append("|---|---:|---:|---:|---:|---:|---:|").append(System.lineSeparator());
        for (GuardrailProfileSummary summary : report.profileSummaries()) {
            builder.append("| `").append(summary.profileName()).append("` | ")
                    .append(format(summary.averageBalanceScore()))
                    .append(" | ").append(format(summary.averageAchievedTransactionsPerSecond()))
                    .append(" | ").append(summary.worstWriteBehindBacklog())
                    .append(" | ").append(summary.worstRedisUsedMemoryBytes())
                    .append(" | ").append(summary.worstCompactionPendingCount())
                    .append(" | ").append(summary.degradedOrDownRuns())
                    .append(" |")
                    .append(System.lineSeparator());
        }

        for (GuardrailScenarioComparisonReport comparison : report.scenarioComparisons()) {
            builder.append(System.lineSeparator())
                    .append("## ").append(comparison.scenarioName())
                    .append(System.lineSeparator()).append(System.lineSeparator());
            builder.append("- kind: `").append(comparison.kind()).append('`').append(System.lineSeparator());
            builder.append("- bestBalanceProfile: `").append(comparison.bestBalanceProfile()).append('`').append(System.lineSeparator());
            builder.append("- bestThroughputProfile: `").append(comparison.bestThroughputProfile()).append('`').append(System.lineSeparator());
            builder.append("- lowestBacklogProfile: `").append(comparison.lowestBacklogProfile()).append('`').append(System.lineSeparator());
            builder.append("- lowestMemoryProfile: `").append(comparison.lowestMemoryProfile()).append('`').append(System.lineSeparator()).append(System.lineSeparator());
            builder.append("| Profile | Balance Score | TPS | Backlog | Redis Mem | Pending | P99 us | Pressure | Health |").append(System.lineSeparator());
            builder.append("|---|---:|---:|---:|---:|---:|---:|---|---|").append(System.lineSeparator());
            for (GuardrailProfileRunReport profileRun : comparison.profileRuns()) {
                ScenarioReport scenarioReport = profileRun.scenarioReport();
                builder.append("| `").append(profileRun.profileName()).append("` | ")
                        .append(format(profileRun.balanceScore()))
                        .append(" | ").append(format(scenarioReport.achievedTransactionsPerSecond()))
                        .append(" | ").append(scenarioReport.writeBehindStreamLength())
                        .append(" | ").append(scenarioReport.redisUsedMemoryBytes())
                        .append(" | ").append(scenarioReport.compactionPendingCount())
                        .append(" | ").append(scenarioReport.p99LatencyMicros())
                        .append(" | `").append(scenarioReport.redisPressureLevel()).append("`")
                        .append(" | `").append(scenarioReport.finalHealthStatus()).append("` |")
                        .append(System.lineSeparator());
            }
        }
        return builder.toString();
    }

    private String toJson(GuardrailProfileComparisonReport report) {
        String comparedProfiles = report.comparedProfiles().stream()
                .map(value -> "\"" + escapeJson(value) + "\"")
                .collect(Collectors.joining(","));
        String profileSummaries = report.profileSummaries().stream()
                .map(this::profileSummaryJson)
                .collect(Collectors.joining(","));
        String scenarioComparisons = report.scenarioComparisons().stream()
                .map(this::scenarioComparisonJson)
                .collect(Collectors.joining(","));
        return "{"
                + "\"suiteName\":\"" + escapeJson(report.suiteName()) + "\","
                + "\"scaleFactor\":" + report.scaleFactor() + ","
                + "\"comparedProfiles\":[" + comparedProfiles + "],"
                + "\"scenarioCount\":" + report.scenarioCount() + ","
                + "\"totalRuns\":" + report.totalRuns() + ","
                + "\"highestAverageBalanceScore\":" + report.highestAverageBalanceScore() + ","
                + "\"bestOverallProfile\":\"" + escapeJson(report.bestOverallProfile()) + "\","
                + "\"worstWriteBehindBacklog\":" + report.worstWriteBehindBacklog() + ","
                + "\"worstRedisUsedMemoryBytes\":" + report.worstRedisUsedMemoryBytes() + ","
                + "\"worstCompactionPendingCount\":" + report.worstCompactionPendingCount() + ","
                + "\"profileSummaries\":[" + profileSummaries + "],"
                + "\"scenarioComparisons\":[" + scenarioComparisons + "]"
                + "}";
    }

    private String profileSummaryJson(GuardrailProfileSummary summary) {
        return "{"
                + "\"profileName\":\"" + escapeJson(summary.profileName()) + "\","
                + "\"scenarioCount\":" + summary.scenarioCount() + ","
                + "\"averageBalanceScore\":" + summary.averageBalanceScore() + ","
                + "\"averageAchievedTransactionsPerSecond\":" + summary.averageAchievedTransactionsPerSecond() + ","
                + "\"worstWriteBehindBacklog\":" + summary.worstWriteBehindBacklog() + ","
                + "\"worstRedisUsedMemoryBytes\":" + summary.worstRedisUsedMemoryBytes() + ","
                + "\"worstCompactionPendingCount\":" + summary.worstCompactionPendingCount() + ","
                + "\"degradedOrDownRuns\":" + summary.degradedOrDownRuns()
                + "}";
    }

    private String scenarioComparisonJson(GuardrailScenarioComparisonReport comparison) {
        String runs = comparison.profileRuns().stream()
                .map(this::profileRunJson)
                .collect(Collectors.joining(","));
        return "{"
                + "\"scenarioName\":\"" + escapeJson(comparison.scenarioName()) + "\","
                + "\"kind\":\"" + comparison.kind().name() + "\","
                + "\"bestBalanceProfile\":\"" + escapeJson(comparison.bestBalanceProfile()) + "\","
                + "\"bestThroughputProfile\":\"" + escapeJson(comparison.bestThroughputProfile()) + "\","
                + "\"lowestBacklogProfile\":\"" + escapeJson(comparison.lowestBacklogProfile()) + "\","
                + "\"lowestMemoryProfile\":\"" + escapeJson(comparison.lowestMemoryProfile()) + "\","
                + "\"profileRuns\":[" + runs + "]"
                + "}";
    }

    private String profileRunJson(GuardrailProfileRunReport run) {
        ScenarioReport report = run.scenarioReport();
        return "{"
                + "\"profileName\":\"" + escapeJson(run.profileName()) + "\","
                + "\"balanceScore\":" + run.balanceScore() + ","
                + "\"throughputRatio\":" + run.throughputRatio() + ","
                + "\"backlogRatio\":" + run.backlogRatio() + ","
                + "\"memoryRatio\":" + run.memoryRatio() + ","
                + "\"p99Ratio\":" + run.p99Ratio() + ","
                + "\"pressureFactor\":" + run.pressureFactor() + ","
                + "\"achievedTransactionsPerSecond\":" + report.achievedTransactionsPerSecond() + ","
                + "\"writeBehindStreamLength\":" + report.writeBehindStreamLength() + ","
                + "\"redisUsedMemoryBytes\":" + report.redisUsedMemoryBytes() + ","
                + "\"compactionPendingCount\":" + report.compactionPendingCount() + ","
                + "\"p99LatencyMicros\":" + report.p99LatencyMicros() + ","
                + "\"redisPressureLevel\":\"" + escapeJson(report.redisPressureLevel()) + "\","
                + "\"finalHealthStatus\":\"" + escapeJson(report.finalHealthStatus()) + "\""
                + "}";
    }

    private double safeDivide(double numerator, double denominator) {
        if (denominator <= 0.0d) {
            return 1.0d;
        }
        return numerator / denominator;
    }

    private double safeDivide(long numerator, long denominator) {
        if (denominator <= 0L) {
            return 1.0d;
        }
        return (double) numerator / (double) denominator;
    }

    private String format(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
