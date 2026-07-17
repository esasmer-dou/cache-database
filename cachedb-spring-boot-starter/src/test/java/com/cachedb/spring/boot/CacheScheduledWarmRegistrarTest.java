package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.starter.CacheWarmPlan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class CacheScheduledWarmRegistrarTest {

    @Test
    void mustResolvePlaceholdersAndRegisterAValidPlanMethod() {
        DefaultListableBeanFactory beanFactory = beanFactory();
        beanFactory.registerSingleton("validPlans", new ValidPlans());
        CacheScheduledWarmCoordinator coordinator = mock(CacheScheduledWarmCoordinator.class);
        ThreadPoolTaskScheduler scheduler = scheduler();
        CacheScheduledWarmRegistrar registrar = new CacheScheduledWarmRegistrar(beanFactory, scheduler, coordinator);
        try {
            registrar.afterSingletonsInstantiated();

            verify(coordinator).register("test-active-window");
            verifyNoMoreInteractions(coordinator);
        } finally {
            registrar.destroy();
            scheduler.shutdown();
        }
    }

    @Test
    void annotationMustTriggerTheCoordinatorOnTheSchedulerThread() throws Exception {
        DefaultListableBeanFactory beanFactory = beanFactory();
        beanFactory.registerSingleton("triggeringPlans", new TriggeringPlans());
        CacheScheduledWarmCoordinator coordinator = mock(CacheScheduledWarmCoordinator.class);
        CountDownLatch triggered = new CountDownLatch(1);
        doAnswer(invocation -> {
            triggered.countDown();
            return null;
        }).when(coordinator).execute(any(), any());
        ThreadPoolTaskScheduler scheduler = scheduler();
        CacheScheduledWarmRegistrar registrar = new CacheScheduledWarmRegistrar(beanFactory, scheduler, coordinator);
        try {
            registrar.afterSingletonsInstantiated();

            assertTrue(triggered.await(2, TimeUnit.SECONDS), "Scheduled warm annotation did not trigger");
            verify(coordinator).register("test-scheduler-trigger");
            verify(coordinator).execute(any(), any());
        } finally {
            registrar.destroy();
            scheduler.shutdown();
        }
    }

    @Test
    void mustFailFastWhenTheAnnotatedMethodDoesNotReturnAWarmPlan() {
        DefaultListableBeanFactory beanFactory = beanFactory();
        beanFactory.registerSingleton("invalidPlans", new InvalidPlans());
        CacheScheduledWarmCoordinator coordinator = mock(CacheScheduledWarmCoordinator.class);
        ThreadPoolTaskScheduler scheduler = scheduler();
        CacheScheduledWarmRegistrar registrar = new CacheScheduledWarmRegistrar(beanFactory, scheduler, coordinator);
        try {
            assertThrows(IllegalStateException.class, registrar::afterSingletonsInstantiated);
        } finally {
            registrar.destroy();
            scheduler.shutdown();
        }
    }

    @Test
    void mustRejectDuplicateClusterWideJobNames() {
        DefaultListableBeanFactory beanFactory = beanFactory();
        beanFactory.registerSingleton("firstPlans", new ValidPlans());
        beanFactory.registerSingleton("duplicatePlans", new DuplicatePlans());
        CacheScheduledWarmCoordinator coordinator = mock(CacheScheduledWarmCoordinator.class);
        ThreadPoolTaskScheduler scheduler = scheduler();
        CacheScheduledWarmRegistrar registrar = new CacheScheduledWarmRegistrar(beanFactory, scheduler, coordinator);
        try {
            assertThrows(IllegalStateException.class, registrar::afterSingletonsInstantiated);
        } finally {
            registrar.destroy();
            scheduler.shutdown();
        }
    }

    private DefaultListableBeanFactory beanFactory() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.addEmbeddedValueResolver(value -> value
                .replace("${test.warm.enabled:true}", "true")
                .replace("${test.warm.delay:PT1H}", "PT1H"));
        return beanFactory;
    }

    private ThreadPoolTaskScheduler scheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("cachedb-scheduled-warm-registrar-test-");
        scheduler.initialize();
        return scheduler;
    }

    static final class ValidPlans {
        @CacheScheduledWarm(
                name = "test-active-window",
                enabledString = "${test.warm.enabled:true}",
                fixedDelayString = "${test.warm.delay:PT1H}",
                initialDelayString = "PT1H",
                lockAtMostForString = "PT1M"
        )
        public CacheWarmPlan plan() {
            return planFor("ValidEntity");
        }
    }

    static final class DuplicatePlans {
        @CacheScheduledWarm(
                name = "test-active-window",
                fixedDelayString = "PT1H",
                initialDelayString = "PT1H",
                lockAtMostForString = "PT1M"
        )
        public CacheWarmPlan plan() {
            return planFor("DuplicateEntity");
        }
    }

    static final class TriggeringPlans {
        @CacheScheduledWarm(
                name = "test-scheduler-trigger",
                fixedDelayString = "PT1H",
                initialDelayString = "PT0.02S",
                lockAtMostForString = "PT1M"
        )
        public CacheWarmPlan plan() {
            return planFor("TriggeringEntity");
        }
    }

    static final class InvalidPlans {
        @CacheScheduledWarm(
                name = "invalid-plan",
                fixedDelayString = "PT1H",
                initialDelayString = "PT1H",
                lockAtMostForString = "PT1M"
        )
        public String plan() {
            return "invalid";
        }
    }

    private static CacheWarmPlan planFor(String entityName) {
        return CacheWarmPlan.builder(entityName)
                .querySpec(QuerySpec.builder().limit(1).build())
                .maxRows(1)
                .build();
    }
}
