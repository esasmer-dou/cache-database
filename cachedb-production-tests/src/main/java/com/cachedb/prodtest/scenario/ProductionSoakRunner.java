package com.reactor.cachedb.prodtest.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class ProductionSoakRunner {

    public ProductionSoakReport run(EcommerceScenarioProfile profile, double scaleFactor, int iterations) throws Exception {
        EcommerceProductionScenarioRunner runner = new EcommerceProductionScenarioRunner();
        EcommerceScenarioProfile effectiveProfile = soakProfile(profile);
        ArrayList<SoakIterationReport> iterationReports = new ArrayList<>();
        for (int iteration = 1; iteration <= Math.max(1, iterations); iteration++) {
            ScenarioReport report = runner.run(effectiveProfile, scaleFactor, effectiveProfile.name() + "-soak-" + iteration);
            iterationReports.add(new SoakIterationReport(iteration, report));
        }

        List<ScenarioReport> reports = iterationReports.stream().map(SoakIterationReport::scenarioReport).toList();
        ProductionSoakReport soakReport = new ProductionSoakReport(
                effectiveProfile.name(),
                scaleFactor,
                iterationReports.size(),
                reports.stream().mapToLong(ScenarioReport::elapsedMillis).sum(),
                reports.stream().mapToDouble(ScenarioReport::achievedTransactionsPerSecond).average().orElse(0.0d),
                reports.stream().mapToDouble(ScenarioReport::achievedTransactionsPerSecond).min().orElse(0.0d),
                reports.stream().mapToDouble(ScenarioReport::achievedTransactionsPerSecond).max().orElse(0.0d),
                reports.stream().mapToLong(ScenarioReport::redisUsedMemoryBytes).max().orElse(0L),
                reports.stream().mapToLong(ScenarioReport::writeBehindStreamLength).max().orElse(0L),
                reports.stream().mapToLong(ScenarioReport::compactionPendingCount).max().orElse(0L),
                reports.stream().mapToLong(ScenarioReport::runtimeProfileSwitchCount).sum(),
                reports.stream().allMatch(ScenarioReport::drainCompleted),
                List.copyOf(new LinkedHashSet<>(reports.stream().map(ScenarioReport::finalHealthStatus).toList())),
                Instant.now(),
                List.copyOf(iterationReports)
        );
        writeReports(soakReport);
        return soakReport;
    }

    private EcommerceScenarioProfile soakProfile(EcommerceScenarioProfile profile) {
        int targetDurationSeconds = Integer.getInteger("cachedb.prod.soak.targetDurationSeconds", -1);
        boolean disableSafetyCaps = Boolean.parseBoolean(System.getProperty("cachedb.prod.soak.disableSafetyCaps", "false"));
        int targetTps = Integer.getInteger("cachedb.prod.soak.targetTps", -1);
        int maxCustomers = Integer.getInteger("cachedb.prod.soak.maxCustomers", 2_000);
        int maxProducts = Integer.getInteger("cachedb.prod.soak.maxProducts", 1_000);
        int maxHotProducts = Integer.getInteger("cachedb.prod.soak.maxHotProducts", 50);
        int maxWorkers = Integer.getInteger("cachedb.prod.soak.maxWorkers", 6);
        int maxDurationSeconds = Integer.getInteger("cachedb.prod.soak.maxDurationSeconds", 10);
        if (disableSafetyCaps) {
            return new EcommerceScenarioProfile(
                    profile.name(),
                    profile.kind(),
                    profile.description(),
                    targetTps > 0 ? targetTps : profile.targetTransactionsPerSecond(),
                    targetDurationSeconds > 0 ? targetDurationSeconds : profile.durationSeconds(),
                    profile.workerThreads(),
                    profile.customerCount(),
                    profile.productCount(),
                    profile.hotProductSetSize(),
                    profile.browsePercent(),
                    profile.productLookupPercent(),
                    profile.cartWritePercent(),
                    profile.inventoryReservePercent(),
                    profile.checkoutPercent(),
                    profile.customerTouchPercent(),
                    profile.writeBehindWorkerThreads(),
                    profile.writeBehindBatchSize(),
                    profile.hotEntityLimit(),
                    profile.pageSize(),
                    profile.entityTtlSeconds(),
                    profile.pageTtlSeconds()
            );
        }
        return new EcommerceScenarioProfile(
                profile.name(),
                profile.kind(),
                profile.description(),
                Math.min(targetTps > 0 ? targetTps : profile.targetTransactionsPerSecond(), Integer.getInteger("cachedb.prod.soak.maxTps", 2_000)),
                Math.min(targetDurationSeconds > 0 ? targetDurationSeconds : profile.durationSeconds(), maxDurationSeconds),
                Math.min(profile.workerThreads(), maxWorkers),
                Math.min(profile.customerCount(), maxCustomers),
                Math.min(profile.productCount(), maxProducts),
                Math.min(profile.hotProductSetSize(), maxHotProducts),
                profile.browsePercent(),
                profile.productLookupPercent(),
                profile.cartWritePercent(),
                profile.inventoryReservePercent(),
                profile.checkoutPercent(),
                profile.customerTouchPercent(),
                Math.min(profile.writeBehindWorkerThreads(), 2),
                Math.min(profile.writeBehindBatchSize(), 100),
                Math.min(profile.hotEntityLimit(), 1_000),
                Math.min(profile.pageSize(), 100),
                Math.min(profile.entityTtlSeconds(), 120),
                Math.min(profile.pageTtlSeconds(), 60)
        );
    }

    private void writeReports(ProductionSoakReport report) throws IOException {
        Path reportDirectory = Path.of("target", "cachedb-prodtest-reports");
        Files.createDirectories(reportDirectory);
        Files.writeString(reportDirectory.resolve(report.scenarioName() + "-soak.json"), toJson(report));
        Files.writeString(reportDirectory.resolve(report.scenarioName() + "-soak.md"), toMarkdown(report));
    }

    private String toJson(ProductionSoakReport report) {
        StringBuilder builder = new StringBuilder("{");
        builder.append("\"scenarioName\":\"").append(escapeJson(report.scenarioName())).append("\",");
        builder.append("\"scaleFactor\":").append(report.scaleFactor()).append(',');
        builder.append("\"iterationCount\":").append(report.iterationCount()).append(',');
        builder.append("\"totalElapsedMillis\":").append(report.totalElapsedMillis()).append(',');
        builder.append("\"averageTps\":").append(report.averageTps()).append(',');
        builder.append("\"minTps\":").append(report.minTps()).append(',');
        builder.append("\"maxTps\":").append(report.maxTps()).append(',');
        builder.append("\"maxRedisMemoryBytes\":").append(report.maxRedisMemoryBytes()).append(',');
        builder.append("\"maxBacklog\":").append(report.maxBacklog()).append(',');
        builder.append("\"maxCompactionPending\":").append(report.maxCompactionPending()).append(',');
        builder.append("\"totalRuntimeProfileSwitches\":").append(report.totalRuntimeProfileSwitches()).append(',');
        builder.append("\"allRunsDrained\":").append(report.allRunsDrained()).append(',');
        builder.append("\"distinctHealthStatuses\":[");
        for (int index = 0; index < report.distinctHealthStatuses().size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append('"').append(escapeJson(report.distinctHealthStatuses().get(index))).append('"');
        }
        builder.append("],\"iterations\":[");
        for (int index = 0; index < report.iterations().size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            SoakIterationReport iteration = report.iterations().get(index);
            builder.append("{\"iteration\":").append(iteration.iteration()).append(',')
                    .append("\"tps\":").append(iteration.scenarioReport().achievedTransactionsPerSecond()).append(',')
                    .append("\"backlog\":").append(iteration.scenarioReport().writeBehindStreamLength()).append(',')
                    .append("\"redisUsedMemoryBytes\":").append(iteration.scenarioReport().redisUsedMemoryBytes()).append(',')
                    .append("\"health\":\"").append(escapeJson(iteration.scenarioReport().finalHealthStatus())).append("\"}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String toMarkdown(ProductionSoakReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Production Soak Report\n\n");
        builder.append("- Scenario: `").append(report.scenarioName()).append("`\n");
        builder.append("- Scale: `").append(report.scaleFactor()).append("`\n");
        builder.append("- Iterations: `").append(report.iterationCount()).append("`\n");
        builder.append("- Avg TPS: `").append(String.format(Locale.ROOT, "%.2f", report.averageTps())).append("`\n");
        builder.append("- Min TPS: `").append(String.format(Locale.ROOT, "%.2f", report.minTps())).append("`\n");
        builder.append("- Max TPS: `").append(String.format(Locale.ROOT, "%.2f", report.maxTps())).append("`\n");
        builder.append("- Max Redis Mem: `").append(report.maxRedisMemoryBytes()).append("`\n");
        builder.append("- Max Backlog: `").append(report.maxBacklog()).append("`\n");
        builder.append("- Max Pending: `").append(report.maxCompactionPending()).append("`\n");
        builder.append("- Total Profile Switches: `").append(report.totalRuntimeProfileSwitches()).append("`\n");
        builder.append("- All Runs Drained: `").append(report.allRunsDrained()).append("`\n\n");
        builder.append("| Iteration | TPS | Backlog | Redis Mem | Health |\n");
        builder.append("| --- | ---: | ---: | ---: | --- |\n");
        for (SoakIterationReport iteration : report.iterations()) {
            builder.append("| ").append(iteration.iteration())
                    .append(" | ").append(String.format(Locale.ROOT, "%.2f", iteration.scenarioReport().achievedTransactionsPerSecond()))
                    .append(" | ").append(iteration.scenarioReport().writeBehindStreamLength())
                    .append(" | ").append(iteration.scenarioReport().redisUsedMemoryBytes())
                    .append(" | ").append(iteration.scenarioReport().finalHealthStatus())
                    .append(" |\n");
        }
        return builder.toString();
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
