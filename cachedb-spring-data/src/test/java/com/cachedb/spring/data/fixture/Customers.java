package com.reactor.cachedb.spring.data.fixture;

import com.reactor.cachedb.annotations.CacheRepository;
import com.reactor.cachedb.spring.data.*;
import java.util.*;
import org.springframework.data.domain.*;

@CacheRepository(entity = Customer.class, springBeanName = "customers")
@CacheDbSpringDataOptions(prefixIndexedColumns = {"name"})
public interface Customers extends CacheDbSpringDataRepository<Customer, Long> {
    List<Customer> findByName(String name);
    List<Customer> queryByName(String name);
    Optional<Customer> getByName(String name);
    Customer readByName(String name);
    Optional<Customer> readById(Long id);
    Customer getFirstByNameOrderByAgeDesc(String name);
    List<Customer> getByNameEquals(String name);
    List<Customer> getByNameIs(String name);
    List<Customer> getByNameIsEquals(String name);
    List<Customer> findByNameIsNot(String name);
    List<Customer> findByAgeNot(Integer age);
    List<Customer> findByAgeIn(Iterable<Integer> ages);
    List<Customer> findByAgeNotIn(List<Integer> ages);
    List<Customer> findByAgeGreaterThan(Integer age);
    List<Customer> findByAgeGreaterThanEqual(Integer age);
    List<Customer> findByAgeLessThan(Integer age);
    List<Customer> findByAgeLessThanEqual(Integer age);
    List<Customer> findByAgeBetween(Integer lower, Integer upper);
    List<Customer> findByNoteIsNull();
    List<Customer> findByNoteIsNotNull();
    List<Customer> findByActiveTrue();
    List<Customer> findByActiveFalse();
    List<Customer> findByNameStartingWith(String prefix);
    List<Customer> findByAgeGreaterThanEqualAndAgeLessThanEqualOrderByNameAscAgeDesc(Integer lower, Integer upper);
    List<Customer> findTop2ByActiveOrderByAgeDesc(Boolean active);
    Customer findFirstByActiveOrderByAgeDesc(Boolean active);
    List<Customer> getFirst2ByActiveOrderByAgeDesc(Boolean active);
    List<Customer> findByActive(Boolean active, Sort sort);
    List<Customer> getByActiveOrderByAgeAsc(Boolean active, Sort sort);
    List<Customer> findByActive(Boolean active, Limit limit);
    List<Customer> findByAge(Integer age, Pageable pageable);
    Page<Customer> findByActive(Boolean active, Pageable pageable);
    Slice<Customer> readByActive(Boolean active, Pageable pageable);
    Window<Customer> findFirst2ByActiveOrderByAgeAsc(Boolean active, KeysetScrollPosition position);
    Window<Customer> findFirst2ByIdGreaterThanOrderByAgeAsc(Long id, KeysetScrollPosition position);
    Window<Customer> readByActiveOrderByAgeDesc(Boolean active, ScrollPosition position, Limit limit);
    long countByActiveTrue();
    boolean existsByName(String name);
    long deleteByName(String name);
    List<Customer> deleteByAgeLessThan(Integer age);
    void deleteByActiveFalse();
}
