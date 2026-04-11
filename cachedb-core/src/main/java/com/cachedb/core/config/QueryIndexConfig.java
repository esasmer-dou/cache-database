package com.reactor.cachedb.core.config;

public record QueryIndexConfig(
        boolean exactIndexEnabled,
        boolean rangeIndexEnabled,
        boolean prefixIndexEnabled,
        boolean textIndexEnabled,
        boolean plannerStatisticsEnabled,
        boolean plannerStatisticsPersisted,
        long plannerStatisticsTtlMillis,
        int plannerStatisticsSampleSize,
        boolean learnedStatisticsEnabled,
        double learnedStatisticsWeight,
        boolean cacheWarmingEnabled,
        int rangeHistogramBuckets,
        int prefixMaxLength,
        int textTokenMinLength,
        int textTokenMaxLength,
        int textMaxTokensPerValue
) {
    public static Builder builder() {
        return new Builder();
    }

    public static QueryIndexConfig defaults() {
        return builder().build();
    }

    public static final class Builder {
        private boolean exactIndexEnabled = true;
        private boolean rangeIndexEnabled = true;
        private boolean prefixIndexEnabled = true;
        private boolean textIndexEnabled = true;
        private boolean plannerStatisticsEnabled = true;
        private boolean plannerStatisticsPersisted = true;
        private long plannerStatisticsTtlMillis = 60_000;
        private int plannerStatisticsSampleSize = 32;
        private boolean learnedStatisticsEnabled = true;
        private double learnedStatisticsWeight = 0.35d;
        private boolean cacheWarmingEnabled = true;
        private int rangeHistogramBuckets = 8;
        private int prefixMaxLength = 12;
        private int textTokenMinLength = 2;
        private int textTokenMaxLength = 32;
        private int textMaxTokensPerValue = 16;

        public Builder exactIndexEnabled(boolean exactIndexEnabled) {
            this.exactIndexEnabled = exactIndexEnabled;
            return this;
        }

        public Builder rangeIndexEnabled(boolean rangeIndexEnabled) {
            this.rangeIndexEnabled = rangeIndexEnabled;
            return this;
        }

        public Builder prefixIndexEnabled(boolean prefixIndexEnabled) {
            this.prefixIndexEnabled = prefixIndexEnabled;
            return this;
        }

        public Builder textIndexEnabled(boolean textIndexEnabled) {
            this.textIndexEnabled = textIndexEnabled;
            return this;
        }

        public Builder plannerStatisticsEnabled(boolean plannerStatisticsEnabled) {
            this.plannerStatisticsEnabled = plannerStatisticsEnabled;
            return this;
        }

        public Builder plannerStatisticsPersisted(boolean plannerStatisticsPersisted) {
            this.plannerStatisticsPersisted = plannerStatisticsPersisted;
            return this;
        }

        public Builder plannerStatisticsTtlMillis(long plannerStatisticsTtlMillis) {
            this.plannerStatisticsTtlMillis = plannerStatisticsTtlMillis;
            return this;
        }

        public Builder plannerStatisticsSampleSize(int plannerStatisticsSampleSize) {
            this.plannerStatisticsSampleSize = plannerStatisticsSampleSize;
            return this;
        }

        public Builder learnedStatisticsEnabled(boolean learnedStatisticsEnabled) {
            this.learnedStatisticsEnabled = learnedStatisticsEnabled;
            return this;
        }

        public Builder learnedStatisticsWeight(double learnedStatisticsWeight) {
            this.learnedStatisticsWeight = learnedStatisticsWeight;
            return this;
        }

        public Builder cacheWarmingEnabled(boolean cacheWarmingEnabled) {
            this.cacheWarmingEnabled = cacheWarmingEnabled;
            return this;
        }

        public Builder rangeHistogramBuckets(int rangeHistogramBuckets) {
            this.rangeHistogramBuckets = rangeHistogramBuckets;
            return this;
        }

        public Builder prefixMaxLength(int prefixMaxLength) {
            this.prefixMaxLength = prefixMaxLength;
            return this;
        }

        public Builder textTokenMinLength(int textTokenMinLength) {
            this.textTokenMinLength = textTokenMinLength;
            return this;
        }

        public Builder textTokenMaxLength(int textTokenMaxLength) {
            this.textTokenMaxLength = textTokenMaxLength;
            return this;
        }

        public Builder textMaxTokensPerValue(int textMaxTokensPerValue) {
            this.textMaxTokensPerValue = textMaxTokensPerValue;
            return this;
        }

        public QueryIndexConfig build() {
            return new QueryIndexConfig(
                    exactIndexEnabled,
                    rangeIndexEnabled,
                    prefixIndexEnabled,
                    textIndexEnabled,
                    plannerStatisticsEnabled,
                    plannerStatisticsPersisted,
                    plannerStatisticsTtlMillis,
                    plannerStatisticsSampleSize,
                    learnedStatisticsEnabled,
                    learnedStatisticsWeight,
                    cacheWarmingEnabled,
                    rangeHistogramBuckets,
                    prefixMaxLength,
                    textTokenMinLength,
                    textTokenMaxLength,
                    textMaxTokensPerValue
            );
        }
    }
}
