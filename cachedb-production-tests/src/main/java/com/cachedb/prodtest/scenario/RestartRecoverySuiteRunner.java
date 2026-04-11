package com.reactor.cachedb.prodtest.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class RestartRecoverySuiteRunner {

    public RestartRecoverySuiteReport run(int cycles) throws Exception {
        ArrayList<RestartRecoveryCycleReport> items = new ArrayList<>();
        ProductionCertificationRunner runner = new ProductionCertificationRunner();
        int requestedCycles = Math.max(1, cycles);
        for (int cycle = 1; cycle <= requestedCycles; cycle++) {
            RestartRecoveryCertificationResult result = runner.verifyRestartRecoveryForSuite();
            items.add(new RestartRecoveryCycleReport(
                    cycle,
                    result.persistedBeforeRestart(),
                    result.recoveredAfterRestart(),
                    result.queryIndexRebuildSuccessful(),
                    result.healthStatus()
            ));
        }
        RestartRecoverySuiteReport report = new RestartRecoverySuiteReport(
                requestedCycles,
                (int) items.stream().filter(item -> item.persisted() && item.recovered() && item.rebuildSucceeded()).count(),
                items.stream().allMatch(item -> item.persisted() && item.recovered() && item.rebuildSucceeded()),
                Instant.now(),
                List.copyOf(items)
        );
        writeReports(report);
        return report;
    }

    private void writeReports(RestartRecoverySuiteReport report) throws IOException {
        Path reportDirectory = Path.of("target", "cachedb-prodtest-reports");
        Files.createDirectories(reportDirectory);
        Files.writeString(reportDirectory.resolve("restart-recovery-suite.json"), toJson(report));
        Files.writeString(reportDirectory.resolve("restart-recovery-suite.md"), toMarkdown(report));
    }

    private String toJson(RestartRecoverySuiteReport report) {
        StringBuilder builder = new StringBuilder("{\"cycleCount\":")
                .append(report.cycleCount())
                .append(",\"successfulCycles\":").append(report.successfulCycles())
                .append(",\"allSuccessful\":").append(report.allSuccessful())
                .append(",\"cycles\":[");
        for (int index = 0; index < report.cycles().size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            RestartRecoveryCycleReport cycle = report.cycles().get(index);
            builder.append("{\"cycle\":").append(cycle.cycle())
                    .append(",\"persisted\":").append(cycle.persisted())
                    .append(",\"recovered\":").append(cycle.recovered())
                    .append(",\"rebuildSucceeded\":").append(cycle.rebuildSucceeded())
                    .append(",\"healthStatus\":\"").append(cycle.healthStatus()).append("\"}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String toMarkdown(RestartRecoverySuiteReport report) {
        StringBuilder builder = new StringBuilder("# Restart Recovery Suite\n\n");
        builder.append("- Cycle Count: `").append(report.cycleCount()).append("`\n");
        builder.append("- Successful Cycles: `").append(report.successfulCycles()).append("`\n");
        builder.append("- All Successful: `").append(report.allSuccessful()).append("`\n\n");
        builder.append("| Cycle | Persisted | Recovered | Rebuild | Health |\n");
        builder.append("| --- | --- | --- | --- | --- |\n");
        for (RestartRecoveryCycleReport cycle : report.cycles()) {
            builder.append("| ").append(cycle.cycle())
                    .append(" | ").append(cycle.persisted())
                    .append(" | ").append(cycle.recovered())
                    .append(" | ").append(cycle.rebuildSucceeded())
                    .append(" | ").append(cycle.healthStatus())
                    .append(" |\n");
        }
        return builder.toString();
    }
}
