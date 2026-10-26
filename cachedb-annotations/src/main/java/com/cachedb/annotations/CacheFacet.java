package com.reactor.cachedb.annotations;

import com.reactor.cachedb.core.query.AggregateFunction;
import java.lang.annotation.*;

/** Compiled into direct repository calls; no runtime proxy or annotation scanning. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CacheFacet {
    String groupBy();
    AggregateFunction function() default AggregateFunction.COUNT;
    String field() default "";
}
