package com.reactor.cachedb.prodtest.scenario;

import java.util.Arrays;
import java.util.List;

public final class RepresentativeCapacityBenchmarkMain {

    private RepresentativeCapacityBenchmarkMain() {
    }

    public static void main(String[] args) throws Exception {
        List<Double> scaleFactors = Arrays.stream(System.getProperty("cachedb.prod.scaleLadder", "0.10,0.25,0.50,1.0").split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Double::parseDouble)
                .toList();
        List<EcommerceScenarioProfile> profiles = RepresentativeCapacityBenchmarkCatalog.all().stream()
                .map(BenchmarkProfileOverrides::apply)
                .toList();

        ScaleLadderBenchmarkReport report = new ScaleLadderBenchmarkRunner().run(
                "representative-container-capacity-benchmark",
                "representative-container-capacity-benchmark",
                profiles,
                scaleFactors
        );
        System.out.println("suite=" + report.suiteName()
                + " runs=" + report.runCount()
                + " bestAvgAchievedTps=" + String.format("%.2f", report.bestAverageAchievedTransactionsPerSecond())
                + " worstP99Micros=" + report.worstP99LatencyMicros()
                + " allRunsDrained=" + report.allRunsDrained());
    }
}
