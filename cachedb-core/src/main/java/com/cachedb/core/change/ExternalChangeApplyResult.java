package com.reactor.cachedb.core.change;

public record ExternalChangeApplyResult(
        ExternalChangeApplyStatus status,
        String entityName,
        Object id,
        ExternalChangeType type,
        ExternalChangeApplyMode mode,
        String detail,
        String errorType,
        String errorMessage
) {
    public ExternalChangeApplyResult {
        status = status == null ? ExternalChangeApplyStatus.FAILED : status;
        entityName = entityName == null ? "" : entityName.trim();
        type = type == null ? ExternalChangeType.UPSERT : type;
        mode = mode == null ? ExternalChangeApplyMode.CACHE_ONLY : mode;
        detail = detail == null ? "" : detail.trim();
        errorType = errorType == null ? "" : errorType.trim();
        errorMessage = errorMessage == null ? "" : errorMessage.trim();
    }

    public static ExternalChangeApplyResult applied(
            ExternalChangeEvent event,
            Object id,
            ExternalChangeApplyMode mode,
            String detail
    ) {
        return new ExternalChangeApplyResult(
                ExternalChangeApplyStatus.APPLIED,
                event.entityName(),
                id,
                event.type(),
                mode,
                detail,
                "",
                ""
        );
    }

    public static ExternalChangeApplyResult ignored(
            ExternalChangeEvent event,
            Object id,
            ExternalChangeApplyMode mode,
            String detail
    ) {
        return new ExternalChangeApplyResult(
                ExternalChangeApplyStatus.IGNORED,
                event.entityName(),
                id,
                event.type(),
                mode,
                detail,
                "",
                ""
        );
    }

    public static ExternalChangeApplyResult failed(
            ExternalChangeEvent event,
            Object id,
            ExternalChangeApplyMode mode,
            String detail,
            Throwable error
    ) {
        return new ExternalChangeApplyResult(
                ExternalChangeApplyStatus.FAILED,
                event == null ? "" : event.entityName(),
                id,
                event == null ? ExternalChangeType.UPSERT : event.type(),
                mode,
                detail,
                error == null ? "" : error.getClass().getName(),
                error == null ? "" : error.getMessage()
        );
    }

    public boolean applied() {
        return status == ExternalChangeApplyStatus.APPLIED;
    }

    public boolean failed() {
        return status == ExternalChangeApplyStatus.FAILED;
    }
}
