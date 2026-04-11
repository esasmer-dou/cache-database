package com.reactor.cachedb.examples.demo;

import java.util.function.Consumer;

@FunctionalInterface
public interface DemoSeedBootstrapper {
    void seed(DemoSeedPayload payload);

    default void seed(DemoSeedPayload payload, Consumer<String> stageReporter) {
        if (stageReporter != null) {
            stageReporter.accept("Seed demo verisi hazirlaniyor");
        }
        seed(payload);
        if (stageReporter != null) {
            stageReporter.accept("Seed tamamlandi");
        }
    }
}
