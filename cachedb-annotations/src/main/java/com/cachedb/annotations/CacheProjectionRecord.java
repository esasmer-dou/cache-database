package com.reactor.cachedb.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Generates reflection-free projection schema and optional entity mapping. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface CacheProjectionRecord {

    Class<?> source() default Void.class;

    String id() default "";

    String name() default "";

    String[] rankedBy() default {};

    /**
     * Optional static method on the projection record that maps the configured source entity.
     * Use this for computed fields that cannot be copied one-to-one from the entity.
     */
    String factoryMethod() default "";

    Refresh refresh() default Refresh.SYNC;

    enum Refresh {
        SYNC,
        ASYNC
    }
}
