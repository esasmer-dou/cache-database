package com.reactor.cachedb.prodtest.scenario;

import java.util.Arrays;
import java.util.List;

public final class FullScaleBenchmarkMain {
    private FullScaleBenchmarkMain() {
    }

    public static void main(String[] args) throws Exception {
        double scaleFactor = Double.parseDouble(System.getProperty("cachedb.prod.scaleFactor", "1.0"));
        String scenarioList = System.getProperty("cachedb.prod.fullSuite.scenarios", "").trim();
        List<EcommerceScenarioProfile> profiles = scenarioList.isBlank()
                ? FullScaleBenchmarkCatalog.all()
                : Arrays.stream(scenarioList.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(FullScaleBenchmarkCatalog::byName)
                .toList();
        profiles = profiles.stream().map(BenchmarkProfileOverrides::apply).toList();

        BenchmarkSuiteReport report = new FullScaleBenchmarkSuiteRunner().run(profiles, scaleFactor);
        System.out.println("suite=" + report.suiteName()
                + " scenarios=" + report.scenarioCount()
                + " avgAchievedTps=" + String.format("%.2f", report.averageAchievedTransactionsPerSecond())
                + " worstP99Micros=" + report.worstP99LatencyMicros()
                + " allDrainsCompleted=" + report.allDrainsCompleted());
    }
}
