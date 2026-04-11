package com.reactor.cachedb.starter;

public record MonitoringServiceSnapshot(
        String serviceName,
        String status,
        String keySignal,
        String detail
) {
}
