package com.reactor.cachedb.annotations;

import java.lang.annotation.*;

/** Generates explicit JSON token code and direct record construction; Jackson is not consulted. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface CacheJsonRecord {
    boolean ignoreUnknown() default false;
}
