package com.reactor.cachedb.prodtest.entity;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheDeleteCommand;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CacheNamedQuery;
import com.reactor.cachedb.annotations.CachePagePreset;
import com.reactor.cachedb.annotations.CacheSaveCommand;
import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;

@CacheEntity(table = "cachedb_prodtest_customers", redisNamespace = "prodtest_customers")
public class EcomCustomerEntity {

    @CacheId(column = "id")
    public Long id;

    @CacheColumn("status")
    public String status;

    @CacheColumn("segment")
    public String segment;

    @CacheColumn("tier")
    public String tier;

    @CacheColumn("last_campaign_code")
    public String lastCampaignCode;

    @CacheNamedQuery("activeCustomers")
    public static QuerySpec activeCustomersQuery(int limit) {
        return QuerySpec.where(QueryFilter.eq("status", "ACTIVE"))
                .orderBy(QuerySort.asc("tier"), QuerySort.asc("segment"), QuerySort.asc("id"))
                .limitTo(limit);
    }

    @CachePagePreset("customersPage")
    public static PageWindow customersPageWindow(int pageNumber, int pageSize) {
        return new PageWindow(pageNumber, pageSize);
    }

    @CacheSaveCommand("promoteVipCustomer")
    public static EcomCustomerEntity promoteVipCustomerCommand(long id, String segment, String campaignCode) {
        EcomCustomerEntity entity = new EcomCustomerEntity();
        entity.id = id;
        entity.status = "ACTIVE";
        entity.segment = segment;
        entity.tier = "VIP";
        entity.lastCampaignCode = campaignCode;
        return entity;
    }

    @CacheDeleteCommand("deleteCustomer")
    public static Long deleteCustomerId(long id) {
        return id;
    }
}
