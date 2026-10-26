package com.reactor.cachedb.core.watch;

@FunctionalInterface
public interface CacheWatcherListener<T, ID> { void onEvent(CacheChangeEvent<T, ID> event); }
