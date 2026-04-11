package com.reactor.cachedb.core.queue;

import java.util.List;

public record IncidentDeliverySnapshot(
        long enqueuedCount,
        long dequeuedCount,
        long droppedBeforeDeliveryCount,
        long lastEnqueuedAtEpochMillis,
        List<IncidentChannelSnapshot> channels,
        IncidentDeliveryRecoverySnapshot recovery
) {
}
