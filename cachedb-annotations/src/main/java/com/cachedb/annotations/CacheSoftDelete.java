package com.reactor.cachedb.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Maps an application field to the durable soft-delete marker. */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.SOURCE)
public @interface CacheSoftDelete {
    String column();

    String activeValue() default "false";

    String deletedValue() default "true";
}
