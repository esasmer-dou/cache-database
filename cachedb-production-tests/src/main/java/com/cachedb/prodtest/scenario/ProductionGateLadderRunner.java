package com.reactor.cachedb.prodtest.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ProductionGateLadderRunner {

    public ProductionGateLadderReport run() throws Exception {
        List<ProductionGateLadderProfile> profiles = configuredProfiles();
        ArrayList<ProductionGateLadderResult> results = new ArrayList<>(profiles.size());
        for (ProductionGateLadderProfile profile : profiles) {
            withProperty("cachedb.prod.certification.scenario", profile.scenarioName(), () ->
                    withProperty("cachedb.prod.certification.scaleFactor", String.valueOf(profile.scaleFactor()), () ->
                            withProperty("cachedb.prod.certification.minTps", formatDouble(profile.minimumTps()), () ->
                                    withProperty("cachedb.prod.certification.maxBacklog", String.valueOf(profile.maxBacklog()), () ->
                                            withProperty("cachedb.prod.certification.requireDrain", String.valueOf(profile.requireDrain()), () ->
                                                    results.add(new ProductionGateLadderResult(profile, new ProductionGateRunner().run()))
                                            )
                                    )
                            )
                    )
            );
        }

        ProductionGateLadderReport report = new ProductionGateLadderReport(
                "production-gate-ladder",
                Instant.now(),
                results.stream().allMatch(result -> result.gateReport().overallStatus() == ProductionGateStatus.PASS),
                results.size(),
                List.copyOf(results)
        );
        writeReports(report);
        return report;
    }

    private List<ProductionGateLadderProfile> configuredProfiles() {
        String rawProfiles = System.getProperty(
                "cachedb.prod.gateLadder.profiles",
                "baseline:campaign-push-spike:0.02:50:2000:false,"
                        + "heavy:campaign-push-spike:0.05:65:3000:true,"
                        + "calibrated-heavy:campaign-push-spike:0.05:63:3000:true"
        );
        ArrayList<ProductionGateLadderProfile> profiles = new ArrayList<>();
        for (String rawProfile : rawProfiles.split(",")) {
            String value = rawProfile.trim();
            if (value.isEmpty()) {
                continue;
            }
            String[] parts = value.split(":");
            if (parts.length != 6) {
                throw new IllegalArgumentException("Invalid gate ladder profile: " + value);
            }
            profiles.add(new ProductionGateLadderProfile(
                    parts[0],
                    parts[1],
                    Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]),
                    Long.parseLong(parts[4]),
                    Boolean.parseBoolean(parts[5])
            ));
        }
        return List.copyOf(profiles);
    }

    private void writeReports(ProductionGateLadderReport report) throws IOException {
        Path reportDirectory = Path.of("target", "cachedb-prodtest-reports");
        Files.createDirectories(reportDirectory);
        Files.writeString(reportDirectory.resolve("production-gate-ladder-report.json"), toJson(report));
        Files.writeString(reportDirectory.resolve("production-gate-ladder-report.md"), toMarkdown(report));
    }

    private String toJson(ProductionGateLadderReport report) {
        StringBuilder builder = new StringBuilder("{");
        builder.append("\"ladderName\":\"").append(escapeJson(report.ladderName())).append("\",");
        builder.append("\"recordedAt\":\"").append(report.recordedAt()).append("\",");
        builder.append("\"passed\":").append(report.passed()).append(",");
        builder.append("\"profileCount\":").append(report.profileCount()).append(",");
        builder.append("\"results\":[");
        for (int index = 0; index < report.results().size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            ProductionGateLadderResult result = report.results().get(index);
            builder.append("{\"profile\":\"").append(escapeJson(result.profile().name())).append("\",")
                    .append("\"scenario\":\"").append(escapeJson(result.profile().scenarioName())).append("\",")
                    .append("\"scaleFactor\":").append(result.profile().scaleFactor()).append(",")
                    .append("\"minimumTps\":").append(result.profile().minimumTps()).append(",")
                    .append("\"maxBacklog\":").append(result.profile().maxBacklog()).append(",")
                    .append("\"requireDrain\":").append(result.profile().requireDrain()).append(",")
                    .append("\"overallStatus\":\"").append(result.gateReport().overallStatus()).append("\",")
                    .append("\"summary\":\"").append(escapeJson(result.gateReport().summary())).append("\"}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String toMarkdown(ProductionGateLadderReport report) {
        StringBuilder builder = new StringBuilder("# Production Gate Ladder Report\n\n");
        builder.append("- Recorded At: `").append(report.recordedAt()).append("`\n");
        builder.append("- Passed: `").append(report.passed()).append("`\n");
        builder.append("- Profiles: `").append(report.profileCount()).append("`\n\n");
        builder.append("| Profile | Scenario | Scale | Min TPS | Max Backlog | Require Drain | Status | Summary |\n");
        builder.append("| --- | --- | ---: | ---: | ---: | --- | --- | --- |\n");
        for (ProductionGateLadderResult result : report.results()) {
            builder.append("| ").append(result.profile().name())
                    .append(" | ").append(result.profile().scenarioName())
                    .append(" | ").append(formatDouble(result.profile().scaleFactor()))
                    .append(" | ").append(formatDouble(result.profile().minimumTps()))
                    .append(" | ").append(result.profile().maxBacklog())
                    .append(" | ").append(result.profile().requireDrain())
                    .append(" | ").append(result.gateReport().overallStatus())
                    .append(" | ").append(result.gateReport().summary().replace("|", "\\|"))
                    .append(" |\n");
        }
        return builder.toString();
    }

    private void withProperty(String key, String value, ThrowingRunnable runnable) throws Exception {
        String previous = System.getProperty(key);
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
        try {
            runnable.run();
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    private String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
