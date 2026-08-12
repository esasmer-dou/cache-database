package com.reactor.cachedb.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares a route-derived warm plan returned as CacheWarmPlan. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface WarmRoute {
    String value();

    /** Repository method whose @CacheRouteQuery definition is reused. */
    String from();

    int maxRows() default 1_000;

    /** Optional int parameter that selects a runtime row count up to maxRows. */
    String maxRowsParameter() default "";

    /** Optional CacheWarmTarget parameter that selects entity or projection-only hydration at call time. */
    String targetParameter() default "";

    String coverageScopeParameter() default "";

    long coverageTtlSeconds() default 86_400L;

    boolean projectionsOnly() default false;
}
