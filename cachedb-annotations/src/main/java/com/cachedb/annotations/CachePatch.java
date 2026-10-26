package com.reactor.cachedb.annotations;

import java.lang.annotation.*;

/** A version-ordered SQL UPDATE command, never an implicit partial UPSERT. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface CachePatch {}
