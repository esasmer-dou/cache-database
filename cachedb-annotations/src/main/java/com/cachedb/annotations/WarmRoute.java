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

    /** Optional int parameter. A single int not consumed by the source query is inferred when omitted. */
    String maxRowsParameter() default "";

    /** Optional CacheWarmTarget parameter. A single parameter of that type is inferred when omitted. */
    String targetParameter() default "";

    /** Defaults to the source HOT route's scope parameter when that parameter is present. */
    String coverageScopeParameter() default "";

    long coverageTtlSeconds() default 86_400L;

    boolean projectionsOnly() default false;
}
