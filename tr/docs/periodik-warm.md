# Periyodik Warm ve Aktif Veri Seti Uzlaştırması

English version: [../../docs/scheduled-warm.md](../../docs/scheduled-warm.md)

Mevcut SQL verisi belirli aralıklarla Redis'e alınacaksa ve aktif veri
politikasının dışında kalan kayıtlar Redis'ten artımlı olarak temizlenecekse
`@CacheScheduledWarm` kullanılır.

Bu özellik, veritabanının tamamını sürekli eşitleyen sınırsız bir mekanizma
değildir. Annotation eklenen her metot; açık, indeksli ve sınırlandırılmış tek
bir `CacheWarmPlan` döndürür.

## Hangi Davranışı Garanti Eder?

| Olay | CacheDB davranışı |
| --- | --- |
| Yazı CacheDB üzerinden gelir | Kayıt önce Redis'e alınır, ardından write-behind ile SQL'e kalıcı olarak yazılır. Bu kayıt için ayrıca periyodik warm gerekmez. |
| Başka bir uygulama doğrudan SQL'e yazar | Sorgu kaydı seçiyorsa ve aktif veri politikası kabul ediyorsa, kayıt bir sonraki başarılı warm çevriminde Redis'e gelir. Bir çevrimlik gecikme kabul edilmiyorsa outbox/CDC kullanılmalıdır. |
| Redis'teki kayıt `TIME_WINDOW` süresinin dışına çıkar | Repository okuması policy'yi yeniden değerlendirir ve eski kaydı döndürmez. Reconciliation, entity, indeks ve projection verisini Redis'ten artımlı olarak kaldırır. |
| Warm çalışırken pod kapanır | Redis lease süresi dolar. Sonraki tetiklemede başka bir pod işi alabilir. Hydration sürüm kontrolü, eski sonucun Redis'teki yeni kaydı ezmesini engeller. |
| Birden fazla pod aynı anda tetiklenir | Yalnızca bir pod lease sahibi olur. Diğerleri en fazla `lockWaitTimeoutString` kadar bekler; tamamlanma kaydını görür veya o çevrimi atlar. |

Periyodik çalışma, SQL'e doğrudan yazılan kaydı sıfır gecikmeyle fark edemez.
Bu ihtiyaç varsa [Outbox ve CDC Apply Runner](outbox-cdc-apply-runner.md)
kullanılmalı; periyodik warm ise emniyet amaçlı uzlaştırma döngüsü olarak
kalmalıdır.

## Kopyala-Çalıştır Örneği: Son Dönem veya Aktif Siparişler

Named query ile entity aktif veri politikası aynı iş kuralını anlatmalıdır.
Sorgunun filter ve sort kolonlarını karşılayan bir SQL indeksi de bulunmalıdır.

```java
@CacheNamedQuery("activeOrderWindow")
public static QuerySpec activeOrderWindowQuery(long cutoffEpochSeconds, int limit) {
    return QuerySpec.anyOf(
                    QueryFilter.gte("order_date", cutoffEpochSeconds),
                    QueryFilter.in(
                            "status",
                            List.<Object>of("NEW", "PAID", "PICKING", "OPEN", "PENDING")
                    )
            )
            .orderBy(QuerySort.desc("order_date"), QuerySort.desc("order_id"))
            .limitTo(limit);
}
```

Zamanlanan metot deklaratiftir. Parametre almaz ve `CacheWarmPlan` döndürür.
Zamanlama, Redis kilidi, heartbeat, tekrar çalıştırmayı önleyen tamamlanma kaydı
ve artımlı temizlik CacheDB tarafından yönetilir.

```java
@Component
public final class ScheduledWarmPlans {

    private final GeneratedCacheModule.Scope domain;
    private final int maxRows;

    public ScheduledWarmPlans(
            GeneratedCacheModule.Scope domain,
            @Value("${app.warm.orders.max-rows:1000}") int maxRows
    ) {
        this.domain = domain;
        this.maxRows = maxRows;
    }

    @CacheScheduledWarm(
            name = "active-order-window",
            enabledString = "${app.warm.orders.enabled:true}",
            fixedDelayString = "${app.warm.orders.fixed-delay:PT15M}",
            initialDelayString = "${app.warm.orders.initial-delay:PT30S}",
            lockAtMostForString = "${app.warm.orders.lock-at-most-for:PT2M}",
            lockWaitTimeoutString = "${app.warm.orders.lock-wait-timeout:PT20S}",
            minimumIntervalString = "${app.warm.orders.minimum-interval:PT15M}",
            reconcileHotSet = true,
            reconcileMaxRowsPerRunString = "${app.warm.orders.reconcile-max-rows:10000}",
            reconcileScanCountString = "${app.warm.orders.reconcile-scan-count:500}"
    )
    public CacheWarmPlan activeOrderWindow() {
        long cutoff = Instant.now().minus(Duration.ofDays(90)).getEpochSecond();
        return domain.orders().warmPlan(
                "active-order-window",
                domain.orders().queries().activeOrderWindowQuery(cutoff, maxRows),
                maxRows
        );
    }
}
```

`application.yml` içindeki karşılığı şöyledir:

```yaml
cachedb:
  registration:
    source: jdbc
    entities:
      OrderEntity:
        hot-entity-limit: 100000
        entity-ttl-seconds: 0
        hot-policy:
          mode: COMPOSITE
          composite-operator: ANY
          children:
            - mode: TIME_WINDOW
              time-column: order_date
              hot-for-seconds: 7776000
            - mode: STATE_WINDOW
              state-column: status
              state-values: [NEW, PAID, PICKING, OPEN, PENDING]
  scheduled-warm:
    enabled: true
    scheduler-pool-size: 2
    heartbeat-threads: 1
```

Bu örnek **son 90 gün VEYA aktif durumdaki siparişler** anlamına gelir. Redis'te
yalnızca son 90 gün tutulacaksa tek bir `TIME_WINDOW` policy kullanılmalı; status
kolu hem policy'den hem sorgudan çıkarılmalıdır.

## Çok Pod'lu Çalışma

Tüm pod'lar aynı iş adını ve zamanlamayı kaydeder. Tetikleme geldiğinde:

1. Her pod Redis'teki son tamamlanma zamanını kontrol eder.
2. Bir pod, `<keyPrefix>:coordination:scheduled-warm:<job>:lock` anahtarını `SET NX PX` ile alır.
3. JDBC warm sürerken lease, ayrı heartbeat executor üzerinden yenilenir.
4. Diğer pod'lar sınırlı aralıklarla bekler; lease tutulurken JDBC loader'ı çalıştırmaz.
5. Lease sahibi Redis'i doldurur, artımlı reconciliation adımını çalıştırır, lease sahipliğini yeniden doğrular ve tamamlanma zamanını yazar.
6. Bekleyen pod tamamlanma kaydını yeniden okur ve aynı işi ikinci kez çalıştırmaz.

Lease bir koordinasyon sınırıdır; distributed transaction değildir. Redis
bağlantısı koparsa veya JVM lease süresinden uzun duraklarsa çalışma
`LEASE_LOST` olarak işaretlenir ve tamamlanma kaydı yazılmaz. Sonraki çevrim aynı
sınırlı planı yeniden çalıştırabilir. Bu nedenle warm hydration idempotent ve
sürüm kontrollü kalmalıdır.

## Annotation Parametreleri

| Parametre | Varsayılan | Kullanım kuralı |
| --- | --- | --- |
| `name` | sınıf ve metot adı | Uygulama genelinde benzersiz ve deployment'lar arasında sabit olmalıdır. İş adı Redis'te tek bir entity ile eşleştirilir. |
| `cron`, `fixedDelayString`, `fixedRateString` | boş | Yalnızca biri tanımlanır. Sürelerde ISO-8601 (`PT15M`) veya `ms`, `s`, `m`, `h`, `d` kullanılabilir. |
| `zone` | sistem saat dilimi | Yalnızca `cron` ile kullanılır. Takvime bağlı işlerde açık saat dilimi ver. |
| `enabledString` | `true` | Spring property placeholder kabul eder; ortama göre açma ve kapama için kullanılır. |
| `mode` | `ENTITY_AND_PROJECTIONS` | Diğer seçenekler `PROJECTIONS_ONLY` ve `DRY_RUN` değerleridir. Reconciliation yalnızca `ENTITY_AND_PROJECTIONS` ile kullanılabilir. |
| `lockAtMostForString` | `PT5M` | Pod kaybolursa lease'in ne kadar sonra serbest kalacağını belirler. Çalışma boyunca yenilenir ve en az bir saniye olmalıdır. İş süresinden büyük olmak zorunda değildir. |
| `lockWaitTimeoutString` | `PT30S` | Lease alamayan pod'un mevcut tetikleme için en fazla ne kadar bekleyeceğini belirler. |
| `lockRetryIntervalString` | `PT0.25S` | Bekleyen pod'un lease'i yeniden deneme aralığıdır. |
| `minimumIntervalString` | zamanlama aralığı | Başarılı çalışmadan sonra cluster genelinde aynı işi tekrar etmeme süresidir. Cron işlerinde açıkça verilmelidir. |
| `reconcileHotSet` | `false` | Redis'teki entity'leri aktif veri policy'sine göre yeniden değerlendirir; reddedilen entity ve projection verisini SQL'e dokunmadan kaldırır. |
| `reconcileMaxRowsPerRunString` | `10000` | Bir tetiklemede incelenecek kayıtlar için sınırlı hedeftir. Redis scan sayısı bir ipucu olduğu için gerçekleşen sayı küçük miktarda aşılabilir. |
| `reconcileScanCountString` | `500` | Redis `ZSCAN COUNT` ipucudur. Uzun süren Redis çağrılarını önlemek için sınırlı tutulmalıdır. |

## Kapasite ve Gecikme Hesabı

Warm girdisi hem `CacheWarmPlan.maxRows` hem de JDBC loader/read guardrail
değerleriyle sınırlıdır. Zamanlanan plandaki satır sayısı `maxQueryLoadRows`
değerini aşmamalıdır.

Temizlik süresi yaklaşık olarak şöyle hesaplanır:

```text
tahmini tam temizlik çevrimi = ceil(aktif kayıt / çevrimde incelenen kayıt) * zamanlama aralığı
```

Örnek: `100.000 / 10.000 * 15 dakika`, tüm setin yaklaşık 150 dakikada
taranması demektir. Redis `SCAN` ailesi cursor ile çalıştığı ve tarama sırasında
set değişebildiği için bu değer kesin SLA değil, kapasite tahminidir.

Fiziksel silme için 15 dakikalık hedef varsa her çalışmada tüm sınırlı aktif set
incelenmeli veya zamanlama aralığı kısaltılmalıdır. Değerleri büyütmeden önce
Redis CPU, SQL gecikmesi, connection pool doluluğu ve JVM GC birlikte
ölçülmelidir.

## Durum Takibi ve Hata Davranışı

Yerel çalışma durumunu göstermek için `CacheScheduledWarmRegistry` kullanılabilir:

```java
@GetMapping("/ops/cache-warm")
List<CacheScheduledWarmSnapshot> scheduledWarm() {
    return registry.snapshots();
}
```

Durumlar `REGISTERED`, `RUNNING`, `COMPLETED`, `SKIPPED_NOT_DUE`,
`SKIPPED_LOCK_TIMEOUT`, `FAILED` ve `LEASE_LOST` değerleridir. Registry pod'a
özeldir; merkezi alarm sistemi tüm pod log ve metriklerini toplamalıdır. Örnek
projelerde aynı bilgi `GET /api/warm/schedules` üzerinden gösterilir.

Reconciliation ilerlemesi için `lastInspectedRows`, `lastEvictedRows`,
`lastMissingRows`, `lastInvalidRows`, `lastReconciliationCursor` ve
`lastReconciliationCycleCompleted` alanları izlenmelidir. Eksik veya okunamayan
Redis payload'ları yalnızca cache'ten çıkarılır; böylece bozuk bir kayıt cursor
ilerlemesini durdurmaz. Cursor yeniden `0` olduğunda tam tarama çevrimi bitmiştir.

## Production Sınıflandırması

- **BEST:** sınırlı ve indeksli sorgu, aynı kuralı kullanan aktif veri policy'si, listelerde projection, Redis lease, reconciliation, metrikler ve düşük gecikmeli dış SQL yazıları için outbox/CDC.
- **ACCEPTABLE:** kaynak verinin bir zamanlama aralığı kadar gecikmesi kabul ediliyorsa scheduled warm ve reconciliation.
- **ANTI-PATTERN:** sınırsız tam tablo warm, kota olmadan tenant başına ayrı iş, SQL pool'dan büyük scheduler pool veya scheduled warm'ı sıfır gecikmeli CDC gibi kullanmak.
