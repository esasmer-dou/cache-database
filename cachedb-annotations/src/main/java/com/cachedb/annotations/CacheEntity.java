package com.reactor.cachedb.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface CacheEntity {
    String table();
    String redisNamespace() default "";
    Class<?> relationLoader() default Void.class;
    Class<?> pageLoader() default Void.class;
}
