package com.reactor.cachedb.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Generates an explicit bounded SQL-only repository method. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface SourceSql {
    String value();

    String[] parameters() default {};

    int maxRows() default 1_000;

    int queryTimeoutSeconds() default 30;

    Class<?> projection() default Void.class;
}
