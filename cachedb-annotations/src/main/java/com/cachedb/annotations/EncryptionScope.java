package com.reactor.cachedb.annotations;

/** Storage boundaries selected at compile time; REDIS_ONLY does not encrypt SQL. */
public enum EncryptionScope {
    REDIS_ONLY,
    REDIS_AND_SOURCE
}
