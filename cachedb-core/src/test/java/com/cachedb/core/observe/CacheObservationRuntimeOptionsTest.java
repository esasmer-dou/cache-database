package com.reactor.cachedb.core.observe;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.config.CacheRuntimeOptions;
import org.junit.jupiter.api.Test;

class CacheObservationRuntimeOptionsTest {
    @Test void nativeHeaderSupplierDefaultsToNoneAndSurvivesCopies() {
        var defaults = CacheRuntimeOptions.defaults();
        assertSame(CacheTraceparentSupplier.NONE, defaults.observationTraceparentSupplier());
        CacheTraceparentSupplier supplier = () -> "00-0123456789abcdef0123456789abcdef-0123456789abcdef-00";
        var original = defaults.toBuilder().observationTraceparentSupplier(supplier).build();
        assertSame(supplier, original.toBuilder().build().observationTraceparentSupplier());
        assertSame(NoOpCacheObservationRecorder.INSTANCE, original.observationRecorder());
        assertThrows(NullPointerException.class, () -> CacheRuntimeOptions.builder().observationTraceparentSupplier(null));
    }

    @Test void defaultIsSingletonNoOpAndOptionsAreNotARecord() {
        assertSame(NoOpCacheObservationRecorder.INSTANCE, CacheRuntimeOptions.defaults().observationRecorder());
        assertFalse(CacheRuntimeOptions.class.isRecord());
    }

    @Test void toBuilderPreservesRecorderAndIndependentOptions() {
        CacheObservationRecorder recorder = operation -> { throw new AssertionError("Construction must not start observations"); };
        java.util.concurrent.Executor borrowed = Runnable::run;
        var original = CacheRuntimeOptions.builder().observationRecorder(recorder)
                .asyncMaxConcurrent(17).asyncExecutor(borrowed).build();
        var copy = original.toBuilder().build();
        assertSame(recorder, copy.observationRecorder());
        assertSame(original.redisReadCoalescing(), copy.redisReadCoalescing());
        assertSame(original.nearCacheInvalidation(), copy.nearCacheInvalidation());
        assertSame(borrowed, copy.asyncExecutor());
        assertEquals(17, copy.asyncMaxConcurrent());
        assertNotSame(original, copy);
        assertEquals("CacheRuntimeOptions", copy.toString());
    }

    @Test void nullIsRejectedAndChangingACopyDoesNotMutateOriginal() {
        assertThrows(NullPointerException.class, () -> CacheRuntimeOptions.builder().observationRecorder(null));
        CacheObservationRecorder recorder = operation -> NoOpCacheObservationRecorder.SCOPE;
        var original = CacheRuntimeOptions.builder().observationRecorder(recorder).build();
        var copy = original.toBuilder().observationRecorder(NoOpCacheObservationRecorder.INSTANCE).build();
        assertSame(recorder, original.observationRecorder());
        assertSame(NoOpCacheObservationRecorder.INSTANCE, copy.observationRecorder());
    }
}
