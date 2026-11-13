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
    # Exact separately tested Oracle sample configuration; never a generic source-overlay permission.
    ReviewedOraclePurgeConfiguration = @{
        before = '56dccca6d2f16c36af67a84123c2e047da8f588e79a701daac22dbbe010af14f'
        after = '7131dc6e36af52f2a4a20e017209be19c98162d3162c7ab172d22e96b601afec'
        source = '456ef8ad0a32a571ec52a345dbeb0a3e5f2dcaf742a7eb3d840f49c831a69a11'
        sdk = '474527305300767b9d6ba35f24e5c5085505680653aea9137cf9524044d116a3'
        pod = '7d9fcc03-d0a9-4973-9a97-b8864c8c75f8'
        scopedReceipt = '29bdd14e7b9caf0b12b3aacb12da65e75b29ac59fd3f3fde9b13cf01a5e07b23'
        nativeProcess = '20e16779f01ab1ad6c3cea36b94d1ac45512746b149e07ac83786ced6af1db6a'
        testsConfiguration = '9fd7258252039809970162349cd5b0943f0b37494fb87f4c8f38cc8463acd15f'
        httpConfiguration = 'bcc2aac0e29f2a791dc9d73c1768cad8637198722255ee651e2c596c22bf92dd'
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
        '578aabbe22bbd815f9f17f061cc070f6b146f4733c41f10339b4840820ee0725' = '8f7e02c3-2b98-4acb-a9bc-c66d6dddc0ee'
        'a84429a4c34c520a0db20c6b7b1c0ec52b6fac51367ab483926910f3dbd82f85' = '8f7e02c3-2b98-4acb-a9bc-c66d6dddc0ee'
        'c0af9bf36ce2cbf2bfde629e9ced204ac0096bf0aeecac39ea20c1632a0952c9' = '8f7e02c3-2b98-4acb-a9bc-c66d6dddc0ee'
        '8c96b2b5b89a07b1f1dd8f6d8c9dde6345d04b1ee87609cae953a47e352471d8' = '8f7e02c3-2b98-4acb-a9bc-c66d6dddc0ee'
        '12a46d3534fa96f53a7161b92443ba290d6301374174d04e05092e3ec7d2a559' = '7d9fcc03-d0a9-4973-9a97-b8864c8c75f8'
        '00d371de27b29322d4fbab22c7837a5008677acfa0bf6470955cf88656351f33' = '7d9fcc03-d0a9-4973-9a97-b8864c8c75f8'
        '9f00a709a3751ff61d5f7b419ff26dd64cf79acad9ce6d2c8878a71f81b627e5' = '7d9fcc03-d0a9-4973-9a97-b8864c8c75f8'
        'e2a858860e1bd275e10c4c4381d0d6d45106fd2b7d6db48b28f7139a9de5bcd5' = '7d9fcc03-d0a9-4973-9a97-b8864c8c75f8'
        'da3e6b7d7f5dee5e9762afe0113a4b4e8f2cdcd351b5059f21e363bd4a8ccbfc' = '7d9fcc03-d0a9-4973-9a97-b8864c8c75f8'
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
