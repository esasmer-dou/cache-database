package com.reactor.cachedb.processor;

import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import com.reactor.cachedb.redis.JedisClusterOperations;
import redis.clients.jedis.*;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
@EnabledIfSystemProperty(named="cachedb.redis.cluster",matches="true")
@Timeout(60)
class EncryptedBulkDeleteClusterTest {
    @TempDir Path temp;
    @Test void generatedEncryptedMappingUsesRealClusterAtomicDeleteChunks()throws Exception{
        var config=DefaultJedisClientConfig.builder().connectionTimeoutMillis(3000).socketTimeoutMillis(5000)
                .hostAndPortMapper(address->System.getProperty("cachedb.redis.cluster.host")!=null?address:
                        new HostAndPort("127.0.0.1",address.getPort()<10000?address.getPort()+10000:address.getPort())).build();
        var client=new JedisClusterOperations(new JedisCluster(Set.of(new HostAndPort(
                System.getProperty("cachedb.redis.cluster.host","127.0.0.1"),Integer.getInteger("cachedb.redis.cluster.port",17001))),config));
        EncryptedBulkDeleteRedisTest.positive(temp,client,false);
    }
}
