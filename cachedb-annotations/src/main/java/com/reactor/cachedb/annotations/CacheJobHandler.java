package com.reactor.cachedb.annotations;

import java.lang.annotation.*;

/** Generates a direct Spring handler adapter. No runtime method discovery or invocation. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface CacheJobHandler {
    String value();
    Class<?> codec() default Void.class;
}
