package com.reactor.cachedb.annotations;

import java.lang.annotation.*;

/** Explicit absolute assignment in a CachePatch method, alongside numeric CacheDelta parameters. */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CacheSet { String value() default ""; }
