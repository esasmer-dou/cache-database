package com.reactor.cachedb.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Mojo(
        name = "doctor",
        defaultPhase = LifecyclePhase.VALIDATE,
        requiresDependencyResolution = ResolutionScope.COMPILE,
        threadSafe = true
)
public final class CacheDbDoctorMojo extends AbstractMojo {
    private static final String GROUP = "com.reactor.cachedb";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "cachedb.provider")
    private String configuredProvider;

    @Parameter(property = "cachedb.doctor.failOnWarnings", defaultValue = "false")
    private boolean failOnWarnings;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Set<String> artifacts = cacheDbArtifacts();
        ArrayList<String> errors = new ArrayList<>();
        ArrayList<String> warnings = new ArrayList<>();

        validateJava(errors);
        validateProvider(artifacts, errors, warnings);
        validateProcessor(artifacts, errors, warnings);
        if (artifacts.contains("cachedb-spring-boot-starter")
                && !artifacts.contains("cachedb-spring-boot-starter-postgres")
                && !artifacts.contains("cachedb-spring-boot-starter-mssql")) {
            warnings.add("Provider-neutral cachedb-spring-boot-starter is present without a provider starter");
        }

        List<String> report = new ArrayList<>();
        report.add("CacheDB doctor");
        report.add("project=" + project.getGroupId() + ":" + project.getArtifactId());
        report.add("artifacts=" + artifacts.stream().sorted().toList());
        report.addAll(errors.stream().map(message -> "ERROR: " + message).toList());
        report.addAll(warnings.stream().map(message -> "WARN: " + message).toList());
        if (errors.isEmpty() && warnings.isEmpty()) {
            report.add("OK: CacheDB build contract is consistent");
        }
        writeReport(report);
        report.forEach(getLog()::info);

        if (!errors.isEmpty() || (failOnWarnings && !warnings.isEmpty())) {
            throw new MojoFailureException("CacheDB doctor found " + errors.size() + " error(s) and "
                    + warnings.size() + " warning(s). See target/cachedb-doctor.txt");
        }
    }

    private Set<String> cacheDbArtifacts() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (Artifact artifact : project.getArtifacts()) {
            if (GROUP.equals(artifact.getGroupId())) {
                ids.add(artifact.getArtifactId());
            }
        }
        return Set.copyOf(ids);
    }

    private void validateJava(List<String> errors) {
        String release = property("maven.compiler.release", property("java.version", ""));
        if (release.isBlank()) {
            errors.add("Set maven.compiler.release to 17 or newer");
            return;
        }
        try {
            int feature = Integer.parseInt(release.replaceAll("[^0-9].*$", ""));
            if (feature < 17) {
                errors.add("CacheDB requires Java release 17 or newer, found " + release);
            }
        } catch (NumberFormatException invalid) {
            errors.add("Could not parse Java release: " + release);
        }
    }

    private void validateProvider(Set<String> artifacts, List<String> errors, List<String> warnings) {
        boolean postgres = artifacts.contains("cachedb-spring-boot-starter-postgres")
                || artifacts.contains("cachedb-storage-postgres");
        boolean mssql = artifacts.contains("cachedb-spring-boot-starter-mssql")
                || artifacts.contains("cachedb-storage-mssql");
        String configured = configuredProvider == null ? "" : configuredProvider.trim().toLowerCase(Locale.ROOT);
        if (postgres && mssql && configured.isBlank()) {
            errors.add("Both SQL providers are present; set -Dcachedb.provider=postgres or mssql for an explicit build contract");
        }
        if (configured.equals("postgres") && !postgres) {
            errors.add("PostgreSQL is configured but its CacheDB provider starter is missing");
        }
        if (configured.equals("mssql") && !mssql) {
            errors.add("MSSQL is configured but its CacheDB provider starter is missing");
        }
        if (!configured.isBlank() && !configured.equals("postgres") && !configured.equals("mssql")
                && !configured.equals("custom")) {
            warnings.add("Unknown cachedb.provider value: " + configured);
        }
    }

    private void validateProcessor(Set<String> artifacts, List<String> errors, List<String> warnings) {
        if (!artifacts.contains("cachedb-annotations")) {
            errors.add("cachedb-annotations is missing");
        }
        if (artifacts.contains("cachedb-processor")) {
            return;
        }
        Plugin compiler = project.getPlugin("org.apache.maven.plugins:maven-compiler-plugin");
        String configuration = compiler == null || compiler.getConfiguration() == null
                ? ""
                : ((Xpp3Dom) compiler.getConfiguration()).toString();
        if (!configuration.contains("cachedb-processor")) {
            warnings.add("cachedb-processor was not found as a dependency or annotationProcessorPath");
        }
    }

    private String property(String name, String fallback) {
        String value = project.getProperties().getProperty(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private void writeReport(List<String> lines) throws MojoExecutionException {
        Path output = project.getBasedir().toPath().resolve("target").resolve("cachedb-doctor.txt");
        try {
            Files.createDirectories(output.getParent());
            Files.write(output, lines);
        } catch (IOException exception) {
            throw new MojoExecutionException("Could not write CacheDB doctor report", exception);
        }
    }
}
