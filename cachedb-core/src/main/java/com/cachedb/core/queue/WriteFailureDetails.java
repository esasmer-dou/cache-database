package com.reactor.cachedb.core.queue;

public record WriteFailureDetails(
        WriteFailureCategory category,
        String sqlState,
        int vendorCode,
        boolean retryable,
        String errorType,
        String message
) {
    public static WriteFailureDetails unknown(Throwable throwable) {
        String type = throwable == null ? "" : throwable.getClass().getName();
        String message = throwable == null || throwable.getMessage() == null ? "" : throwable.getMessage();
        return new WriteFailureDetails(WriteFailureCategory.UNKNOWN, "", 0, false, type, message);
    }

    public static WriteFailureDetails unknown(String errorType, String message) {
        return new WriteFailureDetails(WriteFailureCategory.UNKNOWN, "", 0, false, errorType == null ? "" : errorType, message == null ? "" : message);
    }

    public static WriteFailureDetails fromFields(
            String category,
            String sqlState,
            String vendorCode,
            String retryable,
            String errorType,
            String message
    ) {
        WriteFailureCategory resolvedCategory;
        try {
            resolvedCategory = category == null || category.isBlank()
                    ? WriteFailureCategory.UNKNOWN
                    : WriteFailureCategory.valueOf(category);
        } catch (IllegalArgumentException exception) {
            resolvedCategory = WriteFailureCategory.UNKNOWN;
        }
        int parsedVendorCode = 0;
        if (vendorCode != null && !vendorCode.isBlank()) {
            parsedVendorCode = Integer.parseInt(vendorCode);
        }
        return new WriteFailureDetails(
                resolvedCategory,
                sqlState == null ? "" : sqlState,
                parsedVendorCode,
                Boolean.parseBoolean(retryable),
                errorType == null ? "" : errorType,
                message == null ? "" : message
        );
    }
}
