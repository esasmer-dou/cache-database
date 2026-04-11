package com.reactor.cachedb.prodtest.scenario;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

final class BenchmarkReportGenerationTest {

    private static final String REPORT_DIR_PROPERTY = "cachedb.prod.reportDir";

    @Test
    void shouldGenerateBenchmarkReportsIntoRootTargetDirectory() throws Exception {
        String multiModuleDirectory = System.getProperty("maven.multiModuleProjectDirectory", Path.of(".").toAbsolutePath().normalize().toString());
        String configuredReportDirectory = System.getProperty(REPORT_DIR_PROPERTY, "").trim();
        Path reportDirectory = configuredReportDirectory.isEmpty()
                ? Path.of(multiModuleDirectory, "target", "cachedb-prodtest-reports")
                : Path.of(configuredReportDirectory);
        Files.createDirectories(reportDirectory);

        RepositoryRecipeBenchmarkReport repositoryReport = new RepositoryRecipeBenchmarkRunner().run(200, 1_000);
        RepositoryRecipeBenchmarkMain.writeReports(reportDirectory, "repository-recipe-comparison", repositoryReport);

        ReadShapeBenchmarkReport readShapeReport = new ReadShapeBenchmarkRunner().run(200, 1_000, 12, 24, 6);
        ReadShapeBenchmarkMain.writeReports(reportDirectory, "relation-read-shape-comparison", readShapeReport);

        RankedProjectionBenchmarkReport rankedProjectionReport = new RankedProjectionBenchmarkRunner().run(200, 1_000, 512, 24, 18);
        RankedProjectionBenchmarkMain.writeReports(reportDirectory, "ranked-projection-comparison", rankedProjectionReport);

        Assertions.assertTrue(Files.exists(reportDirectory.resolve("repository-recipe-comparison.md")));
        Assertions.assertTrue(Files.exists(reportDirectory.resolve("repository-recipe-comparison.json")));
        Assertions.assertTrue(Files.exists(reportDirectory.resolve("relation-read-shape-comparison.md")));
        Assertions.assertTrue(Files.exists(reportDirectory.resolve("relation-read-shape-comparison.json")));
        Assertions.assertTrue(Files.exists(reportDirectory.resolve("ranked-projection-comparison.md")));
        Assertions.assertTrue(Files.exists(reportDirectory.resolve("ranked-projection-comparison.json")));
    }
}
