package com.reactor.cachedb.core.change;

public final class ExternalChangeApplyException extends RuntimeException {
    private final ExternalChangeApplyResult result;

    public ExternalChangeApplyException(ExternalChangeApplyResult result) {
        super(result == null ? "External change apply failed" : result.detail());
        this.result = result;
    }

    public ExternalChangeApplyException(ExternalChangeApplyResult result, Throwable cause) {
        super(result == null ? "External change apply failed" : result.detail(), cause);
        this.result = result;
    }

    public ExternalChangeApplyResult result() {
        return result;
    }
}
