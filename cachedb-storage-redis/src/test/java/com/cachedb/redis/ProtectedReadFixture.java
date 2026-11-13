package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.cache.NearCachePolicy;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.model.RelationDefinition;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

final class ProtectedReadFixture {
    static final String NS = "read_guard", TENANT = "a";
    static final ProtectionKey KEY = ProtectionKey.of(NS, TENANT);
    static final Lease LEASE = new Lease(KEY, UUID.randomUUID(), UUID.randomUUID(), 1, 120);
    record Row(String id, String tenant, String value) {}

    static EntityMetadata<Row,String> metadata(String namespace) {
        return new EntityMetadata<>() {
            public String entityName() { return "ReadRow"; }
            public String tableName() { return "read_row"; }
            public String redisNamespace() { return namespace; }
            public String idColumn() { return "id"; }
            public Class<Row> entityType() { return Row.class; }
            public Function<Row,String> idAccessor() { return Row::id; }
            public List<String> columns() { return List.of("id","tenant","value"); }
            public List<RelationDefinition> relations() { return List.of(); }
            public String tenantFieldName() { return "tenant"; }
            public String extractTenantId(Row row) { return row.tenant(); }
            public boolean tenantPurgeEnabled() { return true; }
            public boolean deeplyImmutable() { return true; }
        };
    }
    static final EntityCodec<Row> CODEC = new EntityCodec<>() {
        public String toRedisValue(Row row) { return row.id()+"|"+row.tenant()+"|"+row.value(); }
        public Row fromRedisValue(String payload) {
            String[] values=payload.split("\\|",3);
            return new Row(values[0],values[1],values[2]);
        }
        public Map<String,Object> toColumns(Row row) { return Map.of("id",row.id(),"tenant",row.tenant(),"value",row.value()); }
        public Row fromColumns(Map<String,Object> columns) {
            return new Row((String)columns.get("id"),(String)columns.get("tenant"),(String)columns.get("value"));
        }
    };
    static RedisNearCacheCoordinator.Store store() {
        return new RedisNearCacheCoordinator.Store(new NearCachePolicy(32,10,1048576));
    }
    static RedisTenantReadFence.Publication plan(Generation generation) {
        return new RedisTenantReadFence.Publication(generation,"owner","a","return 1",List.of("payload"),List.of());
    }
    static void await(CountDownLatch latch) {
        try { if (!latch.await(5,TimeUnit.SECONDS)) throw new AssertionError("Latch deadline"); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError("Interrupted"); }
    }
    static void awaitPhysical(CountDownLatch latch) {
        boolean interrupted=false;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        try {
            while(latch.getCount()!=0) {
                try {
                    if(!latch.await(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS))
                        throw new AssertionError("Physical deadline");
                } catch(InterruptedException ignored) { interrupted=true; }
            }
        } finally { if(interrupted) Thread.currentThread().interrupt(); }
    }
    static final class State implements RedisTenantReadFence.Authority {
        final Object lock=new Object();
        long generation=1;
        boolean open=true;
        Runnable captureHook=()->{};
        Runnable validationHook=()->{};
        Function<RedisTenantReadFence.Publication,Object> command=plan->1L;
        final AtomicInteger executions=new AtomicInteger();
        public Generation capture(ProtectionKey key) {
            Generation result;
            synchronized(lock) { requireOpen(new Generation(key,generation)); result=new Generation(key,generation); }
            captureHook.run();
            return result;
        }
        public void validate(Generation original) {
            validationHook.run();
            synchronized(lock) { requireOpen(original); }
        }
        public Object execute(Generation original,RedisTenantReadFence.Publication plan) {
            synchronized(lock) {
                requireOpen(original);
                executions.incrementAndGet();
                return command.apply(plan);
            }
        }
        public void assertClosed(Lease lease,long expected) {
            synchronized(lock) {
                if(open || generation!=expected || !lease.key().equals(KEY))
                    throw new ProtectionException(ProtectionException.Code.LEASE_LOST);
            }
        }
        void closeGeneration() { synchronized(lock) { open=false; } }
        void reopen() { synchronized(lock) { generation++; open=true; } }
        private void requireOpen(Generation original) {
            if(!open || original.value()!=generation || !original.key().equals(KEY))
                throw new ProtectionException(ProtectionException.Code.GENERATION_MISMATCH);
        }
    }
}
