package com.reactor.cachedb.core.change;

public interface ExternalChangeSink {
    void accept(ExternalChangeEvent event);
}
