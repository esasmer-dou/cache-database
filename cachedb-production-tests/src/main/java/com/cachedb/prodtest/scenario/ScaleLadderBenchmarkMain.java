package com.reactor.cachedb.prodtest.scenario;

import java.util.Arrays;
import java.util.List;

public final class ScaleLadderBenchmarkMain {

    private ScaleLadderBenchmarkMain() {
    }

    public static void main(String[] args) throws Exception {
        String scenarioList = System.getProperty("cachedb.prod.fullSuite.scenarios", "").trim();
        List<EcommerceScenarioProfile> profiles = scenarioList.isBlank()
                ? FullScaleBenchmarkCatalog.all()
                : Arrays.stream(scenarioList.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(FullScaleBenchmarkCatalog::byName)
                .toList();

        List<Double> scaleFactors = Arrays.stream(System.getProperty("cachedb.prod.scaleLadder", "0.10,0.25,0.50,1.0").split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Double::parseDouble)
                .toList();

        ScaleLadderBenchmarkReport report = new ScaleLadderBenchmarkRunner().run(profiles, scaleFactors);
        System.out.println("suite=" + report.suiteName()
                + " runs=" + report.runCount()
                + " bestAvgAchievedTps=" + String.format("%.2f", report.bestAverageAchievedTransactionsPerSecond())
                + " worstP99Micros=" + report.worstP99LatencyMicros()
                + " allRunsDrained=" + report.allRunsDrained());
    }
}
