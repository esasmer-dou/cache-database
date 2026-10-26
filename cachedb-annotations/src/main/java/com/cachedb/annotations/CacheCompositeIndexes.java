package com.reactor.cachedb.annotations;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CacheCompositeIndexes {
    CacheCompositeIndex[] value();
}
