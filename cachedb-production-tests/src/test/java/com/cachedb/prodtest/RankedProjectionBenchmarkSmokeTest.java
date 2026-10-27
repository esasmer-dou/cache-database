package com.reactor.cachedb.prodtest;

import com.reactor.cachedb.prodtest.scenario.RankedProjectionBenchmarkReport;
import com.reactor.cachedb.prodtest.scenario.RankedProjectionBenchmarkRunner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

final class RankedProjectionBenchmarkSmokeTest {

    @Test
    void shouldKeepRankedTopWindowCheaperThanWideCandidateScan() {
        // Use representative candidate counts and warm both shapes before comparing steady-state costs.
        RankedProjectionBenchmarkReport report = new RankedProjectionBenchmarkRunner().run(5_000, 20_000, 4_096, 24, 18);

        Assertions.assertEquals(2, report.shapeReports().size());
        Assertions.assertTrue(report.disclaimer().contains("does not benchmark Redis network I/O"));
        Map<String, RankedProjectionBenchmarkReport.ShapeReport> byName = report.shapeReports().stream()
                .collect(Collectors.toMap(RankedProjectionBenchmarkReport.ShapeReport::shapeName, Function.identity()));
        RankedProjectionBenchmarkReport.ShapeReport wideCandidateScan = byName.get("WIDE_CANDIDATE_SCAN");
        RankedProjectionBenchmarkReport.ShapeReport rankedTopWindow = byName.get("RANKED_TOP_WINDOW");
        Assertions.assertNotNull(wideCandidateScan);
        Assertions.assertNotNull(rankedTopWindow);
        String timings = "Measured comparison: wide=" + wideCandidateScan + ", ranked=" + rankedTopWindow;
        System.out.println(timings);
        Assertions.assertTrue(
                wideCandidateScan.materializedObjectsPerOperation() >= 10 * rankedTopWindow.materializedObjectsPerOperation(),
                "The wide-scan fixture must contain a materially wider candidate set than the top window. " + timings
        );
        Assertions.assertTrue(
                rankedTopWindow.averageLatencyNanos() < wideCandidateScan.averageLatencyNanos(),
                "Ranked projection top window should stay cheaper than scanning and sorting the wide candidate set. " + timings
        );
        Assertions.assertTrue(
                rankedTopWindow.p95LatencyNanos() <= wideCandidateScan.p95LatencyNanos(),
                "Ranked projection p95 should not regress above the wide candidate scan. " + timings
        );
        Assertions.assertTrue(
                rankedTopWindow.materializedObjectsPerOperation() < wideCandidateScan.materializedObjectsPerOperation(),
                "Ranked projection should materialize fewer objects than the wide candidate scan."
        );
    }
}
