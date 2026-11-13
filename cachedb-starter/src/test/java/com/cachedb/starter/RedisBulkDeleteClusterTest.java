package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.api.BulkDeleteFailureException;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.redis.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
@EnabledIfSystemProperty(named="cachedb.redis.cluster",matches="true")
@Timeout(60)
class RedisBulkDeleteClusterTest {
    static RedisClientOperations connect(){
        var config=DefaultJedisClientConfig.builder().connectionTimeoutMillis(3000).socketTimeoutMillis(5000)
                .hostAndPortMapper(address->System.getProperty("cachedb.redis.cluster.host")!=null?address:
                        new HostAndPort("127.0.0.1",address.getPort()<10000?address.getPort()+10000:address.getPort())).build();
        return new JedisClusterOperations(new JedisCluster(Set.of(new HostAndPort(
                System.getProperty("cachedb.redis.cluster.host","127.0.0.1"),
                Integer.getInteger("cachedb.redis.cluster.port",17001))),config));
    }
    @Test void realClusterExecutesBoundedChunksWithoutCrossslotOrForegroundSql()throws Exception{
        RedisBulkDeleteRedisTest.boundedDeleteContract(connect());
    }
    @Test void realClusterAtomicallyRejectsChangedOwnershipAndKeepsKnownPartialReceipts()throws Exception{
        try(var f=new RedisBulkDeleteRedisTest.Fixture(connect(),false);var tenant=CacheTenantContext.withTenant("a")){
            f.seed(129);
            f.client.beforeBulk=call->{if(call==2)f.client.commands().set(f.keys.versionKey("f2bulk","129")+":tenant","b");};
            var failure=assertThrows(BulkDeleteFailureException.class,()->f.repository.bulkDelete(f.ids(129)));
            assertEquals(128,failure.accepted().size());assertEquals(List.of("129"),failure.rejected());
            assertFalse(f.exists("1"));assertTrue(f.exists("129"));assertEquals(0,f.ownerSourceCalls.get());
            assertEquals(2,f.client.bulkCalls.get());assertEquals(0,f.client.singleDeletes.get());
        }
    }
}
