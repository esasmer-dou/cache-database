package com.reactor.cachedb.prodtest;

import com.reactor.cachedb.prodtest.scenario.ReadShapeBenchmarkReport;
import com.reactor.cachedb.prodtest.scenario.ReadShapeBenchmarkRunner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

final class ReadShapeBenchmarkSmokeTest {

    @Test
    void shouldProduceComparableReadShapes() {
        ReadShapeBenchmarkReport report = new ReadShapeBenchmarkRunner().run(200, 1_000, 12, 24, 6);

        Assertions.assertEquals(4, report.shapeReports().size());
        Assertions.assertTrue(report.disclaimer().contains("does not benchmark Redis network I/O"));
        Assertions.assertTrue(report.maxAverageSpreadPercent() >= 0.0d);
        Assertions.assertTrue(report.shapeReports().stream().allMatch(shape -> shape.averageLatencyNanos() >= 0L));
        Assertions.assertTrue(report.shapeReports().stream().anyMatch(shape -> shape.shapeName().equals("FULL_AGGREGATE_LIST")
                && shape.materializedLineViewsPerOperation() == 12 * 24));
        Assertions.assertTrue(report.shapeReports().stream().anyMatch(shape -> shape.shapeName().equals("SUMMARY_PLUS_SINGLE_PREVIEW")
                && shape.materializedLineViewsPerOperation() == 6));
        Map<String, ReadShapeBenchmarkReport.ShapeReport> byName = report.shapeReports().stream()
                .collect(Collectors.toMap(ReadShapeBenchmarkReport.ShapeReport::shapeName, Function.identity()));
        ReadShapeBenchmarkReport.ShapeReport summary = byName.get("SUMMARY_LIST");
        ReadShapeBenchmarkReport.ShapeReport summaryWithPreview = byName.get("SUMMARY_PLUS_SINGLE_PREVIEW");
        ReadShapeBenchmarkReport.ShapeReport previewList = byName.get("PREVIEW_LIST");
        ReadShapeBenchmarkReport.ShapeReport fullAggregate = byName.get("FULL_AGGREGATE_LIST");
        Assertions.assertNotNull(summary);
        Assertions.assertNotNull(summaryWithPreview);
        Assertions.assertNotNull(previewList);
        Assertions.assertNotNull(fullAggregate);
        Assertions.assertTrue(summary.estimatedObjectCountPerOperation() < summaryWithPreview.estimatedObjectCountPerOperation(),
                "Summary plus preview should materialize more objects than summary-only.");
        Assertions.assertTrue(summaryWithPreview.estimatedObjectCountPerOperation() < previewList.estimatedObjectCountPerOperation(),
                "Previewing every row should materialize more objects than a single explicit preview.");
        Assertions.assertTrue(previewList.estimatedObjectCountPerOperation() < fullAggregate.estimatedObjectCountPerOperation(),
                "Full aggregate reads should materialize the widest object graph.");
        Assertions.assertFalse("FULL_AGGREGATE_LIST".equals(report.fastestAverageShape()),
                "Full aggregate should never become the fastest relation-heavy first-paint recipe.");
        int summaryObjectCount = report.shapeReports().stream()
                .filter(shape -> shape.shapeName().equals("SUMMARY_LIST"))
                .findFirst()
                .orElseThrow()
                .estimatedObjectCountPerOperation();
        int fullAggregateObjectCount = report.shapeReports().stream()
                .filter(shape -> shape.shapeName().equals("FULL_AGGREGATE_LIST"))
                .findFirst()
                .orElseThrow()
                .estimatedObjectCountPerOperation();
        Assertions.assertTrue(fullAggregateObjectCount > summaryObjectCount);
        Assertions.assertTrue(
                fullAggregate.materializedLineViewsPerOperation() > previewList.materializedLineViewsPerOperation(),
                "Full aggregate should still materialize more child rows than the preview-list shape."
        );
    }
}
