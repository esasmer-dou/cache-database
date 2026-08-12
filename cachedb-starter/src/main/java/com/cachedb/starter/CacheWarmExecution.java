package com.reactor.cachedb.starter;

import java.util.Objects;

/** A warm result coupled to the exact declarative plan and execution mode. */
public record CacheWarmExecution(
        CacheWarmPlan plan,
        CacheWarmExecutionMode mode,
        CacheWarmResult result
) {
    public CacheWarmExecution {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(result, "result");
        if (!plan.name().equals(result.planName()) || !plan.entityName().equals(result.entityName())) {
            throw new IllegalArgumentException("Warm result does not belong to the supplied plan");
        }
    }

    public boolean dryRun() {
        return mode == CacheWarmExecutionMode.DRY_RUN;
    }

    public boolean projectionsOnly() {
        return plan.projectionsOnly();
    }

    public String routeName() {
        return plan.coverageRouteName().isBlank() ? plan.name() : plan.coverageRouteName();
    }

    public String scope() {
        return plan.coverageScope();
    }

    public CacheWarmSummary summary() {
        return summary(routeName());
    }

    public CacheWarmSummary summary(String operation) {
        return new CacheWarmSummary(
                operation,
                plan.name(),
                routeName(),
                plan.entityName(),
                scope(),
                plan.maxRows(),
                result.loadedRows(),
                result.submittedRows(),
                result.durationMillis(),
                plan.target(),
                mode,
                result.notes()
        );
    }
}
