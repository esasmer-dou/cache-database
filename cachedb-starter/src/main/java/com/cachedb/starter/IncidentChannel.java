package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.queue.AdminIncidentRecord;
import com.reactor.cachedb.core.queue.IncidentChannelSnapshot;

interface IncidentChannel extends AutoCloseable {
    boolean deliver(AdminIncidentRecord record);
    IncidentChannelSnapshot snapshot();
    @Override
    default void close() {
    }
}
