package com.reactor.cachedb.core.page;

import java.util.Optional;

@FunctionalInterface
public interface EntityByIdLoader<T, ID> {
    Optional<T> load(ID id);
}
