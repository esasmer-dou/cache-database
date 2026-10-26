package com.reactor.cachedb.annotations;

import java.lang.annotation.*;

/** Bounded synchronous unit of work. Acceptance receipts exist only after commit. */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface CacheTransactional {
    boolean awaitDurable() default false;
    long timeoutMillis() default 5000;
}
