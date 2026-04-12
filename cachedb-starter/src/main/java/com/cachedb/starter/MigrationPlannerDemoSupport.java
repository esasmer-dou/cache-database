package com.reactor.cachedb.starter;

import java.util.List;
import java.util.Objects;

public interface MigrationPlannerDemoSupport {

    Descriptor descriptor();

    BootstrapResult bootstrap(BootstrapRequest request);

    record Descriptor(
            boolean available,
            String title,
            String summary,
            int defaultCustomerCount,
            int defaultHotCustomerCount,
            int defaultMaxOrdersPerCustomer,
            MigrationPlanner.Request plannerDefaults
    ) {
        public Descriptor {
            title = title == null ? "" : title;
            summary = summary == null ? "" : summary;
            plannerDefaults = Objects.requireNonNullElseGet(plannerDefaults, MigrationPlanner.Request::defaults);
        }
    }

    record BootstrapRequest(
            int customerCount,
            int hotCustomerCount,
            int maxOrdersPerCustomer
    ) {
        public BootstrapRequest normalize() {
            int normalizedCustomers = Math.max(10, customerCount);
            int normalizedHotCustomers = Math.max(1, Math.min(normalizedCustomers, hotCustomerCount));
            int normalizedMaxOrders = Math.max(50, maxOrdersPerCustomer);
            return new BootstrapRequest(normalizedCustomers, normalizedHotCustomers, normalizedMaxOrders);
        }
    }

    record BootstrapResult(
            String rootSurface,
            String childSurface,
            String rootTable,
            String childTable,
            List<String> viewNames,
            long customerCount,
            long orderCount,
            long hottestCustomerOrderCount,
            List<String> sampleRootIds,
            List<String> notes,
            MigrationPlanner.Request plannerDefaults
    ) {
        public BootstrapResult {
            viewNames = viewNames == null ? List.of() : List.copyOf(viewNames);
            sampleRootIds = sampleRootIds == null ? List.of() : List.copyOf(sampleRootIds);
            notes = notes == null ? List.of() : List.copyOf(notes);
            plannerDefaults = Objects.requireNonNullElseGet(plannerDefaults, MigrationPlanner.Request::defaults);
            rootSurface = rootSurface == null ? "" : rootSurface;
            childSurface = childSurface == null ? "" : childSurface;
            rootTable = rootTable == null ? "" : rootTable;
            childTable = childTable == null ? "" : childTable;
        }
    }
}
