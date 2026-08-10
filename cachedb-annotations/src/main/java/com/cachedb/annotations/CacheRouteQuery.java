package com.reactor.cachedb.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a query without exposing physical SQL column names to application code.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface CacheRouteQuery {
    CachePredicate[] predicates() default {};

    CacheOrder[] orderBy() default {};

    /** int parameter used as the bounded row limit. */
    String limitParameter() default "";

    int fixedLimit() default 100;

    /** WindowRequest parameter used for keyset pagination. */
    String windowParameter() default "";
}
