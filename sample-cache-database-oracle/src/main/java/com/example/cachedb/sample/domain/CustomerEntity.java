package com.example.cachedb.sample.domain;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CacheRelation;

import java.util.List;

@CacheEntity(
        table = "sample_customers",
        redisNamespace = "sample-customers"
)
public class CustomerEntity {

    @CacheId(column = "customer_id")
    public Long customerId;

    @CacheColumn("tax_number")
    public String taxNumber;

    @CacheColumn("customer_type")
    public String customerType;

    @CacheColumn("segment")
    public String segment;

    @CacheColumn("status")
    public String status;

    @CacheColumn("created_at")
    public Long createdAt;

    @CacheColumn("updated_at")
    public Long updatedAt;

    @CacheRelation(
            target = OrderEntity.class,
            mappedBy = "customerId",
            kind = CacheRelation.RelationKind.ONE_TO_MANY,
            batchLoadOnly = true,
            maxRowsPerParent = 25,
            parentBatchSize = 16,
            orderBy = {"orderDate DESC", "orderId DESC"}
    )
    public List<OrderEntity> orders;

    public CustomerEntity() {
    }
}
