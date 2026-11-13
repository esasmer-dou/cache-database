package com.reactor.cachedb.spring.data;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.api.CacheSession;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.spring.data.fixture.*;
import com.reactor.cachedb.starter.CacheDatabase;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.domain.*;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class SpringDataRedisIntegrationTest {
    private List<Long> ids(List<Customer> rows) { return rows.stream().map(Customer::id).sorted().toList(); }

    @Test void generatedSpringBeansExerciseTheFullSurfaceOnRedisAndPreserveTenantAndCausalGuards() throws Exception {
        String prefix = "f2-" + UUID.randomUUID();
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + prefix + ";DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE f2_customers (
                      id BIGINT PRIMARY KEY, tenant_id VARCHAR(256), display_name VARCHAR(200),
                      age INTEGER, active BOOLEAN, note VARCHAR(200), entity_version BIGINT DEFAULT 0)
                    """);
        }
        var policy = CachePolicy.builder().hotEntityLimit(100).build();
        var config = CacheDatabaseConfig.builder()
                .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                .writeBehind(WriteBehindConfig.builder().enabled(false).streamKey(prefix + ":writes")
                        .compactionStreamKey(prefix + ":compaction").build())
                .queryIndex(QueryIndexConfig.builder().prefixIndexEnabled(true).build())
                .resourceLimits(ResourceLimits.builder().defaultCachePolicy(policy).build()).build();
        try (var redis = new JedisPooled(System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1"),
                Integer.getInteger("cachedb.snapshot.redis.port", 16383));
                var database = new CacheDatabase(redis, source, config)) {
            try {
                database.registerJdbcBacked(CustomerCacheBinding.METADATA, CustomerCacheBinding.CODEC, policy);
                new ApplicationContextRunner().withBean(CacheSession.class, () -> database)
                        .withUserConfiguration(CustomersCacheDbConfiguration.class, ManagedCustomersCacheDbConfiguration.class)
                        .run(context -> {
                            assertNull(context.getStartupFailure());
                            var repo = context.getBean("customers", Customers.class);
                            assertEquals(CustomersCacheDbImplementation.class, repo.getClass());
                            assertEquals(ManagedCustomersCacheDbImplementation.class,
                                    context.getBean("managedCustomers", ManagedCustomers.class).getClass());
                            try (var tenant = CacheTenantContext.withTenant("a")) {
                                repo.saveAll(List.of(new Customer(1L, "a", "Ada", 20, true, null),
                                        new Customer(2L, "a", "Ada", 30, true, "n"),
                                        new Customer(3L, "a", "Bob", 40, false, "b"),
                                        new Customer(10L, "a", "Cora", 30, true, "c")));
                            }
                            try (var tenant = CacheTenantContext.withTenant("b")) { repo.save(new Customer(5L, "b", "Ada", 90, true, null)); }
                            try (var tenant = CacheTenantContext.withTenant("a")) {
                                assertEquals(4, repo.count()); assertTrue(repo.existsById(1L));
                                assertEquals(List.of(1L, 2L), ids(repo.findByName("Ada")));
                                assertEquals(ids(repo.findByName("Ada")), ids(repo.queryByName("Ada")));
            assertEquals(ids(repo.findByName("Ada")), ids(repo.getByNameEquals("Ada")));
                                assertEquals(ids(repo.findByName("Ada")), ids(repo.getByNameIs("Ada")));
                                assertEquals(ids(repo.findByName("Ada")), ids(repo.getByNameIsEquals("Ada")));
                                assertEquals(List.of(3L, 10L), ids(repo.findByNameIsNot("Ada")));
                                assertEquals(List.of(1L, 3L), ids(repo.findByAgeNot(30)));
                                assertEquals(List.of(1L, 3L), ids(repo.findByAgeIn(List.of(20, 40))));
                                assertEquals(List.of(2L, 10L), ids(repo.findByAgeNotIn(List.of(20, 40))));
                                assertEquals(List.of(2L, 3L, 10L), ids(repo.findByAgeGreaterThan(20)));
                                assertEquals(List.of(2L, 3L, 10L), ids(repo.findByAgeGreaterThanEqual(30)));
                                assertEquals(List.of(1L), ids(repo.findByAgeLessThan(30)));
                                assertEquals(List.of(1L, 2L, 10L), ids(repo.findByAgeLessThanEqual(30)));
                                assertEquals(List.of(1L, 2L, 10L), ids(repo.findByAgeBetween(20, 30)));
                                assertEquals(List.of(1L), ids(repo.findByNoteIsNull()));
                                assertEquals(List.of(2L, 3L, 10L), ids(repo.findByNoteIsNotNull()));
                                assertEquals(List.of(1L, 2L, 10L), ids(repo.findByActiveTrue()));
                                assertEquals(List.of(3L), ids(repo.findByActiveFalse()));
                                assertEquals(List.of(1L, 2L), ids(repo.findByNameStartingWith("Ad")));
                                assertEquals(3, repo.countByActiveTrue()); assertTrue(repo.existsByName("Ada"));
                                assertEquals(1, repo.findByActive(true, Limit.of(1)).size());
                                assertEquals(2, repo.findTop2ByActiveOrderByAgeDesc(true).size());
                                assertEquals(30, repo.findFirstByActiveOrderByAgeDesc(true).age());
                                assertEquals(20, repo.findByActive(true, Sort.by("age")).get(0).age());
                                assertEquals(3, repo.findByActive(true, PageRequest.of(0, 2)).getTotalElements());
                                assertTrue(repo.readByActive(true, PageRequest.of(0, 2)).hasNext());
                                var first = repo.findFirst2ByActiveOrderByAgeAsc(true, ScrollPosition.keyset());
                                var next = repo.findFirst2ByActiveOrderByAgeAsc(true, (KeysetScrollPosition) first.positionAt(1));
                                assertEquals(1, next.size()); assertFalse(next.hasNext());
                                assertThrows(RuntimeException.class, () -> repo.findById(5L));
                                assertEquals(List.of(1L, 2L), ids(repo.findAllById(List.of(1L, 2L))));
                                var backend = CustomerCacheBinding.repository(database);
                                var receipt = backend.saveWithReceipt(new Customer(90L, "a", "causal", 22, true, null));
                                try (var causal = CacheCausalContext.withReceipt("f2-customers", receipt)) {
                                    String before = CacheCausalContext.encodeToken();
                                    assertEquals(90L, repo.findById(90L).orElseThrow().id());
                                    assertEquals(before, CacheCausalContext.encodeToken());
                                }
                                try (var causal = CacheCausalContext.withMinVersion("f2-customers", 90L, receipt.version() + 100)) {
                                    assertThrows(RuntimeException.class, () -> repo.findById(90L));
                                }
                                repo.deleteById(90L);
                                assertEquals(2, repo.deleteByName("Ada"));
                                assertEquals(List.of(10L), ids(repo.deleteByAgeLessThan(35)));
                                repo.deleteByActiveFalse(); assertEquals(0, repo.count());
                            }
                            assertTrue(CacheTenantContext.currentTenant().isEmpty());
                            assertEquals("", CacheCausalContext.encodeToken());
                        });
            } finally {
                String cursor = "0";
                for (int round = 0; round < 64; round++) {
                    var scan = redis.scan(cursor, new ScanParams().match(prefix + ":*").count(500));
                    cursor = scan.getCursor();
                    for (String key : scan.getResult()) redis.unlink(key);
                    if (cursor.equals("0")) break;
                }
                assertEquals("0", cursor, "Bounded fixture cleanup did not finish");
            }
        }
    }
}
