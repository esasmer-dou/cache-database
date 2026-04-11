package com.reactor.cachedb.core.queue;

public record AdminAlertRule(
        String code,
        String description,
        AdminIncidentSeverity warningSeverity,
        AdminIncidentSeverity criticalSeverity
) {
}
