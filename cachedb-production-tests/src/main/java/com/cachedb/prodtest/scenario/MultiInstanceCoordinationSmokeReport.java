package com.reactor.cachedb.prodtest.scenario;

import java.time.Instant;
import java.util.List;

public record MultiInstanceCoordinationSmokeReport(
        Instant recordedAt,
        boolean uniqueConsumerNamesVerified,
        List<String> observedConsumerNames,
        boolean leaderLeaseFailoverVerified,
        String leaderLeaseKey,
        String initialLeaderInstanceId,
        String failoverLeaderInstanceId,
        long historySamplesBeforeFailover,
        long historySamplesAfterFailover,
        boolean writeBehindClaimFailoverVerified,
        String pendingOwnerConsumerName,
        long claimantClaimedCount,
        boolean persistedAfterClaim,
        boolean pendingDrainedAfterClaim,
        boolean allSuccessful
) {
}
