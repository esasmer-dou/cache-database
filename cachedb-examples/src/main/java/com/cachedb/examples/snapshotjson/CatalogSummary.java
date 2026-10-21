package com.reactor.cachedb.examples.snapshotjson;

import com.reactor.cachedb.annotations.CacheJsonRecord;

/** The processor generates CatalogSummaryJsonCodec; no manual mapper or runtime binding. */
@CacheJsonRecord
public record CatalogSummary(String title) {}
