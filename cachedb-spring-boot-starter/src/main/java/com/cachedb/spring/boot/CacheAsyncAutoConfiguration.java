package com.reactor.cachedb.spring.boot;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.OrderComparator;

/** Executor selection is a runtime option, never an extra component in the base configuration record. */
@AutoConfiguration(before = CacheDatabaseSpringBootAutoConfiguration.class)
public class CacheAsyncAutoConfiguration {
    @Bean
    CacheRuntimeOptionsCustomizer cacheDbAsyncExecutorCustomizer(ConfigurableListableBeanFactory beans) {
        return new ExecutorCustomizer(beans);
    }

    private static final class ExecutorCustomizer implements CacheRuntimeOptionsCustomizer, Ordered {
        private static final String WARM_SCHEDULER = "cacheDbScheduledWarmTaskScheduler";
        private final ConfigurableListableBeanFactory beans;
        private ExecutorCustomizer(ConfigurableListableBeanFactory beans) { this.beans = beans; }
        @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
        @Override public void customize(com.reactor.cachedb.core.config.CacheRuntimeOptions.Builder builder,
                CacheDbSpringProperties properties) {
            Executor selected = selectExecutor();
            if (selected != null) builder.asyncExecutor(selected);
        }

        private Executor selectExecutor() {
            var candidates = new ArrayList<String>();
            for (String name : BeanFactoryUtils.beanNamesForTypeIncludingAncestors(beans, Executor.class, true, false)) {
                if (isWarmScheduler(name)) continue;
                BeanDefinition definition = definition(name);
                if (definition == null || definition.isAutowireCandidate()) candidates.add(name);
            }
            if (candidates.isEmpty()) return null;
            if (candidates.size() == 1) return beans.getBean(candidates.getFirst(), Executor.class);

            String primary = null;
            for (String name : candidates) {
                BeanDefinition definition = definition(name);
                if (definition == null || !definition.isPrimary()) continue;
                if (primary != null) {
                    boolean local = beans.containsBeanDefinition(name);
                    boolean previousLocal = beans.containsBeanDefinition(primary);
                    if (local == previousLocal) throw ambiguous(candidates);
                    if (local) primary = name;
                } else primary = name;
            }
            if (primary != null) return beans.getBean(primary, Executor.class);

            // Retain Spring's priority tie-breaking, but only within the filtered candidates.
            String preferred = null;
            Integer priority = null;
            if (beans instanceof DefaultListableBeanFactory factory
                    && factory.getDependencyComparator() instanceof OrderComparator comparator) {
                for (String name : candidates) {
                    Object value = beans.containsSingleton(name) ? beans.getBean(name) : beans.getType(name, false);
                    Integer current = value == null ? null : comparator.getPriority(value);
                    if (current == null) continue;
                    if (priority != null && current.equals(priority)) throw ambiguous(candidates);
                    if (priority == null || current < priority) { priority = current; preferred = name; }
                }
            }
            if (preferred != null) return beans.getBean(preferred, Executor.class);
            throw ambiguous(candidates);
        }

        private boolean isWarmScheduler(String name) {
            if (WARM_SCHEDULER.equals(name)) return true;
            for (String alias : beans.getAliases(name)) if (WARM_SCHEDULER.equals(alias)) return true;
            return false;
        }

        private BeanDefinition definition(String name) {
            ConfigurableListableBeanFactory owner = beans;
            while (!owner.containsLocalBean(name)
                    && owner.getParentBeanFactory() instanceof ConfigurableListableBeanFactory parent) owner = parent;
            return owner.containsBeanDefinition(name) ? owner.getMergedBeanDefinition(name) : null;
        }

        private static NoUniqueBeanDefinitionException ambiguous(List<String> candidates) {
            return new NoUniqueBeanDefinitionException(Executor.class, candidates);
        }
    }
}
