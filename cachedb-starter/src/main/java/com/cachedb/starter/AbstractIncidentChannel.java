package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.queue.IncidentChannelSnapshot;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

abstract class AbstractIncidentChannel implements IncidentChannel {

    private final String channelName;
    private final AtomicLong deliveredCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();
    private final AtomicLong droppedCount = new AtomicLong();
    private final AtomicLong lastDeliveredAtEpochMillis = new AtomicLong();
    private final AtomicLong lastErrorAtEpochMillis = new AtomicLong();
    private final AtomicReference<String> lastErrorType = new AtomicReference<>();
    private final AtomicReference<String> lastErrorMessage = new AtomicReference<>();

    protected AbstractIncidentChannel(String channelName) {
        this.channelName = channelName;
    }

    protected void markDelivered() {
        deliveredCount.incrementAndGet();
        lastDeliveredAtEpochMillis.set(System.currentTimeMillis());
    }

    protected void markFailed(Exception exception) {
        failedCount.incrementAndGet();
        lastErrorAtEpochMillis.set(System.currentTimeMillis());
        lastErrorType.set(exception.getClass().getName());
        lastErrorMessage.set(exception.getMessage());
    }

    protected void markDropped() {
        droppedCount.incrementAndGet();
    }

    @Override
    public IncidentChannelSnapshot snapshot() {
        return new IncidentChannelSnapshot(
                channelName,
                deliveredCount.get(),
                failedCount.get(),
                droppedCount.get(),
                lastDeliveredAtEpochMillis.get(),
                lastErrorAtEpochMillis.get(),
                lastErrorType.get(),
                lastErrorMessage.get()
        );
    }
}
