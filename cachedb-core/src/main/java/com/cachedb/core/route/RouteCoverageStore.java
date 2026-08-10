package com.reactor.cachedb.core.route;

import java.time.Duration;
import java.time.Instant;

public interface RouteCoverageStore {
    RouteCoverage get(String routeName, String scope, Duration maxAge);

    void markWarming(String routeName, String scope, Duration ttl);

    void markComplete(String routeName, String scope, long sourceRows, long submittedRows, Duration ttl);

    void markPartial(String routeName, String scope, long sourceRows, long submittedRows, String detail, Duration ttl);

    void markFailed(String routeName, String scope, String detail, Duration ttl);

    static RouteCoverageStore noop() {
        return NoOpHolder.INSTANCE;
    }

    final class NoOpHolder {
        private static final RouteCoverageStore INSTANCE = new RouteCoverageStore() {
            @Override
            public RouteCoverage get(String routeName, String scope, Duration maxAge) {
                return RouteCoverage.notWarmed(routeName, scope);
            }

            @Override
            public void markWarming(String routeName, String scope, Duration ttl) {
            }

            @Override
            public void markComplete(String routeName, String scope, long sourceRows, long submittedRows, Duration ttl) {
            }

            @Override
            public void markPartial(String routeName, String scope, long sourceRows, long submittedRows, String detail, Duration ttl) {
            }

            @Override
            public void markFailed(String routeName, String scope, String detail, Duration ttl) {
            }
        };

        private NoOpHolder() {
        }
    }
}
