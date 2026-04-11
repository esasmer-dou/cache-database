package com.reactor.cachedb.examples.demo;

import com.reactor.cachedb.core.cache.CachePolicy;

import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

public final class DemoScenarioTuning {

    public static final String DEFAULT_PREFIX = "cachedb.demo.";

    private final int cacheHotEntityLimit;
    private final int cachePageSize;
    private final long cacheEntityTtlSeconds;
    private final long cachePageTtlSeconds;
    private final int customerSeedCount;
    private final int productSeedCount;
    private final int cartSeedCount;
    private final int orderSeedCount;
    private final int orderLineSeedCount;
    private final int entityViewPageSize;
    private final int repositoryCountPageSize;
    private final int readerPageSize;
    private final int readerPageWindowVariants;
    private final int bulkCustomerReadPageSize;
    private final int topCustomerOrderReadLimit;
    private final int highLineOrderReadLimit;
    private final int highLineOrderThreshold;
    private final int bulkProductReadSize;
    private final int maxOrderLineQuantity;
    private final int maxOrderLineItemCount;
    private final int maxCartQuantity;
    private final long stopAwaitTerminationMillis;
    private final long errorBackoffMillis;
    private final int httpWorkerThreads;
    private final int uiAutoRefreshMillis;
    private final DemoLoadProfile low;
    private final DemoLoadProfile medium;
    private final DemoLoadProfile high;

    private DemoScenarioTuning(
            int cacheHotEntityLimit,
            int cachePageSize,
            long cacheEntityTtlSeconds,
            long cachePageTtlSeconds,
            int customerSeedCount,
            int productSeedCount,
            int cartSeedCount,
            int orderSeedCount,
            int orderLineSeedCount,
            int entityViewPageSize,
            int repositoryCountPageSize,
            int readerPageSize,
            int readerPageWindowVariants,
            int bulkCustomerReadPageSize,
            int topCustomerOrderReadLimit,
            int highLineOrderReadLimit,
            int highLineOrderThreshold,
            int bulkProductReadSize,
            int maxOrderLineQuantity,
            int maxOrderLineItemCount,
            int maxCartQuantity,
            long stopAwaitTerminationMillis,
            long errorBackoffMillis,
            int httpWorkerThreads,
            int uiAutoRefreshMillis,
            DemoLoadProfile low,
            DemoLoadProfile medium,
            DemoLoadProfile high
    ) {
        this.cacheHotEntityLimit = Math.max(1, cacheHotEntityLimit);
        this.cachePageSize = Math.max(1, cachePageSize);
        this.cacheEntityTtlSeconds = Math.max(0L, cacheEntityTtlSeconds);
        this.cachePageTtlSeconds = Math.max(0L, cachePageTtlSeconds);
        this.customerSeedCount = Math.max(1, customerSeedCount);
        this.productSeedCount = Math.max(1, productSeedCount);
        this.cartSeedCount = Math.max(1, cartSeedCount);
        this.orderSeedCount = Math.max(1, orderSeedCount);
        this.orderLineSeedCount = Math.max(this.orderSeedCount, orderLineSeedCount);
        this.entityViewPageSize = Math.max(1, entityViewPageSize);
        this.repositoryCountPageSize = Math.max(1, repositoryCountPageSize);
        this.readerPageSize = Math.max(1, readerPageSize);
        this.readerPageWindowVariants = Math.max(1, readerPageWindowVariants);
        this.bulkCustomerReadPageSize = Math.max(1, bulkCustomerReadPageSize);
        this.topCustomerOrderReadLimit = Math.max(1, topCustomerOrderReadLimit);
        this.highLineOrderReadLimit = Math.max(1, highLineOrderReadLimit);
        this.highLineOrderThreshold = Math.max(1, highLineOrderThreshold);
        this.bulkProductReadSize = Math.max(1, bulkProductReadSize);
        this.maxOrderLineQuantity = Math.max(1, maxOrderLineQuantity);
        this.maxOrderLineItemCount = Math.max(1, maxOrderLineItemCount);
        this.maxCartQuantity = Math.max(1, maxCartQuantity);
        this.stopAwaitTerminationMillis = Math.max(1L, stopAwaitTerminationMillis);
        this.errorBackoffMillis = Math.max(1L, errorBackoffMillis);
        this.httpWorkerThreads = Math.max(1, httpWorkerThreads);
        this.uiAutoRefreshMillis = Math.max(0, uiAutoRefreshMillis);
        this.low = Objects.requireNonNull(low, "low");
        this.medium = Objects.requireNonNull(medium, "medium");
        this.high = Objects.requireNonNull(high, "high");
    }

    public static DemoScenarioTuning defaults() {
        return fromProperties(new Properties(), DEFAULT_PREFIX);
    }

    public static DemoScenarioTuning fromSystemProperties(String prefix) {
        return fromProperties(System.getProperties(), prefix);
    }

    public static DemoScenarioTuning fromProperties(Properties properties, String prefix) {
        Lookup lookup = new Lookup(properties, prefix);
        return new DemoScenarioTuning(
                lookup.integer("cache.hotEntityLimit", 1_600),
                lookup.integer("cache.pageSize", 64),
                lookup.longValue("cache.entityTtlSeconds", 900L),
                lookup.longValue("cache.pageTtlSeconds", 180L),
                lookup.integer("seed.customers", 1_800),
                lookup.integer("seed.products", 1_400),
                lookup.integer("seed.carts", 4_500),
                lookup.integer("seed.orders", 3_600),
                lookup.integer("seed.orderLines", 54_000),
                lookup.integer("view.pageSize", 18),
                lookup.integer("view.countPageSize", 6_000),
                lookup.integer("view.readerPageSize", 64),
                lookup.integer("view.readerPageWindowVariants", 20),
                lookup.integer("read.bulkCustomerPageSize", 192),
                lookup.integer("read.topCustomerOrderLimit", 160),
                lookup.integer("read.highLineOrderLimit", 120),
                lookup.integer("read.highLineOrderThreshold", 40),
                lookup.integer("read.bulkProductSize", 120),
                lookup.integer("write.maxOrderLineQuantity", 24),
                lookup.integer("write.maxOrderLineItemCount", 160),
                lookup.integer("write.maxCartQuantity", 48),
                lookup.longValue("stop.awaitTerminationMillis", 5_000L),
                lookup.longValue("error.backoffMillis", 50L),
                lookup.integer("ui.workerThreads", 2),
                lookup.integer("ui.autoRefreshMillis", 3_000),
                profile(lookup, DemoScenarioLevel.LOW),
                profile(lookup, DemoScenarioLevel.MEDIUM),
                profile(lookup, DemoScenarioLevel.HIGH)
        );
    }

    private static DemoLoadProfile profile(Lookup lookup, DemoScenarioLevel level) {
        String prefix = "load." + level.propertyKey() + ".";
        return new DemoLoadProfile(
                lookup.integer(prefix + "readers", level.defaultReaderThreads()),
                lookup.integer(prefix + "writers", level.defaultWriterThreads()),
                lookup.longValue(prefix + "readerPauseMillis", level.defaultReaderPauseMillis()),
                lookup.longValue(prefix + "writerPauseMillis", level.defaultWriterPauseMillis())
        );
    }

    public CachePolicy cachePolicy() {
        return CachePolicy.builder()
                .hotEntityLimit(cacheHotEntityLimit)
                .pageSize(cachePageSize)
                .entityTtlSeconds(cacheEntityTtlSeconds)
                .pageTtlSeconds(cachePageTtlSeconds)
                .build();
    }

    public DemoLoadProfile profile(DemoScenarioLevel level) {
        return switch (level) {
            case LOW -> low;
            case MEDIUM -> medium;
            case HIGH -> high;
        };
    }

    public String seedSummary() {
        return String.format(
                Locale.ROOT,
                "%d musteri, %d urun, %d sepet, %d siparis ve %d siparis satiri olmak uzere toplam %d kayit seedlenir",
                customerSeedCount,
                productSeedCount,
                cartSeedCount,
                orderSeedCount,
                orderLineSeedCount,
                customerSeedCount + productSeedCount + cartSeedCount + orderSeedCount + orderLineSeedCount
        );
    }

    public int cacheHotEntityLimit() {
        return cacheHotEntityLimit;
    }

    public int cachePageSize() {
        return cachePageSize;
    }

    public long cacheEntityTtlSeconds() {
        return cacheEntityTtlSeconds;
    }

    public long cachePageTtlSeconds() {
        return cachePageTtlSeconds;
    }

    public int customerSeedCount() {
        return customerSeedCount;
    }

    public int productSeedCount() {
        return productSeedCount;
    }

    public int cartSeedCount() {
        return cartSeedCount;
    }

    public int orderSeedCount() {
        return orderSeedCount;
    }

    public int orderLineSeedCount() {
        return orderLineSeedCount;
    }

    public int entityViewPageSize() {
        return entityViewPageSize;
    }

    public int repositoryCountPageSize() {
        return repositoryCountPageSize;
    }

    public int readerPageSize() {
        return readerPageSize;
    }

    public int readerPageWindowVariants() {
        return readerPageWindowVariants;
    }

    public int bulkCustomerReadPageSize() {
        return bulkCustomerReadPageSize;
    }

    public int topCustomerOrderReadLimit() {
        return topCustomerOrderReadLimit;
    }

    public int highLineOrderReadLimit() {
        return highLineOrderReadLimit;
    }

    public int highLineOrderThreshold() {
        return highLineOrderThreshold;
    }

    public int bulkProductReadSize() {
        return bulkProductReadSize;
    }

    public int maxOrderLineQuantity() {
        return maxOrderLineQuantity;
    }

    public int maxOrderLineItemCount() {
        return maxOrderLineItemCount;
    }

    public int maxCartQuantity() {
        return maxCartQuantity;
    }

    public long stopAwaitTerminationMillis() {
        return stopAwaitTerminationMillis;
    }

    public long errorBackoffMillis() {
        return errorBackoffMillis;
    }

    public int httpWorkerThreads() {
        return httpWorkerThreads;
    }

    public int uiAutoRefreshMillis() {
        return uiAutoRefreshMillis;
    }

    public DemoLoadProfile low() {
        return low;
    }

    public DemoLoadProfile medium() {
        return medium;
    }

    public DemoLoadProfile high() {
        return high;
    }

    private static final class Lookup {
        private final Properties properties;
        private final String prefix;

        private Lookup(Properties properties, String prefix) {
            this.properties = Objects.requireNonNull(properties, "properties");
            if (prefix == null || prefix.isBlank()) {
                this.prefix = "";
            } else {
                this.prefix = prefix.endsWith(".") ? prefix : prefix + ".";
            }
        }

        private String string(String key) {
            String value = properties.getProperty(prefix + key);
            return value == null ? null : value.trim();
        }

        private int integer(String key, int defaultValue) {
            String value = string(key);
            return value == null || value.isEmpty() ? defaultValue : Integer.parseInt(value);
        }

        private long longValue(String key, long defaultValue) {
            String value = string(key);
            return value == null || value.isEmpty() ? defaultValue : Long.parseLong(value);
        }
    }
}
