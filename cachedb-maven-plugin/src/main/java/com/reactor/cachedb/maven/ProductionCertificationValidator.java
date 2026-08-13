package com.reactor.cachedb.maven;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

final class ProductionCertificationValidator {

    private static final String COMMIT_PATTERN = "[0-9a-fA-F]{7,40}";
    private static final String STABLE_VERSION_PATTERN = "\\d+\\.\\d+\\.\\d+";

    private static final List<String> REQUIRED_COLUMNS = List.of(
            "RouteName",
            "RouteKind",
            "Owner",
            "QueryShape",
            "CacheDbShape",
            "WarmStatus",
            "WarmEvidence",
            "CompareStatus",
            "CompareEvidence",
            "MemoryStatus",
            "MemoryEvidence",
            "CutoverStatus",
            "RollbackPlan",
            "RollbackEvidence",
            "Blocker"
    );
    private static final Set<String> ROUTE_KINDS = Set.of("screen", "api", "batch", "worker", "report");
    private static final Set<String> CACHE_SHAPES = Set.of(
            "generated",
            "projection",
            "ranked projection",
            "repository",
            "cold path"
    );
    private static final Set<String> WARM_STATUSES = Set.of("passed", "not required");
    private static final Set<String> COMPARE_STATUSES = Set.of("matched", "cold-path-approved");
    private static final Set<String> MEMORY_STATUSES = Set.of("within budget", "not applicable");
    private static final Set<String> CUTOVER_STATUSES = Set.of("ready", "canary", "live");

    Result validate(Path evidenceDirectory, Path coverageFile) throws IOException {
        Path root = evidenceDirectory.toAbsolutePath().normalize();
        Path coverage = coverageFile.toAbsolutePath().normalize();
        ArrayList<String> failures = new ArrayList<>();
        Properties manifest = loadManifest(root, failures);
        validateManifest(root, manifest, failures);
        List<CSVRecord> routes = loadRoutes(coverage, failures);
        validateRoutes(root, manifest, routes, failures);
        validateRouteCount(manifest, routes.size(), failures);
        return new Result(
                value(manifest, "application"),
                value(manifest, "environment"),
                routes.size(),
                List.copyOf(failures)
        );
    }

    private Properties loadManifest(Path root, List<String> failures) throws IOException {
        Path manifestPath = root.resolve("certification.properties");
        Properties properties = new Properties();
        if (!Files.isRegularFile(manifestPath)) {
            failures.add("Missing certification manifest: " + manifestPath);
            return properties;
        }
        try (Reader reader = Files.newBufferedReader(manifestPath)) {
            properties.load(reader);
        }
        return properties;
    }

    private void validateManifest(Path root, Properties manifest, List<String> failures) {
        requireValue(manifest, "application", failures);
        requireValue(manifest, "environment", failures);
        requirePattern(manifest, "application.commit", COMMIT_PATTERN, failures);
        requirePattern(manifest, "framework.version", STABLE_VERSION_PATTERN, failures);
        requireBoolean(manifest, "inventory.complete", failures);
        requirePositiveInteger(manifest, "inventory.routeCount", failures);
        requirePassed(manifest, "redis.failover", failures);
        requirePassed(manifest, "sql.failover", failures);
        requirePassed(manifest, "rollback.drill", failures);
        requirePassed(manifest, "canary.ready", failures);
        requireEvidence(root, manifest, "redis.failoverEvidence", failures);
        requireEvidence(root, manifest, "sql.failoverEvidence", failures);
        requireEvidence(root, manifest, "rollback.drillEvidence", failures);
        requireEvidence(root, manifest, "canary.evidence", failures);
    }

    private List<CSVRecord> loadRoutes(Path coverage, List<String> failures) throws IOException {
        if (!Files.isRegularFile(coverage)) {
            failures.add("Missing route coverage CSV: " + coverage);
            return List.of();
        }
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build();
        try (Reader reader = Files.newBufferedReader(coverage);
             CSVParser parser = format.parse(reader)) {
            Map<String, Integer> headers = parser.getHeaderMap();
            for (String required : REQUIRED_COLUMNS) {
                if (!headers.containsKey(required)) {
                    failures.add("Route coverage CSV is missing required column: " + required);
                }
            }
            if (!failures.isEmpty()) {
                return List.of();
            }
            return parser.getRecords();
        }
    }

    private void validateRoutes(
            Path root,
            Properties manifest,
            List<CSVRecord> routes,
            List<String> failures
    ) {
        if (routes.isEmpty()) {
            failures.add("Route coverage CSV contains no production routes");
            return;
        }
        HashSet<String> routeNames = new HashSet<>();
        for (CSVRecord route : routes) {
            String routeName = required(route, "RouteName", "row " + route.getRecordNumber(), failures);
            String context = routeName.isBlank() ? "row " + route.getRecordNumber() : routeName;
            if (!routeName.isBlank() && !routeNames.add(routeName.toLowerCase(Locale.ROOT))) {
                failures.add(context + ": RouteName must be unique");
            }
            required(route, "Owner", context, failures);
            required(route, "QueryShape", context, failures);
            requireOneOf(route, "RouteKind", ROUTE_KINDS, context, failures);
            requireOneOf(route, "CacheDbShape", CACHE_SHAPES, context, failures);
            requireOneOf(route, "WarmStatus", WARM_STATUSES, context, failures);
            requireOneOf(route, "CompareStatus", COMPARE_STATUSES, context, failures);
            requireOneOf(route, "MemoryStatus", MEMORY_STATUSES, context, failures);
            requireOneOf(route, "CutoverStatus", CUTOVER_STATUSES, context, failures);
            required(route, "RollbackPlan", context, failures);
            requireRouteEvidence(root, manifest, route, "WarmEvidence", context, failures);
            requireRouteEvidence(root, manifest, route, "CompareEvidence", context, failures);
            requireRouteEvidence(root, manifest, route, "MemoryEvidence", context, failures);
            requireRouteEvidence(root, manifest, route, "RollbackEvidence", context, failures);
            String blocker = normalized(route, "Blocker");
            if (!blocker.isBlank() && !"none".equals(blocker)) {
                failures.add(context + ": unresolved blocker: " + route.get("Blocker").trim());
            }
        }
    }

    private void validateRouteCount(Properties manifest, int routeCount, List<String> failures) {
        String expected = value(manifest, "inventory.routeCount");
        if (expected.isBlank()) {
            return;
        }
        try {
            int expectedCount = Integer.parseInt(expected);
            if (expectedCount != routeCount) {
                failures.add("inventory.routeCount=" + expectedCount + " but coverage CSV contains " + routeCount + " routes");
            }
        } catch (NumberFormatException ignored) {
            // The manifest validator already reports the malformed value.
        }
    }

    private void requireValue(Properties properties, String key, List<String> failures) {
        if (value(properties, key).isBlank()) {
            failures.add("Missing manifest value: " + key);
        }
    }

    private void requireBoolean(Properties properties, String key, List<String> failures) {
        if (!"true".equalsIgnoreCase(value(properties, key))) {
            failures.add(key + " must be true");
        }
    }

    private void requirePositiveInteger(Properties properties, String key, List<String> failures) {
        String raw = value(properties, key);
        try {
            if (Integer.parseInt(raw) <= 0) {
                failures.add(key + " must be greater than zero");
            }
        } catch (NumberFormatException exception) {
            failures.add(key + " must be a positive integer");
        }
    }

    private void requirePattern(Properties properties, String key, String pattern, List<String> failures) {
        String raw = value(properties, key);
        if (!raw.matches(pattern)) {
            failures.add(key + " has an invalid value: '" + raw + "'");
        }
    }

    private void requirePassed(Properties properties, String key, List<String> failures) {
        if (!"passed".equalsIgnoreCase(value(properties, key))) {
            failures.add(key + " must be passed");
        }
    }

    private void requireEvidence(Path root, Properties properties, String key, List<String> failures) {
        String relativePath = value(properties, key);
        if (relativePath.isBlank()) {
            failures.add("Missing manifest evidence path: " + key);
            return;
        }
        validateEvidencePath(root, properties, relativePath, key, failures);
    }

    private String required(CSVRecord record, String column, String context, List<String> failures) {
        String value = record.get(column).trim();
        if (value.isBlank()) {
            failures.add(context + ": " + column + " is blank");
        }
        return value;
    }

    private void requireOneOf(
            CSVRecord record,
            String column,
            Set<String> accepted,
            String context,
            List<String> failures
    ) {
        String value = normalized(record, column);
        if (!accepted.contains(value)) {
            failures.add(context + ": " + column + " must be one of " + accepted + ", found '" + record.get(column) + "'");
        }
    }

    private void requireRouteEvidence(
            Path root,
            Properties manifest,
            CSVRecord record,
            String column,
            String context,
            List<String> failures
    ) {
        String relativePath = record.get(column).trim();
        if (relativePath.isBlank()) {
            failures.add(context + ": " + column + " is blank");
            return;
        }
        validateEvidencePath(root, manifest, relativePath, context + ": " + column, failures);
    }

    private void validateEvidencePath(
            Path root,
            Properties manifest,
            String relativePath,
            String context,
            List<String> failures
    ) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            failures.add(context + " must stay inside the certification evidence directory");
            return;
        }
        try {
            if (!Files.isRegularFile(resolved) || Files.size(resolved) == 0L) {
                failures.add(context + " does not reference a non-empty evidence file: " + relativePath);
                return;
            }
            validateEvidenceHeader(resolved, manifest, context, failures);
        } catch (IOException exception) {
            failures.add(context + " could not be inspected: " + exception.getMessage());
        }
    }

    private void validateEvidenceHeader(
            Path evidenceFile,
            Properties manifest,
            String context,
            List<String> failures
    ) throws IOException {
        HashMap<String, String> header = new HashMap<>();
        for (String line : Files.readAllLines(evidenceFile)) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).trim();
            header.putIfAbsent(key, value);
        }
        requireEvidenceHeader(header, "status", context, failures);
        requireEvidenceHeader(header, "commit", context, failures);
        requireEvidenceHeader(header, "environment", context, failures);
        requireEvidenceHeader(header, "owner", context, failures);
        requireEvidenceHeader(header, "generated-at", context, failures);
        requireEvidenceHeader(header, "summary", context, failures);
        if (!"passed".equalsIgnoreCase(header.getOrDefault("status", ""))) {
            failures.add(context + ": evidence status must be passed");
        }
        String commit = header.getOrDefault("commit", "");
        if (!commit.matches(COMMIT_PATTERN)) {
            failures.add(context + ": evidence commit must be a 7-40 character Git SHA");
        } else if (!commit.equalsIgnoreCase(value(manifest, "application.commit"))) {
            failures.add(context + ": evidence commit does not match application.commit");
        }
        if (!header.getOrDefault("environment", "").equalsIgnoreCase(value(manifest, "environment"))) {
            failures.add(context + ": evidence environment does not match the certification manifest");
        }
        String generatedAt = header.getOrDefault("generated-at", "");
        if (!generatedAt.isBlank()) {
            try {
                OffsetDateTime.parse(generatedAt);
            } catch (DateTimeException exception) {
                failures.add(context + ": generated-at must be an ISO-8601 timestamp with an offset");
            }
        }
    }

    private void requireEvidenceHeader(
            Map<String, String> header,
            String key,
            String context,
            List<String> failures
    ) {
        if (header.getOrDefault(key, "").isBlank()) {
            failures.add(context + ": evidence header is missing " + key);
        }
    }

    private String normalized(CSVRecord record, String column) {
        return record.get(column).trim().toLowerCase(Locale.ROOT);
    }

    private String value(Properties properties, String key) {
        return properties.getProperty(key, "").trim();
    }

    record Result(String application, String environment, int routeCount, List<String> failures) {
        boolean passed() {
            return failures.isEmpty();
        }
    }
}
