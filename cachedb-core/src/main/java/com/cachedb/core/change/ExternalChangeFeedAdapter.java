package com.reactor.cachedb.core.change;

public interface ExternalChangeFeedAdapter extends AutoCloseable {
    void start(ExternalChangeSink sink);

    @Override
    void close();
}
