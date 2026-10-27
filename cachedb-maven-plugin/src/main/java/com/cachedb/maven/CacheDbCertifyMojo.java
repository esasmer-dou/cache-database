package com.reactor.cachedb.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Mojo(name = "certify", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public final class CacheDbCertifyMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(
            property = "cachedb.certification.directory",
            defaultValue = "${project.basedir}/cachedb-certification",
            required = true
    )
    private File evidenceDirectory;

    @Parameter(
            property = "cachedb.certification.coverage",
            defaultValue = "${project.basedir}/cachedb-certification/route-coverage.csv",
            required = true
    )
    private File coverageFile;

    @Parameter(
            property = "cachedb.certification.report",
            defaultValue = "${project.build.directory}/cachedb-production-certification.md",
            required = true
    )
    private File reportFile;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        ProductionCertificationValidator.Result result;
        try {
            result = new ProductionCertificationValidator().validate(
                    evidenceDirectory.toPath(),
                    coverageFile.toPath()
            );
            writeReport(result);
        } catch (IOException exception) {
            throw new MojoExecutionException("Could not read CacheDB production certification evidence", exception);
        }

        if (!result.passed()) {
            throw new MojoFailureException("CacheDB production certification failed with "
                    + result.failures().size() + " issue(s). See " + reportFile.toPath());
        }
        getLog().info("CacheDB production certification passed for " + result.application()
                + " in " + result.environment() + " with " + result.routeCount() + " route(s)");
    }

    private void writeReport(ProductionCertificationValidator.Result result) throws IOException {
        ArrayList<String> report = new ArrayList<>();
        report.add("# CacheDB Production Certification");
        report.add("");
        report.add("- Project: `" + project.getGroupId() + ":" + project.getArtifactId() + "`");
        report.add("- Application: `" + safe(result.application()) + "`");
        report.add("- Environment: `" + safe(result.environment()) + "`");
        report.add("- Routes: `" + result.routeCount() + "`");
        report.add("- Result: `" + (result.passed() ? "PASS" : "FAIL") + "`");
        if (!result.failures().isEmpty()) {
            report.add("");
            report.add("## Blocking Issues");
            report.add("");
            report.addAll(result.failures().stream().map(failure -> "- " + failure).toList());
        }
        Path reportPath = reportFile.toPath().toAbsolutePath().normalize();
        Files.createDirectories(reportPath.getParent());
        Files.write(reportPath, List.copyOf(report));
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value.replace("`", "'");
    }
}
