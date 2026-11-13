package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.async.CacheAsyncExecutor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Contextual nonblocking continuation with per-waiter cancellation, never shared-I/O cancellation. */
final class RedisAsyncContinuation {
    static <A, B> CompletableFuture<B> compose(CacheAsyncExecutor gate, CompletableFuture<A> first,
            Function<A, CompletableFuture<B>> next) {
        var result = new Relay<B>();
        result.install(first);
        final class Step {
            A value;
            Throwable error;
            CompletableFuture<B> following;
            final Runnable contextual = gate.contextual(() -> {
                if (!result.isDone() && error == null) following = java.util.Objects.requireNonNull(next.apply(value));
            });
            void completed(A response, Throwable problem) {
                value = response;
                error = problem;
                try {
                    if (error == null) contextual.run();
                } catch (Throwable failure) { error = failure; }
                finally { value = null; }
                // User callbacks do not run inside the captured tenant/trace scope.
                if (error != null) {
                    result.active.compareAndSet(first, null);
                    result.completeExceptionally(unwrap(error));
                }
                else if (following != null) {
                    var current = following;
                    following = null;
                    result.install(current);
                    current.whenComplete((answer, failure) -> {
                        result.active.compareAndSet(current, null);
                        if (failure == null) result.complete(answer);
                        else result.completeExceptionally(unwrap(failure));
                    });
                }
            }
        }
        var step = new Step();
        first.whenComplete(step::completed);
        return result;
    }

    private static final class Relay<T> extends CompletableFuture<T> {
        final AtomicReference<CompletableFuture<?>> active = new AtomicReference<>();
        void install(CompletableFuture<?> stage) {
            active.set(stage);
            if (isCancelled()) stage.cancel(false);
        }
        @Override public boolean cancel(boolean interrupt) {
            if (isDone()) return isCancelled();
            CompletableFuture<?> stage = active.get();
            if (stage != null) stage.cancel(false);
            return super.cancel(false);
        }
    }
    private static Throwable unwrap(Throwable problem) {
        while ((problem instanceof java.util.concurrent.CompletionException
                || problem instanceof java.util.concurrent.ExecutionException) && problem.getCause() != null)
            problem = problem.getCause();
        return problem;
    }
    private RedisAsyncContinuation() {}
}
