package com.example.cachedb.transactions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.reactor.cachedb.annotations.CacheTransactional;
import com.reactor.cachedb.core.api.*;
import com.reactor.cachedb.spring.boot.CacheTransactionAutoConfiguration;
import com.reactor.cachedb.starter.CacheDatabase;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;

class CacheTransactionalBoundaryTest {
    static final class Batch implements CacheWriteBatch {
        final List<Object> staged = new ArrayList<>(); int commits;
        public <T> CacheWriteBatch put(T value) { staged.add(value); return this; }
        public <T> CacheWriteBatch put(T value, long expected) { return put(value); }
        public <T, ID> CacheWriteBatch delete(Class<T> type, ID id) { staged.add(id); return this; }
        public CacheWriteBatchReceipt commit() { commits++; return new CacheWriteBatchReceipt("one", "1-0", List.of(1L)); }
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class Configuration {
        @Bean Batch batch() { return new Batch(); }
        @Bean CacheDatabase database(Batch batch) {
            var database = mock(CacheDatabase.class); when(database.writeBatch()).thenReturn(batch);
            when(database.entityRegistry()).thenReturn(mock(com.reactor.cachedb.core.registry.EntityRegistry.class));
            return database;
        }
        @Bean Service service() { return new Service(); }
    }
    public static class Service {
        @CacheTransactional public String commit() { CacheUnitOfWork.current().put("order").put("line"); return "result"; }
        @CacheTransactional public void abort() { CacheUnitOfWork.current().put("discarded"); throw new IllegalArgumentException("business validation"); }
        @CacheTransactional public void immediateReceipt() { CacheUnitOfWork.rejectImmediateReceipt(); }
    }
    @Test void proxiedServiceCommitsOnceDiscardsOnFailureAndAlwaysClearsThreadContext() {
        try (var context = new AnnotationConfigApplicationContext(Configuration.class, CacheTransactionAutoConfiguration.class)) {
            var service = context.getBean(Service.class); var batch = context.getBean(Batch.class);
            assertEquals("result", service.commit()); assertEquals(1, batch.commits);
            assertEquals(List.of("order", "line"), batch.staged); assertFalse(CacheUnitOfWork.active());
            assertThrows(IllegalArgumentException.class, service::abort); assertEquals(1, batch.commits); assertFalse(CacheUnitOfWork.active());
            assertThrows(IllegalStateException.class, service::immediateReceipt); assertEquals(1, batch.commits); assertFalse(CacheUnitOfWork.active());
        }
    }
}
