package com.reactor.cachedb.prodtest.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class ProductionGateRunner {

    public ProductionGateReport run() throws Exception {
        ProductionCertificationReport certification = new ProductionCertificationRunner().run();
        FaultInjectionSuiteReport faultInjection = new FaultInjectionSuiteRunner().run();
        CrashReplayChaosSuiteReport crashReplayChaos = certification.crashReplayChaos();

        ArrayList<ProductionGateCheck> checks = new ArrayList<>();
        checks.add(fromCertification(certification));
        checks.add(fromFaultInjection(faultInjection));
        checks.add(fromCrashReplay(crashReplayChaos));
        checks.add(fromDrain(certification));
        checks.add(fromHardRejection(certification));

        ProductionGateStatus overall = checks.stream()
                .map(ProductionGateCheck::status)
                .reduce(ProductionGateStatus.PASS, (left, right) -> combine(left, right));

        String summary = checks.stream()
                .map(check -> check.name() + "=" + check.status().name())
                .collect(Collectors.joining(", "));

        ProductionGateReport report = new ProductionGateReport(
                "production-gate",
                Instant.now(),
                overall,
                List.copyOf(checks),
                summary
        );
        writeReports(report);
        return report;
    }

    private ProductionGateCheck fromCertification(ProductionCertificationReport report) {
        return new ProductionGateCheck(
                "certification",
                report.passed() ? ProductionGateStatus.PASS : ProductionGateStatus.FAIL,
                "benchmark + recovery certification",
                "passed=" + report.passed()
                        + ", tps=" + formatDouble(report.benchmarkReport().achievedTransactionsPerSecond())
                        + ", backlog=" + report.benchmarkReport().writeBehindStreamLength()
        );
    }

    private ProductionGateCheck fromFaultInjection(FaultInjectionSuiteReport report) {
        return new ProductionGateCheck(
                "fault_injection",
                report.allSuccessful() ? ProductionGateStatus.PASS : ProductionGateStatus.FAIL,
                report.successfulScenarios() + "/" + report.scenarioCount() + " scenarios passed",
                report.scenarios().stream()
                        .filter(item -> !item.passed())
                        .map(item -> item.scenarioName() + ":" + item.note())
                        .collect(Collectors.joining("; "))
        );
    }

    private ProductionGateCheck fromCrashReplay(CrashReplayChaosSuiteReport report) {
        return new ProductionGateCheck(
                "crash_replay_chaos",
                report.allSuccessful() ? ProductionGateStatus.PASS : ProductionGateStatus.FAIL,
                report.successfulScenarios() + "/" + report.scenarioCount() + " scenarios passed",
                report.scenarios().stream()
                        .filter(item -> !item.passed())
                        .map(item -> item.scenarioName() + ":" + item.note())
                        .collect(Collectors.joining("; "))
        );
    }

    private ProductionGateCheck fromDrain(ProductionCertificationReport report) {
        boolean completed = report.benchmarkReport().drainCompleted();
        return new ProductionGateCheck(
                "drain_completion",
                completed ? ProductionGateStatus.PASS : ProductionGateStatus.WARN,
                "drainCompleted=" + completed,
                "drainMillis=" + report.benchmarkReport().drainMillis()
                        + ", finalHealth=" + report.benchmarkReport().finalHealthStatus()
        );
    }

    private ProductionGateCheck fromHardRejection(ProductionCertificationReport report) {
        boolean clean = report.benchmarkReport().hardRejectedWriteCount() == 0L;
        return new ProductionGateCheck(
                "hard_rejections",
                clean ? ProductionGateStatus.PASS : ProductionGateStatus.FAIL,
                clean ? "no hard producer rejections" : "hard producer rejection observed",
                "hardRejectedWriteCount=" + report.benchmarkReport().hardRejectedWriteCount()
                        + ", health=" + report.benchmarkReport().finalHealthStatus()
                        + ", profile=" + report.benchmarkReport().finalRuntimeProfile()
        );
    }

    private ProductionGateStatus combine(ProductionGateStatus left, ProductionGateStatus right) {
        if (left == ProductionGateStatus.FAIL || right == ProductionGateStatus.FAIL) {
            return ProductionGateStatus.FAIL;
        }
        if (left == ProductionGateStatus.WARN || right == ProductionGateStatus.WARN) {
            return ProductionGateStatus.WARN;
        }
        return ProductionGateStatus.PASS;
    }

    private void writeReports(ProductionGateReport report) throws IOException {
        Path reportDirectory = Path.of("target", "cachedb-prodtest-reports");
        Files.createDirectories(reportDirectory);
        Files.writeString(reportDirectory.resolve("production-gate-report.json"), toJson(report));
        Files.writeString(reportDirectory.resolve("production-gate-report.md"), toMarkdown(report));
    }

    private String toJson(ProductionGateReport report) {
        StringBuilder builder = new StringBuilder("{\"gateName\":\"")
                .append(escapeJson(report.gateName()))
                .append("\",\"recordedAt\":\"").append(report.recordedAt())
                .append("\",\"overallStatus\":\"").append(report.overallStatus())
                .append("\",\"summary\":\"").append(escapeJson(report.summary()))
                .append("\",\"checks\":[");
        for (int index = 0; index < report.checks().size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            ProductionGateCheck check = report.checks().get(index);
            builder.append("{\"name\":\"").append(escapeJson(check.name())).append("\",")
                    .append("\"status\":\"").append(check.status()).append("\",")
                    .append("\"summary\":\"").append(escapeJson(check.summary())).append("\",")
                    .append("\"details\":\"").append(escapeJson(check.details())).append("\"}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String toMarkdown(ProductionGateReport report) {
        StringBuilder builder = new StringBuilder("# Production Gate Report\n\n");
        builder.append("- Recorded At: `").append(report.recordedAt()).append("`\n");
        builder.append("- Overall Status: `").append(report.overallStatus()).append("`\n");
        builder.append("- Summary: `").append(report.summary()).append("`\n\n");
        builder.append("| Check | Status | Summary | Details |\n");
        builder.append("| --- | --- | --- | --- |\n");
        for (ProductionGateCheck check : report.checks()) {
            builder.append("| ").append(check.name())
                    .append(" | ").append(check.status())
                    .append(" | ").append(check.summary().replace("|", "\\|"))
                    .append(" | ").append((check.details() == null ? "" : check.details()).replace("|", "\\|"))
                    .append(" |\n");
        }
        return builder.toString();
    }

    private String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String escapeJson(String value) {
        return value == null ? "" : value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
