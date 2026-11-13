package com.reactor.cachedb.spring.boot;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.async.CacheAsyncExecutor;
import com.reactor.cachedb.core.config.CacheRuntimeOptions;
import com.reactor.cachedb.core.crypto.CacheKeyProvider;
import com.reactor.cachedb.core.crypto.KeyMaterial;
import com.reactor.cachedb.core.trace.TraceContexts;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class CacheAsyncRuntimeBindingTest {
    @Test void explicitConcurrencyAndGlobalReplyBudgetBindIntoRuntimeOptions() {
        var source = new MapConfigurationPropertySource(Map.of(
                "cachedb.async.max-concurrent", "256",
                "cachedb.redis.read-coalescing.max-live-reply-bytes", "67108864"));
        var properties = new Binder(source).bind("cachedb", Bindable.of(CacheDbSpringProperties.class)).get();
        var options = new CacheDatabaseSpringBootAutoConfiguration().cacheRuntimeOptions(properties);
        assertEquals(256, options.asyncMaxConcurrent());
        assertEquals(64L * 1024 * 1024, options.redisReadCoalescing().maxLiveReplyBytes());
        assertEquals(256, options.toBuilder().build().asyncMaxConcurrent());
    }

    @Test void uniqueExecutorIsBorrowedAndAmbiguousBeansFailClosed() {
        Executor first = task -> { throw new AssertionError("Configuration must not execute work"); };
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("first", first);
        var customizer = new CacheAsyncAutoConfiguration().cacheDbAsyncExecutorCustomizer(beans);
        var builder = CacheRuntimeOptions.builder();
        customizer.customize(builder, new CacheDbSpringProperties());
        assertSame(first, builder.build().asyncExecutor());
        beans.registerSingleton("second", (Executor) task -> {});
        assertThrows(NoUniqueBeanDefinitionException.class,
                () -> customizer.customize(CacheRuntimeOptions.builder(), new CacheDbSpringProperties()));
    }

    @Test void invalidConcurrencyAndReplyBudgetsFailDuringPropertyValidationEvenWhenDisabled() {
        var properties = new CacheDbSpringProperties();
        properties.setEnabled(false);
        properties.getAsync().setMaxConcurrent(0);
        assertThrows(IllegalArgumentException.class, properties::afterPropertiesSet);
        properties.getAsync().setMaxConcurrent(1024);
        properties.getRedis().getReadCoalescing().setMaxLiveReplyBytes(1);
        assertThrows(IllegalArgumentException.class, properties::afterPropertiesSet);
    }

    private static final Executor LATE_EXECUTOR = task -> { throw new AssertionError("Binding cannot dispatch work"); };

    private ApplicationContextRunner boot(String profiles) {
        return boot(profiles, WarmSchedulerAutoConfiguration.class);
    }

    private ApplicationContextRunner boot(String profiles, Class<?> warmConfiguration) {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                TaskExecutionAutoConfiguration.class, CacheAsyncAutoConfiguration.class,
                CacheEncryptionAutoConfiguration.class, warmConfiguration, RuntimeOptionsAutoConfiguration.class))
                .withUserConfiguration(EncryptionProfile.class)
                .withPropertyValues("spring.profiles.active=" + profiles);
    }

    @ParameterizedTest @ValueSource(strings = {"normal", "demo", "demo,encryption"})
    void bootApplicationExecutorAndOwnedSchedulerCoexistWithoutCustomExecutors(String profiles) {
        boot(profiles).run(context -> {
            assertNull(context.getStartupFailure());
            var options = context.getBean(CacheRuntimeOptions.class);
            var application = context.getBean("applicationTaskExecutor", ThreadPoolTaskExecutor.class);
            var scheduler = context.getBean("cacheDbScheduledWarmTaskScheduler", ThreadPoolTaskScheduler.class);
            assertSame(application, options.asyncExecutor());
            assertNotSame(scheduler, options.asyncExecutor());
            if (profiles.contains("encryption")) assertSame(context.getBean(CacheKeyProvider.class), options.keyProvider());
        });
    }

    @ParameterizedTest @ValueSource(strings = {"normal", "demo", "demo,encryption"})
    void onlyTheOwnedWarmSchedulerLeavesTheRuntimeGateOnItsOwnedVirtualExecutor(String profiles) {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                CacheAsyncAutoConfiguration.class, CacheEncryptionAutoConfiguration.class,
                WarmSchedulerAutoConfiguration.class, RuntimeOptionsAutoConfiguration.class))
                .withUserConfiguration(EncryptionProfile.class)
                .withPropertyValues("spring.profiles.active=" + profiles).run(context -> {
                    assertNull(context.getStartupFailure());
                    var options = context.getBean(CacheRuntimeOptions.class);
                    assertNull(options.asyncExecutor());
                    try (var gate = gate(options)) {
                        assertTrue(gate.submit(() -> Thread.currentThread().isVirtual()).get(2, TimeUnit.SECONDS));
                    }
                });
    }

    @ParameterizedTest @ValueSource(strings = {"normal", "demo", "demo,encryption"})
    void oneCustomExecutorIsBorrowedAndBootBacksOff(String profiles) {
        boot(profiles).withUserConfiguration(SingleExecutor.class).run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.containsBean("applicationTaskExecutor"));
            assertSame(context.getBean("first"), context.getBean(CacheRuntimeOptions.class).asyncExecutor());
        });
    }

    @ParameterizedTest @ValueSource(strings = {"normal", "demo", "demo,encryption"})
    void twoCustomExecutorsStillFailStartupRatherThanSelectingAnOwnedFallback(String profiles) {
        boot(profiles).withUserConfiguration(TwoExecutors.class).run(context -> assertAmbiguous(context.getStartupFailure()));
    }

    @ParameterizedTest @ValueSource(strings = {"normal", "demo", "demo,encryption"})
    void userPrimaryWinsAmongTwoCustomExecutors(String profiles) {
        boot(profiles).withUserConfiguration(PrimaryExecutor.class).run(context -> {
            assertNull(context.getStartupFailure());
            assertSame(context.getBean("second"), context.getBean(CacheRuntimeOptions.class).asyncExecutor());
        });
    }

    @ParameterizedTest @ValueSource(strings = {"normal", "demo", "demo,encryption"})
    void explicitRuntimeOptionsBeanBypassesAutomaticAmbiguousSelection(String profiles) {
        boot(profiles).withUserConfiguration(TwoExecutors.class, ExplicitOptions.class).run(context -> {
            assertNull(context.getStartupFailure());
            var options = context.getBean(CacheRuntimeOptions.class);
            assertEquals(7, options.asyncMaxConcurrent());
            assertSame(context.getBean("first"), options.asyncExecutor());
        });
    }

    @ParameterizedTest @ValueSource(strings = {"normal", "demo", "demo,encryption"})
    void lateRuntimeCustomizerCanReplaceTheBorrowedExecutor(String profiles) {
        boot(profiles).withUserConfiguration(LateOptions.class).run(context -> {
            assertNull(context.getStartupFailure());
            var options = context.getBean(CacheRuntimeOptions.class);
            assertSame(LATE_EXECUTOR, options.asyncExecutor());
            if (profiles.contains("encryption")) assertSame(context.getBean(CacheKeyProvider.class), options.keyProvider());
        });
    }

    @ParameterizedTest @ValueSource(strings = {"normal", "demo", "demo,encryption"})
    void aUserScheduledExecutorIsNotExcludedAndTheGateNeverClosesIt(String profiles) {
        boot(profiles).withUserConfiguration(UserScheduledExecutor.class).run(context -> {
            assertNull(context.getStartupFailure());
            var borrowed = context.getBean("customScheduler", ScheduledExecutorService.class);
            var options = context.getBean(CacheRuntimeOptions.class);
            assertSame(borrowed, options.asyncExecutor());
            try (var gate = gate(options)) { assertEquals(42, gate.submit(() -> 42).get(2, TimeUnit.SECONDS)); }
            assertFalse(borrowed.isShutdown(), "Closing the CacheDB gate must not close a borrowed scheduler");
            assertEquals(43, borrowed.submit(() -> 43).get(2, TimeUnit.SECONDS));
        });
    }

    @ParameterizedTest @ValueSource(strings = {"normal", "demo", "demo,encryption"})
    void theOwnedSchedulerIsExcludedWhenItsReservedNameIsAnAlias(String profiles) {
        boot(profiles, AliasedWarmSchedulerAutoConfiguration.class).run(context -> {
            assertNull(context.getStartupFailure());
            assertSame(context.getBean("warmCanonical"), context.getBean("cacheDbScheduledWarmTaskScheduler"));
            assertSame(context.getBean("applicationTaskExecutor"), context.getBean(CacheRuntimeOptions.class).asyncExecutor());
        });
    }

    @Test void twoUserPrimaryExecutorsRemainAmbiguous() {
        boot("demo").withUserConfiguration(TwoPrimaryExecutors.class)
                .run(context -> assertAmbiguous(context.getStartupFailure()));
    }

    @Test void excludingAPrimaryWarmSchedulerDoesNotHideTheUserPrimary() {
        boot("demo", PrimaryWarmSchedulerAutoConfiguration.class).withUserConfiguration(PrimaryExecutor.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertSame(context.getBean("second"), context.getBean(CacheRuntimeOptions.class).asyncExecutor());
                });
    }

    @Test void userPrioritySelectionStillUsesSpringsDependencyComparator() {
        boot("demo").withUserConfiguration(PriorityExecutors.class).run(context -> {
            assertNull(context.getStartupFailure());
            assertSame(context.getBean("second"), context.getBean(CacheRuntimeOptions.class).asyncExecutor());
        });
    }

    @Test void twoAncestorPrimariesAreAmbiguousWithoutALocalPrimary() {
        var parent = new DefaultListableBeanFactory();
        registerExecutor(parent, "first", true, true);
        registerExecutor(parent, "second", true, true);
        var child = new DefaultListableBeanFactory(parent);
        var failure = assertThrows(NoUniqueBeanDefinitionException.class, () ->
                new CacheAsyncAutoConfiguration().cacheDbAsyncExecutorCustomizer(child)
                        .customize(CacheRuntimeOptions.builder(), new CacheDbSpringProperties()));
        assertEquals(Set.of("first", "second"), Set.copyOf(failure.getBeanNamesFound()));
    }

    @Test void aLocalPrimaryWinsEvenWhenTheAncestorContainsTwoPrimaries() {
        var parent = new DefaultListableBeanFactory();
        registerExecutor(parent, "first", true, true);
        registerExecutor(parent, "second", true, true);
        var child = new DefaultListableBeanFactory(parent);
        registerExecutor(child, "local", true, true);
        var builder = CacheRuntimeOptions.builder();
        new CacheAsyncAutoConfiguration().cacheDbAsyncExecutorCustomizer(child)
                .customize(builder, new CacheDbSpringProperties());
        assertSame(child.getBean("local"), builder.build().asyncExecutor());
    }

    @Test void nonAutowireCandidatesAndUnrelatedLazyFactoriesAreNotSelectedOrInitialized() {
        var beans = new DefaultListableBeanFactory();
        registerExecutor(beans, "first", false, true);
        registerExecutor(beans, "hidden", true, false);
        var jobCreations = new AtomicInteger();
        var job = new RootBeanDefinition(UntypedJobFactory.class, () -> {
            jobCreations.incrementAndGet();
            throw new AssertionError("Executor lookup must not initialize unrelated jobs");
        });
        job.setLazyInit(true); beans.registerBeanDefinition("unrelatedJob", job);
        var builder = CacheRuntimeOptions.builder();
        new CacheAsyncAutoConfiguration().cacheDbAsyncExecutorCustomizer(beans)
                .customize(builder, new CacheDbSpringProperties());
        assertSame(beans.getBean("first"), builder.build().asyncExecutor());
        assertEquals(0, jobCreations.get());
        assertFalse(beans.containsSingleton("hidden"));
        assertFalse(beans.containsSingleton("unrelatedJob"));
    }

    private static void registerExecutor(DefaultListableBeanFactory beans, String name, boolean primary, boolean eligible) {
        var definition = new RootBeanDefinition(Executor.class,
                () -> (Executor) task -> { throw new AssertionError("Binding cannot dispatch work"); });
        definition.setPrimary(primary); definition.setAutowireCandidate(eligible); definition.setLazyInit(true);
        beans.registerBeanDefinition(name, definition);
    }

    static final class UntypedJobFactory implements FactoryBean<Object> {
        public Object getObject() { throw new AssertionError("Executor lookup cannot create jobs"); }
        public Class<?> getObjectType() { return Object.class; }
    }

    private static CacheAsyncExecutor gate(CacheRuntimeOptions options) {
        return new CacheAsyncExecutor(options.asyncMaxConcurrent(), options.asyncExecutor(),
                TraceContexts.threadLocal(), Duration.ofSeconds(2));
    }

    private static void assertAmbiguous(Throwable failure) {
        assertNotNull(failure, "Ambiguous executor configuration must fail actual context startup");
        while (failure.getCause() != null) failure = failure.getCause();
        var ambiguous = assertInstanceOf(NoUniqueBeanDefinitionException.class, failure);
        assertEquals(Set.of("first", "second"), Set.copyOf(ambiguous.getBeanNamesFound()));
    }

    @AutoConfiguration(after = TaskExecutionAutoConfiguration.class)
    static class WarmSchedulerAutoConfiguration {
        @Bean(name = "cacheDbScheduledWarmTaskScheduler")
        ThreadPoolTaskScheduler scheduler(CacheDbSpringProperties properties) {
            return new CacheDatabaseSpringBootAutoConfiguration().cacheDbScheduledWarmTaskScheduler(properties);
        }
    }

    @AutoConfiguration(after = TaskExecutionAutoConfiguration.class)
    static class AliasedWarmSchedulerAutoConfiguration {
        @Bean(name = {"warmCanonical", "cacheDbScheduledWarmTaskScheduler", "warmAlias"})
        ThreadPoolTaskScheduler scheduler(CacheDbSpringProperties properties) {
            return new CacheDatabaseSpringBootAutoConfiguration().cacheDbScheduledWarmTaskScheduler(properties);
        }
    }

    @AutoConfiguration(after = TaskExecutionAutoConfiguration.class)
    static class PrimaryWarmSchedulerAutoConfiguration {
        @Bean(name = "cacheDbScheduledWarmTaskScheduler") @Primary
        ThreadPoolTaskScheduler scheduler(CacheDbSpringProperties properties) {
            return new CacheDatabaseSpringBootAutoConfiguration().cacheDbScheduledWarmTaskScheduler(properties);
        }
    }

    @AutoConfiguration(after = {CacheAsyncAutoConfiguration.class, TaskExecutionAutoConfiguration.class,
            CacheEncryptionAutoConfiguration.class, WarmSchedulerAutoConfiguration.class})
    @EnableConfigurationProperties(CacheDbSpringProperties.class)
    static class RuntimeOptionsAutoConfiguration {
        @Bean @ConditionalOnMissingBean(CacheRuntimeOptions.class)
        CacheRuntimeOptions options(CacheDbSpringProperties properties, ObjectProvider<CacheRuntimeOptionsCustomizer> customizers) {
            return new CacheDatabaseSpringBootAutoConfiguration().cacheRuntimeOptions(properties, customizers);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SingleExecutor {
        @Bean Executor first() { return task -> { throw new AssertionError("Binding cannot dispatch work"); }; }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoExecutors {
        @Bean Executor first() { return task -> { throw new AssertionError("Binding cannot dispatch work"); }; }
        @Bean Executor second() { return task -> { throw new AssertionError("Binding cannot dispatch work"); }; }
    }

    @Configuration(proxyBeanMethods = false)
    static class PrimaryExecutor {
        @Bean Executor first() { return task -> { throw new AssertionError("Binding cannot dispatch work"); }; }
        @Bean @Primary Executor second() { return task -> { throw new AssertionError("Binding cannot dispatch work"); }; }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoPrimaryExecutors {
        @Bean @Primary Executor first() { return task -> { throw new AssertionError("Binding cannot dispatch work"); }; }
        @Bean @Primary Executor second() { return task -> { throw new AssertionError("Binding cannot dispatch work"); }; }
    }

    @Configuration(proxyBeanMethods = false)
    static class ExplicitOptions {
        @Bean CacheRuntimeOptions explicitOptions(@Qualifier("first") Executor first) {
            return CacheRuntimeOptions.builder().asyncMaxConcurrent(7).asyncExecutor(first).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LateOptions {
        @Bean @Order(Ordered.LOWEST_PRECEDENCE)
        CacheRuntimeOptionsCustomizer lateExecutor() { return (builder, properties) -> builder.asyncExecutor(LATE_EXECUTOR); }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserScheduledExecutor {
        @Bean(destroyMethod = "shutdown")
        ScheduledExecutorService customScheduler() { return Executors.newSingleThreadScheduledExecutor(); }
    }

    @Configuration(proxyBeanMethods = false) @Profile("encryption")
    static class EncryptionProfile {
        @Bean CacheKeyProvider keys() {
            return new CacheKeyProvider() {
                public KeyMaterial current(String ring) { throw new AssertionError("Options binding cannot fetch keys"); }
                public Optional<KeyMaterial> byId(String ring, String id) { throw new AssertionError("Options binding cannot fetch keys"); }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PriorityExecutors {
        @Bean Executor first() { return new LowerPriorityExecutor(); }
        @Bean Executor second() { return new HigherPriorityExecutor(); }
    }
    @jakarta.annotation.Priority(20) static final class LowerPriorityExecutor implements Executor {
        public void execute(Runnable task) { throw new AssertionError("Binding cannot dispatch work"); }
    }
    @jakarta.annotation.Priority(10) static final class HigherPriorityExecutor implements Executor {
        public void execute(Runnable task) { throw new AssertionError("Binding cannot dispatch work"); }
    }
}
