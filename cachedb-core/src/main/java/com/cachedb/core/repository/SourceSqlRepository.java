package com.reactor.cachedb.core.repository;

import java.util.List;

@FunctionalInterface
public interface SourceSqlRepository<T> {
    List<T> query(SourceSqlQuery query);
}
