package com.reactor.cachedb.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares deterministic client-side or provider-backed id allocation. */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.SOURCE)
public @interface CacheGeneratedId {
    Strategy value();

    String sequence() default "";

    int allocationSize() default 64;

    enum Strategy {
        UUID,
        ULID,
        SEQUENCE
    }
}
