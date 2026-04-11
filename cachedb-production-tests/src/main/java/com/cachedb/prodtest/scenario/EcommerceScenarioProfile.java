package com.reactor.cachedb.prodtest.scenario;

public record EcommerceScenarioProfile(
        String name,
        EcommerceScenarioKind kind,
        String description,
        int targetTransactionsPerSecond,
        int durationSeconds,
        int workerThreads,
        int customerCount,
        int productCount,
        int hotProductSetSize,
        int browsePercent,
        int productLookupPercent,
        int cartWritePercent,
        int inventoryReservePercent,
        int checkoutPercent,
        int customerTouchPercent,
        int writeBehindWorkerThreads,
        int writeBehindBatchSize,
        int hotEntityLimit,
        int pageSize,
        int entityTtlSeconds,
        int pageTtlSeconds
) {
    public int totalWeight() {
        return browsePercent
                + productLookupPercent
                + cartWritePercent
                + inventoryReservePercent
                + checkoutPercent
                + customerTouchPercent;
    }
}
