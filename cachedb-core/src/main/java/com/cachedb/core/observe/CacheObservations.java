package com.reactor.cachedb.core.observe;

import com.reactor.cachedb.core.repository.HotLookup;
import com.reactor.cachedb.core.route.RouteCacheContext;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** One operation lifetime, with independent caller/worker/completion bindings. */
public final class CacheObservations {
    private CacheObservations() {}
    public static CacheObservationRecorder.Scope start(CacheObservationRecorder recorder, CacheOperation operation, String entity) {
        CacheObservationRecorder.Scope scope = null;
        try {
            scope = Objects.requireNonNull(recorder.start(operation), "scope");
            scope.entity(entity);
            var route = RouteCacheContext.currentContract();
            if (route != null) scope.route(route.routeName());
            return scope;
        } catch (RuntimeException instrumentationFailure) {
            if (scope != null) close(scope);
            return NoOpCacheObservationRecorder.SCOPE;
        }
    }
    public static <V> V call(CacheObservationRecorder recorder, CacheOperation operation, String entity, Supplier<V> action) {
        return call(recorder, operation, entity, null, action);
    }
    public static <V> V call(CacheObservationRecorder recorder, CacheOperation operation, String entity,
            Object operationOwner, Supplier<V> action) {
        var scope = start(recorder, operation, entity);
        try (var binding = CacheObservationContext.attach(scope, operationOwner)) {
            V value = action.get();
            success(scope, operation, value);
            return value;
        } catch (RuntimeException | Error failure) {
            error(scope, failure); throw failure;
        } finally { close(scope); }
    }
    /** Returns the ORIGINAL future: cancellation, receipts and delegate completion semantics survive. */
    public static <V> CompletableFuture<V> callAsync(CacheObservationRecorder recorder, CacheOperation operation,
            String entity, Supplier<CompletableFuture<V>> action) {
        return callAsync(recorder, operation, entity, null, action);
    }
    public static <V> CompletableFuture<V> callAsync(CacheObservationRecorder recorder, CacheOperation operation,
            String entity, Object operationOwner, Supplier<CompletableFuture<V>> action) {
        var scope = start(recorder, operation, entity);
        CompletableFuture<V> future;
        try (var binding = CacheObservationContext.attach(scope, operationOwner)) {
            future = Objects.requireNonNull(action.get(), "async result");
        } catch (RuntimeException | Error failure) {
            error(scope, failure); close(scope); throw failure;
        }
        future.whenComplete((value, failure) -> {
            try (var binding = CacheObservationContext.attach(scope, operationOwner)) {
                if (failure == null) success(scope, operation, value);
                else error(scope, unwrap(failure));
            } finally { close(scope); }
        });
        return future;
    }
    private static Throwable unwrap(Throwable failure) {
        return (failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null
                ? failure.getCause() : failure;
    }
    public static void success(CacheObservationRecorder.Scope scope, CacheOperation operation, Object value) {
        Outcome outcome = Outcome.HIT;
        if (value instanceof HotLookup<?> hot) outcome = hot.isTombstoned() ? Outcome.TOMBSTONE : hot.isHit() ? Outcome.HIT : Outcome.MISS;
        else if (value instanceof Optional<?> optional && optional.isEmpty()
                || value instanceof Collection<?> collection && collection.isEmpty()
                || value instanceof Map<?, ?> map && map.isEmpty()
                || value instanceof com.reactor.cachedb.core.repository.CursorPage<?> page && page.isEmpty()
                || operation == CacheOperation.COUNT && value instanceof Number number && number.longValue() == 0) outcome = Outcome.MISS;
        try { scope.outcomeIfAbsent(outcome); } catch (RuntimeException ignored) { }
    }
    public static void error(CacheObservationRecorder.Scope scope, Throwable failure) {
        try { scope.error(failure); } catch (RuntimeException ignored) { }
    }
    public static void close(CacheObservationRecorder.Scope scope) {
        try { scope.close(); } catch (RuntimeException ignored) { }
    }
}
