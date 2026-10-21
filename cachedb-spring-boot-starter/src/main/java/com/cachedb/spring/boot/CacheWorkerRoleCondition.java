package com.reactor.cachedb.spring.boot;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Locale;

/** Avoid creating scheduled worker beans on lightweight API deployments. */
final class CacheWorkerRoleCondition implements Condition {
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !"CLIENT_ONLY"
                .equals(
                        context.getEnvironment()
                                .getProperty("cachedb.runtime-role", "FULL")
                                .toUpperCase(Locale.ROOT)
                                .replace('-', '_'));
    }
}
