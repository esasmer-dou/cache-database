package com.reactor.cachedb.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Adds a generated route-level cache contract to a named query. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface CacheRoute {
    String value() default "";
    String projection() default "";
    int pageSize() default 100;
    int hotWindow() default 1_000;
    int maxColdReadSize() default 0;
    long memoryBudgetBytes() default 0L;
    boolean strict() default true;
    /** Query method parameter replaced by hotWindow when a warm plan is generated. */
    String limitParameter() default "limit";
}
