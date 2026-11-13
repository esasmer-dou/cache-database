@{
    Schema = 'cachedb.k8s-acceptance/v1'
    SnapshotSchema = 'cachedb.k8s-acceptance/v2'
    Kind = 'KUBERNETES_RUNTIME_ACCEPTANCE'
    SdkFiles = 92
    Providers = @('postgresql', 'mssql', 'oracle')
    # Test-peer corrections only. No SDK/runtime source delta is eligible for case-level composition.
    ReviewedTestSources = @{
        '6c6d42774c8c32d904b2a973f5eb5757e95541a1615847b65b30dfd67afd8c1e' = @{
            'cachedb-starter/src/test/java/com/cachedb/starter/RedisReadWiringRedisTest.java' = '9985d253e39f921f97a870754528202c0d84c801a1d7a49ee8d711afb5fcb814'
        }
        '494a1c479680e7e3e60508adcad9531ec0f03cf1b2c9b47c45ab38b143d6788f' = @{
            'cachedb-starter/src/test/java/com/cachedb/starter/RedisReadWiringRedisTest.java' = '9985d253e39f921f97a870754528202c0d84c801a1d7a49ee8d711afb5fcb814'
            'cachedb-storage-redis/src/test/java/com/cachedb/redis/RedisReadRedirectServer.java' = '5e23dbb31d5cb39f683ac304fcf3062d7446e6024b748f154fb6dae09b9b203a'
        }
    }
    # Original transport receipts, not reconstructed summaries or aggregate receipts.
    ReviewedOriginalReceipts = @{
        '765307e4742afcc42d887e63927306889760135a3fc43fa41b6d32fa63740019' = 'df21bb8a-a92f-4473-8f7a-f6742ceb7884'
        '63e4c8aaa704e1d8f1df8837a85fa16d08d05962e9e1c60988da22e83f0cc6b6' = '0b2ca4ca-bce5-4883-97a7-c811d97f520c'
        'd6a7787e14cd45945b2b3d688b446da7b01283f64ef23b5ea56b3b62ffc8056b' = 'fb8fb837-77cd-487e-98cb-e3aa33e78028'
        'bb801de2dc272114ef29a5bb2b579ee2ff0be8d3b823679a6faa5c61a861d98b' = 'a1df5fa5-04a6-47c6-817e-de069d209a1d'
        '4cec20d6cd7fa2796e0ba626c141a2b16fc0a6771ee747c60cd977fd1ab3dbe5' = '06535919-a6e3-45db-b940-8f79144a3172'
        '0210e1a1ab68d5c07264d5c4488f4f68c9685ddbabb1151c445637c5fd557a33' = 'bca1728a-ab1b-4d7d-a3bb-c564f3912570'
        'a7b914cdadef3ea6625901e6b1e0e0e90747047620f6956a9af5e5e4536fed64' = 'a97e8b1e-9a50-4f2f-bdbf-f2eac24c3228'
        '614709961b8f08a388c3678f04d2eee3d55969d1fe8cbd9b10e86e0b96b7a8c5' = 'e1128e7e-41dc-4816-94a5-2a232b992164'
        '92efb2b7b0796582e4fca44fca44a515e2b5b79210dc2b00a7e50fee5fb2a029' = 'ed2d3c93-f24f-4133-bbae-c3cf709563c6'
    }
    ReviewedPrefixOriginals = @{
        'f1fce3ee1ec153e2826bdad4faef0ad95a2a3924e1d1edfa85c687b29e217dc2' = @{
            profile = 'BB_PROCESS'; inventorySha256 = 'a281a4f118dc06a6a9b138eaeb4ffb025cc32b478c58e4fdcdc7a04c0e7b304e'
        }
        'bc13c2e1d48c6559de6e80a1dcc68b246186abe2189d86223741cc5032aa319a' = @{
            profile = 'BB_BASE_XML'; inventorySha256 = 'c1af95385475fea0d8f02264463bc087b22467533405ab8196f03da4d8ae866e'
        }
        '6f020592270ea937539e320cb8a9e44f66017de1646d3817980acf24bc973687' = @{
            profile = 'BB_F3_XML'; inventorySha256 = 'db3e7d4aca169447ddb3a8d72cc2d68a63de3adcfde854c39b3cfb5286d92ebf'
        }
    }
    # These commands execute in Kubernetes. The public job verifies their sealed output only.
    Controls = @(
        './tools/ci/validate-framework-readiness.ps1'
        './tools/ci/test-documentation-guards.ps1 -EvidenceDirectory ./target/documentation-guard-controls'
        './tools/ci/check-tr-docs.ps1'
        './tools/ci/check-markdown-links.ps1'
        './tools/ci/check-readme-quality.ps1'
        './tools/samples/sync-feature-guide.ps1 -Check'
        './tools/ci/check-postman-collections.ps1'
        './tools/ci/check-v018-sample-coverage.ps1'
        './tools/ci/test-v019-postman-contract.ps1'
        'node ./tools/ci/test-v019-sample-matrix.mjs'
        'node ./tools/ci/test-redis-fixture-probes.mjs'
        './tools/ci/test-range-data-postman-contract.ps1'
        './tools/ci/test-v019-runtime-workflow.ps1 -EvidenceDirectory ./target/sample-runtime-workflow-controls'
        './tools/ci/check-sample-provider-parity.ps1'
        './tools/ci/check-sample-framework-usage.ps1'
        './tools/ci/check-framework-principles.ps1'
        './tools/ci/test-framework-principles.ps1'
        'mvn -B -P oss-release -DskipTests -Dgpg.skip=true package'
        './tools/ci/test-performance-resource-contract.ps1 -EvidenceDirectory ./target/performance-resource-controls'
        './tools/ci/test-release-module-packaging.ps1 -EvidenceDirectory ./target/release-packaging-controls'
        './tools/ci/test-public-maven-repository.ps1 -EvidenceDirectory ./target/public-maven-repository-controls'
        './tools/ci/test-ga-release-workflow-contract.ps1 -EvidenceDirectory ./target/ga-workflow-controls'
        './tools/ci/test-prepublication-readiness.ps1 -EvidenceDirectory ./target/prepublication-controls'
        './tools/ci/test-jmh-histogram-consistency.ps1 -EvidenceDirectory ./target/histogram-controls'
        './tools/ci/test-benchmark-tool-packaging.ps1 -EvidenceDirectory ./target/benchmark-tool-packaging-controls'
        './tools/ci/test-workflow-trigger-contract.ps1 -EvidenceDirectory ./target/workflow-trigger-controls'
        './tools/ci/check-release-artifacts.ps1'
        './tools/ci/test-public-api-compatibility.ps1'
        './tools/ci/check-public-api-compatibility.ps1'
        './tools/ci/test-k8s-acceptance.ps1'
    )
    # Exact AE harness declaration, not a claim that an original argv/environment capture exists.
    # Capture-v1 authority remains separate from script-declaration-v2 authority.
    OfflinePackageInvocation = @{
        ApprovedRecordSha256 = ''
        PinnedExecutedScript = @{
            # Admit original documents independently; never pin a fabricated historical capture.
            ApprovedOriginalSha256 = @{
                pod = 'a5945e23a0b000c6e22fd75d548323c2bcde1af116cf524c5b13befc4a9c0b4c'
                authority = 'd8ae4c6ca3f5622a73d875c37d82f9ca8535d7e0fff68f0cce492d70c0381fb1'
            }
            LauncherArgumentSha256 = '57a81edd4ff087ee3f59ec6a700e2a46f09c1df3ea2f8ad401ec25100e150723'
            OriginalEvidenceRoles = @('pod', 'authority', 'buildReceipt', 'sourceManifest', 'sdkManifest',
                'buildLog', 'buildExit', 'buildStarted', 'buildFinished', 'wrapperExit', 'sourcePreservationExit', 'sdkPreservationExit')
        }
        Argv = @('mvn', '-o', '-s', '/workspace/input/settings-offline.xml', '-gs', '/workspace/input/settings-offline.xml',
            '-Dmaven.repo.local=/workspace/repository', '-B', '-P', 'oss-release', '-DskipTests', '-Dgpg.skip=true', 'package')
        WrapperArgv = @('timeout', '--signal=TERM', '--kill-after=10s', '470')
        WorkingDirectory = '/workspace/source'
        Environment = @{
            LC_ALL = 'C'; TZ = 'UTC'; MAVEN_SKIP_RC = '1'; JAVA_HOME = '/workspace/toolchain/java'
            PATH = '/workspace/toolchain/java/bin:/workspace/toolchain/maven/bin:/workspace/toolchain/node/bin:/usr/bin:/bin'
            HOME = '/workspace/home'; XDG_CACHE_HOME = '/workspace/home/.cache'; XDG_CONFIG_HOME = '/workspace/home/.config'
            TMPDIR = '/workspace/tmp'; MAVEN_OPTS = '-Xms64m -Xmx1024m -XX:ActiveProcessorCount=2'
            NODE_OPTIONS = '--max-old-space-size=192'
        }
        UnsetEnvironment = @('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH')
        InputSha256 = @{
            runner = 'cb7e853a059b24a71672d6d36a319a08b0ec9c4c532369ed9289a30ca845d667'
            settings = '183668ce4e29a3f85583130a51ad0fb3fadf8af4235cd38c8151a3a62787cd21'
            plan = '78e7ef32fd4a41b072589954fd30e9e7bcbffad767dd5930d0ab62e5b79d87e9'
        }
    }
    Baseline = @(
        @{ Name = 'catalog'; Kind = 'Catalog'; Requests = 23 }
        @{ Name = 'v018'; Kind = 'Controls'; Requests = 40 }
        @{ Name = 'sample'; Kind = 'Business'; Requests = 84 }
        @{ Name = 'range-data'; Kind = 'RangeData'; Requests = 37 }
    )
}
