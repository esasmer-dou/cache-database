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
    /** Explicit index contract; the wildcard preserves historical all-column indexes. */
    String[] indexedColumns() default {"*"};
    int compressionThresholdBytes() default 0;
    /** Opt in only after checking foreign keys, unique constraints, triggers and business ordering. */
    /**
     * Opts into regrouping distinct (table,id) writes when every entity in a flush batch opts in.
     * Same-key causal order is always preserved. Safe only when statement order across keys is
     * independent: no foreign keys, triggers or cross-row constraints depend on that order.
     * Do not enable for parent/child inserts across tables, triggers reading sibling rows, or
     * unique values swapped between rows in one flush window. Default false preserves order.
     */
    boolean independentWriteReordering() default false;
}
