package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.AdminReportJobConfig;
import com.reactor.cachedb.core.queue.AdminExportFormat;
import com.reactor.cachedb.core.queue.AdminReportJobSnapshot;
import com.reactor.cachedb.core.queue.DeadLetterQuery;
import com.reactor.cachedb.core.queue.ReconciliationQuery;
import com.reactor.cachedb.core.queue.WorkerErrorCapture;
import com.reactor.cachedb.core.queue.WorkerErrorDetails;
import com.reactor.cachedb.redis.RedisLeaderLease;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class AdminReportJobWorker implements AutoCloseable {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private final Supplier<CacheDatabaseAdmin> adminSupplier;
    private final AdminReportJobConfig config;
    private final RedisLeaderLease leaderLease;
    private final String threadName;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong runCount = new AtomicLong();
    private final AtomicLong writtenFileCount = new AtomicLong();
    private final AtomicLong lastRunAtEpochMillis = new AtomicLong();
    private final AtomicLong lastErrorAtEpochMillis = new AtomicLong();
    private final AtomicReference<String> lastOutputDirectory = new AtomicReference<>();
    private final AtomicReference<String> lastErrorType = new AtomicReference<>();
    private final AtomicReference<String> lastErrorMessage = new AtomicReference<>();
    private final AtomicReference<String> lastErrorRootType = new AtomicReference<>();
    private final AtomicReference<String> lastErrorRootMessage = new AtomicReference<>();
    private final AtomicReference<String> lastErrorOrigin = new AtomicReference<>();
    private final AtomicReference<String> lastErrorStackTrace = new AtomicReference<>();
    private Thread workerThread;

    public AdminReportJobWorker(Supplier<CacheDatabaseAdmin> adminSupplier, AdminReportJobConfig config) {
        this(adminSupplier, config, RedisLeaderLease.disabled(), "cachedb-admin-report-job", true);
    }

    public AdminReportJobWorker(
            Supplier<CacheDatabaseAdmin> adminSupplier,
            AdminReportJobConfig config,
            RedisLeaderLease leaderLease,
            String threadName
    ) {
        this(adminSupplier, config, leaderLease, threadName, true);
    }

    private AdminReportJobWorker(
            Supplier<CacheDatabaseAdmin> adminSupplier,
            AdminReportJobConfig config,
            RedisLeaderLease leaderLease,
            String threadName,
            boolean enabled
    ) {
        this.adminSupplier = adminSupplier;
        this.config = config;
        this.leaderLease = leaderLease == null ? RedisLeaderLease.disabled() : leaderLease;
        this.threadName = threadName == null || threadName.isBlank() ? "cachedb-admin-report-job" : threadName;
        this.enabled = enabled;
    }

    static AdminReportJobWorker disabled(AdminReportJobConfig config) {
        return new AdminReportJobWorker(() -> null, config, RedisLeaderLease.disabled(), "cachedb-admin-report-job", false);
    }

    public void start() {
        if (!enabled || !config.enabled() || !running.compareAndSet(false, true)) {
            return;
        }
        workerThread = new Thread(this::runLoop, threadName);
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private void runLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                if (!leaderLease.tryAcquireOrRenewLeadership()) {
                    Thread.sleep(config.intervalMillis());
                    continue;
                }
                runOnce();
                Thread.sleep(config.intervalMillis());
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException | IOException exception) {
                captureError(exception);
                sleepQuietly(config.intervalMillis());
            }
        }
    }

    public void runOnce() throws IOException {
        CacheDatabaseAdmin admin = adminSupplier.get();
        Path outputDirectory = Path.of(config.outputDirectory()).toAbsolutePath().normalize();
        Files.createDirectories(outputDirectory);
        lastOutputDirectory.set(outputDirectory.toString());

        int filesWrittenThisRun = 0;
        if (config.writeDeadLetters()) {
            admin.writeDeadLetterReport(
                    resolveReportPath(outputDirectory, "dead-letters", config.format()),
                    DeadLetterQuery.builder().limit(config.queryLimit()).build(),
                    config.format()
            );
            filesWrittenThisRun++;
        }
        if (config.writeReconciliation()) {
            admin.writeReconciliationReport(
                    resolveReportPath(outputDirectory, "reconciliation", config.format()),
                    ReconciliationQuery.builder().limit(config.queryLimit()).build(),
                    config.format()
            );
            filesWrittenThisRun++;
        }
        if (config.writeArchive()) {
            admin.writeArchiveReport(
                    resolveReportPath(outputDirectory, "archive", config.format()),
                    ReconciliationQuery.builder().limit(config.queryLimit()).build(),
                    config.format()
            );
            filesWrittenThisRun++;
        }
        if (config.writeIncidents()) {
            admin.writeIncidentReport(
                    resolveReportPath(outputDirectory, "incidents", config.format()),
                    config.format(),
                    config.queryLimit()
            );
            filesWrittenThisRun++;
        }
        if (config.writeDiagnostics()) {
            admin.writeDiagnosticsReport(
                    resolveReportPath(outputDirectory, "diagnostics", config.format()),
                    config.format(),
                    config.queryLimit()
            );
            filesWrittenThisRun++;
        }
        if (config.persistDiagnostics()) {
            admin.persistDiagnostics("admin-report-job", "scheduled report snapshot");
            admin.persistIncidents("admin-report-job");
        }

        rotateReports(outputDirectory, "dead-letters", config.format());
        rotateReports(outputDirectory, "reconciliation", config.format());
        rotateReports(outputDirectory, "archive", config.format());
        rotateReports(outputDirectory, "incidents", config.format());
        rotateReports(outputDirectory, "diagnostics", config.format());

        writtenFileCount.addAndGet(filesWrittenThisRun);
        runCount.incrementAndGet();
        lastRunAtEpochMillis.set(System.currentTimeMillis());
    }

    private Path resolveReportPath(Path outputDirectory, String baseName, AdminExportFormat format) {
        String timestamp = config.includeTimestampInFileName() ? "-" + TIMESTAMP_FORMAT.format(Instant.now()) : "";
        return outputDirectory.resolve(baseName + timestamp + "." + format.fileExtension());
    }

    private void rotateReports(Path outputDirectory, String baseName, AdminExportFormat format) throws IOException {
        String suffix = "." + format.fileExtension();
        List<Path> matchingFiles;
        try (var stream = Files.list(outputDirectory)) {
            matchingFiles = stream
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return fileName.startsWith(baseName) && fileName.endsWith(suffix);
                    })
                    .sorted(Comparator.comparingLong(this::safeLastModifiedTime).reversed())
                    .toList();
        }

        long now = System.currentTimeMillis();
        for (int index = 0; index < matchingFiles.size(); index++) {
            Path file = matchingFiles.get(index);
            boolean exceedsMaxFiles = config.maxRetainedFilesPerReport() > 0 && index >= config.maxRetainedFilesPerReport();
            boolean exceedsRetention = config.fileRetentionMillis() > 0
                    && now - safeLastModifiedTime(file) > config.fileRetentionMillis();
            if (exceedsMaxFiles || exceedsRetention) {
                Files.deleteIfExists(file);
            }
        }
    }

    private long safeLastModifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return 0L;
        }
    }

    private void captureError(Exception exception) {
        WorkerErrorDetails details = WorkerErrorCapture.capture(exception);
        lastErrorAtEpochMillis.set(System.currentTimeMillis());
        lastErrorType.set(details.errorType());
        lastErrorMessage.set(details.errorMessage());
        lastErrorRootType.set(details.rootErrorType());
        lastErrorRootMessage.set(details.rootErrorMessage());
        lastErrorOrigin.set(details.origin());
        lastErrorStackTrace.set(details.stackTrace());
    }

    private void sleepQuietly(long sleepMillis) {
        if (sleepMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    public AdminReportJobSnapshot snapshot() {
        return new AdminReportJobSnapshot(
                runCount.get(),
                writtenFileCount.get(),
                lastRunAtEpochMillis.get(),
                lastOutputDirectory.get(),
                lastErrorAtEpochMillis.get(),
                lastErrorType.get(),
                lastErrorMessage.get(),
                lastErrorRootType.get(),
                lastErrorRootMessage.get(),
                lastErrorOrigin.get(),
                lastErrorStackTrace.get()
        );
    }

    @Override
    public void close() {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                workerThread.join(5_000);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        leaderLease.close();
    }
}
