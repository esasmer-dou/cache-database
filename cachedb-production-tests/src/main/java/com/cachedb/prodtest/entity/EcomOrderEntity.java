package com.reactor.cachedb.prodtest.entity;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CacheNamedQuery;
import com.reactor.cachedb.annotations.CacheProjectionDefinition;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;

@CacheEntity(table = "cachedb_prodtest_orders", redisNamespace = "prodtest_orders")
public class EcomOrderEntity {

    @CacheId(column = "id")
    public Long id;

    @CacheColumn("customer_id")
    public Long customerId;

    @CacheColumn("sku")
    public String sku;

    @CacheColumn("quantity")
    public Integer quantity;

    @CacheColumn("total_amount")
    public Double totalAmount;

    @CacheColumn("status")
    public String status;

    @CacheColumn("campaign_code")
    public String campaignCode;

    @CacheProjectionDefinition("orderSummary")
    public static EntityProjection<EcomOrderEntity, EcomOrderReadModels.OrderSummaryReadModel, Long> orderSummaryProjection() {
        return EcomOrderReadModels.ORDER_SUMMARY_PROJECTION;
    }

    @CacheNamedQuery("topCustomerOrders")
    public static QuerySpec topCustomerOrdersQuery(long customerId, int limit) {
        return QuerySpec.where(QueryFilter.eq("customer_id", customerId))
                .orderBy(QuerySort.desc("total_amount"), QuerySort.desc("quantity"), QuerySort.asc("id"))
                .limitTo(limit);
    }
}
