package com.reactor.cachedb.core.active;

import com.reactor.cachedb.core.api.EntityRepository;

import java.util.Optional;

public final class ActiveRecordInstance<T, ID> {

    private final T entity;
    private final EntityRepository<T, ID> repository;
    private final ID id;

    public ActiveRecordInstance(T entity, ID id, EntityRepository<T, ID> repository) {
        this.entity = entity;
        this.id = id;
        this.repository = repository;
    }

    public T entity() {
        return entity;
    }

    public T save() {
        return repository.save(entity);
    }

    public void delete() {
        repository.deleteById(id);
    }

    public Optional<T> reload() {
        return repository.findById(id);
    }
}
