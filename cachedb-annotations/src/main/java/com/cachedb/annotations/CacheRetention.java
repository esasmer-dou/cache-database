package com.reactor.cachedb.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Processor must validate a temporal sort index; no runtime annotation discovery. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface CacheRetention {
    String field();
    int days();
    RetentionAction action() default RetentionAction.EVICT_CACHE;
}
