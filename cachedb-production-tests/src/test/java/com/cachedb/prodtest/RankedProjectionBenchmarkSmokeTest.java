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
        RankedProjectionBenchmarkReport report = new RankedProjectionBenchmarkRunner().run(100, 500, 512, 24, 18);

        Assertions.assertEquals(2, report.shapeReports().size());
        Assertions.assertTrue(report.disclaimer().contains("does not benchmark Redis network I/O"));
        Map<String, RankedProjectionBenchmarkReport.ShapeReport> byName = report.shapeReports().stream()
                .collect(Collectors.toMap(RankedProjectionBenchmarkReport.ShapeReport::shapeName, Function.identity()));
        RankedProjectionBenchmarkReport.ShapeReport wideCandidateScan = byName.get("WIDE_CANDIDATE_SCAN");
        RankedProjectionBenchmarkReport.ShapeReport rankedTopWindow = byName.get("RANKED_TOP_WINDOW");
        Assertions.assertNotNull(wideCandidateScan);
        Assertions.assertNotNull(rankedTopWindow);
        Assertions.assertTrue(
                rankedTopWindow.averageLatencyNanos() < wideCandidateScan.averageLatencyNanos(),
                "Ranked projection top window should stay cheaper than scanning and sorting the wide candidate set."
        );
        Assertions.assertTrue(
                rankedTopWindow.p95LatencyNanos() <= wideCandidateScan.p95LatencyNanos(),
                "Ranked projection p95 should not regress above the wide candidate scan."
        );
        Assertions.assertTrue(
                rankedTopWindow.materializedObjectsPerOperation() < wideCandidateScan.materializedObjectsPerOperation(),
                "Ranked projection should materialize fewer objects than the wide candidate scan."
        );
    }
}
