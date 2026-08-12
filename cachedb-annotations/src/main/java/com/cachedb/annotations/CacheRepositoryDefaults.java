package com.reactor.cachedb.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Compile-time defaults for route annotations declared by one repository.
 * Explicit method-level annotation values always take precedence.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface CacheRepositoryDefaults {
    HotRoute.Population hotPopulation() default HotRoute.Population.ON_DEMAND;

    int hotPageSize() default 100;

    int hotWindow() default 1_000;

    long hotMemoryBudgetBytes() default 0L;

    long hotMaxStalenessSeconds() default 300L;

    boolean hotStrict() default true;

    int sourceMaxRows() default 500;

    int sourceTimeoutSeconds() default 30;

    int warmMaxRows() default 1_000;
}
