package com.reactor.cachedb.annotations;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface CacheWatcher {
    Class<?> entity();
    String filterField();
    String filterEquals();
}
