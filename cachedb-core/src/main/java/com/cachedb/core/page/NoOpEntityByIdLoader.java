package com.reactor.cachedb.core.page;

import java.util.Optional;

public final class NoOpEntityByIdLoader<T, ID> implements EntityByIdLoader<T, ID> {
    @Override
    public Optional<T> load(ID id) {
        return Optional.empty();
    }
}
