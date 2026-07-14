package com.reactor.cachedb.core.queue;

import java.sql.SQLException;

public final class StaleWriteRejectedException extends SQLException {

    public StaleWriteRejectedException(QueuedWriteOperation operation, Long currentVersion) {
        super(
                "Version-guarded write was rejected for entity=" + operation.entityName()
                        + ", id=" + operation.id()
                        + ", incomingVersion=" + operation.version()
                        + ", currentVersion=" + (currentVersion == null ? "missing" : currentVersion),
                "CDB01"
        );
    }
}
