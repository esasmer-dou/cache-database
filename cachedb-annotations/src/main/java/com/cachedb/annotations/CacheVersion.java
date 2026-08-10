package com.reactor.cachedb.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Maps the SQL version fence used by optimistic writes and write-behind. */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.SOURCE)
public @interface CacheVersion {
    String column() default "entity_version";
}
