package com.reactor.cachedb.starter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

final class ProductionReportCatalog {

    private static final List<String> REPORT_PRIORITY = List.of(
            "production-gate-report",
            "production-gate-ladder-report",
            "production-certification-report",
            "final-production-go-no-go-report",
            "fault-injection-suite",
            "crash-replay-chaos-suite",
            "restart-recovery-suite",
            "campaign-push-spike-soak-1h",
            "campaign-push-spike-soak-4h"
    );

    private final List<Path> roots;

    ProductionReportCatalog(List<Path> roots) {
        this.roots = List.copyOf(roots);
    }

    List<ProductionReportSnapshot> latestReports() {
        LinkedHashMap<String, ProductionReportSnapshot> reports = new LinkedHashMap<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(root)) {
                stream.filter(Files::isRegularFile)
                        .map(this::toSnapshot)
                        .flatMap(Optional::stream)
                        .forEach(snapshot -> reports.merge(
                                snapshot.reportKey(),
                                snapshot,
                                (left, right) -> left.lastModifiedAt().isAfter(right.lastModifiedAt()) ? left : right
                        ));
            } catch (IOException ignored) {
                // Best-effort catalog for admin visibility.
            }
        }
        ArrayList<ProductionReportSnapshot> items = new ArrayList<>(reports.values());
        items.sort(Comparator
                .comparingInt((ProductionReportSnapshot snapshot) -> reportOrder(snapshot.reportKey()))
                .thenComparing(ProductionReportSnapshot::lastModifiedAt, Comparator.reverseOrder()));
        return List.copyOf(items);
    }

    private Optional<ProductionReportSnapshot> toSnapshot(Path path) {
        String fileName = path.getFileName().toString();
        String reportKey = detectReportKey(fileName);
        if (reportKey == null) {
            return Optional.empty();
        }
        try {
            FileTime lastModifiedTime = Files.getLastModifiedTime(path);
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return Optional.of(new ProductionReportSnapshot(
                    reportKey,
                    fileName,
                    path.toAbsolutePath().toString(),
                    lastModifiedTime.toInstant(),
                    detectStatus(content),
                    detectHeadline(content, reportKey)
            ));
        } catch (IOException exception) {
            return Optional.of(new ProductionReportSnapshot(
                    reportKey,
                    fileName,
                    path.toAbsolutePath().toString(),
                    Instant.EPOCH,
                    "UNKNOWN",
                    exception.getClass().getSimpleName() + ": " + exception.getMessage()
            ));
        }
    }

    private String detectReportKey(String fileName) {
        String normalized = fileName.toLowerCase(Locale.ROOT);
        if (!(normalized.endsWith(".md") || normalized.endsWith(".json"))) {
            return null;
        }
        for (String key : REPORT_PRIORITY) {
            if (normalized.startsWith(key.toLowerCase(Locale.ROOT))) {
                return key;
            }
        }
        return null;
    }

    private int reportOrder(String reportKey) {
        int index = REPORT_PRIORITY.indexOf(reportKey);
        return index >= 0 ? index : Integer.MAX_VALUE;
    }

    private String detectStatus(String content) {
        String upper = content.toUpperCase(Locale.ROOT);
        if (upper.contains("NO-GO")) {
            return "NO-GO";
        }
        if (upper.contains("FAIL")) {
            return "FAIL";
        }
        if (upper.contains("WARN")) {
            return "WARN";
        }
        if (upper.contains("PASS")) {
            return "PASS";
        }
        if (upper.contains("GO")) {
            return "GO";
        }
        return "UNKNOWN";
    }

    private String detectHeadline(String content, String reportKey) {
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            if (trimmed.startsWith("#")) {
                return trimmed.replaceFirst("^#+\\s*", "");
            }
            if (trimmed.length() <= 160) {
                return trimmed;
            }
            break;
        }
        return reportKey;
    }

    static List<Path> defaultRoots(Path workspaceRoot) {
        return List.of(
                workspaceRoot.resolve("target").resolve("cachedb-prodtest-reports"),
                workspaceRoot.resolve("cachedb-production-tests").resolve("target").resolve("cachedb-prodtest-reports"),
                workspaceRoot.resolve("docs"),
                workspaceRoot.resolve("tr").resolve("docs")
        );
    }
}
