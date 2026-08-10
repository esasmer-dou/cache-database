package com.reactor.cachedb.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A compile-time validated stable sort over a Java entity field. */
@Target({})
@Retention(RetentionPolicy.SOURCE)
public @interface CacheOrder {
    String field();

    Direction direction() default Direction.ASC;

    enum Direction {
        ASC,
        DESC
    }
}
