package com.reactor.cachedb.examples.demo;

import com.reactor.cachedb.examples.demo.entity.DemoCartEntity;
import com.reactor.cachedb.examples.demo.entity.DemoCustomerEntity;
import com.reactor.cachedb.examples.demo.entity.DemoOrderEntity;
import com.reactor.cachedb.examples.demo.entity.DemoOrderLineEntity;
import com.reactor.cachedb.examples.demo.entity.DemoProductEntity;

import java.util.List;

public record DemoSeedPayload(
        List<DemoCustomerEntity> customers,
        List<DemoProductEntity> products,
        List<DemoCartEntity> carts,
        List<DemoOrderEntity> orders,
        List<DemoOrderLineEntity> orderLines
) {
}
