package com.reactor.cachedb.core.queue;

import java.sql.SQLException;
import java.util.List;

public interface WriteBehindFlusher {
    void flush(QueuedWriteOperation operation) throws SQLException;

    default void flushBatch(List<QueuedWriteOperation> operations) throws SQLException {
        for (QueuedWriteOperation operation : operations) {
            flush(operation);
        }
    }
}
