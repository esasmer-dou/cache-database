package com.reactor.cachedb.prodtest.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ProductionSoakPlanRunner {

    public ProductionSoakPlanReport run() throws Exception {
        List<ProductionSoakPlan> plans = configuredPlans();
        ProductionSoakRunner runner = new ProductionSoakRunner();
        ArrayList<ProductionSoakPlanResult> results = new ArrayList<>(plans.size());
        for (ProductionSoakPlan plan : plans) {
            withProperty("cachedb.prod.soak.targetDurationSeconds", String.valueOf(plan.targetDurationSeconds()), () ->
                    withProperty("cachedb.prod.soak.disableSafetyCaps", String.valueOf(plan.disableSafetyCaps()), () ->
                            results.add(new ProductionSoakPlanResult(
                                    plan,
                                    runner.run(ScenarioCatalog.byName(plan.scenarioName()), plan.scaleFactor(), plan.iterations())
                            ))
                    )
            );
        }

        ProductionSoakPlanReport report = new ProductionSoakPlanReport(
                "production-soak-plan",
                Instant.now(),
                results.size(),
                List.copyOf(results)
        );
        writeReports(report);
        return report;
    }

    private List<ProductionSoakPlan> configuredPlans() {
        String rawPlans = System.getProperty(
                "cachedb.prod.soak.plans",
                "soak-1h:campaign-push-spike:0.02:1:3600:false,soak-4h:campaign-push-spike:0.02:1:14400:false"
        );
        ArrayList<ProductionSoakPlan> plans = new ArrayList<>();
        for (String rawPlan : rawPlans.split(",")) {
            String value = rawPlan.trim();
            if (value.isEmpty()) {
                continue;
            }
            String[] parts = value.split(":");
            if (parts.length != 6) {
                throw new IllegalArgumentException("Invalid soak plan: " + value);
            }
            plans.add(new ProductionSoakPlan(
                    parts[0],
                    parts[1],
                    Double.parseDouble(parts[2]),
                    Integer.parseInt(parts[3]),
                    Integer.parseInt(parts[4]),
                    Boolean.parseBoolean(parts[5])
            ));
        }
        return List.copyOf(plans);
    }

    private void writeReports(ProductionSoakPlanReport report) throws IOException {
        Path reportDirectory = Path.of("target", "cachedb-prodtest-reports");
        Files.createDirectories(reportDirectory);
        Files.writeString(reportDirectory.resolve("production-soak-plan-report.json"), toJson(report));
        Files.writeString(reportDirectory.resolve("production-soak-plan-report.md"), toMarkdown(report));
    }

    private String toJson(ProductionSoakPlanReport report) {
        StringBuilder builder = new StringBuilder("{");
        builder.append("\"planName\":\"").append(escapeJson(report.planName())).append("\",");
        builder.append("\"recordedAt\":\"").append(report.recordedAt()).append("\",");
        builder.append("\"runCount\":").append(report.runCount()).append(",");
        builder.append("\"results\":[");
        for (int index = 0; index < report.results().size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            ProductionSoakPlanResult result = report.results().get(index);
            builder.append("{\"name\":\"").append(escapeJson(result.plan().name())).append("\",")
                    .append("\"scenario\":\"").append(escapeJson(result.plan().scenarioName())).append("\",")
                    .append("\"scaleFactor\":").append(result.plan().scaleFactor()).append(",")
                    .append("\"iterations\":").append(result.plan().iterations()).append(",")
                    .append("\"targetDurationSeconds\":").append(result.plan().targetDurationSeconds()).append(",")
                    .append("\"disableSafetyCaps\":").append(result.plan().disableSafetyCaps()).append(",")
                    .append("\"averageTps\":").append(result.report().averageTps()).append(",")
                    .append("\"maxRedisMemoryBytes\":").append(result.report().maxRedisMemoryBytes()).append(",")
                    .append("\"maxBacklog\":").append(result.report().maxBacklog()).append(",")
                    .append("\"allRunsDrained\":").append(result.report().allRunsDrained()).append("}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String toMarkdown(ProductionSoakPlanReport report) {
        StringBuilder builder = new StringBuilder("# Production Soak Plan Report\n\n");
        builder.append("- Recorded At: `").append(report.recordedAt()).append("`\n");
        builder.append("- Runs: `").append(report.runCount()).append("`\n\n");
        builder.append("| Plan | Scenario | Scale | Iterations | Target Duration (s) | Safety Caps | Avg TPS | Max Redis Mem | Max Backlog | All Drained |\n");
        builder.append("| --- | --- | ---: | ---: | ---: | --- | ---: | ---: | ---: | --- |\n");
        for (ProductionSoakPlanResult result : report.results()) {
            builder.append("| ").append(result.plan().name())
                    .append(" | ").append(result.plan().scenarioName())
                    .append(" | ").append(formatDouble(result.plan().scaleFactor()))
                    .append(" | ").append(result.plan().iterations())
                    .append(" | ").append(result.plan().targetDurationSeconds())
                    .append(" | ").append(!result.plan().disableSafetyCaps())
                    .append(" | ").append(formatDouble(result.report().averageTps()))
                    .append(" | ").append(result.report().maxRedisMemoryBytes())
                    .append(" | ").append(result.report().maxBacklog())
                    .append(" | ").append(result.report().allRunsDrained())
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
