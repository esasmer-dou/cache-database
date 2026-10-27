package com.reactor.cachedb.annotations;

import java.lang.annotation.*;

/** One component of an explicitly ordered lexicographical continuation boundary. */
@Target({})
@Retention(RetentionPolicy.SOURCE)
public @interface CacheSeek {
    String field();
    String param();
    CachePredicate.Operator direction() default CachePredicate.Operator.LT;
}
