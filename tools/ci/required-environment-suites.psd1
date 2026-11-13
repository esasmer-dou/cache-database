@{
    Standalone = @(
        'RedisAtomicReplayIdentityRedisTest',
        'RedisInitialGenerationHardeningRedisTest', 'CacheInitialNativePurgeHardeningRedisTest',
        'InitialSourceContractTest', 'SourceOwnerBootstrapTest', 'InitialSqlSupplementContractTest',
        'FreshIdentityContractsTest', 'JdbcFreshIdentityAllocatorTest', 'FreshIdentityAllocatorProvidersTest',
        'FreshIdentityNativeLifecycleProvidersRedisTest', 'FreshOwnerAdmissionScriptRedisTest',
        'FreshIdentityRefillProvidersRedisTest',
        'JdbcSourceOwnerOnboardingTest', 'SourceOwnerOnboardingProvidersTest', 'InitialSourcePagesProvidersTest',
        'InitialJournalCleanupProvidersTest', 'InitialTokenPlansMssqlTest', 'RedisTenantReadFenceReuseTest',
        'CacheInitialBootstrapApiBoundaryTest', 'CacheInitialClosureWaitTest', 'CacheInitialNamespaceRedisTest',
        'CacheInitialProfileSafetyRedisTest', 'GeneratedInitialNativeRedisTest',
        'GeneratedInitialProtectedVirtualThreadRedisTest', 'KilledInitialParticipantRedisTest',
        'CacheInitialNativeBridgeProvidersRedisTest', 'CacheInitialNativeRecoveryRedisTest',
        'CacheInitialNativeAmbiguityRedisTest', 'CacheInitialAuthorityRedisTest',
        'CacheInitialSourceBootstrapRedisTest',
        'CacheSourceOwnerOnboardingTest', 'CacheTenantRuntimeEnrollmentRedisTest', 'CacheTenantRuntimeProcessRedisTest',
        'CacheTenantRuntimeVirtualThreadRedisTest',
        'HotPathEvolutionRedisTest', 'RedisQueryIndexDiffRedisTest', 'RedisIndexAggregateBatchTest',
        'RedisT4IndexBatchContractTest', 'RedisT4AggregationRedisTest',
        'RedisOwnerCommandTemplateTest', 'RedisStandaloneAtomicWindowRedisTest',
        'RedisStandaloneCountRoutingTest', 'RedisStandaloneCountRoutingRedisTest',
        'RedisStandaloneCountEvalPipelineTest', 'RedisStandaloneCountFacetPipelineRedisTest',
        'RedisStandaloneBinaryFacetAclRedisTest', 'RedisConsumerPackagingContractTest',
        'RedisGuardedCandidateFetchRedisTest', 'RedisCompactFacetCountsRedisTest',
        'RedisNearCacheTest', 'Java21VirtualThreadReadinessTest', 'SnapshotCodecReadinessTest',
        'SnapshotProvidersFixtureLifecycleTest', 'GeneratedInitialNativeFixtureDdlTest',
        'RedisVirtualThreadPoolFairnessRedisTest', 'BootstrapUriRedisClientFactoryTest',
        'BootstrapUriRedisClientFactoryRedisTest', 'WriteFenceProvidersIntegrationTest',
        'JdbcWriteFenceSqlShapeTest', 'MssqlWriteFenceSargabilityIntegrationTest',
        'RedisCompositeSignatureTest', 'RedisCausalReadsTest', 'CacheCausalContextTest', 'JdbcReorderingMetricsTest',
        'RedisReadCoalescingConfigTest', 'RedisReadCoalescingIntegrationConfigTest',
        'CacheRuntimeOptionsBootstrapTest', 'DefaultCacheSessionOptionPropagationTest',
        'CacheRepositoryInitializationRedisTest', 'CacheRepositoryInitializationVirtualThreadRedisTest',
        'RedisReadWiringRedisTest', 'RedisReadCoalescerTest', 'RedisReadCoalescingRedisTest',
        'RedisReadAsyncDeadlineTest', 'RedisReadClusterTransportTest', 'RedisReadDebatchAdmissionTest',
        'RedisReadDeliveryMemoryTest', 'RedisReadDisabledAsyncFifoTest', 'RedisReadRedirectFramingTest',
        'RedisReadSyncMemoryAdmissionTest', 'RedisReadSyncOverflowWaitTest', 'RedisRepositoryReadStateTest',
        'RedisReadCoalescingBindingTest', 'RedisReadCoalescingMetricsTest',
        'NearCacheInvalidationIntegrationConfigTest', 'NearCacheInvalidationBindingTest',
        'NearCacheInvalidationMetricsTest', 'RedisNearCacheStampTest', 'RedisServerTrackingTest',
        'RedisTrackingBlackholeTest', 'RedisTrackingHeartbeatLeaseTest', 'RedisTrackingIdleHeartbeatTest',
        'RedisTrackingStartupCancellationTest', 'RedisTrackingStartupSocketCancellationTest',
        'RedisTrackingOverlappingPrefixTest', 'RedisInvalidationMapperTest', 'RedisTopologyNotificationTest',
        'NearCacheTrackingWiringRedisTest', 'CacheAsyncExecutorTest', 'CacheAsyncExternalCompletionTest',
        'CacheAsyncDeliveryLifetimeTest', 'OffloadingAsyncEntityRepositoryTest', 'CacheAsyncRuntimeOptionsTest',
        'CacheAsyncRuntimeBindingTest', 'CacheAsyncMdcContextTest', 'CacheAsyncRedisTest',
        'CacheAsyncNativeBulkSafetyRedisTest', 'RedisNativeAsyncEligibilityTest', 'RedisAsyncReadWindowTest',
        'RedisAsyncContinuationTest', 'RedisAsyncReadDeliverySafetyTest', 'RedisAsyncWindowDeliveryLifetimeTest',
        'RedisAsyncBoundedValuesTest', 'ReactiveEntityRepositoryTest', 'CacheRepositoryProcessorTest',
        'GeneratedRepositoryLazySourceTest',
        'CausalQueryGuardRedisTest', 'RedisStandaloneIndexSnapshotTest', 'RedisStandaloneIndexSnapshotRedisTest',
        'RedisStandaloneIndexAclTest', 'RedisStandaloneIndexAclRedisTest', 'RedisStandaloneExecAclTest',
        'RedisFacetNativeBatchRedisTest',
        'CacheObservationContextTest', 'CacheTraceparentTest', 'ObservingEntityRepositoryTest',
        'MicrometerCacheObservationRecorderTest', 'CacheObservationAutoConfigurationTest',
        'CacheObservationRedisContractTest', 'NativeObservationPropagationTest', 'JdbcNativeObservationTest',
        'RedisNativeObservationIntegrationTest', 'CacheObservationRuntimeOptionsTest',
        'LinkedFlushObservationHandlerTest', 'NativeWriterFlushAcceptanceTest',
        'CacheObservationRuntimeOptionsCustomizerTest', 'NativeDatabaseObservationTest',
        'RedisCompositeRangeTest', 'RedisCompositeRangeRedisTest', 'CacheCompositeRangeProcessorTest',
        'RedisCompositePlannerIntegrationTest', 'RedisCompositeMigrationFenceTest', 'RedisCompositeLong53Test',
        'RedisLexicalWindowCursorTest', 'RedisLexicalScoreContractTest', 'RedisLexicalSeekProcessorTest',
        'RedisLongLexicalWindowCursorTest', 'RedisCompositeLongCursorTest', 'RedisLongLexicalSeekProcessorTest',
        'CacheDbCacheContractTest', 'CacheDbSpringCacheAutoConfigurationTest', 'CacheDbCacheRedisTest',
        'CacheDbCacheVirtualThreadRedisTest', 'CacheDbCacheAnnotationsRedisTest', 'CacheDbCacheJvmFlightsTest',
        'CacheDbCacheInvalidationCoordinatorTest', 'CacheDbCacheZeroRttRedisTest',
        'EncryptedContractsTest', 'JcaFieldEncryptionTest', 'StaticKeyProviderTest',
        'CacheEncryptedCodecProcessorTest', 'CacheProtectionMetadataProcessorTest',
        'EncryptedCursorContractRedisTest', 'EncryptedStorageBoundaryRedisTest',
        'EncryptedStorageIntegrationTest', 'EncryptedJdbcBoundaryTest', 'CacheEncryptionAutoConfigurationTest',
        'CacheDbCacheSubscriptionTest', 'CacheDbCacheSubscriptionRedisTest',
        'SpringDataRepositoryTest', 'SpringDataRedisIntegrationTest', 'SpringDataQueryFingerprintTest',
        'SpringDataQueryBudgetTest', 'SpringDataProtectedWindowTest', 'SpringDataProcessorContractTest',
        'SpringDataExecutionLimitsTest', 'SpringDataBulkDeleteContractTest',
        'EntityQueryCapacityTest', 'RedisEntityQueryCapacityTest',
        'SpringDataEntityQueryCapacityTest', 'SpringDataEntityQueryCapacityRedisTest',
        'BulkDeleteContractTest', 'RedisBulkDeleteBoundsTest', 'RedisBulkDeleteRedisTest', 'RedisBulkDeleteProvidersTest',
        'RetentionPolicyTest', 'RetentionSweeperTest', 'RedisRetentionScriptsTest', 'RedisRetentionAdapterTest',
        'RetentionJobAdapterTest', 'ProtectionProtocolTest', 'ExistingRecordPatternArityTest',
        'TenantWriteStampContextTest', 'TenantReadFenceTest', 'PurgeParticipantManifestTest',
        'RedisTenantVersionAllocatorTest', 'RedisTenantReadFenceTest', 'RedisQueuedGenerationCodecTest',
        'RedisPurgeIndexLayoutTest', 'RedisPurgeGenerationIntegrationTest', 'RedisProtectionArgumentsTest',
        'RedisProtectedReadBridgeRedisTest', 'RedisProtectedPointReadsTest', 'RedisIdentityPurgeParticipantTest',
        'RedisGenerationBarrierTest', 'JdbcTenantPurgeDialectTest', 'PurgeQueueWorkCodecTest',
        'TenantPurgeProvidersTest', 'RedisIdentityPurgeSourceIsolationProvidersTest', 'CacheDataProtectionProvidersTest',
        'EmptyTenantActivationProvidersTest',
        'RedisEncryptedBulkDeleteChecksTest', 'EncryptedBulkDeleteCodecAdmissionTest', 'EncryptedBulkDeleteRedisTest',
        'RedisReencryptionRunnerTest', 'GeneratedReencryptionRedisTest', 'RedisNonTenantReencryptionRunnerTest',
        'RedisTenantProducerTest', 'ProtectedProducerRootRedisTest', 'RedisFunctionsVersionContractTest',
        'RedisFunctionLibraryCoexistenceRedisTest', 'RedisCanonicalDeleteSourceTest',
        'CacheDatabaseTenantRuntimeIsolationRedisTest', 'CacheDatabaseReencryptionRedisTest',
        'RedisRuntimeReencryptionTransportTest', 'CacheDbCacheCloseSocketTest', 'CacheDbCacheCloseOwnershipTest'
        'BoundedRedisWireTest', 'BoundedRedisWireSocketTest'
    )
    StandaloneMethods = @{
        CacheSourceOwnerOnboardingTest = @(
            'administrationPrecedesConfigurationValidationOrIo',
            'worklistHasStableBoundedPagesAndCanonicalOpaqueCursors',
            'byteBudgetSplitsBeforeTransportAndCountsUtf8RatherThanCharacters',
            'emptyDuplicateOversizedAndOverBudgetWorklistsFailClosed',
            'worklistCannotBecomeUnboundedThroughTinyPages',
            'exactWorklistOrderAndContentsArePartOfPageIdentity',
            'rootAdapterUsesOnlyNativeIdentityCodecsNotConfiguredJdbcCallbacks'
        )
        CacheTenantRuntimeEnrollmentRedisTest = @(
            'sourceOwnerFactoryBindsActualRootAndReviewedWorklistWithoutSqlOrOwnerWrites',
            'sourceOwnerInstallIsIdempotentWithoutChangingPayloadVersionsTombstonesOrTtl',
            'sourceOwnerForeignLaterIdentityPreventsEveryEarlierOwnerWrite',
            'sourceOwnerUnknownSourceNeverClaimsExistingOrMissingIdentity',
            'sourceOwnerRetainedQueueAndCompactionHistoryCannotBeStampedOrDeleted',
            'sourceOwnerFutureOrMalformedVersionAndWrongTypeFailBeforeOwnerCreation',
            'sourceOwnerReceiptCannotRepairLostOwnerOrHideChangedSourceProof',
            'sourceOwnerMutationAtomicallyRejectsStalePeerAcknowledgementAndOldLease',
            'sourceOwnerActualRootWarmsExistingSqlOwnedWorklistAndCannotRebindTerminalCheckpoint',
            'sourceOwnerActualSqlForeignIdentityEndsIncompleteWithoutClaimingItOrReopening',
            'sourceOwnerRequiresNativeSealAndWillNotRecreateADeletedPageSeal',
            'sourceOwnerSealedPayloadVersionTombstoneMetadataAndExistenceCannotChange',
            'sourceOwnerSealRetryReconstructsFromNativeStateAndRejectsSamePageIdDifferentWorklist',
            'sourceOwnerPartialFencingRetainsItsFirstSnapshotAndDoesNotAdvanceOrOverwriteOnChange',
            'sourceOwnerSnapshotBoundsUnknownMetadataAndTotalPayloadBeforeOwnerOrSealWrites'
        )
        RedisStandaloneBinaryFacetAclRedisTest = @(
            'compactBinaryReadsOnlyExplicitCommandAndKeySelectors',
            'evalKeyAdmissionFailureInEveryFamilyAndWindowCannotReturnPartialCounts',
            'innerCommandKeyDenialsInEveryWindowPreserveLuaErrorsInsteadOfDeclining',
            'allowedOwnerMgetDoesNotRequireAdditionalGetSelectorPermissions',
            'rejectedOwnerMgetUsesOnlyQualifiedGetAndRetainsMixedLivenessChecks',
            'verifiedEvalCommandRejectionDeclinesBinaryButExistingFallbackStillThrows',
            'wrongtypeAndMissingMetadataInEveryWindowNeverBecomeCapabilityFallbacks',
            'tombstonedWindowsDoNotRemoveDeclaredOwnerOrIndexKeyAdmissionRequirements'
        )
        RedisConsumerPackagingContractTest = @('standaloneRedisConsumerRetainsJedisRequiredLoggingApiWithoutTestClasspathHelp')
        SnapshotProvidersFixtureLifecycleTest = @(
            'sourceAcquisitionFailureDoesNotAcquireRedis',
            'redisAcquisitionFailureClosesAcquiredSqlPool',
            'failedRootCreateClosesOnlyAttemptedOwnedScope',
            'failedChildCreatePreservesSetupFailureAndAttemptsBothTables',
            'rootNameCollisionNeverDropsExistingTable',
            'childNameCollisionDropsOnlyNewRoot',
            'failedInsertCleansBothOwnedTables',
            'setupErrorStillCleansEveryOwnedResource',
            'closeAttemptsEveryCleanupAndSuppressesInOrder',
            'scanFailureStillClosesRedis',
            'unlinkFailureStillClosesRedis',
            'repeatedSameFailureDoesNotSelfSuppress',
            'closeIsIdempotentAndUsesOnlyOwnedScope',
            'tryWithResourcesKeepsBodyFailurePrimary'
        )
        GeneratedInitialNativeFixtureDdlTest = @('tenantColumnUsesMssqlUnicodeWithoutChangingOtherProviderDdl')
        RedisOwnerCommandTemplateTest = @(
            'stockCountExistsKeepsLegacyWireBytesAndBudgetForMalformedUnicode',
            'stockCountExistsBytesRemainRequestLocalAcrossTransactions',
            'indexEvalSharesIdenticalScriptBytesButNotCommandsOrKeyArgumentsWithinPhase',
            'indexEvalScriptBytesDoNotLeakAcrossRequestPhases',
            'indexEvalDifferentScriptCannotReuseStaleBytes',
            'indexEvalSharedScriptChargesFullBytesForEveryTransmissionAndRejectsAtLegacyBoundary',
            'indexEvalSharingPreservesLegacyWireAndBudgetForUtf8AndMalformedSurrogates',
            'customIndexEvalScriptsRetainIndependentBuffersWithoutChangingWireOrBudget',
            'countAndFacetEvalPhasesKeepDeferredCustomConnectionScriptsIndependent'
        )
        CacheInitialSourceBootstrapRedisTest = @(
            'postgresFirstNamespaceAndPurge',
            'mssqlFirstNamespaceAndPurge',
            'oracleFirstNamespaceAndPurge',
            'postgresSqlOpenCommitUncertainty',
            'mssqlSqlOpenCommitUncertainty',
            'oracleSqlOpenCommitUncertainty'
        )
        CacheInitialNativeBridgeProvidersRedisTest = @(
            'encryptedFixtureQueueEnvelopeHonorsProtectedWriteContract',
            'nonemptyEncryptedSourceReachesOpenThroughTheActualRootNamespace',
            'twoActualRootsAcknowledgeOneInitialLeaseWithAHalfSecondPeerLoop',
            'stoppingAnEnrolledActualRootDoesNotRetireItOrManufactureAnAcknowledgement'
        )
        EncryptedJdbcBoundaryTest = @(
            'sourceSchemaChecksActualEnvelopeCapacityAndRejectsNumericAndClobMappings',
            'cachedShortCapacityRejectsLargerAuthenticatedSecondWrite',
            'cachedCapacityCannotBeReusedAfterTheMetadataTableChanges',
            'mixedBatchCapacityPreflightRejectsBeforeFenceTransactionOrDmlIncludingCachedRetry',
            'missingDuplicateNullAndNonpositiveCapacityMetadataFailClosed',
            'cachedCapacityRechecksEveryEnvelopeAndRejectsNonAsciiAliasesAndGlobalOverflow',
            'malformedLastMemberFailsBeforeAnyFenceInitializationOrConnectionAccess'
        )
        SourceOwnerOnboardingProvidersTest = @(
            'closedIndexedReaderKeepsCaseDistinctTenantsAndResumesPendingPage(String)[1]',
            'closedIndexedReaderKeepsCaseDistinctTenantsAndResumesPendingPage(String)[2]',
            'closedIndexedReaderKeepsCaseDistinctTenantsAndResumesPendingPage(String)[3]',
            'completedSourcePurgeCannotBeRepurposedAsOwnershipPreparationOrReopening(String)[1]',
            'completedSourcePurgeCannotBeRepurposedAsOwnershipPreparationOrReopening(String)[2]',
            'completedSourcePurgeCannotBeRepurposedAsOwnershipPreparationOrReopening(String)[3]'
        )
        EmptyTenantActivationProvidersTest = @(
            'sqlOpenCrashBeforeRedisOpenRecoversTheSameReservation(String)[1]',
            'sqlOpenCrashBeforeRedisOpenRecoversTheSameReservation(String)[2]',
            'sqlOpenCrashBeforeRedisOpenRecoversTheSameReservation(String)[3]',
            'lostRedisOpenOutcomeAndReacquisitionNeverRewindVersionsOrEraseNewWrites(String)[1]',
            'lostRedisOpenOutcomeAndReacquisitionNeverRewindVersionsOrEraseNewWrites(String)[2]',
            'lostRedisOpenOutcomeAndReacquisitionNeverRewindVersionsOrEraseNewWrites(String)[3]'
        )
        FreshIdentityAllocatorProvidersTest = @(
            'namespaceReservationIsImmutableBoundedAndFencedAcrossTenantsAndLeaseHandoff(String)[1]',
            'namespaceReservationIsImmutableBoundedAndFencedAcrossTenantsAndLeaseHandoff(String)[2]',
            'namespaceReservationIsImmutableBoundedAndFencedAcrossTenantsAndLeaseHandoff(String)[3]',
            'closedBindingMismatchIsRejectedBeforeInsertOrAllocation(String)[1]',
            'closedBindingMismatchIsRejectedBeforeInsertOrAllocation(String)[2]',
            'closedBindingMismatchIsRejectedBeforeInsertOrAllocation(String)[3]',
            'initialBindingMismatchIsRejectedBeforeInsert(String)[1]',
            'initialBindingMismatchIsRejectedBeforeInsert(String)[2]',
            'initialBindingMismatchIsRejectedBeforeInsert(String)[3]',
            'epochZeroReceiptCorruptionRequiresRecovery(String)[1]',
            'epochZeroReceiptCorruptionRequiresRecovery(String)[2]',
            'epochZeroReceiptCorruptionRequiresRecovery(String)[3]',
            'strictOpenReservationsNeverReplayCommittedRequestsAcrossIndependentAllocators(String)[1]',
            'strictOpenReservationsNeverReplayCommittedRequestsAcrossIndependentAllocators(String)[2]',
            'strictOpenReservationsNeverReplayCommittedRequestsAcrossIndependentAllocators(String)[3]'
        )
        FreshIdentityNativeLifecycleProvidersRedisTest = @(
            'initialActivePurgeRetainsNamespaceNonreuseAndFencesOldAuthority(String)[1]',
            'initialActivePurgeRetainsNamespaceNonreuseAndFencesOldAuthority(String)[2]',
            'initialActivePurgeRetainsNamespaceNonreuseAndFencesOldAuthority(String)[3]',
            'admittedFreshOwnersEnableOrdinaryWritesAndArePurgedBeforeEmptyGenerationWrites(String)[1]',
            'admittedFreshOwnersEnableOrdinaryWritesAndArePurgedBeforeEmptyGenerationWrites(String)[2]',
            'admittedFreshOwnersEnableOrdinaryWritesAndArePurgedBeforeEmptyGenerationWrites(String)[3]',
            'recoveredEmptyActivationAdmitsFreshIdentitiesWithoutReplayingOldAuthority(String)[1]',
            'recoveredEmptyActivationAdmitsFreshIdentitiesWithoutReplayingOldAuthority(String)[2]',
            'recoveredEmptyActivationAdmitsFreshIdentitiesWithoutReplayingOldAuthority(String)[3]',
            'reopenedFreshIdentityFlushesWithOriginalGenerationAndRejectsPrePurgeReplay(String)[1]',
            'reopenedFreshIdentityFlushesWithOriginalGenerationAndRejectsPrePurgeReplay(String)[2]',
            'reopenedFreshIdentityFlushesWithOriginalGenerationAndRejectsPrePurgeReplay(String)[3]'
        )
        FreshIdentityRefillProvidersRedisTest = @(
            'initialOpenRefillRetainsActivationProofAndAdmitsOrdinaryWrite(String)[1]',
            'initialOpenRefillRetainsActivationProofAndAdmitsOrdinaryWrite(String)[2]',
            'initialOpenRefillRetainsActivationProofAndAdmitsOrdinaryWrite(String)[3]',
            'uninstalledRefillAndStaleRequestsNeverClaimOwnersOrReissueIds(String)[1]',
            'uninstalledRefillAndStaleRequestsNeverClaimOwnersOrReissueIds(String)[2]',
            'uninstalledRefillAndStaleRequestsNeverClaimOwnersOrReissueIds(String)[3]',
            'reacquiredEmptyActivationKeepsFrozenRangeAndAdmitsAfterOpenRefill(String)[1]',
            'reacquiredEmptyActivationKeepsFrozenRangeAndAdmitsAfterOpenRefill(String)[2]',
            'reacquiredEmptyActivationKeepsFrozenRangeAndAdmitsAfterOpenRefill(String)[3]',
            'openRefillRejectsForgedRangesAndNativeProofChangesWithoutMutation(String)[1]',
            'openRefillRejectsForgedRangesAndNativeProofChangesWithoutMutation(String)[2]',
            'openRefillRejectsForgedRangesAndNativeProofChangesWithoutMutation(String)[3]'
        )
        FreshOwnerAdmissionScriptRedisTest = @(
            'successfulAdmissionCreatesOnlyPersistentOwnerAndZeroVersionPairsAndCannotReplay',
            'existingZeroVersionRejectsTheWholeRangeWithoutClaimingAnyOwner',
            'markerPairLimitIsBoundedAndDoesNotConsumeVersions(int)[1]',
            'markerPairLimitIsBoundedAndDoesNotConsumeVersions(int)[2]',
            'denyingOneVersionOrOwnerWriteCannotLeaveEitherMarker(int)[1]',
            'denyingOneVersionOrOwnerWriteCannotLeaveEitherMarker(int)[2]',
            'anyExistingIdentityKeyRejectsTheWholeRangeWithoutOverwritingIt(int)[1]',
            'anyExistingIdentityKeyRejectsTheWholeRangeWithoutOverwritingIt(int)[2]',
            'anyExistingIdentityKeyRejectsTheWholeRangeWithoutOverwritingIt(int)[3]',
            'anyExistingIdentityKeyRejectsTheWholeRangeWithoutOverwritingIt(int)[4]',
            'anyExistingIdentityKeyRejectsTheWholeRangeWithoutOverwritingIt(int)[5]',
            'anyExistingIdentityKeyRejectsTheWholeRangeWithoutOverwritingIt(int)[6]',
            'anyExistingIdentityKeyRejectsTheWholeRangeWithoutOverwritingIt(int)[7]',
            'anyExistingIdentityKeyRejectsTheWholeRangeWithoutOverwritingIt(int)[8]',
            'anyExistingIdentityKeyRejectsTheWholeRangeWithoutOverwritingIt(int)[9]',
            'changedRunGenerationControlAndRosterEachLeaveNoOwners',
            'malformedOrExpiredVersionAllocatorDoesNotMutateControlOrOwners',
            'msetnxAclDenialCannotLeaveAPartiallyAdmittedRange',
            'oversizedCounterRejectsWithoutControlEnrollmentOrOwnerMutation',
            'oversizedControlFieldsRejectWithoutEnrollmentOrOwnerMutation(int, String, String)[1]',
            'oversizedControlFieldsRejectWithoutEnrollmentOrOwnerMutation(int, String, String)[2]',
            'oversizedControlFieldsRejectWithoutEnrollmentOrOwnerMutation(int, String, String)[3]',
            'oversizedControlFieldsRejectWithoutEnrollmentOrOwnerMutation(int, String, String)[4]',
            'oversizedControlFieldsRejectWithoutEnrollmentOrOwnerMutation(int, String, String)[5]',
            'oversizedVersionFieldsRejectWithoutControlEnrollmentOrOwnerMutation(String)[1]',
            'oversizedVersionFieldsRejectWithoutControlEnrollmentOrOwnerMutation(String)[2]',
            'oversizedVersionFieldsRejectWithoutControlEnrollmentOrOwnerMutation(String)[3]',
            'oversizedVersionFieldsRejectWithoutControlEnrollmentOrOwnerMutation(String)[4]',
            'oversizedVersionFieldsRejectWithoutControlEnrollmentOrOwnerMutation(String)[5]',
            'oversizedVersionFieldsRejectWithoutControlEnrollmentOrOwnerMutation(String)[6]'
        )
        CacheInitialNativePurgeHardeningRedisTest = @(
            'postgresPurgeResumesAfterNativeEraseBeforeCheckpointCommitAndPreservesForeignTenant',
            'mssqlPurgeResumesAfterNativeEraseBeforeCheckpointCommitAndPreservesForeignTenant',
            'oraclePurgeResumesAfterNativeEraseBeforeCheckpointCommitAndPreservesForeignTenant'
        )
    }
    Cluster = @(
        'RedisClusterOperationsTest', 'RedisCausalReadsTest', 'RedisReadCoalescingClusterTest',
        'RedisReadPhysicalCapacityClusterTest', 'RedisServerTrackingClusterTest',
        'NearCacheTrackingClusterWiringTest', 'RedisT4IndexBatchClusterTest', 'RedisCompositeRangeClusterTest',
        'RedisCompositeLong53Test', 'CacheDbCacheClusterRedisTest', 'RedisBulkDeleteClusterTest',
        'EncryptedBulkDeleteClusterTest', 'RedisStandaloneCountFacetPipelineRedisTest'
    )
    ClusterMethods = @{
        RedisCausalReadsTest = @('liveClusterReadsCoLocatedAndSplitTriplesWithoutCrossSlotCommands')
        RedisCompositeLong53Test = @('domainProofAndWriterPoisonShareTheCompositeClusterSlot')
        RedisStandaloneCountFacetPipelineRedisTest = @('realClusterAbove512DeclinesCountAndFacetPhasesWithoutInvokingAnyWindowFactory')
    }
}
