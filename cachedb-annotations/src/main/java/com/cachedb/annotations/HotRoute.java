package com.reactor.cachedb.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares an explicitly Redis-only read route. No SQL fallback is generated. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface HotRoute {
    String value();

    Class<?> projection() default Void.class;

    int pageSize() default 100;

    int hotWindow() default 1_000;

    long memoryBudgetBytes() default 0L;

    /** Parameter used to isolate coverage, for example tenantId or customerId. */
    String coverageScopeParameter() default "";

    long maxStalenessSeconds() default 300L;

    boolean strict() default true;
}
