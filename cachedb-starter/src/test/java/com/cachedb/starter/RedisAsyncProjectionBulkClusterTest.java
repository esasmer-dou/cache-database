package com.reactor.cachedb.starter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
@EnabledIfSystemProperty(named="cachedb.redis.cluster",matches="true")
@Timeout(60)
class RedisAsyncProjectionBulkClusterTest {
    @Test void delayedRefreshCannotResurrectDeletedProjectionOnRealCluster()throws Exception{
        RedisAsyncProjectionBulkSafetyTest.delayedRefreshContract(RedisBulkDeleteClusterTest.connect(),false);
    }
    @Test void delayedRefreshCannotOverwriteRecreatedProjectionOnRealCluster()throws Exception{
        RedisAsyncProjectionBulkSafetyTest.delayedRefreshContract(RedisBulkDeleteClusterTest.connect(),true);
    }
}
