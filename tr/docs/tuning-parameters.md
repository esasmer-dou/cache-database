# Tuning Parametreleri

Bu dokuman, artik kod degistirmeden ayarlanabilen runtime parametrelerini toplar.

Iki katman vardir:

- Redis ve PostgreSQL istemci/bootstrap tuning ayarlari
- write-behind, guardrail, admin, index, schema bootstrap ve cache davranisi icin `CacheDatabaseConfig` override'lari

Bir property set edilmezse, asagidaki tabloda yazan varsayilan deger kullanilir.

## Prefix Modeli

Global core override:

- `cachedb.config.*`

Kapsam bazli core override:

- `cachedb.demo.config.*`
- `cachedb.admin.config.*`

Kapsam bazli baglanti tuning:

- `cachedb.demo.redis.*`
- `cachedb.demo.postgres.*`
- `cachedb.admin.redis.*`
- `cachedb.admin.postgres.*`

Ornek:

```powershell
-Dcachedb.config.writeBehind.workerThreads=8
-Dcachedb.config.redisGuardrail.usedMemoryWarnBytes=2147483648
-Dcachedb.demo.redis.pool.maxTotal=96
-Dcachedb.demo.postgres.connectTimeoutSeconds=15
```

## Redis Client Tuning

| Property | Varsayilan | Ne ise yarar |
| --- | --- | --- |
| `<scope>.redis.uri` | runtime'a gore | Redis hedef URI'si. |
| `<scope>.redis.pool.maxTotal` | `64` | Havuzdaki maksimum Redis baglantisi. |
| `<scope>.redis.pool.maxIdle` | `16` | Sicak tutulacak maksimum idle Redis baglantisi. |
| `<scope>.redis.pool.minIdle` | `4` | Hazir tutulacak minimum idle Redis baglantisi. |
| `<scope>.redis.pool.maxWaitMillis` | `5000` | Havuz doluyken caller'in ne kadar bekleyecegi. |
| `<scope>.redis.pool.blockWhenExhausted` | `true` | Havuz doluyken hemen hata vermek yerine beklemeyi acip kapatir. |
| `<scope>.redis.pool.testOnBorrow` | `false` | Havuzdan alinan baglantiyi validate eder. |
| `<scope>.redis.pool.testWhileIdle` | `false` | Idle baglantilari bakim dongusunda validate eder. Varsayilan olarak kapali tutulur; arka planda `PING` maliyeti ve timeout gurultusu uretmesin diye. |
| `<scope>.redis.pool.timeBetweenEvictionRunsMillis` | `30000` | Idle connection bakim periyodu. |
| `<scope>.redis.pool.minEvictableIdleTimeMillis` | `60000` | Idle baglantinin ne kadar sonra atilabilecegi. |
| `<scope>.redis.pool.numTestsPerEvictionRun` | `3` | Her bakim dongusunda test edilen idle baglanti sayisi. |
| `<scope>.redis.pool.connectionTimeoutMillis` | `2000` | Yeni Redis soketleri icin connect timeout. |
| `<scope>.redis.pool.readTimeoutMillis` | `5000` | Normal Redis komutlari icin read timeout. |
| `<scope>.redis.pool.blockingReadTimeoutMillis` | `15000` | Stream read gibi blocking Redis komutlari icin read timeout. Worker block timeout degerinin ustunde tutulmalidir; aksi halde sahte `Read timed out` gorulebilir. |

## Spring Boot Starter Redis Topolojisi

Bu property'ler ozellikle `cachedb-spring-boot-starter` icin gecerlidir.

| Property | Varsayilan | Ne ise yarar |
| --- | --- | --- |
| `cachedb.profile` | `default` | Starter profil kisayolu. `default`, `development`, `production`, `benchmark`, `memory-constrained` ve `minimal-overhead` degerlerini destekler. |
| `cachedb.redis.uri` | `redis://127.0.0.1:6379` | Starter icindeki foreground repository Redis URI'si. |
| `cachedb.redis-uri` | `redis://127.0.0.1:6379` | `cachedb.redis.uri` icin geriye uyumlu eski alias. |
| `cachedb.redis.pool.maxTotal` | `64` | Foreground repository havuzunun maksimum boyutu. |
| `cachedb.redis.pool.maxIdle` | `16` | Foreground repository havuzunun maksimum idle boyutu. |
| `cachedb.redis.pool.minIdle` | `4` | Foreground repository havuzunun minimum idle boyutu. |
| `cachedb.redis.pool.maxWaitMillis` | `5000` | Foreground repository havuzunda maksimum bekleme suresi. |
| `cachedb.redis.pool.blockWhenExhausted` | `true` | Foreground havuz doluyken bekleme davranisini acip kapatir. |
| `cachedb.redis.pool.testOnBorrow` | `false` | Foreground baglantilari borrow aninda validate eder. |
| `cachedb.redis.pool.testWhileIdle` | `false` | Foreground idle baglantilari bakim dongusunde validate eder. Varsayilan olarak kapali; repository yoluna idle-validation `PING` gurultusu bindirmemek icin. |
| `cachedb.redis.pool.timeBetweenEvictionRunsMillis` | `30000` | Foreground idle-eviction bakim periyodu. |
| `cachedb.redis.pool.minEvictableIdleTimeMillis` | `60000` | Foreground idle baglantilarin atilma esigi. |
| `cachedb.redis.pool.numTestsPerEvictionRun` | `3` | Foreground bakim dongusunda test edilen idle baglanti sayisi. |
| `cachedb.redis.pool.connectionTimeoutMillis` | `2000` | Foreground Redis connect timeout. |
| `cachedb.redis.pool.readTimeoutMillis` | `5000` | Foreground Redis normal komut read timeout'u. |
| `cachedb.redis.pool.blockingReadTimeoutMillis` | `15000` | Foreground Redis blocking komut read timeout'u. |
| `cachedb.redis.background.enabled` | `true` | Background worker/admin havuzunu acar veya kapatir. |
| `cachedb.redis.background.uri` | `cachedb.redis.uri` fallback'i | Background worker/admin Redis URI'si. |
| `cachedb.redis.background.pool.maxTotal` | `24` | Background worker/admin havuzunun maksimum boyutu. |
| `cachedb.redis.background.pool.maxIdle` | `8` | Background worker/admin havuzunun maksimum idle boyutu. |
| `cachedb.redis.background.pool.minIdle` | `2` | Background worker/admin havuzunun minimum idle boyutu. |
| `cachedb.redis.background.pool.maxWaitMillis` | `5000` | Background havuzunda maksimum bekleme suresi. |
| `cachedb.redis.background.pool.blockWhenExhausted` | `true` | Background havuz doluyken bekleme davranisini acip kapatir. |
| `cachedb.redis.background.pool.testOnBorrow` | `false` | Background baglantilari borrow aninda validate eder. |
| `cachedb.redis.background.pool.testWhileIdle` | `false` | Background idle baglantilari bakim dongusunde validate eder. Varsayilan olarak kapali; worker/admin havuzlari periyodik validation yerine reconnect ile toparlansin diye. |
| `cachedb.redis.background.pool.timeBetweenEvictionRunsMillis` | `30000` | Background idle-eviction bakim periyodu. |
| `cachedb.redis.background.pool.minEvictableIdleTimeMillis` | `60000` | Background idle baglantilarin atilma esigi. |
| `cachedb.redis.background.pool.numTestsPerEvictionRun` | `3` | Background bakim dongusunda test edilen idle baglanti sayisi. |
| `cachedb.redis.background.pool.connectionTimeoutMillis` | `2000` | Background Redis connect timeout. |
| `cachedb.redis.background.pool.readTimeoutMillis` | `10000` | Background Redis normal komut read timeout'u. |
| `cachedb.redis.background.pool.blockingReadTimeoutMillis` | `30000` | Background Redis blocking stream ve recovery komutlari icin read timeout. |

## Runtime Coordination

Bu property'ler cok pod'lu ortamda worker kimligini ve singleton operasyonel loop'lari ayarlar.

### Core Override'lar

| Property | Varsayilan | Ne ise yarar |
| --- | --- | --- |
| `cachedb.config.runtimeCoordination.instanceId` | bos | Acik runtime instance id. Bos birakilirsa CacheDB bunu environment'tan cozer ve gerekirse UUID uretir. |
| `cachedb.config.runtimeCoordination.appendInstanceIdToConsumerNames` | `true` | Cozulen instance id'yi worker consumer name prefix'lerine ekler. Kubernetes'te ortak consumer group kullaniminda acik tutulmalidir. |
| `cachedb.config.runtimeCoordination.leaderLeaseEnabled` | `true` | Cleanup/report/history benzeri singleton loop'lar icin Redis leader lease'i acar. |
| `cachedb.config.runtimeCoordination.leaderLeaseSegment` | `coordination:leader` | Ana key prefix altinda leader lease key'leri icin kullanilan Redis segment'i. |
| `cachedb.config.runtimeCoordination.leaderLeaseTtlMillis` | `15000` | Singleton operasyonel loop'lar icin Redis lease TTL suresi. |
| `cachedb.config.runtimeCoordination.leaderLeaseRenewIntervalMillis` | `5000` | Lider pod'un lease'i ne siklikta yenileyecegi. |

### Spring Boot Starter Kisayollari

| Property | Varsayilan | Ne ise yarar |
| --- | --- | --- |
| `cachedb.runtime.instance-id` | bos | Runtime instance id icin starter dostu alias. |
| `cachedb.runtime.append-instance-id-to-consumer-names` | `true` | Pod-unique consumer adlari icin starter dostu bayrak. |
| `cachedb.runtime.leader-lease-enabled` | `true` | Singleton ops loop'lari icin Redis leader lease'i acan starter dostu bayrak. |
| `cachedb.runtime.leader-lease-segment` | `coordination:leader` | Leader lease key'leri icin starter dostu Redis segment'i. |
| `cachedb.runtime.leader-lease-ttl-millis` | `15000` | Leader lease TTL suresi. |
| `cachedb.runtime.leader-lease-renew-interval-millis` | `5000` | Leader lease yenileme araligi. |

Operasyonel notlar:

- consumer group'lar pod'lar arasinda ortak kalir; sadece consumer adlari pod-unique olur
- otomatik instance id cozme sirasi `cachedb.runtime.instance-id`, `CACHE_DB_INSTANCE_ID`, `HOSTNAME`, `POD_NAME`, `COMPUTERNAME`, sonra uretilen UUID seklindedir
- leader lease bugun cleanup/report/history benzeri loop'lari kapsar; ana consumer-group worker'lar bu yolla singleton yapilmaz
- tek Redis hala koordinasyon katmaninin merkezi bagimliligidir; production'da durable/HA Redis kullan
- worker thread sayisini pod bazli degil, cluster toplami olarak dusun
- ayni host uzerinde local smoke kosarken acik `cachedb.runtime.instance-id` degerleri ver ya da `tools/ops/cluster/run-multi-instance-coordination-smoke.ps1` script'ini kullan; cunku `HOSTNAME` genelde tum local process'lerde ortaktir

## PostgreSQL Client Tuning

| Property | Varsayilan | Ne ise yarar |
| --- | --- | --- |
| `<scope>.postgres.jdbcUrl` | runtime'a gore | PostgreSQL JDBC URL'i. |
| `<scope>.postgres.user` | runtime'a gore | Veritabani kullanicisi. |
| `<scope>.postgres.password` | runtime'a gore | Veritabani sifresi. |
| `<scope>.postgres.connectTimeoutSeconds` | `30` | PostgreSQL connect timeout. |
| `<scope>.postgres.socketTimeoutSeconds` | `300` | PostgreSQL socket read timeout. |
| `<scope>.postgres.tcpKeepAlive` | `true` | PostgreSQL baglantilarinda TCP keepalive acar. |
| `<scope>.postgres.rewriteBatchedInserts` | `true` | JDBC'nin batch insert'leri daha hizli multi-value insert'e donusturmesini saglar. |
| `<scope>.postgres.prepareThreshold` | `5` | Server-prepared statement moduna gecis esigi. |
| `<scope>.postgres.defaultRowFetchSize` | `0` | Varsayilan row fetch size. `0` driver default davranisini korur. |
| `<scope>.postgres.applicationName` | `cache-database` | PostgreSQL oturumundaki application name. |
| `<scope>.postgres.additionalParameters` | bos | `key=value;key=value` formatinda ek JDBC query parametreleri. |

## Core Runtime Tuning

### Write-Behind

| Property | Varsayilan | Ne ise yarar |
| --- | --- | --- |
| `cachedb.config.writeBehind.enabled` | `true` | Write-behind hattini acar/kapatir. |
| `cachedb.config.writeBehind.workerThreads` | `max(1, cpu/2)` | Write-behind worker sayisi. |
| `cachedb.config.writeBehind.batchSize` | `128` | Stream'den cekilen temel batch boyu. Eslı zamanlı yazma trafiginde drain hizini artirmak icin hafif yukseltilmistir. |
| `cachedb.config.writeBehind.dedicatedWriteConsumerGroupEnabled` | `true` | Ayrik compaction consumer group kullanir. |
| `cachedb.config.writeBehind.durableCompactionEnabled` | `true` | Redis tarafinda durable compaction state tutar. |
| `cachedb.config.writeBehind.batchFlushEnabled` | `true` | Batch flush davranisini acar. |
| `cachedb.config.writeBehind.tableAwareBatchingEnabled` | `true` | Flush gruplarini tablo/entity tipine gore ayirir. |
| `cachedb.config.writeBehind.flushGroupParallelism` | `4` | Paralel flush grup sayisi. PostgreSQL flush overlap'ini arttirir. |
| `cachedb.config.writeBehind.flushPipelineDepth` | `4` | Ayni anda ilerleyen flush dalga derinligi. Backlog altinda worker'in daha dolu calismasini saglar. |
| `cachedb.config.writeBehind.coalescingEnabled` | `true` | Gecersizlesmis yazilari tek son state'e indirger. |
| `cachedb.config.writeBehind.maxFlushBatchSize` | `128` | Tek flush batch'indeki maksimum satir. |
| `cachedb.config.writeBehind.batchStaleCheckEnabled` | `true` | PostgreSQL flush oncesi stale batch kayitlarini eler. |
| `cachedb.config.writeBehind.adaptiveBacklogHighWatermark` | `250` | Yuksek backlog profiline gecis esigi. |
| `cachedb.config.writeBehind.adaptiveBacklogCriticalWatermark` | `750` | Kritik backlog profiline gecis esigi. Worker'in suren baskida daha erken buyumesi icin dusurulmustur. |
| `cachedb.config.writeBehind.adaptiveHighFlushBatchSize` | `256` | Yuksek backlog altindaki flush batch boyu. |
| `cachedb.config.writeBehind.adaptiveCriticalFlushBatchSize` | `512` | Kritik backlog altindaki flush batch boyu. |
| `cachedb.config.writeBehind.postgresMultiRowFlushEnabled` | `true` | Multi-row PostgreSQL upsert/delete yolunu acar. |
| `cachedb.config.writeBehind.postgresMultiRowStatementRowLimit` | `64` | Uretilen multi-row statement basina satir limiti. |
| `cachedb.config.writeBehind.postgresCopyBulkLoadEnabled` | `true` | PostgreSQL `COPY` yolunu acar. |
| `cachedb.config.writeBehind.postgresCopyThreshold` | `128` | `COPY` yoluna gecis minimum satir sayisi. |
| `cachedb.config.writeBehind.blockTimeoutMillis` | `2000` | Redis stream block timeout. |
| `cachedb.config.writeBehind.idleSleepMillis` | `250` | Worker idle sleep suresi. |
| `cachedb.config.writeBehind.maxFlushRetries` | `3` | Genel flush retry sayisi. |
| `cachedb.config.writeBehind.retryBackoffMillis` | `1000` | Retry arasindaki bekleme. |
| `cachedb.config.writeBehind.streamKey` | `cachedb:stream:write-behind` | Temel write-behind stream key'i. |
| `cachedb.config.writeBehind.consumerGroup` | `cachedb-write-behind` | Temel consumer group. |
| `cachedb.config.writeBehind.consumerNamePrefix` | `cachedb-worker` | Temel consumer name prefix. |
| `cachedb.config.writeBehind.compactionStreamKey` | `cachedb:stream:write-behind:compaction` | Compaction stream key'i. |
| `cachedb.config.writeBehind.compactionConsumerGroup` | `cachedb-write-behind-compaction` | Compaction consumer group. |
| `cachedb.config.writeBehind.compactionConsumerNamePrefix` | `cachedb-compaction-worker` | Compaction consumer name prefix. |
| `cachedb.config.writeBehind.compactionShardCount` | `4` | Compaction shard sayisi. Eszamanli yazmalarda durable compaction stream hot-spot'unu azaltir. |
| `cachedb.config.writeBehind.autoCreateConsumerGroup` | `true` | Redis consumer group'lari otomatik olusturur. |
| `cachedb.config.writeBehind.shutdownAwaitMillis` | `10000` | Graceful shutdown bekleme suresi. |
| `cachedb.config.writeBehind.daemonThreads` | `true` | Worker thread'lerini daemon olarak calistirir. |
| `cachedb.config.writeBehind.recoverPendingEntries` | `true` | Startup'ta orphaned pending entry'leri claim eder. |
| `cachedb.config.writeBehind.claimIdleMillis` | `5000` | Claim icin idle esigi. |
| `cachedb.config.writeBehind.claimBatchSize` | `100` | Her dongude claim edilen entry sayisi. |
| `cachedb.config.writeBehind.deadLetterMaxLength` | `10000` | Write-behind DLQ stream trim hedefi. |
| `cachedb.config.writeBehind.deadLetterStreamKey` | `cachedb:stream:write-behind:dlq` | Write-behind DLQ stream key'i. |
| `cachedb.config.writeBehind.compactionMaxLength` | `10000` | Compaction stream trim hedefi. |
| `cachedb.config.writeBehind.retryOverrides` | bos | Entity bazli retry override. Format asagida. |
| `cachedb.config.writeBehind.entityFlushPolicies` | bos | Entity bazli PostgreSQL flush policy. Format asagida. |

### Resource Limits ve Default Cache Policy

| Property | Varsayilan | Ne ise yarar |
| --- | --- | --- |
| `cachedb.config.resourceLimits.maxRegisteredEntities` | `1000` | Tek `CacheDatabase` icinde kaydedilebilecek maksimum entity sayisi. |
| `cachedb.config.resourceLimits.maxColumnsPerOperation` | `256` | Tek mutation icinde takip edilen maksimum kolon sayisi. |
| `cachedb.config.resourceLimits.defaultCachePolicy.hotEntityLimit` | `1000` | Varsayilan hot entity butcesi. |
| `cachedb.config.resourceLimits.defaultCachePolicy.pageSize` | `100` | Varsayilan page cache boyu. |
| `cachedb.config.resourceLimits.defaultCachePolicy.lruEvictionEnabled` | `true` | LRU benzeri eviction davranisini acar. |
| `cachedb.config.resourceLimits.defaultCachePolicy.entityTtlSeconds` | `0` | Entity TTL. `0` TTL yok demektir. |
| `cachedb.config.resourceLimits.defaultCachePolicy.pageTtlSeconds` | `60` | Page cache TTL suresi. |

### Keyspace, Functions, Relations, Page Cache

| Property | Varsayilan | Ne ise yarar |
| --- | --- | --- |
| `cachedb.config.keyspace.keyPrefix` | `cachedb` | Global Redis key prefix. |
| `cachedb.config.keyspace.entitySegment` | `entity` | Entity key segment'i. |
| `cachedb.config.keyspace.pageSegment` | `page` | Page cache segment'i. |
| `cachedb.config.keyspace.versionSegment` | `version` | Version key segment'i. |
| `cachedb.config.keyspace.tombstoneSegment` | `tombstone` | Tombstone segment'i. |
| `cachedb.config.keyspace.hotSetSegment` | `hotset` | Hot-set segment'i. |
| `cachedb.config.keyspace.indexSegment` | `index` | Query index segment'i. |
| `cachedb.config.keyspace.compactionSegment` | `compaction` | Compaction segment'i. |
| `cachedb.config.redisFunctions.enabled` | `true` | Redis Functions yolunu acar. |
| `cachedb.config.redisFunctions.autoLoadLibrary` | `true` | Startup'ta Redis Function library yukler. |
| `cachedb.config.redisFunctions.replaceLibraryOnLoad` | `true` | Yukleme sirasinda mevcut library'yi degistirir. |
| `cachedb.config.redisFunctions.strictLoading` | `true` | Function loading temiz tamamlanamazsa fail-fast davranir. |
| `cachedb.config.redisFunctions.libraryName` | `cachedb` | Redis Function library adi. |
| `cachedb.config.redisFunctions.upsertFunctionName` | `entity_upsert` | Upsert function giris noktasi. |
| `cachedb.config.redisFunctions.deleteFunctionName` | `entity_delete` | Delete function giris noktasi. |
| `cachedb.config.redisFunctions.compactionCompleteFunctionName` | `compaction_complete` | Compaction-complete function giris noktasi. |
| `cachedb.config.redisFunctions.templateResourcePath` | `/functions/cachedb-functions.lua` | Function kaynak template yolu. |
| `cachedb.config.redisFunctions.sourceOverride` | bos | Library icin tam kaynak override'i. |
| `cachedb.config.relations.batchSize` | `250` | Varsayilan relation batch boyu. |
| `cachedb.config.relations.maxFetchDepth` | `3` | Maksimum relation fetch derinligi. |
| `cachedb.config.relations.failOnMissingPreloader` | `false` | Eksik relation preloader durumunda fail davranisini belirler. |
| `cachedb.config.pageCache.readThroughEnabled` | `true` | Page-cache read-through davranisini acar. |
| `cachedb.config.pageCache.failOnMissingPageLoader` | `false` | Read-through icin page loader yoksa fail olup olmayacagini belirler. |
| `cachedb.config.pageCache.evictionBatchSize` | `100` | Page-cache eviction batch boyu. |

### Query Index

| Property | Varsayilan | Ne ise yarar |
| --- | --- | --- |
| `cachedb.config.queryIndex.exactIndexEnabled` | `true` | Exact-match index'lerini acar. |
| `cachedb.config.queryIndex.rangeIndexEnabled` | `true` | Range index'lerini acar. |
| `cachedb.config.queryIndex.prefixIndexEnabled` | `true` | Prefix index'lerini acar. |
| `cachedb.config.queryIndex.textIndexEnabled` | `true` | Text index'lerini acar. |
| `cachedb.config.queryIndex.plannerStatisticsEnabled` | `true` | Planner statistics toplamayi acar. |
| `cachedb.config.queryIndex.plannerStatisticsPersisted` | `true` | Planner statistics'i Redis'te kalici tutar. |
| `cachedb.config.queryIndex.plannerStatisticsTtlMillis` | `60000` | Planner statistics TTL suresi. |
| `cachedb.config.queryIndex.plannerStatisticsSampleSize` | `32` | Planner statistics sample boyu. |
| `cachedb.config.queryIndex.learnedStatisticsEnabled` | `true` | Learned planner weighting'i acar. |
| `cachedb.config.queryIndex.learnedStatisticsWeight` | `0.35` | Learned statistics agirligi. |
| `cachedb.config.queryIndex.cacheWarmingEnabled` | `true` | Index ve planner warming davranisini acar. |
| `cachedb.config.queryIndex.rangeHistogramBuckets` | `8` | Range histogram bucket sayisi. |
| `cachedb.config.queryIndex.prefixMaxLength` | `12` | Indexlenen maksimum prefix uzunlugu. |
| `cachedb.config.queryIndex.textTokenMinLength` | `2` | Indexlenen minimum token uzunlugu. |
| `cachedb.config.queryIndex.textTokenMaxLength` | `32` | Indexlenen maksimum token uzunlugu. |
| `cachedb.config.queryIndex.textMaxTokensPerValue` | `16` | Alan basi maksimum token sayisi. |

### Projection Refresh

Bu property'ler `EntityProjection.asyncRefresh()` tarafinda kullanilan durable Redis Stream tabanli projection refresh worker'ini ayarlar.

| Property | Varsayilan | Ne ise yarar |
| --- | --- | --- |
| `cachedb.config.projectionRefresh.enabled` | `true` | Projection refresh stream ve worker hattini acar/kapatir. |
| `cachedb.config.projectionRefresh.streamKey` | `cachedb:stream:projection-refresh` | Projection refresh event'lerinin yazildigi Redis stream key'i. |
| `cachedb.config.projectionRefresh.consumerGroup` | `cachedb-projection-refresh` | Projection refresh worker'larinin kullandigi Redis consumer group. |
| `cachedb.config.projectionRefresh.consumerNamePrefix` | `projection-refresh` | Worker consumer name'leri uretilirken kullanilan prefix. |
| `cachedb.config.projectionRefresh.batchSize` | `100` | Tek worker batch'indeki maksimum refresh event sayisi. |
| `cachedb.config.projectionRefresh.blockTimeoutMillis` | `1000` | Redis stream blocking read timeout suresi. |
| `cachedb.config.projectionRefresh.idleSleepMillis` | `250` | Refresh isi yokken worker sleep suresi. |
| `cachedb.config.projectionRefresh.autoCreateConsumerGroup` | `true` | Consumer group yoksa otomatik olusturur. |
| `cachedb.config.projectionRefresh.recoverPendingEntries` | `true` | Stale pending projection event'lerini recover etmeye calisir. |
| `cachedb.config.projectionRefresh.claimIdleMillis` | `30000` | Pending projection event'inin claim edilmesi icin idle esigi. |
| `cachedb.config.projectionRefresh.claimBatchSize` | `100` | Tek turda claim edilen maksimum pending projection event sayisi. |
| `cachedb.config.projectionRefresh.maxStreamLength` | `100000` | Projection refresh stream icin yaklasik trim hedefi. |
| `cachedb.config.projectionRefresh.deadLetterEnabled` | `true` | Projection refresh poison/dead-letter stream hattini acar/kapatir. |
| `cachedb.config.projectionRefresh.deadLetterStreamKey` | `cachedb:stream:projection-refresh-dlq` | Poison projection refresh event'lerinin yazildigi Redis stream key'i. |
| `cachedb.config.projectionRefresh.deadLetterMaxLength` | `25000` | Projection refresh dead-letter stream icin yaklasik trim hedefi. |
| `cachedb.config.projectionRefresh.maxAttempts` | `3` | Bir projection refresh event'i dead-letter'a dusmeden once denenebilecek maksimum isleme sayisi. |
| `cachedb.config.projectionRefresh.deadLetterWarnThreshold` | `1` | Admin incidents/services tarafinda projection refresh dead-letter backlog icin warning esigi. |
| `cachedb.config.projectionRefresh.deadLetterCriticalThreshold` | `25` | Admin incidents/services tarafinda projection refresh dead-letter backlog icin critical esigi. |
| `cachedb.config.projectionRefresh.shutdownAwaitMillis` | `5000` | Projection refresh worker graceful shutdown bekleme suresi. |
| `cachedb.config.projectionRefresh.daemonThreads` | `true` | Projection refresh worker'i daemon thread olarak calistirir. |

Operasyonel notlar:

- async projection refresh artik Redis Stream seviyesinde durable'dir
- refresh event'leri process restart sonrasinda kaybolmaz ve birden fazla uygulama node'u tarafindan tuketilebilir
- model tasarim geregi hala eventual consistency tabanlidir
- poison projection refresh event'leri ayrik bir Redis Stream dead-letter queue'ya tasinir
- replay islemi admin API ve birlikte gelen ops script'leri uzerinden yapilabilir
- ama henuz poison queue, replay tooling veya ayrik admin telemetry iceren tam bir projection platformu degildir
- runtime coordination suffix acikken projection refresh consumer adlari varsayilan olarak pod-unique olur

### Redis Guardrails

| Property | Varsayilan | Ne ise yarar |
| --- | --- | --- |
| `cachedb.config.redisGuardrail.enabled` | `true` | Redis guardrail davranisini acar. |
| `cachedb.config.redisGuardrail.producerBackpressureEnabled` | `true` | Producer'larin pressure altinda yavaslamasini saglar. |
| `cachedb.config.redisGuardrail.usedMemoryWarnBytes` | `0` | Redis memory warning esigi. `0` devre disi demektir. |
| `cachedb.config.redisGuardrail.usedMemoryCriticalBytes` | `0` | Redis memory critical esigi. `0` devre disi demektir. |
| `cachedb.config.redisGuardrail.writeBehindBacklogWarnThreshold` | `250` | Write-behind backlog warning esigi. |
| `cachedb.config.redisGuardrail.writeBehindBacklogCriticalThreshold` | `750` | Write-behind backlog critical esigi. |
| `cachedb.config.redisGuardrail.compactionPendingWarnThreshold` | `1000` | Compaction pending warning esigi. |
| `cachedb.config.redisGuardrail.compactionPendingCriticalThreshold` | `5000` | Compaction pending critical esigi. |
| `cachedb.config.redisGuardrail.writeBehindBacklogHardLimit` | `0` | Write-behind hard cap. `0` kapali. |
| `cachedb.config.redisGuardrail.compactionPendingHardLimit` | `0` | Compaction pending hard cap. `0` kapali. |
| `cachedb.config.redisGuardrail.compactionPayloadHardLimit` | `0` | Compaction payload hard cap. `0` kapali. |
| `cachedb.config.redisGuardrail.rejectWritesOnHardLimit` | `false` | Hard limitte sadece degrade etmek yerine yaziyi reddeder. |
| `cachedb.config.redisGuardrail.shedPageCacheWritesOnHardLimit` | `true` | Hard limitte page-cache write'i keser. |
| `cachedb.config.redisGuardrail.shedReadThroughCacheOnHardLimit` | `true` | Hard limitte read-through cache fill'i keser. |
| `cachedb.config.redisGuardrail.shedHotSetTrackingOnHardLimit` | `true` | Hard limitte hot-set tracking'i keser. |
| `cachedb.config.redisGuardrail.shedQueryIndexWritesOnHardLimit` | `true` | Hard limitte query-index write'i keser. |
| `cachedb.config.redisGuardrail.shedQueryIndexReadsOnHardLimit` | `true` | Hard limitte query-index read'i keser. |
| `cachedb.config.redisGuardrail.shedPlannerLearningOnHardLimit` | `true` | Hard limitte planner learning'i keser. |
| `cachedb.config.redisGuardrail.highSleepMillis` | `2` | Warning/high pressure altinda producer sleep suresi. |
| `cachedb.config.redisGuardrail.criticalSleepMillis` | `5` | Critical pressure altinda producer sleep suresi. |
| `cachedb.config.redisGuardrail.sampleIntervalMillis` | `500` | Guardrail sample periyodu. |
| `cachedb.config.redisGuardrail.automaticRuntimeProfileSwitchingEnabled` | `true` | Otomatik runtime profile switching'i acar. |
| `cachedb.config.redisGuardrail.warnSamplesToBalanced` | `3` | `STANDARD -> BALANCED` icin gereken sample sayisi. |
| `cachedb.config.redisGuardrail.criticalSamplesToAggressive` | `2` | `AGGRESSIVE` profile gecis sample sayisi. |
| `cachedb.config.redisGuardrail.warnSamplesToDeescalateAggressive` | `4` | `AGGRESSIVE -> BALANCED` icin gereken sample sayisi. |
| `cachedb.config.redisGuardrail.normalSamplesToStandard` | `5` | `STANDARD` profile donus sample sayisi. |
| `cachedb.config.redisGuardrail.compactionPayloadTtlSeconds` | `3600` | Compaction payload key TTL suresi. |
| `cachedb.config.redisGuardrail.compactionPendingTtlSeconds` | `3600` | Compaction pending key TTL suresi. |
| `cachedb.config.redisGuardrail.versionKeyTtlSeconds` | `86400` | Version key TTL suresi. |
| `cachedb.config.redisGuardrail.tombstoneTtlSeconds` | `86400` | Tombstone TTL suresi. |
| `cachedb.config.redisGuardrail.autoRecoverDegradedIndexesEnabled` | `true` | Pressure dustugunde degraded index'leri otomatik rebuild eder. |
| `cachedb.config.redisGuardrail.degradedIndexRebuildCooldownMillis` | `30000` | Yeni rebuild denemesi icin cooldown. |
| `cachedb.config.redisGuardrail.entityPolicies` | bos | Namespace bazli hard-limit shedding policy. Format asagida. |
| `cachedb.config.redisGuardrail.queryPolicies` | bos | Query-class bazli shedding policy. Format asagida. |

### Dead-Letter Recovery

| Property | Varsayilan | Ne ise yarar |
| --- | --- | --- |
| `cachedb.config.deadLetterRecovery.enabled` | `true` | DLQ recovery worker'i acar. |
| `cachedb.config.deadLetterRecovery.workerThreads` | `1` | DLQ worker sayisi. |
| `cachedb.config.deadLetterRecovery.blockTimeoutMillis` | `2000` | DLQ stream block timeout. |
| `cachedb.config.deadLetterRecovery.idleSleepMillis` | `250` | DLQ worker idle sleep. |
| `cachedb.config.deadLetterRecovery.consumerGroup` | `cachedb-write-behind-dlq` | DLQ consumer group. |
| `cachedb.config.deadLetterRecovery.consumerNamePrefix` | `cachedb-dlq-worker` | DLQ consumer name prefix. |
| `cachedb.config.deadLetterRecovery.autoCreateConsumerGroup` | `true` | DLQ consumer group otomatik olusturur. |
| `cachedb.config.deadLetterRecovery.shutdownAwaitMillis` | `10000` | DLQ worker graceful shutdown suresi. |
| `cachedb.config.deadLetterRecovery.daemonThreads` | `true` | DLQ worker thread'lerini daemon calistirir. |
| `cachedb.config.deadLetterRecovery.claimIdleMillis` | `5000` | Claim icin pending idle esigi. |
| `cachedb.config.deadLetterRecovery.claimBatchSize` | `100` | Bir dongude claim edilen DLQ entry sayisi. |
| `cachedb.config.deadLetterRecovery.maxReplayRetries` | `3` | DLQ replay retry sayisi. |
| `cachedb.config.deadLetterRecovery.replayBackoffMillis` | `1000` | DLQ replay backoff suresi. |
| `cachedb.config.deadLetterRecovery.reconciliationStreamKey` | `cachedb:stream:write-behind:reconciliation` | Reconciliation stream key. |
| `cachedb.config.deadLetterRecovery.archiveResolvedEntries` | `true` | Cozulmus entry'leri archive eder. |
| `cachedb.config.deadLetterRecovery.archiveStreamKey` | `cachedb:stream:write-behind:archive` | Archive stream key. |
| `cachedb.config.deadLetterRecovery.cleanupEnabled` | `true` | Retention cleanup davranisini acar. |
| `cachedb.config.deadLetterRecovery.cleanupIntervalMillis` | `60000` | Cleanup periyodu. |
| `cachedb.config.deadLetterRecovery.cleanupBatchSize` | `250` | Cleanup batch boyu. |
| `cachedb.config.deadLetterRecovery.cleanupEnabled` | `true` | Retention cleanup loop'unu acar. Cok pod'lu modda bu loop artik leader lease altinda calisir; cleanup isi ayni anda sadece tek node tarafindan yapilir. |
| `cachedb.config.deadLetterRecovery.deadLetterRetentionMillis` | `0` | DLQ retention. `0` sonsuz tutar. |
| `cachedb.config.deadLetterRecovery.reconciliationRetentionMillis` | `604800000` | Reconciliation retention suresi. |
| `cachedb.config.deadLetterRecovery.archiveRetentionMillis` | `2592000000` | Archive retention suresi. |
| `cachedb.config.deadLetterRecovery.deadLetterMaxLength` | `10000` | DLQ stream max length. |
| `cachedb.config.deadLetterRecovery.reconciliationMaxLength` | `10000` | Reconciliation stream max length. |
| `cachedb.config.deadLetterRecovery.archiveMaxLength` | `10000` | Archive stream max length. |
| `cachedb.config.deadLetterRecovery.retryOverrides` | bos | Entity bazli replay retry override. Format asagida. |

### Admin Monitoring, Reporting, HTTP ve Schema Bootstrap

| Property | Varsayilan | Ne ise yarar |
| --- | --- | --- |
| `cachedb.config.adminMonitoring.writeBehindWarnThreshold` | `250` | Write-behind backlog warning incident esigi. Kisa sureli burst'lerde gereksiz DEGRADED sinyali uretmemesi icin varsayilan Redis guardrail warning seviyesiyle hizalidir. |
| `cachedb.config.adminMonitoring.writeBehindCriticalThreshold` | `750` | Write-behind backlog critical incident esigi. Varsayilan Redis guardrail critical seviyesiyle hizalidir. |
| `cachedb.config.adminMonitoring.deadLetterWarnThreshold` | `10` | DLQ boyu warning esigi. |
| `cachedb.config.adminMonitoring.deadLetterCriticalThreshold` | `100` | DLQ boyu critical esigi. |
| `cachedb.config.adminMonitoring.recoveryFailedWarnThreshold` | `10` | Recovery fail warning esigi. |
| `cachedb.config.adminMonitoring.recoveryFailedCriticalThreshold` | `100` | Recovery fail critical esigi. |
| `cachedb.config.adminMonitoring.recentErrorWindowMillis` | `60000` | Recent worker error incident penceresi. |
| `cachedb.config.adminMonitoring.historySampleIntervalMillis` | `5000` | Server-side monitoring sample periyodu. |
| `cachedb.config.adminMonitoring.historyMinSampleIntervalMillis` | `1000` | Override sonrasi history sample periyodu icin alt sinir. |
| `cachedb.config.adminMonitoring.historyMaxSamples` | `720` | Redis monitoring-history stream'inde tutulacak maksimum sample sayisi. |
| `cachedb.config.adminMonitoring.historyMinSamples` | `32` | Monitoring-history retention'i icin alt sample siniri. |
| `cachedb.config.adminMonitoring.alertRouteHistoryMinSamples` | `64` | Alert-route history buffer'i icin alt sample siniri. |
| `cachedb.config.adminMonitoring.alertRouteHistorySampleMultiplier` | `4` | Alert-route history buffer boyutunu monitoring history'ye gore buyuten carpandir. |
| `cachedb.config.adminMonitoring.telemetryTtlSeconds` | `86400` | Redis tabanli admin telemetry key'leri icin varsayilan TTL. Kullanici override etmezse telemetry 1 gun icinde silinir. |
| `cachedb.config.adminMonitoring.monitoringHistoryStreamKey` | `cachedb:stream:admin:monitoring-history` | Monitoring history sample'lari icin Redis stream key'i. |
| `cachedb.config.adminMonitoring.alertRouteHistoryStreamKey` | `cachedb:stream:admin:alert-route-history` | Alert route history sample'lari icin Redis stream key'i. |
| `cachedb.config.adminMonitoring.performanceHistoryStreamKey` | `cachedb:stream:admin:performance-history` | Performance history sample'lari icin Redis stream key'i. |
| `cachedb.config.adminMonitoring.performanceSnapshotKey` | `cachedb:hash:admin:performance` | Guncel storage-performance snapshot'i ve scenario breakdown'lari icin Redis hash key'i. |
| `cachedb.config.adminMonitoring.incidentTtlSeconds` | `86400` | Incident stream entry'leri ve cooldown key'leri icin TTL. |
| `cachedb.config.adminMonitoring.incidentStreamKey` | `cachedb:stream:admin:incidents` | Incident stream key'i. |
| `cachedb.config.adminMonitoring.incidentMaxLength` | `2000` | Incident stream trim hedefi. |
| `cachedb.config.adminMonitoring.incidentCooldownMillis` | `30000` | Ayni incident'in tekrar emit edilmesi icin cooldown. |
| `cachedb.config.adminMonitoring.incidentDeliveryQueueFloor` | `64` | Incident delivery worker'lari icin bellek ici kuyruk alt siniri. |
| `cachedb.config.adminMonitoring.incidentDeliveryPollTimeoutMillis` | `500` | Incident delivery kuyrugu bosken worker poll bekleme suresi. |
| `cachedb.config.adminMonitoring.incidentWebhook.*` | kod default'lari | Webhook incident delivery tuning'i. |
| `cachedb.config.adminMonitoring.incidentQueue.*` | kod default'lari | Redis queue incident delivery tuning'i. |
| `cachedb.config.adminMonitoring.incidentEmail.*` | kod default'lari | SMTP incident delivery tuning'i. |
| `cachedb.config.adminMonitoring.incidentDeliveryDlq.*` | kod default'lari | Incident-delivery DLQ ve replay tuning'i. |
| `cachedb.config.adminReportJob.enabled` | `false` | Admin report job'unu acar. |
| `cachedb.config.adminReportJob.intervalMillis` | `300000` | Report job periyodu. |
| `cachedb.config.adminReportJob.outputDirectory` | `build/reports/cachedb-admin` | Report output dizini. |
| `cachedb.config.adminReportJob.format` | `JSON` | Report export format'i. |
| `cachedb.config.adminReportJob.queryLimit` | `500` | Her report bolumu icin maksimum kayit. |
| `cachedb.config.adminReportJob.writeDeadLetters` | `true` | Dead-letter export'unu dahil eder. |
| `cachedb.config.adminReportJob.writeReconciliation` | `true` | Reconciliation export'unu dahil eder. |
| `cachedb.config.adminReportJob.writeArchive` | `true` | Archive export'unu dahil eder. |
| `cachedb.config.adminReportJob.writeIncidents` | `true` | Incident export'unu dahil eder. |
| `cachedb.config.adminReportJob.writeDiagnostics` | `true` | Diagnostics export'unu dahil eder. |
| `cachedb.config.adminReportJob.includeTimestampInFileName` | `true` | Report dosya adina timestamp ekler. |
| `cachedb.config.adminReportJob.maxRetainedFilesPerReport` | `10` | Report tipi basina maksimum saklanan dosya. |
| `cachedb.config.adminReportJob.fileRetentionMillis` | `604800000` | Admin report retention suresi. |
| `cachedb.config.adminReportJob.persistDiagnostics` | `true` | Diagnostics'i Redis stream'e kalici yazar. |
| `cachedb.config.adminReportJob.diagnosticsStreamKey` | `cachedb:stream:admin:diagnostics` | Diagnostics stream key'i. |
| `cachedb.config.adminReportJob.diagnosticsMaxLength` | `2000` | Diagnostics stream trim hedefi. |
| `cachedb.config.adminReportJob.diagnosticsTtlSeconds` | `86400` | Diagnostics stream entry'leri icin TTL. |
| `cachedb.config.adminHttp.enabled` | `false` | Admin HTTP server'i acar. |
| `cachedb.config.adminHttp.host` | `127.0.0.1` | Admin HTTP bind host. |
| `cachedb.config.adminHttp.port` | `0` | Admin HTTP portu. `0` ise cagirici explicit set eder. |
| `cachedb.config.adminHttp.backlog` | `64` | HTTP socket backlog. |
| `cachedb.config.adminHttp.workerThreads` | `2` | HTTP worker thread sayisi. |
| `cachedb.config.adminHttp.dashboardEnabled` | `true` | HTML dashboard'u servis eder. |
| `cachedb.config.adminHttp.corsEnabled` | `false` | Permissive CORS header'larini acar. |
| `cachedb.config.adminHttp.dashboardTitle` | `CacheDB Admin` | Dashboard baslik metni. |
| `cachedb.config.schemaBootstrap.mode` | `DISABLED` | Schema bootstrap modu. |
| `cachedb.config.schemaBootstrap.autoApplyOnStart` | `false` | Startup'ta schema bootstrap uygular. |
| `cachedb.config.schemaBootstrap.includeVersionColumn` | `true` | Uretilen DDL'e version kolonu ekler. |
| `cachedb.config.schemaBootstrap.includeDeletedColumn` | `true` | Uretilen DDL'e deleted kolonu ekler. |
| `cachedb.config.schemaBootstrap.schemaName` | bos | Hedef PostgreSQL schema adi. |

## Yapisal Override Formatlari

`retryOverrides`:

```text
entityName,operationType,maxRetries,backoffMillis|entityName,*,maxRetries,backoffMillis
```

`entityFlushPolicies`:

```text
entityName,operationType,stateCompactionEnabled,preferCopy,preferMultiRow,maxBatchSize,statementRowLimit,copyThreshold,persistenceSemantics
```

`redisGuardrail.entityPolicies`:

```text
namespace,shedPageCacheWrites,shedReadThroughCache,shedHotSetTracking,shedQueryIndexWrites,shedQueryIndexReads,shedPlannerLearning,autoRebuildIndexes
```

`redisGuardrail.queryPolicies`:

```text
namespace,queryClass,shedReads,shedLearning
```

`incidentEmail.toAddresses` ve `incidentEmail.pinnedServerCertificateSha256`:

```text
value1,value2,value3
```

`postgres.additionalParameters`:

```text
key=value;key=value
```

## Diger Tuning Alanlari Nerede

- Benchmark ve certification'a ozel yuk parametreleri [cachedb-production-tests/README.md](/E:/ReactorRepository/cache-database/tr/cachedb-production-tests/README.md) icinde kalmaya devam eder.
- Demo'ya ozel URL, port ve yuk profili ayarlari [cachedb-examples/README.md](/E:/ReactorRepository/cache-database/tr/cachedb-examples/README.md) icinde ayrica listelenir.

## Demo Runtime Tuning

Bu property'ler basit load demo davranisini kod degistirmeden ayarlamak icin kullanilir.

| Property | Default | Ne ise yarar |
| --- | --- | --- |
| `cachedb.demo.cache.hotEntityLimit` | `100` | Demo entity hot-set limiti. |
| `cachedb.demo.cache.pageSize` | `20` | Demo repository page boyutu. |
| `cachedb.demo.cache.entityTtlSeconds` | `600` | Demo entity TTL suresi. |
| `cachedb.demo.cache.pageTtlSeconds` | `120` | Demo page TTL suresi. |
| `cachedb.demo.bindHost` | `0.0.0.0` | Demo ve admin HTTP bind host'u. |
| `cachedb.demo.publicHost` | `127.0.0.1` | Demo/admin URL'lerinde gosterilen public host. |
| `cachedb.demo.admin.port` | `8080` | Demo main icindeki admin dashboard portu. |
| `cachedb.demo.admin.workerThreads` | `2` | Demo main icindeki admin HTTP worker thread sayisi. |
| `cachedb.demo.ui.port` | `8090` | Demo load UI portu. |
| `cachedb.demo.keyPrefix` | `cachedb-demo` | Demo namespace icin Redis key prefix'i. |
| `cachedb.demo.functionPrefix` | `demo_<uuid>` | Demo process icin Redis Function library/function isim prefix'i. |
| `cachedb.demo.seed.customers` | `48` | Seed edilecek demo customer sayisi. |
| `cachedb.demo.seed.products` | `36` | Seed edilecek demo product sayisi. |
| `cachedb.demo.seed.carts` | `32` | Seed edilecek demo cart sayisi. |
| `cachedb.demo.seed.orders` | `32` | Seed edilecek demo order sayisi. |
| `cachedb.demo.view.pageSize` | `12` | Demo tablolarinda gosterilen satir sayisi. |
| `cachedb.demo.view.countPageSize` | `500` | Demo ekranindaki sayaclar icin repository'den cekilen page boyutu. |
| `cachedb.demo.view.readerPageSize` | `10` | Reader thread'lerinin paging read islerinde kullandigi page boyutu. |
| `cachedb.demo.view.readerPageWindowVariants` | `3` | Reader thread'lerinin dondugu page window sayisi. |
| `cachedb.demo.stop.awaitTerminationMillis` | `5000` | Demo worker'larinin duzgun kapanmasi icin beklenen sure. |
| `cachedb.demo.error.backoffMillis` | `50` | Demo worker hatasindan sonra beklenen geri cekilme suresi. |
| `cachedb.demo.ui.workerThreads` | `2` | Demo UI HTTP worker thread sayisi. |
| `cachedb.demo.ui.autoRefreshMillis` | `3000` | Demo UI tarayici auto-refresh araligi. `0` olursa timer bazli refresh kapanir. |
| `cachedb.demo.load.low.readers` | `4` | LOW profilindeki reader thread sayisi. |
| `cachedb.demo.load.low.writers` | `2` | LOW profilindeki writer thread sayisi. |
| `cachedb.demo.load.low.readerPauseMillis` | `18` | LOW profilindeki reader cycle bekleme suresi. |
| `cachedb.demo.load.low.writerPauseMillis` | `28` | LOW profilindeki writer cycle bekleme suresi. |
| `cachedb.demo.load.medium.readers` | `8` | MEDIUM profilindeki reader thread sayisi. |
| `cachedb.demo.load.medium.writers` | `4` | MEDIUM profilindeki writer thread sayisi. |
| `cachedb.demo.load.medium.readerPauseMillis` | `8` | MEDIUM profilindeki reader cycle bekleme suresi. |
| `cachedb.demo.load.medium.writerPauseMillis` | `14` | MEDIUM profilindeki writer cycle bekleme suresi. |
| `cachedb.demo.load.high.readers` | `16` | HIGH profilindeki reader thread sayisi. |
| `cachedb.demo.load.high.writers` | `8` | HIGH profilindeki writer thread sayisi. |
| `cachedb.demo.load.high.readerPauseMillis` | `2` | HIGH profilindeki reader cycle bekleme suresi. |
| `cachedb.demo.load.high.writerPauseMillis` | `5` | HIGH profilindeki writer cycle bekleme suresi. |

## Admin Dashboard UI Tuning

Bu property'ler yerlesik HTTP dashboard metinlerini ve stilini Java koduna girmeden ayarlamak icin kullanilir.

Admin dashboard ayrica aktif effective tuning degerlerini su yuzeylerden gosterir:

- `/api/tuning`
- `/api/tuning/export?format=json|markdown`
- `/api/tuning/flags`
- `/dashboard` icindeki `Current Effective Tuning` bolumu

Bu gorunum iki kaynagi birlestirir:

- aktif `CacheDatabaseConfig` uzerinden turetilen effective core degerler
- `cachedb.` ile baslayan explicit JVM/system-property override'lari

| Property | Default | Ne ise yarar |
| --- | --- | --- |
| `cachedb.admin.ui.bootstrapCssUrl` | Bootstrap 5.3.3 CDN URL'i | Admin dashboard'un kullandigi CSS URL'i. |
| `cachedb.admin.ui.themeCss` | yerlesik dashboard CSS'i | Admin dashboard sayfasina inject edilen tam CSS blogu. |
| `cachedb.admin.ui.navbarSubtitle` | `Simple HTTP admin dashboard with Bootstrap + AJAX` | Ust navbar'da gosterilen alt baslik. |
| `cachedb.admin.ui.loadingText` | `Loading…` | AJAX bolumleri dolmadan once gosterilen placeholder metin. |
| `cachedb.admin.ui.resetToolsTitle` | `Admin Reset Tools` | Reset tools karti basligi. |
| `cachedb.admin.ui.resetTelemetryLabel` | `Reset Telemetry History` | Telemetry reset buton/bolum etiketi. |
| `cachedb.admin.ui.resetTelemetryDescription` | yerlesik reset aciklamasi | Reset tools kartinda gosterilen ozet metin. |
| `cachedb.admin.ui.resetTelemetryExplainTitle` | `Reset Telemetry History ne yapar?` | Inline reset aciklamasi basligi. |
| `cachedb.admin.ui.resetTelemetryExplainBody` | yerlesik reset aciklamasi | Live refresh karti altindaki aciklama metni. |
| `cachedb.admin.ui.liveRefreshTitle` | `Live Refresh` | Refresh kontrol karti basligi. |
| `cachedb.admin.ui.refreshNowLabel` | `Refresh Now` | Manuel refresh butonu etiketi. |
| `cachedb.admin.ui.toggleRefreshLabel` | `Pause` | Pause/resume butonu etiketi. |
| `cachedb.admin.ui.refreshOptions` | `0:Paused,5000:5 seconds,10000:10 seconds,30000:30 seconds,60000:60 seconds` | Refresh dropdown secenekleri `millis:label` formatinda. |
| `cachedb.admin.ui.defaultRefreshMillis` | `5000` | Varsayilan secili auto-refresh araligi. |
| `cachedb.admin.ui.autoRefreshLabel` | `Auto Refresh` | Auto-refresh dropdown'u ustundeki etiket. |
| `cachedb.admin.ui.lastUpdatedLabel` | `Last Updated` | Last-updated alaninin ustundeki etiket. |
| `cachedb.admin.ui.lastUpdatedNever` | `never` | Ilk AJAX refresh oncesi gosterilen placeholder. |
| `cachedb.admin.ui.resetTelemetryNotRunYet` | `No telemetry reset has been run yet.` | Ilk telemetry reset oncesi gosterilen durum metni. |
| `cachedb.admin.ui.resetTelemetryInProgress` | `Resetting admin telemetry...` | Telemetry reset calisirken gosterilen durum metni. |
| `cachedb.admin.ui.resetTelemetryResultPrefix` | `Cleared: diagnostics ` | Basarili reset ozetinin baslangic metni. |
| `cachedb.admin.ui.resetTelemetryIncidentsSegment` | `, incidents ` | Temizlenen incident sayisi parcasi. |
| `cachedb.admin.ui.resetTelemetryHistorySegment` | `, history ` | Temizlenen monitoring-history sayisi parcasi. |
| `cachedb.admin.ui.resetTelemetryRouteHistorySegment` | `, route history ` | Temizlenen alert-route-history sayisi parcasi. |
| `cachedb.admin.ui.resetTelemetryErrorPrefix` | `Reset failed: ` | Telemetry reset hata mesaji prefix'i. |
| `cachedb.admin.ui.resumeLabel` | `Resume` | Auto-refresh toggle butonunun resume etiketi. |
| `cachedb.admin.ui.pauseLabel` | `Pause` | Auto-refresh toggle butonunun pause etiketi. |
| `cachedb.admin.ui.howToReadTitle` | `How To Read This Dashboard` | Dashboard nasil okunur bolumu basligi. |
| `cachedb.admin.ui.howToRead.step1Title` | `1. First look` | Ilk operator yonlendirme adimi basligi. |
| `cachedb.admin.ui.howToRead.step1Body` | yerlesik yardim metni | Ilk operator yonlendirme adimi aciklamasi. |
| `cachedb.admin.ui.howToRead.step2Title` | `2. Where is the problem?` | Ikinci operator yonlendirme adimi basligi. |
| `cachedb.admin.ui.howToRead.step2Body` | yerlesik yardim metni | Ikinci operator yonlendirme adimi aciklamasi. |
| `cachedb.admin.ui.howToRead.step3Title` | `3. What should I do next?` | Ucuncu operator yonlendirme adimi basligi. |
| `cachedb.admin.ui.howToRead.step3Body` | yerlesik yardim metni | Ucuncu operator yonlendirme adimi aciklamasi. |
| `cachedb.admin.ui.sectionGuideTitle` | `Section Guide` | Section guide bolumu basligi. |
| `cachedb.admin.ui.sectionGuide.liveTrendsTitle` | `Live Trends` | Section guide icindeki live-trends basligi. |
| `cachedb.admin.ui.sectionGuide.liveTrendsBody` | yerlesik guide metni | Section guide icindeki live-trends aciklamasi. |
| `cachedb.admin.ui.sectionGuide.triageTitle` | `Triage` | Section guide icindeki triage basligi. |
| `cachedb.admin.ui.sectionGuide.triageBody` | yerlesik guide metni | Section guide icindeki triage aciklamasi. |
| `cachedb.admin.ui.sectionGuide.topSignalsTitle` | `Top Failing Signals` | Section guide icindeki top-signals basligi. |
| `cachedb.admin.ui.sectionGuide.topSignalsBody` | yerlesik guide metni | Section guide icindeki top-signals aciklamasi. |
| `cachedb.admin.ui.sectionGuide.routingTitle` | `Alert Routing / Runbooks` | Section guide icindeki routing/runbooks basligi. |
| `cachedb.admin.ui.sectionGuide.routingBody` | yerlesik guide metni | Section guide icindeki routing/runbooks aciklamasi. |
| `cachedb.admin.ui.liveTrendsTitle` | `Live Trends` | Live trend bolumu basligi. |
| `cachedb.admin.ui.liveTrends.backlogLabel` | `Write-behind backlog` | Backlog sparkline ustundeki etiket. |
| `cachedb.admin.ui.liveTrends.redisMemoryLabel` | `Redis memory` | Memory sparkline ustundeki etiket. |
| `cachedb.admin.ui.liveTrends.deadLetterLabel` | `Dead-letter backlog` | DLQ sparkline ustundeki etiket. |
| `cachedb.admin.ui.alertRouteTrendsTitle` | `Alert Route Trends` | Alert-route trend bolumu basligi. |
| `cachedb.admin.ui.alertRouteTrends.deliveredLabel` | `Channel delivered count` | Route-delivered trend grafigi ustundeki etiket. |
| `cachedb.admin.ui.alertRouteTrends.failedLabel` | `Channel failed count` | Route-failed trend grafigi ustundeki etiket. |
| `cachedb.admin.ui.incidentSeverityTrendsTitle` | `Incident Severity Trends` | Incident severity trend bolumu basligi. |
| `cachedb.admin.ui.topFailingSignalsTitle` | `Top Failing Signals` | Top failing signals bolumu basligi. |
| `cachedb.admin.ui.failingSignals.activeRecentPrefix` | `active ` | Active failing-signal sayisi on eki. |
| `cachedb.admin.ui.failingSignals.activeRecentSeparator` | ` / recent ` | Active ve recent sayilari arasindaki ayirici. |
| `cachedb.admin.ui.failingSignals.lastSeenPrefix` | `last seen ` | Failing-signal son gorulme zamani on eki. |
| `cachedb.admin.ui.triageTitle` | `Triage` | Triage karti basligi. |
| `cachedb.admin.ui.triage.primaryBottleneckPrefix` | `Primary bottleneck: ` | Mevcut darboğaz degerinden once gosterilen prefix. |
| `cachedb.admin.ui.serviceStatusTitle` | `Service Status` | Service-status karti basligi. |
| `cachedb.admin.ui.healthTitle` | `Health` | Health karti basligi. |
| `cachedb.admin.ui.incidentsTitle` | `Incidents` | Incidents karti basligi. |
| `cachedb.admin.ui.deploymentTitle` | `Deployment` | Deployment bolumu basligi. |
| `cachedb.admin.ui.schemaStatusTitle` | `Schema Status` | Schema-status bolumu basligi. |
| `cachedb.admin.ui.schemaHistoryTitle` | `Schema History` | Schema-history bolumu basligi. |
| `cachedb.admin.ui.starterProfilesTitle` | `Starter Profiles` | Starter-profiles bolumu basligi. |
| `cachedb.admin.ui.apiRegistryTitle` | `API Registry` | API-registry bolumu basligi. |
| `cachedb.admin.ui.currentEffectiveTuningTitle` | `Current Effective Tuning` | Effective-tuning bolumu basligi. |
| `cachedb.admin.ui.tuning.capturedAtLabel` | `Captured at` | Tuning snapshot zamani ustundeki etiket. |
| `cachedb.admin.ui.tuning.overrideCountLabel` | `Explicit overrides` | Explicit override sayisi ustundeki etiket. |
| `cachedb.admin.ui.tuning.entryCountLabel` | `Visible entries` | Gosterilen tuning satiri sayisi ustundeki etiket. |
| `cachedb.admin.ui.tuning.exportJsonLabel` | `Export JSON` | JSON tuning export buton etiketi. |
| `cachedb.admin.ui.tuning.exportMarkdownLabel` | `Export Markdown` | Markdown tuning export buton etiketi. |
| `cachedb.admin.ui.tuning.copyFlagsLabel` | `Copy Startup Flags` | Effective tuning'i `-D...` flag olarak kopyalama buton etiketi. |
| `cachedb.admin.ui.tuning.exportStatusIdle` | `Choose an export action.` | Tuning export alani icin ilk yardim metni. |
| `cachedb.admin.ui.tuning.exportLoading` | `Loading export...` | Export yuklenirken gosterilen durum metni. |
| `cachedb.admin.ui.tuning.exportJsonSuccess` | `JSON export loaded below.` | JSON export yuklendikten sonra gosterilen durum metni. |
| `cachedb.admin.ui.tuning.exportMarkdownSuccess` | `Markdown export loaded below.` | Markdown export yuklendikten sonra gosterilen durum metni. |
| `cachedb.admin.ui.tuning.copyFlagsSuccess` | `Startup flags copied to clipboard.` | Startup flag'lar panoya kopyalandiktan sonra gosterilen durum metni. |
| `cachedb.admin.ui.tuning.exportErrorPrefix` | `Export failed: ` | Tuning export hata prefix'i. |
| `cachedb.admin.ui.certificationTitle` | `Certification` | Certification bolumu basligi. |
| `cachedb.admin.ui.alertRoutingTitle` | `Alert Routing` | Alert-routing bolumu basligi. |
| `cachedb.admin.ui.runbooksTitle` | `Runbooks` | Runbooks bolumu basligi. |
| `cachedb.admin.ui.alertRouteHistoryTitle` | `Alert Route History` | Alert-route history bolumu basligi. |
| `cachedb.admin.ui.schemaDdlTitle` | `Schema DDL` | Schema DDL bolumu basligi. |
| `cachedb.admin.ui.runtimeProfileChurnTitle` | `Runtime Profile Churn` | Runtime profile churn bolumu basligi. |
| `cachedb.admin.ui.explainTitle` | `Explain` | Explain bolumu basligi. |
| `cachedb.admin.ui.explain.entityLabel` | `Entity` | Explain entity alani etiketi. |
| `cachedb.admin.ui.explain.filterLabel` | `Filter` | Explain filter alani etiketi. |
| `cachedb.admin.ui.explain.sortLabel` | `Sort` | Explain sort alani etiketi. |
| `cachedb.admin.ui.explain.limitLabel` | `Limit` | Explain limit alani etiketi. |
| `cachedb.admin.ui.explain.includeLabel` | `Include` | Explain include alani etiketi. |
| `cachedb.admin.ui.runExplainLabel` | `Run Explain` | Explain aksiyon butonu etiketi. |
| `cachedb.admin.ui.metric.writeBehindTitle` | `Write-behind` | En ustteki write-behind metrik kart etiketi. |
| `cachedb.admin.ui.metric.deadLetterTitle` | `Dead-letter` | En ustteki DLQ metrik kart etiketi. |
| `cachedb.admin.ui.metric.diagnosticsTitle` | `Diagnostics` | En ustteki diagnostics metrik kart etiketi. |
| `cachedb.admin.ui.metric.incidentsTitle` | `Incidents` | En ustteki incident metrik kart etiketi. |
| `cachedb.admin.ui.metric.learnedStatsTitle` | `Learned Stats` | En ustteki learned-stats metrik kart etiketi. |
| `cachedb.admin.ui.metric.redisMemoryTitle` | `Redis Memory` | En ustteki Redis memory metrik kart etiketi. |
| `cachedb.admin.ui.metric.compactionPendingTitle` | `Compaction Pending` | En ustteki compaction-pending metrik kart etiketi. |
| `cachedb.admin.ui.metric.runtimeProfileTitle` | `Runtime Profile` | En ustteki runtime-profile metrik kart etiketi. |
| `cachedb.admin.ui.metric.alertDeliveredTitle` | `Alert Delivered` | En ustteki delivered alert metrik kart etiketi. |
| `cachedb.admin.ui.metric.alertFailedTitle` | `Alert Failed` | En ustteki failed alert metrik kart etiketi. |
| `cachedb.admin.ui.metric.alertDroppedTitle` | `Alert Dropped` | En ustteki dropped alert metrik kart etiketi. |
| `cachedb.admin.ui.metric.criticalSignalsTitle` | `Critical Signals` | En ustteki critical-signal metrik kart etiketi. |
| `cachedb.admin.ui.metric.warningSignalsTitle` | `Warning Signals` | En ustteki warning-signal metrik kart etiketi. |
| `cachedb.admin.ui.empty.highSignalFailures` | `No high-signal failures detected.` | Top-failing-signals bolumu bosken gosterilen metin. |
| `cachedb.admin.ui.empty.triageEvidence` | `No triage evidence` | Triage evidence tablosu bosken gosterilen metin. |
| `cachedb.admin.ui.empty.services` | `No services` | Service status bosken gosterilen metin. |
| `cachedb.admin.ui.empty.activeIssues` | `No active issues` | Health issue listesi bosken gosterilen metin. |
| `cachedb.admin.ui.empty.activeIncidents` | `No active incidents` | Incident listesi bosken gosterilen metin. |
| `cachedb.admin.ui.empty.profileSwitches` | `No profile switches` | Runtime-profile churn bosken gosterilen metin. |
| `cachedb.admin.ui.empty.deploymentData` | `No deployment data` | Deployment satirlari bosken gosterilen metin. |
| `cachedb.admin.ui.empty.schemaData` | `No schema data` | Schema status bosken gosterilen metin. |
| `cachedb.admin.ui.empty.migrationHistory` | `No migration history` | Schema history bosken gosterilen metin. |
| `cachedb.admin.ui.empty.starterProfiles` | `No starter profiles` | Starter profiles bosken gosterilen metin. |
| `cachedb.admin.ui.empty.registeredEntities` | `No registered entities` | Registry bosken gosterilen metin. |
| `cachedb.admin.ui.empty.tuning` | `No tuning data` | Effective-tuning tablosu bosken gosterilen metin. |
| `cachedb.admin.ui.empty.certificationReports` | `No certification reports` | Certification artefact bolumu bosken gosterilen metin. |
| `cachedb.admin.ui.empty.alertRoutes` | `No alert routes` | Alert routing bosken gosterilen metin. |
| `cachedb.admin.ui.empty.routeHistory` | `No route history` | Alert-route history bosken gosterilen metin. |
| `cachedb.admin.ui.empty.runbooks` | `No runbooks` | Runbooks bosken gosterilen metin. |
| `cachedb.admin.ui.deployment.autoApplyLabel` | `auto-apply` | Schema auto-apply acikken deployment satir etiketi. |
| `cachedb.admin.ui.deployment.manualLabel` | `manual` | Schema apply manuelken deployment satir etiketi. |
| `cachedb.admin.ui.deployment.writeBehindOnLabel` | `write-behind on` | Write-behind acikken deployment satir etiketi. |
| `cachedb.admin.ui.deployment.writeBehindOffLabel` | `write-behind off` | Write-behind kapaliyken deployment satir etiketi. |
| `cachedb.admin.ui.deployment.workersSuffix` | ` workers` | Write-behind worker sayisina eklenen sonek. |
| `cachedb.admin.ui.deployment.durableCompactionLabel` | `durable compaction` | Durable compaction acikken deployment satir etiketi. |
| `cachedb.admin.ui.deployment.noCompactionLabel` | `no compaction` | Durable compaction kapaliyken deployment satir etiketi. |
| `cachedb.admin.ui.deployment.activeStreamsSuffix` | ` active streams` | Active stream sayisina eklenen sonek. |
| `cachedb.admin.ui.deployment.guardrailsOnLabel` | `guardrails on` | Guardrails acikken deployment satir etiketi. |
| `cachedb.admin.ui.deployment.guardrailsOffLabel` | `guardrails off` | Guardrails kapaliyken deployment satir etiketi. |
| `cachedb.admin.ui.deployment.autoProfileSwitchLabel` | `auto profile switch` | Otomatik profile switching acikken deployment satir etiketi. |
| `cachedb.admin.ui.deployment.manualProfileLabel` | `manual profile` | Otomatik profile switching kapaliyken deployment satir etiketi. |
| `cachedb.admin.ui.deployment.keyPrefixLabel` | `key prefix` | Aktif key prefix satir etiketi. |
| `cachedb.admin.ui.schema.migrationStepsLabel` | `migration steps` | Schema-status icindeki migration-steps etiketi. |
| `cachedb.admin.ui.schema.createTableStepsLabel` | `create table steps` | Schema-status icindeki create-table-steps etiketi. |
| `cachedb.admin.ui.schema.addColumnStepsLabel` | `add column steps` | Schema-status icindeki add-column-steps etiketi. |
| `cachedb.admin.ui.schema.ddlEntitiesLabel` | `ddl entities` | Schema-status icindeki ddl-entities etiketi. |
| `cachedb.admin.ui.schema.stepsPrefix` | `steps=` | Schema-history step ozetlerinde kullanilan prefix. |
| `cachedb.admin.ui.profiles.guardrailsEnabledLabel` | `guardrails` | Guardrails acikken starter-profile satir etiketi. |
| `cachedb.admin.ui.profiles.guardrailsDisabledLabel` | `no guardrails` | Guardrails kapaliyken starter-profile satir etiketi. |
| `cachedb.admin.ui.registry.columnsPrefix` | `cols=` | Registry kolon sayisi ozet prefix'i. |
| `cachedb.admin.ui.registry.hotPrefix` | `hot=` | Registry hot-entity ozet prefix'i. |
| `cachedb.admin.ui.registry.pagePrefix` | `page=` | Registry page-size ozet prefix'i. |
| `cachedb.admin.ui.alertRouting.deliveredLabel` | `delivered` | Route son durum fallback etiketi, delivered aktivite varsa. |
| `cachedb.admin.ui.alertRouting.idleLabel` | `idle` | Route son durum fallback etiketi, aktivite yoksa. |
| `cachedb.admin.ui.chart.trend.backlogColor` | `#9c3f2b` | Backlog trend sparkline cizgi rengi. |
| `cachedb.admin.ui.chart.trend.memoryColor` | `#2563eb` | Memory trend sparkline cizgi rengi. |
| `cachedb.admin.ui.chart.trend.deadLetterColor` | `#dc2626` | DLQ trend sparkline cizgi rengi. |
| `cachedb.admin.ui.chart.backgroundColor` | `#fbf7ef` | Yerlesik grafiklerde kullanilan ortak SVG arka plan rengi. |
| `cachedb.admin.ui.chart.axisColor` | `#c9baa2` | Yerlesik grafiklerde kullanilan ortak eksen/kilavuz rengi. |
| `cachedb.admin.ui.chart.mutedTextColor` | `#7b8794` | Yerlesik grafiklerde kullanilan soluk aciklama rengi. |
| `cachedb.admin.ui.chart.route.webhookColor` | `#9c3f2b` | `webhook` route trend rengi. |
| `cachedb.admin.ui.chart.route.queueColor` | `#2563eb` | `queue` route trend rengi. |
| `cachedb.admin.ui.chart.route.smtpColor` | `#0f766e` | `smtp` route trend rengi. |
| `cachedb.admin.ui.chart.route.deliveryDlqColor` | `#dc2626` | `delivery-dlq` route trend rengi. |
| `cachedb.admin.ui.chart.route.fallbackColor` | `#1d2525` | Bilinmeyen kanallar icin fallback route trend rengi. |
| `cachedb.admin.ui.chart.severity.criticalColor` | `#dc2626` | `critical` incident severity trend rengi. |
| `cachedb.admin.ui.chart.severity.warningColor` | `#d97706` | `warning` incident severity trend rengi. |
| `cachedb.admin.ui.chart.severity.infoColor` | `#2563eb` | `info` incident severity trend rengi. |
| `cachedb.admin.ui.chart.churn.lineColor` | `#9c3f2b` | Runtime-profile churn cizgi rengi. |
| `cachedb.admin.ui.chart.churn.dotColor` | `#1d2525` | Runtime-profile churn nokta rengi. |
| `cachedb.admin.ui.chart.churn.axisTextColor` | `#6d5e49` | Runtime-profile churn eksen yazi rengi. |
| `cachedb.admin.ui.chart.profile.aggressiveLabel` | `AGGRESSIVE` | Aggressive profile eksen etiketi. |
| `cachedb.admin.ui.chart.profile.balancedLabel` | `BALANCED` | Balanced profile eksen etiketi. |
| `cachedb.admin.ui.chart.profile.standardLabel` | `STANDARD` | Standard profile eksen etiketi. |

## Benchmark Catalog Tuning

Bu property'ler production-test senaryo kataloglarini yerlesik degerler yerine tamamen disaridan tanimlamani saglar.

| Property | Default | Ne ise yarar |
| --- | --- | --- |
| `cachedb.prod.catalog.scenarios` | yerlesik base scenario catalog | `ScenarioCatalog` listesini tamamen degistirir. |
| `cachedb.prod.catalog.fullScaleScenarios` | yerlesik 50k scenario catalog | `FullScaleBenchmarkCatalog` listesini tamamen degistirir. |
| `cachedb.prod.catalog.representativeScenarioNames` | `campaign-push-spike-50k,weekend-browse-storm-50k,write-behind-backpressure-50k` | Representative benchmark senaryo secimini degistirir. |

`cachedb.prod.catalog.scenarios` ve `cachedb.prod.catalog.fullScaleScenarios` formati:

```text
name;kind;description;targetTps;durationSeconds;workerThreads;customerCount;productCount;hotProductSetSize;browsePercent;productLookupPercent;cartWritePercent;inventoryReservePercent;checkoutPercent;customerTouchPercent;writeBehindWorkerThreads;writeBehindBatchSize;hotEntityLimit;pageSize;entityTtlSeconds;pageTtlSeconds
```

Birden fazla senaryo `|` ile ayrilir.
