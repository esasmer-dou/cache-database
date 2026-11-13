package com.reactor.cachedb.annotations;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(CacheCompositeIndexes.class)
public @interface CacheCompositeIndex {
    String name() default "";
    /** Equality prefix: 2..4 columns, or 1..4 when a distinct sortBy column completes the index. */
    String[] columns();
    /** Optional persisted score column; empty preserves the legacy EQ-only layout. */
    String sortBy() default "";
}
