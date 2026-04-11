package com.reactor.cachedb.core.queue;

import com.reactor.cachedb.core.model.WriteOperation;

public interface WriteBehindQueue {
    <T, ID> void enqueue(WriteOperation<T, ID> operation);
}
