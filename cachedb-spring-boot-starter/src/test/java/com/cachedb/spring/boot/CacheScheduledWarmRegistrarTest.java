package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.starter.CacheWarmPlan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
        CacheScheduledWarmCoordinator coordinator = mock(CacheScheduledWarmCoordinator.class);
        ThreadPoolTaskScheduler scheduler = scheduler();
        CacheScheduledWarmRegistrar registrar = registrar(
                beanFactory,
                scheduler,
                coordinator,
                task(descriptor(
                        "test-active-window",
                        "${test.warm.enabled:true}",
                        "${test.warm.delay:PT1H}",
                        "PT1H"
                ), () -> planFor("ValidEntity"))
        );
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
        CacheScheduledWarmCoordinator coordinator = mock(CacheScheduledWarmCoordinator.class);
        CountDownLatch triggered = new CountDownLatch(1);
        doAnswer(invocation -> {
            triggered.countDown();
            return null;
        }).when(coordinator).execute(any(), any());
        ThreadPoolTaskScheduler scheduler = scheduler();
        CacheScheduledWarmRegistrar registrar = registrar(
                beanFactory,
                scheduler,
                coordinator,
                task(descriptor("test-scheduler-trigger", "true", "PT1H", "PT0.02S"),
                        () -> planFor("TriggeringEntity"))
        );
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
    void mustFailFastWhenTheGeneratedDescriptorContainsNoSchedule() {
        DefaultListableBeanFactory beanFactory = beanFactory();
        CacheScheduledWarmCoordinator coordinator = mock(CacheScheduledWarmCoordinator.class);
        ThreadPoolTaskScheduler scheduler = scheduler();
        CacheScheduledWarmDescriptor invalid = descriptor("invalid-plan", "true", "", "PT1H");
        CacheScheduledWarmRegistrar registrar = registrar(
                beanFactory,
                scheduler,
                coordinator,
                task(invalid, () -> planFor("InvalidEntity"))
        );
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
        CacheScheduledWarmCoordinator coordinator = mock(CacheScheduledWarmCoordinator.class);
        ThreadPoolTaskScheduler scheduler = scheduler();
        CacheScheduledWarmRegistrar registrar = new CacheScheduledWarmRegistrar(
                beanFactory,
                scheduler,
                coordinator,
                List.of(
                        task(descriptor("test-active-window", "true", "PT1H", "PT1H"),
                                () -> planFor("ValidEntity")),
                        task(descriptor("test-active-window", "true", "PT1H", "PT1H"),
                                () -> planFor("DuplicateEntity"))
                )
        );
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

    private CacheScheduledWarmRegistrar registrar(
            DefaultListableBeanFactory beanFactory,
            ThreadPoolTaskScheduler scheduler,
            CacheScheduledWarmCoordinator coordinator,
            CacheScheduledWarmTask task
    ) {
        return new CacheScheduledWarmRegistrar(beanFactory, scheduler, coordinator, List.of(task));
    }

    private CacheScheduledWarmTask task(
            CacheScheduledWarmDescriptor descriptor,
            Supplier<CacheWarmPlan> supplier
    ) {
        return new CacheScheduledWarmTask() {
            @Override
            public CacheScheduledWarmDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public CacheWarmPlan createPlan() {
                return supplier.get();
            }
        };
    }

    private CacheScheduledWarmDescriptor descriptor(
            String name,
            String enabled,
            String fixedDelay,
            String initialDelay
    ) {
        return new CacheScheduledWarmDescriptor(
                "sample.WarmPlans",
                "plan",
                name,
                "",
                "",
                fixedDelay,
                "",
                initialDelay,
                enabled,
                CacheScheduledWarmMode.ENTITY_AND_PROJECTIONS,
                "PT1M",
                "PT0S",
                "PT0.25S",
                "",
                false,
                "10000",
                "500"
        );
    }

    private static CacheWarmPlan planFor(String entityName) {
        return CacheWarmPlan.builder(entityName)
                .querySpec(QuerySpec.builder().limit(1).build())
                .maxRows(1)
                .build();
    }
}
