package com.reactor.cachedb.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a bounded per-parent sorted index such as customer_id + order_date.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
@Repeatable(CachePartitionedIndexes.class)
public @interface CachePartitionedIndex {
    String partitionBy();
    String[] sortBy();
}
