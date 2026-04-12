package com.reactor.cachedb.examples.demo.entity;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;

import java.time.Instant;

@CacheEntity(table = "cachedb_migration_demo_customers", redisNamespace = "migration-demo-customers")
public class MigrationDemoCustomerEntity {

    @CacheId(column = "customer_id")
    public Long customerId;

    @CacheColumn("tax_number")
    public String taxNumber;

    @CacheColumn("customer_type")
    public String customerType;

    @CacheColumn("customer_status")
    public String customerStatus;

    @CacheColumn("country_code")
    public String countryCode;

    @CacheColumn("created_at")
    public Instant createdAt;

    public MigrationDemoCustomerEntity() {
    }
}
