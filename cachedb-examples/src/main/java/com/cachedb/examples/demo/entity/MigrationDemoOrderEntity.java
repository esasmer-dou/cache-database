package com.reactor.cachedb.examples.demo.entity;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CacheProjectionDefinition;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.examples.demo.DemoMigrationReadModelPatterns;

import java.time.Instant;

@CacheEntity(table = "cachedb_migration_demo_orders", redisNamespace = "migration-demo-orders")
public class MigrationDemoOrderEntity {

    @CacheId(column = "order_id")
    public Long orderId;

    @CacheColumn("customer_id")
    public Long customerId;

    @CacheColumn("order_date")
    public Instant orderDate;

    @CacheColumn("order_amount")
    public Double orderAmount;

    @CacheColumn("currency_code")
    public String currencyCode;

    @CacheColumn("order_type")
    public String orderType;

    @CacheColumn("rank_score")
    public Double rankScore;

    @CacheColumn("created_at")
    public Instant createdAt;

    public MigrationDemoOrderEntity() {
    }

    @CacheProjectionDefinition(DemoMigrationReadModelPatterns.SUMMARY_PROJECTION_NAME)
    public static EntityProjection<MigrationDemoOrderEntity, DemoMigrationReadModelPatterns.MigrationOrderSummaryView, Long> migrationSummaryProjection() {
        return DemoMigrationReadModelPatterns.SUMMARY_PROJECTION;
    }

    @CacheProjectionDefinition(DemoMigrationReadModelPatterns.RANKED_PROJECTION_NAME)
    public static EntityProjection<MigrationDemoOrderEntity, DemoMigrationReadModelPatterns.MigrationOrderRankedView, Long> migrationRankedProjection() {
        return DemoMigrationReadModelPatterns.RANKED_PROJECTION;
    }
}
