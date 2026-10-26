package com.reactor.cachedb.annotations;

import java.lang.annotation.*;

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CacheDelta {
    String value() default "";
    long min() default Long.MIN_VALUE;
    long max() default Long.MAX_VALUE;
}
