package com.reactor.cachedb.annotations;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface CacheSchemaVersion {
    int version() default 1;
    /** Must implement CachePayloadUpcaster and have a public no-argument constructor. */
    Class<? extends com.reactor.cachedb.core.codec.CachePayloadUpcaster> upcaster()
            default com.reactor.cachedb.core.codec.NoOpPayloadUpcaster.class;
}
