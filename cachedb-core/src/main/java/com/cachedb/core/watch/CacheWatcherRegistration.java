package com.reactor.cachedb.core.watch;

public interface CacheWatcherRegistration extends AutoCloseable {
    String watcherId();
    boolean isActive();
    @Override void close();
}
