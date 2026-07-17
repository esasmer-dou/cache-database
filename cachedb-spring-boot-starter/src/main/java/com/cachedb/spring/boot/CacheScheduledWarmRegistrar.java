package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.starter.CacheWarmPlan;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CacheScheduledWarmRegistrar implements SmartInitializingSingleton, DisposableBean {

    private static final Pattern SIMPLE_DURATION = Pattern.compile("^([0-9]+)(ms|s|m|h|d)?$", Pattern.CASE_INSENSITIVE);

    private final ConfigurableListableBeanFactory beanFactory;
    private final ThreadPoolTaskScheduler taskScheduler;
    private final CacheScheduledWarmCoordinator coordinator;
    private final List<ScheduledFuture<?>> scheduledTasks = new ArrayList<>();

    CacheScheduledWarmRegistrar(
            ConfigurableListableBeanFactory beanFactory,
            ThreadPoolTaskScheduler taskScheduler,
            CacheScheduledWarmCoordinator coordinator
    ) {
        this.beanFactory = beanFactory;
        this.taskScheduler = taskScheduler;
        this.coordinator = coordinator;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Set<String> registeredNames = new LinkedHashSet<>();
        for (String beanName : beanFactory.getBeanNamesForType(Object.class, false, false)) {
            Class<?> beanType = beanFactory.getType(beanName, false);
            if (beanType == null) {
                continue;
            }
            Map<Method, CacheScheduledWarm> methods = MethodIntrospector.selectMethods(
                    beanType,
                    (MethodIntrospector.MetadataLookup<CacheScheduledWarm>) method ->
                            AnnotatedElementUtils.findMergedAnnotation(method, CacheScheduledWarm.class)
            );
            if (methods.isEmpty()) {
                continue;
            }
            Object bean = beanFactory.getBean(beanName);
            for (Map.Entry<Method, CacheScheduledWarm> entry : methods.entrySet()) {
                register(bean, beanType, entry.getKey(), entry.getValue(), registeredNames);
            }
        }
    }

    private void register(
            Object bean,
            Class<?> beanType,
            Method method,
            CacheScheduledWarm annotation,
            Set<String> registeredNames
    ) {
        validateMethod(method);
        if (!resolveBoolean(annotation.enabledString(), method, "enabledString")) {
            return;
        }
        if ("-".equals(resolve(annotation.cron()))) {
            return;
        }
        CacheScheduledWarmDefinition definition = definition(beanType, method, annotation);
        if (!registeredNames.add(definition.jobName())) {
            throw new IllegalStateException("Duplicate @CacheScheduledWarm job name: " + definition.jobName());
        }
        Method invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
        ReflectionUtils.makeAccessible(invocable);
        Supplier<CacheWarmPlan> supplier = () -> invokePlan(bean, invocable, definition.jobName());
        coordinator.register(definition.jobName());
        ScheduledFuture<?> future = schedule(definition, () -> coordinator.execute(definition, supplier));
        if (future != null) {
            scheduledTasks.add(future);
        }
    }

    private ScheduledFuture<?> schedule(CacheScheduledWarmDefinition definition, Runnable task) {
        return switch (definition.scheduleKind()) {
            case CRON -> taskScheduler.schedule(
                    task,
                    new CronTrigger(
                            definition.cron(),
                            definition.zone().isBlank()
                                    ? ZoneId.systemDefault()
                                    : ZoneId.of(definition.zone())
                    )
            );
            case FIXED_DELAY -> taskScheduler.scheduleWithFixedDelay(
                    task,
                    Instant.now().plus(definition.initialDelay()),
                    definition.interval()
            );
            case FIXED_RATE -> taskScheduler.scheduleAtFixedRate(
                    task,
                    Instant.now().plus(definition.initialDelay()),
                    definition.interval()
            );
        };
    }

    private CacheScheduledWarmDefinition definition(
            Class<?> beanType,
            Method method,
            CacheScheduledWarm annotation
    ) {
        String configuredName = resolve(annotation.name());
        String jobName = configuredName.isBlank()
                ? beanType.getName() + "#" + method.getName()
                : configuredName;
        String cron = resolve(annotation.cron());
        String fixedDelay = resolve(annotation.fixedDelayString());
        String fixedRate = resolve(annotation.fixedRateString());
        int scheduleCount = present(cron) + present(fixedDelay) + present(fixedRate);
        if (scheduleCount != 1) {
            throw new IllegalStateException(
                    "@CacheScheduledWarm " + jobName
                            + " must define exactly one of cron, fixedDelayString, or fixedRateString"
            );
        }

        CacheScheduledWarmDefinition.ScheduleKind scheduleKind;
        Duration interval = Duration.ZERO;
        if (!cron.isBlank()) {
            scheduleKind = CacheScheduledWarmDefinition.ScheduleKind.CRON;
        } else if (!fixedDelay.isBlank()) {
            scheduleKind = CacheScheduledWarmDefinition.ScheduleKind.FIXED_DELAY;
            interval = positiveDuration(fixedDelay, jobName, "fixedDelayString");
        } else {
            scheduleKind = CacheScheduledWarmDefinition.ScheduleKind.FIXED_RATE;
            interval = positiveDuration(fixedRate, jobName, "fixedRateString");
        }

        Duration initialDelay = nonNegativeDuration(
                resolve(annotation.initialDelayString()),
                jobName,
                "initialDelayString"
        );
        Duration lockAtMostFor = positiveDuration(
                resolve(annotation.lockAtMostForString()),
                jobName,
                "lockAtMostForString"
        );
        if (lockAtMostFor.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalStateException(
                    "@CacheScheduledWarm " + jobName
                            + " lockAtMostForString must be at least one second so the lease can be renewed safely"
            );
        }
        Duration lockWaitTimeout = nonNegativeDuration(
                resolve(annotation.lockWaitTimeoutString()),
                jobName,
                "lockWaitTimeoutString"
        );
        Duration lockRetryInterval = positiveDuration(
                resolve(annotation.lockRetryIntervalString()),
                jobName,
                "lockRetryIntervalString"
        );
        String configuredMinimumInterval = resolve(annotation.minimumIntervalString());
        Duration minimumInterval = configuredMinimumInterval.isBlank()
                ? scheduleKind == CacheScheduledWarmDefinition.ScheduleKind.CRON ? Duration.ofSeconds(1) : interval
                : nonNegativeDuration(configuredMinimumInterval, jobName, "minimumIntervalString");

        if (annotation.reconcileHotSet() && annotation.mode() != CacheScheduledWarmMode.ENTITY_AND_PROJECTIONS) {
            throw new IllegalStateException(
                    "@CacheScheduledWarm " + jobName
                            + " can reconcile the hot set only in ENTITY_AND_PROJECTIONS mode"
            );
        }
        int reconcileMaxRowsPerRun = positiveInt(
                resolve(annotation.reconcileMaxRowsPerRunString()),
                jobName,
                "reconcileMaxRowsPerRunString",
                1_000_000
        );
        int reconcileScanCount = positiveInt(
                resolve(annotation.reconcileScanCountString()),
                jobName,
                "reconcileScanCountString",
                10_000
        );

        return new CacheScheduledWarmDefinition(
                jobName,
                scheduleKind,
                cron,
                resolve(annotation.zone()),
                interval,
                initialDelay,
                annotation.mode(),
                lockAtMostFor,
                lockWaitTimeout,
                lockRetryInterval,
                minimumInterval,
                annotation.reconcileHotSet(),
                reconcileMaxRowsPerRun,
                reconcileScanCount
        );
    }

    private void validateMethod(Method method) {
        if (method.getParameterCount() != 0) {
            throw new IllegalStateException("@CacheScheduledWarm method must not declare parameters: " + method);
        }
        if (!CacheWarmPlan.class.isAssignableFrom(method.getReturnType())) {
            throw new IllegalStateException("@CacheScheduledWarm method must return CacheWarmPlan: " + method);
        }
    }

    private CacheWarmPlan invokePlan(Object bean, Method method, String jobName) {
        try {
            return (CacheWarmPlan) method.invoke(bean);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Could not access @CacheScheduledWarm method for " + jobName, failure);
        } catch (InvocationTargetException failure) {
            Throwable target = failure.getTargetException();
            if (target instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("@CacheScheduledWarm plan method failed for " + jobName, target);
        }
    }

    private boolean resolveBoolean(String value, Method method, String fieldName) {
        String resolved = resolve(value);
        if ("true".equalsIgnoreCase(resolved)) {
            return true;
        }
        if ("false".equalsIgnoreCase(resolved)) {
            return false;
        }
        throw new IllegalStateException(
                "@CacheScheduledWarm " + fieldName + " must resolve to true or false for " + method
        );
    }

    private Duration positiveDuration(String value, String jobName, String fieldName) {
        Duration duration = parseDuration(value, jobName, fieldName);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalStateException(
                    "@CacheScheduledWarm " + jobName + " " + fieldName + " must be greater than zero"
            );
        }
        return duration;
    }

    private Duration nonNegativeDuration(String value, String jobName, String fieldName) {
        Duration duration = parseDuration(value, jobName, fieldName);
        if (duration.isNegative()) {
            throw new IllegalStateException(
                    "@CacheScheduledWarm " + jobName + " " + fieldName + " must not be negative"
            );
        }
        return duration;
    }

    private Duration parseDuration(String value, String jobName, String fieldName) {
        if (value == null || value.isBlank()) {
            return Duration.ZERO;
        }
        String normalized = value.trim();
        try {
            if (normalized.toUpperCase(Locale.ROOT).startsWith("P")) {
                return Duration.parse(normalized.toUpperCase(Locale.ROOT));
            }
            Matcher matcher = SIMPLE_DURATION.matcher(normalized);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("unsupported duration format");
            }
            long amount = Long.parseLong(matcher.group(1));
            String unit = matcher.group(2) == null ? "ms" : matcher.group(2).toLowerCase(Locale.ROOT);
            return switch (unit) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> throw new IllegalArgumentException("unsupported duration unit");
            };
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "@CacheScheduledWarm " + jobName + " " + fieldName
                            + " has invalid duration '" + normalized + "'",
                    failure
            );
        }
    }

    private int positiveInt(String value, String jobName, String fieldName, int maximum) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0 || parsed > maximum) {
                throw new IllegalArgumentException("value is outside the supported range");
            }
            return parsed;
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "@CacheScheduledWarm " + jobName + " " + fieldName
                            + " must resolve to an integer between 1 and " + maximum,
                    failure
            );
        }
    }

    private String resolve(String value) {
        String resolved = beanFactory.resolveEmbeddedValue(value == null ? "" : value);
        return resolved == null ? "" : resolved.trim();
    }

    private int present(String value) {
        return value == null || value.isBlank() ? 0 : 1;
    }

    @Override
    public void destroy() {
        for (ScheduledFuture<?> task : List.copyOf(scheduledTasks)) {
            task.cancel(false);
        }
        scheduledTasks.clear();
    }
}
