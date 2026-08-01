package com.reactor.cachedb.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Configures package-level generated CacheDB integration surfaces. */
@Target(ElementType.PACKAGE)
@Retention(RetentionPolicy.SOURCE)
public @interface CacheDomain {
    boolean spring() default false;
}
