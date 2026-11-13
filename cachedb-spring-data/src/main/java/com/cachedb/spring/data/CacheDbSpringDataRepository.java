package com.reactor.cachedb.spring.data;

import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.ListPagingAndSortingRepository;
import org.springframework.data.repository.NoRepositoryBean;

/** Implemented by CacheRepositoryProcessor, never by a Spring Data runtime proxy. */
@NoRepositoryBean
public interface CacheDbSpringDataRepository<T, ID>
        extends ListCrudRepository<T, ID>, ListPagingAndSortingRepository<T, ID> {}
