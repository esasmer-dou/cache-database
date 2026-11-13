package com.reactor.cachedb.spring.boot;

/** Test-only ownership cleanup; one failure must not strand the remaining resources. */
final class FixtureResources {
    private FixtureResources() {}

    static void close(AutoCloseable... resources) throws Exception {
        Throwable failure = null;
        for (var resource : resources) {
            if (resource == null) continue;
            try {
                resource.close();
            } catch (Exception | Error cleanup) {
                if (failure == null) failure = cleanup;
                else if (failure != cleanup) failure.addSuppressed(cleanup);
            }
        }
        if (failure instanceof Exception checked) throw checked;
        if (failure instanceof Error fatal) throw fatal;
    }

    static void closeOnFailure(Throwable failure, AutoCloseable... resources) {
        try {
            close(resources);
        } catch (Exception | Error cleanup) {
            if (failure != cleanup) failure.addSuppressed(cleanup);
        }
    }
}
