package com.reactor.cachedb.annotations;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(CacheCompositeIndexes.class)
public @interface CacheCompositeIndex {
    String name() default "";
    String[] columns();
}
