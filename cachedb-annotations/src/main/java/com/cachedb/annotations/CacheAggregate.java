package com.reactor.cachedb.annotations;

import com.reactor.cachedb.core.query.AggregateFunction;
import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface CacheAggregate {
    AggregateFunction function() default AggregateFunction.COUNT;
    String field() default "";
}
