package com.reactor.cachedb.core.api;

import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.repository.CursorPage;
import com.reactor.cachedb.core.repository.WindowRequest;

/** Explicit Redis score/UTF-8-member capability, not a typed/source seek fallback. */
public interface RedisLexicalWindowRepository<T> {
    CursorPage<T> queryRedisLexicalWindow(QuerySpec base, WindowRequest window, String compositeIndex,
            String routeName, String scope, Double afterScore, String afterId);

    /** Additive exact-Long capability. Other implementations retain their existing behavior. */
    default CursorPage<T> queryRedisLongLexicalWindow(QuerySpec base, WindowRequest window, String compositeIndex,
            String routeName, String scope, Long afterValue, String afterId) {
        throw new UnsupportedOperationException("Repository does not expose exact Long lexical windows");
    }

    @SuppressWarnings("unchecked")
    static <T> CursorPage<T> readLong(EntityRepository<T, ?> repository, QuerySpec base, WindowRequest window,
            String compositeIndex, String routeName, String scope, Long afterValue, String afterId) {
        if (!(repository instanceof RedisLexicalWindowRepository<?> capability))
            throw new UnsupportedOperationException("Repository does not expose the Redis lexical window capability");
        return ((RedisLexicalWindowRepository<T>) capability).queryRedisLongLexicalWindow(
                base, window, compositeIndex, routeName, scope, afterValue, afterId);
    }

    @SuppressWarnings("unchecked")
    static <T> CursorPage<T> read(EntityRepository<T, ?> repository, QuerySpec base, WindowRequest window,
            String compositeIndex, String routeName, String scope, Double afterScore, String afterId) {
        if (!(repository instanceof RedisLexicalWindowRepository<?> capability))
            throw new UnsupportedOperationException("Repository does not expose the Redis lexical window capability");
        return ((RedisLexicalWindowRepository<T>) capability).queryRedisLexicalWindow(
                base, window, compositeIndex, routeName, scope, afterScore, afterId);
    }
}
