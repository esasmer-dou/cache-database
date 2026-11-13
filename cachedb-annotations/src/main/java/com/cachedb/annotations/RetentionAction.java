package com.reactor.cachedb.annotations;

/** Cache eviction is not a SQL delete and must never manufacture a write-behind DELETE. */
public enum RetentionAction { EVICT_CACHE, DELETE }
