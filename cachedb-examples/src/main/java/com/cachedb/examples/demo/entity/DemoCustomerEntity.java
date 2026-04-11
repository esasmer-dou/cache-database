package com.reactor.cachedb.examples.demo.entity;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;

@CacheEntity(table = "cachedb_demo_customers", redisNamespace = "demo-customers")
public class DemoCustomerEntity {

    @CacheId(column = "id")
    public Long id;

    @CacheColumn("username")
    public String username;

    @CacheColumn("tier")
    public String tier;

    @CacheColumn("status")
    public String status;

    public DemoCustomerEntity() {
    }
}
