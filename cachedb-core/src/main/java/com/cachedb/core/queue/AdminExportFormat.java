package com.reactor.cachedb.core.queue;

public enum AdminExportFormat {
    JSON("application/json", "json"),
    CSV("text/csv", "csv"),
    MARKDOWN("text/markdown", "md");

    private final String contentType;
    private final String fileExtension;

    AdminExportFormat(String contentType, String fileExtension) {
        this.contentType = contentType;
        this.fileExtension = fileExtension;
    }

    public String contentType() {
        return contentType;
    }

    public String fileExtension() {
        return fileExtension;
    }
}
