package com.reactor.cachedb.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a user-owned repository interface for compile-time implementation.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface CacheRepository {
    Class<?> entity();

    /** Generate a Spring bean configuration next to the repository. */
    boolean springBean() default true;

    /**
     * Optional explicit Spring bean name. Use this when repositories in
     * different packages share the same simple interface name.
     */
    String springBeanName() default "";
}
