package com.reactor.cachedb.prodtest.scenario;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class GuardrailProfileComparisonMain {

    private GuardrailProfileComparisonMain() {
    }

    public static void main(String[] args) throws Exception {
        double scaleFactor = Double.parseDouble(System.getProperty("cachedb.prod.scaleFactor", "1.0"));
        List<String> comparedProfiles = Arrays.stream(System.getProperty("cachedb.prod.guardrail.compareProfiles", "STANDARD,BALANCED,AGGRESSIVE").split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .toList();
        List<EcommerceScenarioProfile> scenarioProfiles = resolveScenarioProfiles();

        GuardrailProfileComparisonReport report = new GuardrailProfileComparisonRunner().run(
                "guardrail-profile-comparison",
                "guardrail-profile-comparison",
                scenarioProfiles,
                scaleFactor,
                comparedProfiles
        );
        System.out.println("suite=" + report.suiteName()
                + " scaleFactor=" + report.scaleFactor()
                + " scenarios=" + report.scenarioCount()
                + " profiles=" + String.join(",", report.comparedProfiles())
                + " bestOverallProfile=" + report.bestOverallProfile()
                + " highestAverageBalanceScore=" + String.format(Locale.US, "%.2f", report.highestAverageBalanceScore()));
    }

    private static List<EcommerceScenarioProfile> resolveScenarioProfiles() {
        String configuredScenarios = System.getProperty("cachedb.prod.guardrail.compareScenarios", "").trim();
        if (configuredScenarios.isEmpty()) {
            return RepresentativeCapacityBenchmarkCatalog.all().stream()
                    .map(BenchmarkProfileOverrides::apply)
                    .toList();
        }
        return Arrays.stream(configuredScenarios.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(FullScaleBenchmarkCatalog::byName)
                .map(BenchmarkProfileOverrides::apply)
                .toList();
    }
}
