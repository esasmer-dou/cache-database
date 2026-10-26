package com.reactor.cachedb.annotations;

import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.SOURCE)
public @interface CacheTenantId {}
