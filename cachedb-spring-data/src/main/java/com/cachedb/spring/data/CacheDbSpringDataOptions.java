package com.reactor.cachedb.spring.data;

import java.lang.annotation.*;

/** Required prefix-index contract; the generated constructor also checks runtime prefix-index enablement. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface CacheDbSpringDataOptions {
    String[] prefixIndexedColumns() default {};
}
