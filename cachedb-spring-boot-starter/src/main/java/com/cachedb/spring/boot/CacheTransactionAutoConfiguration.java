package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.annotations.CacheTransactional;
import com.reactor.cachedb.core.api.*;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.CacheTransactionTemplate;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.*;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;

/** Annotations are resolved once at startup, never scanned for each mutation. */
@AutoConfiguration(after = CacheDatabaseSpringBootAutoConfiguration.class)
@ConditionalOnClass(ProxyFactory.class)
public class CacheTransactionAutoConfiguration {
    @Bean @ConditionalOnMissingBean
    CacheTransactionTemplate cacheTransactionTemplate(CacheDatabase database) { return new CacheTransactionTemplate(database); }

    @Bean
    static BeanPostProcessor cacheTransactionBoundaries(ObjectProvider<CacheDatabase> databases) {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                Class<?> type = org.springframework.aop.support.AopUtils.getTargetClass(bean);
                if (type.getName().startsWith("com.reactor.cachedb.")) return bean;
                var definitions = new HashMap<Method, CacheTransactional>();
                var classDefinition = AnnotatedElementUtils.findMergedAnnotation(type, CacheTransactional.class);
                for (Method method : ReflectionUtils.getUniqueDeclaredMethods(type)) {
                    var definition = AnnotatedElementUtils.findMergedAnnotation(method, CacheTransactional.class);
                    if (definition == null) definition = classDefinition;
                    if (definition == null || !Modifier.isPublic(method.getModifiers())
                            || Modifier.isStatic(method.getModifiers()) || method.getDeclaringClass() == Object.class) continue;
                    if (Modifier.isFinal(method.getModifiers()) || Modifier.isFinal(type.getModifiers()))
                        throw new IllegalStateException("@CacheTransactional requires nonfinal public service methods: " + method);
                    if (definition.timeoutMillis() < 1 || definition.timeoutMillis() > 300_000)
                        throw new IllegalArgumentException("Transaction timeout must be 1..300000ms");
                    if (java.util.concurrent.Future.class.isAssignableFrom(method.getReturnType())
                            || java.util.concurrent.CompletionStage.class.isAssignableFrom(method.getReturnType())
                            || java.util.stream.BaseStream.class.isAssignableFrom(method.getReturnType()))
                        throw new IllegalStateException("CacheDB units of work are synchronous, not deferred: " + method);
                    definitions.put(method, definition);
                }
                if (definitions.isEmpty()) return bean;
                for (Class<?> contract : org.springframework.util.ClassUtils.getAllInterfacesForClass(type)) {
                    for (Method method : contract.getMethods()) {
                        var implementation = ReflectionUtils.findMethod(type, method.getName(), method.getParameterTypes());
                        var definition = definitions.get(implementation);
                        if (definition != null) definitions.put(method, definition);
                    }
                }
                MethodInterceptor boundary = invocation -> {
                    var definition = definitions.get(invocation.getMethod());
                    if (definition == null) return invocation.proceed();
                    var database = databases.getObject();
                    Object result; CacheWriteBatchReceipt receipt;
                    try (var work = CacheUnitOfWork.begin(database.entityRegistry(), database.writeBatch())) {
                        result = invocation.proceed(); receipt = work.commit();
                    }
                    if (definition.awaitDurable() && !database.awaitDurable(receipt, Duration.ofMillis(definition.timeoutMillis())))
                        throw new com.reactor.cachedb.core.repository.CacheTransactionDurabilityTimeoutException(receipt, Duration.ofMillis(definition.timeoutMillis()));
                    return invocation.getMethod().getReturnType() == CacheWriteBatchReceipt.class ? receipt : result;
                };
                if (bean instanceof org.springframework.aop.framework.Advised advised) {
                    if (advised.isFrozen()) throw new IllegalStateException("Cannot attach @CacheTransactional to a frozen proxy: " + name);
                    advised.addAdvice(boundary); return bean;
                }
                var factory = new ProxyFactory(bean); factory.setProxyTargetClass(true);
                factory.addAdvice(boundary);
                return factory.getProxy(type.getClassLoader());
            }
        };
    }
}
