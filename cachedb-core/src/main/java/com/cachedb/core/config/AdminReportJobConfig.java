package com.reactor.cachedb.core.config;

import com.reactor.cachedb.core.queue.AdminExportFormat;

public record AdminReportJobConfig(
        boolean enabled,
        long intervalMillis,
        String outputDirectory,
        AdminExportFormat format,
        int queryLimit,
        boolean writeDeadLetters,
        boolean writeReconciliation,
        boolean writeArchive,
        boolean writeIncidents,
        boolean writeDiagnostics,
        boolean includeTimestampInFileName,
        int maxRetainedFilesPerReport,
        long fileRetentionMillis,
        boolean persistDiagnostics,
        String diagnosticsStreamKey,
        long diagnosticsMaxLength,
        long diagnosticsTtlSeconds
) {
    public static Builder builder() {
        return new Builder();
    }

    public static AdminReportJobConfig defaults() {
        return builder().build();
    }

    public static final class Builder {
        private boolean enabled;
        private long intervalMillis = 300_000;
        private String outputDirectory = "build/reports/cachedb-admin";
        private AdminExportFormat format = AdminExportFormat.JSON;
        private int queryLimit = 500;
        private boolean writeDeadLetters = true;
        private boolean writeReconciliation = true;
        private boolean writeArchive = true;
        private boolean writeIncidents = true;
        private boolean writeDiagnostics = true;
        private boolean includeTimestampInFileName = true;
        private int maxRetainedFilesPerReport = 10;
        private long fileRetentionMillis = 7L * 24 * 60 * 60 * 1000;
        private boolean persistDiagnostics = true;
        private String diagnosticsStreamKey = "cachedb:stream:admin:diagnostics";
        private long diagnosticsMaxLength = 2_000;
        private long diagnosticsTtlSeconds = 86_400;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder intervalMillis(long intervalMillis) {
            this.intervalMillis = intervalMillis;
            return this;
        }

        public Builder outputDirectory(String outputDirectory) {
            this.outputDirectory = outputDirectory;
            return this;
        }

        public Builder format(AdminExportFormat format) {
            this.format = format;
            return this;
        }

        public Builder queryLimit(int queryLimit) {
            this.queryLimit = queryLimit;
            return this;
        }

        public Builder writeDeadLetters(boolean writeDeadLetters) {
            this.writeDeadLetters = writeDeadLetters;
            return this;
        }

        public Builder writeReconciliation(boolean writeReconciliation) {
            this.writeReconciliation = writeReconciliation;
            return this;
        }

        public Builder writeArchive(boolean writeArchive) {
            this.writeArchive = writeArchive;
            return this;
        }

        public Builder writeIncidents(boolean writeIncidents) {
            this.writeIncidents = writeIncidents;
            return this;
        }

        public Builder writeDiagnostics(boolean writeDiagnostics) {
            this.writeDiagnostics = writeDiagnostics;
            return this;
        }

        public Builder includeTimestampInFileName(boolean includeTimestampInFileName) {
            this.includeTimestampInFileName = includeTimestampInFileName;
            return this;
        }

        public Builder maxRetainedFilesPerReport(int maxRetainedFilesPerReport) {
            this.maxRetainedFilesPerReport = maxRetainedFilesPerReport;
            return this;
        }

        public Builder fileRetentionMillis(long fileRetentionMillis) {
            this.fileRetentionMillis = fileRetentionMillis;
            return this;
        }

        public Builder persistDiagnostics(boolean persistDiagnostics) {
            this.persistDiagnostics = persistDiagnostics;
            return this;
        }

        public Builder diagnosticsStreamKey(String diagnosticsStreamKey) {
            this.diagnosticsStreamKey = diagnosticsStreamKey;
            return this;
        }

        public Builder diagnosticsMaxLength(long diagnosticsMaxLength) {
            this.diagnosticsMaxLength = diagnosticsMaxLength;
            return this;
        }

        public Builder diagnosticsTtlSeconds(long diagnosticsTtlSeconds) {
            this.diagnosticsTtlSeconds = diagnosticsTtlSeconds;
            return this;
        }

        public AdminReportJobConfig build() {
            return new AdminReportJobConfig(
                    enabled,
                    intervalMillis,
                    outputDirectory,
                    format,
                    queryLimit,
                    writeDeadLetters,
                    writeReconciliation,
                    writeArchive,
                    writeIncidents,
                    writeDiagnostics,
                    includeTimestampInFileName,
                    maxRetainedFilesPerReport,
                    fileRetentionMillis,
                    persistDiagnostics,
                    diagnosticsStreamKey,
                    diagnosticsMaxLength,
                    diagnosticsTtlSeconds
            );
        }
    }
}
