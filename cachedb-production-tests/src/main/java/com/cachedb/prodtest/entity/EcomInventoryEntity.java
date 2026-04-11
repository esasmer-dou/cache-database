package com.reactor.cachedb.prodtest.entity;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;

@CacheEntity(table = "cachedb_prodtest_inventory", redisNamespace = "prodtest_inventory")
public class EcomInventoryEntity {

    @CacheId(column = "id")
    public Long id;

    @CacheColumn("sku")
    public String sku;

    @CacheColumn("warehouse_code")
    public String warehouseCode;

    @CacheColumn("available_units")
    public Long availableUnits;

    @CacheColumn("reserved_units")
    public Long reservedUnits;
}
