package com.reactor.cachedb.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares an explicit, bounded durable SQL read route. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface SourceRoute {
    String value();

    Class<?> projection() default Void.class;

    int maxRows() default 500;

    int timeoutSeconds() default 30;
}
