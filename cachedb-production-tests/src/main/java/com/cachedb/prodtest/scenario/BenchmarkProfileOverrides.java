package com.reactor.cachedb.prodtest.scenario;

public final class BenchmarkProfileOverrides {
    private static final int DURATION_OVERRIDE_SECONDS = Integer.getInteger("cachedb.prod.fullSuite.durationSeconds", -1);
    private static final int WORKER_OVERRIDE = Integer.getInteger("cachedb.prod.fullSuite.workerThreads", -1);
    private static final double DATASET_SCALE = Double.parseDouble(System.getProperty("cachedb.prod.fullSuite.datasetScale", "1.0"));
    private static final int MAX_CUSTOMERS = Integer.getInteger("cachedb.prod.fullSuite.maxCustomers", -1);
    private static final int MAX_PRODUCTS = Integer.getInteger("cachedb.prod.fullSuite.maxProducts", -1);
    private static final int MAX_HOT_PRODUCTS = Integer.getInteger("cachedb.prod.fullSuite.maxHotProducts", -1);
    private static final int MAX_HOT_ENTITY_LIMIT = Integer.getInteger("cachedb.prod.fullSuite.maxHotEntityLimit", -1);
    private static final int MAX_PAGE_SIZE = Integer.getInteger("cachedb.prod.fullSuite.maxPageSize", -1);

    private BenchmarkProfileOverrides() {
    }

    public static EcommerceScenarioProfile apply(EcommerceScenarioProfile profile) {
        int durationSeconds = DURATION_OVERRIDE_SECONDS > 0 ? DURATION_OVERRIDE_SECONDS : profile.durationSeconds();
        int workerThreads = WORKER_OVERRIDE > 0 ? WORKER_OVERRIDE : profile.workerThreads();
        int customerCount = cap(MAX_CUSTOMERS, scaledValue(profile.customerCount()));
        int productCount = cap(MAX_PRODUCTS, scaledValue(profile.productCount()));
        int hotProductSetSize = Math.max(1, cap(MAX_HOT_PRODUCTS, scaledValue(profile.hotProductSetSize())));
        int hotEntityLimit = Math.max(64, cap(MAX_HOT_ENTITY_LIMIT, scaledValue(profile.hotEntityLimit())));
        int pageSize = Math.max(10, cap(MAX_PAGE_SIZE, scaledValue(profile.pageSize())));
        return new EcommerceScenarioProfile(
                profile.name(),
                profile.kind(),
                profile.description(),
                profile.targetTransactionsPerSecond(),
                durationSeconds,
                workerThreads,
                customerCount,
                productCount,
                hotProductSetSize,
                profile.browsePercent(),
                profile.productLookupPercent(),
                profile.cartWritePercent(),
                profile.inventoryReservePercent(),
                profile.checkoutPercent(),
                profile.customerTouchPercent(),
                profile.writeBehindWorkerThreads(),
                profile.writeBehindBatchSize(),
                hotEntityLimit,
                pageSize,
                profile.entityTtlSeconds(),
                profile.pageTtlSeconds()
        );
    }

    private static int scaledValue(int value) {
        return Math.max(1, (int) Math.round(value * Math.max(0.0001d, DATASET_SCALE)));
    }

    private static int cap(int maxValue, int value) {
        return maxValue > 0 ? Math.min(maxValue, value) : value;
    }
}
