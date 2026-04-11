package com.reactor.cachedb.core.config;

public record RuntimeCoordinationConfig(
        String instanceId,
        boolean appendInstanceIdToConsumerNames,
        boolean leaderLeaseEnabled,
        String leaderLeaseSegment,
        long leaderLeaseTtlMillis,
        long leaderLeaseRenewIntervalMillis
) {
    public static Builder builder() {
        return new Builder();
    }

    public static RuntimeCoordinationConfig defaults() {
        return builder().build();
    }

    public static final class Builder {
        private String instanceId = "";
        private boolean appendInstanceIdToConsumerNames = true;
        private boolean leaderLeaseEnabled = true;
        private String leaderLeaseSegment = "coordination:leader";
        private long leaderLeaseTtlMillis = 15_000L;
        private long leaderLeaseRenewIntervalMillis = 5_000L;

        public Builder instanceId(String instanceId) {
            this.instanceId = instanceId == null ? "" : instanceId;
            return this;
        }

        public Builder appendInstanceIdToConsumerNames(boolean appendInstanceIdToConsumerNames) {
            this.appendInstanceIdToConsumerNames = appendInstanceIdToConsumerNames;
            return this;
        }

        public Builder leaderLeaseEnabled(boolean leaderLeaseEnabled) {
            this.leaderLeaseEnabled = leaderLeaseEnabled;
            return this;
        }

        public Builder leaderLeaseSegment(String leaderLeaseSegment) {
            this.leaderLeaseSegment = leaderLeaseSegment == null ? "" : leaderLeaseSegment;
            return this;
        }

        public Builder leaderLeaseTtlMillis(long leaderLeaseTtlMillis) {
            this.leaderLeaseTtlMillis = leaderLeaseTtlMillis;
            return this;
        }

        public Builder leaderLeaseRenewIntervalMillis(long leaderLeaseRenewIntervalMillis) {
            this.leaderLeaseRenewIntervalMillis = leaderLeaseRenewIntervalMillis;
            return this;
        }

        public RuntimeCoordinationConfig build() {
            return new RuntimeCoordinationConfig(
                    instanceId,
                    appendInstanceIdToConsumerNames,
                    leaderLeaseEnabled,
                    leaderLeaseSegment,
                    leaderLeaseTtlMillis,
                    leaderLeaseRenewIntervalMillis
            );
        }
    }
}
