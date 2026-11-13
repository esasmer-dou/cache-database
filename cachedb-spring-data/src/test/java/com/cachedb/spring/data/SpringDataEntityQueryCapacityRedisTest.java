package com.reactor.cachedb.spring.data;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.api.EntityQueryCapacity;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.spring.data.fixture.*;
import com.reactor.cachedb.starter.CacheDatabase;
import java.io.PrintWriter;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.domain.PageRequest;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class SpringDataEntityQueryCapacityRedisTest {
    private static final class NoJdbc implements DataSource {
        final AtomicInteger calls = new AtomicInteger();
        public Connection getConnection() { calls.incrementAndGet(); throw new AssertionError("No foreground SQL for query capacity or hot-owner bulk delete"); }
        public Connection getConnection(String user, String password) { return getConnection(); }
        public PrintWriter getLogWriter() { return null; }
        public void setLogWriter(PrintWriter writer) {}
        public void setLoginTimeout(int seconds) {}
        public int getLoginTimeout() { return 0; }
        public Logger getParentLogger() { return Logger.getGlobal(); }
        public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLException("Not a wrapper"); }
        public boolean isWrapperFor(Class<?> type) { return false; }
    }
    private static final class Fixture implements AutoCloseable {
        final String prefix = "f2_capacity_" + UUID.randomUUID().toString().replace("-", "");
        final String library = prefix + "_functions";
        final NoJdbc source = new NoJdbc();
        final JedisPooled redis = new JedisPooled(System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1"),
                Integer.getInteger("cachedb.snapshot.redis.port", 16383));
        final CacheDatabase database;
        final Customers repository;
        Fixture(boolean zero) {
            var policy = CachePolicy.builder().hotEntityLimit(zero ? 8 : 32).pageSize(zero ? 8 : 4).build();
            var functions = RedisFunctionsConfig.builder().libraryName(library).upsertFunctionName(library + "_upsert")
                    .deleteFunctionName(library + "_delete").compactionCompleteFunctionName(library + "_complete").build();
            var config = CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                    .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build()).redisFunctions(functions)
                    .readShapeGuardrail(ReadShapeGuardrailConfig.builder().hotSetHeadroom(10).build())
                    .writeBehind(WriteBehindConfig.builder().enabled(false).streamKey(prefix + ":stream")
                            .compactionStreamKey(prefix + ":compaction").build()).build();
            database = new CacheDatabase(redis, source, config);
            database.start();
            database.register(CustomerCacheBinding.METADATA, CustomerCacheBinding.CODEC, policy);
            repository = new CustomersCacheDbImplementation(database, true);
        }
        String entity(long id) { return database.redisKeyStrategy().entityKey("f2-customers", id); }
        void provisionOwners(String tenant, Long... ids) {
            // Trusted fixture bootstrap, never inferred from a query or performed by bulk deletion.
            for (Long id : ids) redis.set(database.redisKeyStrategy().versionKey("f2-customers", id) + ":tenant", tenant);
        }
        public void close() {
            try {
                database.close();
                String cursor = "0";
                for (int pages = 0; pages < 128; pages++) {
                    var page = redis.scan(cursor, new ScanParams().match(prefix + ":*").count(128));
                    cursor = page.getCursor();
                    if (!page.getResult().isEmpty()) redis.del(page.getResult().toArray(String[]::new));
                    if (cursor.equals("0")) break;
                }
                assertEquals("0", cursor, "Bounded fixture cleanup must finish");
                redis.functionDelete(library); redis.del("cachedb:function-library:" + library + ":marker");
            } finally { redis.close(); }
        }
    }

    @Test void positiveBudgetSelectsTheWholeSmallMatchBeforeBulkAndPreservesOtherTenantAndRows() {
        try (var fixture = new Fixture(false)) {
            assertEquals(EntityQueryCapacity.finite(4), CustomerCacheBinding.repository(fixture.database).entityQueryCapacity());
            fixture.provisionOwners("a", 1L, 2L, 3L); fixture.provisionOwners("b", 4L);
            try (var tenant = CacheTenantContext.withTenant("a")) {
                fixture.repository.saveAll(List.of(new Customer(1L, "a", "expired", 20, true, null),
                        new Customer(2L, "a", "expired", 20, true, null), new Customer(3L, "a", "active", 20, true, null)));
            }
            try (var tenant = CacheTenantContext.withTenant("b")) { fixture.repository.save(new Customer(4L, "b", "expired", 20, true, null)); }
            String active = fixture.redis.get(fixture.entity(3)), foreign = fixture.redis.get(fixture.entity(4));
            try (var tenant = CacheTenantContext.withTenant("a")) {
                assertEquals(List.of(1L, 2L), fixture.repository.findByName("expired").stream().map(Customer::id).sorted().toList());
                assertEquals(2, fixture.repository.deleteByName("expired"));
                assertTrue(fixture.repository.findById(1L).isEmpty()); assertTrue(fixture.repository.findById(2L).isEmpty());
                assertEquals(List.of(3L), fixture.repository.findAll().stream().map(Customer::id).toList());
            }
            assertNotNull(active); assertNotNull(foreign);
            assertEquals(active, fixture.redis.get(fixture.entity(3))); assertEquals(foreign, fixture.redis.get(fixture.entity(4)));
            assertEquals(0, fixture.source.calls.get());
        }
    }

    @Test void zeroBudgetRejectsDerivedSelectionWithoutMutationWhileKnownIdBulkStillWorksWithoutSql() {
        try (var fixture = new Fixture(true); var tenant = CacheTenantContext.withTenant("a")) {
            assertEquals(EntityQueryCapacity.finite(0), CustomerCacheBinding.repository(fixture.database).entityQueryCapacity());
            fixture.provisionOwners("a", 1L, 2L);
            fixture.repository.saveAll(List.of(new Customer(1L, "a", "expired", 20, true, null), new Customer(2L, "a", "active", 20, true, null)));
            var before = List.of(fixture.redis.get(fixture.entity(1)), fixture.redis.get(fixture.entity(2)));
            var failure = assertThrows(IllegalStateException.class, () -> fixture.repository.deleteByName("expired"));
            assertEquals("Derived delete has zero effective entity query capacity; no query or delete was attempted. Use explicit known IDs for deletion or an authorized bounded administrative job", failure.getMessage());
            assertThrows(IllegalStateException.class, fixture.repository::findAll);
            assertThrows(IllegalArgumentException.class, () -> fixture.repository.findAll(PageRequest.of(0, 1)));
            assertEquals(before, List.of(fixture.redis.get(fixture.entity(1)), fixture.redis.get(fixture.entity(2))));
            assertEquals(0, fixture.source.calls.get());
            assertThrows(com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException.class,
                    () -> fixture.repository.deleteAllById(List.of(1L, 999L)));
            assertEquals(before, List.of(fixture.redis.get(fixture.entity(1)), fixture.redis.get(fixture.entity(2))));
            assertEquals(0, fixture.source.calls.get());
            fixture.repository.deleteAllById(List.of(1L));
            assertNull(fixture.redis.get(fixture.entity(1))); assertEquals(before.get(1), fixture.redis.get(fixture.entity(2)));
            assertEquals(0, fixture.source.calls.get());
        }
    }
}
