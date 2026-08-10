package com.reactor.cachedb.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares a Redis-only point lookup with an optional bounded relation preview. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface CacheLookup {
    String idParameter() default "id";

    String relation() default "";

    /** Optional int parameter. When omitted, relationLimit is used. */
    String relationLimitParameter() default "";

    int relationLimit() default 25;

    int maxRelationRows() default 100;
}
