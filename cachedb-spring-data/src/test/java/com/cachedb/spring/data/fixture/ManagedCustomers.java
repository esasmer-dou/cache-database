package com.reactor.cachedb.spring.data.fixture;

import com.reactor.cachedb.annotations.CacheRepository;
import com.reactor.cachedb.spring.data.CacheDbSpringDataRepository;
import org.springframework.data.domain.*;

@CacheRepository(entity = Customer.class, springBeanName = "managedCustomers")
public interface ManagedCustomers extends CacheDbSpringDataRepository<Customer, Long> {
    Page<Customer> findByActive(Boolean active, Pageable pageable);
    long countByActiveTrue();
}
