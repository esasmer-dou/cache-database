# COUNT/FACET Okuma Modeli

English version: [../../docs/count-facet-read-model.md](../../docs/count-facet-read-model.md)

Belirli bir gözlem yaşını kabul eden panolarda açıkça tanımlanmış bir okuma
modeli kullanılabilir. Yenileme, sınırlandırılmış bir `CountFacetValues`
hesaplar; Redis yayımlanan nesli saklar. Okuyucular entity listesini dolaşmaz
ve kaynak sorgusunu yeniden çalıştırmaz. Bu ayrı bir API'dir; repository
`COUNT` veya `FACET` çağrılarının davranışını kendiliğinden değiştirmez.

```java
CountFacetReadModel model = database.countFacetReadModel(ticketMetadata,
        new CountFacetDefinition("support-ticket-status-v1",
                Duration.ofSeconds(15), Duration.ofMinutes(5), Duration.ofSeconds(30)));
```

Kaynağın önceden kaydedilmiş gerçek `EntityMetadata<?, ?>` nesnesini verin.
Fabrika, veritabanı nesnesinin Redis bağlantısını ve anahtar stratejisini kullanır.
Metadata bağı, kaynak SQL'i **üretmez veya yetkilendirmez**; supplier için entity
TTL, sahiplik, tombstone ya da satır düzeyi erişim kurallarını da uygulamaz.
Aggregate'in seçim ve güvenlik koşullarını uygulama açıkça sağlamalıdır.
Örtük kaynak TTL yorumu veya canlı sorguya otomatik geçiş yoktur.

## Süreler Ve Sonuçlar

API; `definition()`, `inspect()`, `refresh(Supplier<CountFacetValues>)` ve
`requireFresh()` metotlarını sunar.

| Okuma durumu | Anlamı |
| --- | --- |
| `MISSING` | Saklanan sonuç yoktur; `snapshot()` boştur. |
| `FRESH` | Redis kontrol zamanı ile `observedFrom` arasındaki fark, `maxAge` değerinden kesin olarak küçüktür. |
| `STALE` | Sonuç vardır ancak gözlem yaşı `maxAge` değerine eşit veya daha büyüktür. |

Bu tanımda **yaş tam 15 saniye olduğunda sonuç `STALE` olur**. Güncellik,
supplier çalışmadan önce yenileme lease'i alınırken Redis sunucu saatinden
başlar; yayımlanma zamanından başlamaz. Beş dakikalık saklama süresi ise
yayımlanma anından başlar. Eski sonucun saklanması, güncel kabul edilerek
sunulabileceği anlamına gelmez. `inspect()` yenileme yapmaz, SQL çalıştırmaz.
Depolama, ACL, bozuk durum ve saat tutarlılığı hataları iletilir;
`MISSING` olarak gizlenmez.

| Yenileme durumu | Anlamı |
| --- | --- |
| `PUBLISHED` | Bu çağrı yeni bir nesil yayımlamıştır; `snapshot()` yayımlama sonucunu içerir. |
| `BUSY` | Lease başka bir yenilemededir; bu çağrı supplier'ı çalıştırmamıştır. |
| `LEASE_LOST` | Supplier çalışmıştır ancak çağrı lease sahipliğini kaybettiği için hesapladığı sonucu yayımlamamıştır. |

`requireFresh()`, yalnızca güncel bir okumanın ardından `CountFacetSnapshot`
döndürür; aksi durumda `IllegalStateException` fırlatır. Kendisi yenileme yapmaz.
Yayımlanan sonuç mutlaka güncel değildir: kaynak sorgusu, daha uzun olan lease
süresi içinde kalsa bile `maxAge` süresini tüketebilir. Snapshot;
`generation`, `observedFrom`, `observedThrough`, `publishedAt` ve `values`
alanlarını taşır. Bu zamanlar bir gözlem aralığı belirtir; SQL ile Redis'i
kapsayan bir transaction veya kaynağın tamamı için transaction tutarlılığı
sağladığını göstermez.

## Kopyalanabilir Örnek: Destek Talebi Panosu

Aşağıdaki uygulama sınıfı, üretilmiş bir binding sabiti varsaymak yerine gerçek
metadata nesnesini parametre alır. Önceden kaydedilmiş, tenant alanı bulunan,
**tenant purge koruması etkin olmayan** bir talep entity'si ve onunla eşleşen
SQL `DataSource` nesnesi bekler. Örnek tablo
`support_ticket(tenant_id, status, deleted_at, ...)` biçimindedir. Aynı tenant
için tüm yetkili okuyucular aynı durum toplamlarını görmeye yetkili olmalıdır.
Metotlara girmeden önce tenant kimliğini doğrulayın ve erişimi yetkilendirin;
`CacheTenantContext` bir yetkilendirme servisi değildir.

Tek sorgu en fazla dört grup döndürür; toplam aynı satırlardan hesaplanır.
Sabit SQL'i gerçek şemaya, erişim kurallarına ve geçerlilik politikasına uyarlayın.
SQL Server'da seçilen kayıt sayısı `COUNT` fonksiyonunun integer sınırını
aşabilecekse `COUNT(*)` yerine `COUNT_BIG(*)` kullanın.

```java
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.readmodel.CountFacetDefinition;
import com.reactor.cachedb.core.readmodel.CountFacetRead;
import com.reactor.cachedb.core.readmodel.CountFacetReadModel;
import com.reactor.cachedb.core.readmodel.CountFacetRefresh;
import com.reactor.cachedb.core.readmodel.CountFacetSnapshot;
import com.reactor.cachedb.core.readmodel.CountFacetValues;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.starter.CacheDatabase;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;

public final class SupportTicketDashboard {
    private static final System.Logger LOG =
            System.getLogger(SupportTicketDashboard.class.getName());
    private static final Set<String> GROUPS =
            Set.of("OPEN", "PENDING", "RESOLVED", "OTHER");
    private static final CountFacetDefinition DEFINITION = new CountFacetDefinition(
            "support-ticket-status-v1", Duration.ofSeconds(15),
            Duration.ofMinutes(5), Duration.ofSeconds(30));
    private static final String SQL = """
            SELECT dashboard.status_group, COUNT(*) AS ticket_count
            FROM (
                SELECT CASE status
                    WHEN 'OPEN' THEN 'OPEN'
                    WHEN 'PENDING' THEN 'PENDING'
                    WHEN 'RESOLVED' THEN 'RESOLVED'
                    ELSE 'OTHER'
                END AS status_group
                FROM support_ticket
                WHERE tenant_id = ? AND deleted_at IS NULL
            ) dashboard
            GROUP BY dashboard.status_group
            ORDER BY dashboard.status_group
            """;

    private final DataSource sourceDataSource;
    private final CountFacetReadModel model;
    private final Supplier<CountFacetValues> source = this::loadValues;

    public SupportTicketDashboard(CacheDatabase database,
            EntityMetadata<?, ?> ticketMetadata, DataSource sourceDataSource) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(ticketMetadata, "ticketMetadata");
        if (ticketMetadata.tenantFieldName() == null
                || ticketMetadata.tenantFieldName().isBlank()) {
            throw new IllegalArgumentException("A tenant-scoped ticket binding is required");
        }
        this.sourceDataSource = Objects.requireNonNull(sourceDataSource, "sourceDataSource");
        this.model = database.countFacetReadModel(ticketMetadata, DEFINITION);
    }

    public CountFacetRefresh refreshTenant(String authorizedTenant) {
        try (var scope = CacheTenantContext.withTenant(authorizedTenant)) {
            return model.refresh(source);
        }
    }

    public CountFacetRead inspectTenant(String authorizedTenant) {
        try (var scope = CacheTenantContext.withTenant(authorizedTenant)) {
            return model.inspect();
        }
    }

    public CountFacetSnapshot requireFreshTenant(String authorizedTenant) {
        try (var scope = CacheTenantContext.withTenant(authorizedTenant)) {
            return model.requireFresh();
        }
    }

    public ScheduledFuture<?> scheduleTenant(String authorizedTenant,
            ScheduledExecutorService scheduler) {
        Objects.requireNonNull(scheduler, "scheduler");
        return scheduler.scheduleWithFixedDelay(() -> {
            try {
                CountFacetRefresh result = refreshTenant(authorizedTenant);
                LOG.log(System.Logger.Level.DEBUG,
                        "Ticket dashboard refresh: {0}", result.status());
            } catch (RuntimeException failure) {
                // Do not log tenant ids, SQL values, or exception messages here.
                LOG.log(System.Logger.Level.WARNING,
                        "Ticket dashboard refresh failed: {0}",
                        failure.getClass().getSimpleName());
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    private CountFacetValues loadValues() {
        String tenant = CacheTenantContext.currentTenant().orElseThrow();
        var groups = new LinkedHashMap<String, Long>();
        long total = 0;
        try (var connection = sourceDataSource.getConnection();
                var statement = connection.prepareStatement(SQL)) {
            statement.setString(1, tenant);
            statement.setQueryTimeout(5);
            statement.setFetchSize(4);
            statement.setMaxRows(5);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String group = rows.getString(1);
                    long count = rows.getLong(2);
                    if (rows.wasNull() || count <= 0 || !GROUPS.contains(group)
                            || groups.size() == 4 || groups.putIfAbsent(group, count) != null) {
                        throw new IllegalStateException("Invalid ticket aggregate result");
                    }
                    total = Math.addExact(total, count);
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Ticket dashboard source query failed", failure);
        }
        return new CountFacetValues(total, groups);
    }
}
```

İlk yenilemeyi açıkça `refreshTenant(authorizedTenant)` ile yapın; güncel sonucu
`requireFreshTenant(authorizedTenant).values()` ile sunun. Zamanlanmış metot,
worker iş parçacığında tenant bağlamını kurarak **aynı supplier'ı** kullanır.
Yaşam döngüsü yönetilen, sınırlandırılmış ortak bir scheduler verin; dönen işi
saklayıp kapanışta iptal edin. Yalnızca sınırlandırılmış, güvenilir bir tenant
listesini zamanlayın; her gelen istek için yeni iş veya executor oluşturmayın.

Yalnızca güncel veri sunan HTTP ucunda sadece `FRESH` için `200` döndürün;
`MISSING` ve `STALE` durumlarını, aggregate değerleri olmadan açık durum
etiketiyle `503 Service Unavailable` yanıtına eşleyin. Depolama/ACL hatalarını
uydurulmuş `MISSING` yerine ayrı bir kullanılamama yanıtıyla bildirin. Ürün
bilinçli olarak eski sonucu gösteren ayrı bir uç sunuyorsa saklanan değerleri
açık `STALE` etiketi ve gözlem zamanlarıyla döndürebilir; ancak bunları güncel
yanıt gibi sunmamalı veya örtük kaynak sorgusu başlatmamalıdır. Örnekteki
`requireFreshTenant` yalnızca güncel sonuç sözleşmesi içindir; HTTP katmanı
verinin sunulamaması durumunu açıkça eşlemelidir.

Birden fazla pod aynı modeli zamanlayabilir. Benzersiz token taşıyan Redis
`SET NX PX` lease'i çakışan yenileme sahiplerini ayırır; yayımlama aynı token'ı
atomik olarak doğrular. Farklı podların art arda yaptığı yenilemeleri
birleştirmez ve farklı model anahtarlarını koordine etmez. Tüm podların toplam
kaynak sorgusu hızını bütçeleyin ve tetiklemeleri zamana yayın.

## Kaynak Ve Hata Sınırları

- **30 saniyelik lease, kaynak sorgusu zaman aşımı değildir.** Sürenin dolması
  SQL'i durdurmaz; geciken supplier, lease'i kaybettiğinde yayımlama yapamaz.
  Örnekte JDBC sorgu zaman aşımı beş saniyedir. Bağlantı havuzundan beklemeyi,
  sürücü/ağ beklemelerini, scheduler eşzamanlılığını ve Redis işlemlerini de
  sınırlandırın. İşin durması için lease'e güvenmek yerine sürücünün iptal
  davranışını doğrulayın.
- Dört sonuç satırı, kaynağın yalnızca dört satırının incelendiği anlamına
  gelmez. Seçici tenant koşulu ve uygun kaynak indeksi/sorgu planı kullanın;
  kaynak maliyetini ölçüp sınırlandırın. API, sınırsız taramayı ucuzlatmaz.
- `CountFacetValues`, negatif olmayan bir toplam ve en fazla 256 grup kabul
  eder. Anahtarlar geçerli UTF-16 olmalı, her biri en fazla 4.096 UTF-16 birimi
  içermelidir. Grup sayıları pozitif olmalı ve toplamları genel toplamı
  aşmamalıdır. Boş sonuç, sıfır toplam ve boş map'tir. Redis uygulaması ayrıca
  kodlanmış değerleri 4 MiB ile sınırlar; hash alanlarını yüklemeden önce
  uzunluklarını denetler. Ham sorgu snapshot'ının 16 MiB bütçesi bu API'nin
  payload sınırı değildir.
- Snapshot grup adları ve sayıları sıradan, **şifrelenmemiş Redis değerleridir**;
  entity alan şifreleme annotation'ları bu payload'ı şifrelemez. Gizli veya
  kişisel veri içeren boyutlar kullanmayın. Örnekteki gibi sabit, hassas olmayan
  grup etiketlerini tercih edin ve Redis erişimini buna göre sınırlandırın.
- Supplier hatası veya geçersiz sonuç, önceki snapshot'ı değiştirmez; otomatik
  kaynak tekrar denemesi yoktur. Sonraki zamanlanmış çağrı yeniden deneyebilir.
  `BUSY`, `LEASE_LOST`, eski/eksik sonuç ve iletilen hataları açıkça işleyin.
  Yayımlamanın ağ yanıtı kaybolursa hiçbir şey yazılmadığını varsaymayın;
  sınırlandırılmış tekrar denemesine karar vermeden önce saklanan durumu okuyun.
- Yenileme sonuçlarını, kaynak gecikmesini, gözlem yaşını ve eski/eksik
  okumaları izleyin; payload veya gizli tenant verilerini loglamayın. Veri
  sunulamıyorsa uygulamanın açık hata yanıtını kullanın. Sessizce ham aggregate'e
  geçmeyin veya yalnızca güncel veri sunan uçtan eski sonuç döndürmeyin.

Ham entity/indeks, sahiplik, tombstone, süre sonu ve doğrudan SQL değişiklikleri
bu snapshot'ı kendiliğinden geçersiz kılmaz. `FRESH`, tanımlanan gözlem yaşının
sağlandığını belirtir; alttaki her satırın hâlâ aynı olduğunu göstermez.
Anında erişim iptali veya Redis'teki tam güncel üyelik gerekiyorsa bu gözlem
temelli okuma modeli o sözleşmeyi karşılamaz.

## Kimlik Ve Purge Kısıtları

Yapılandırılmış anahtar stratejisi; namespace, tanım adı ve yakalanan tenant
(veya açık global kapsam) ile modeli ayırır. Tenant alanlı model her işlemde
tenant bağlamı ister; global model etkin tenant bağlamını reddeder. Supplier,
yakalanan kapsamda senkron çalışır; bu kapsamı değiştirmemelidir.

Anahtar; SQL provider, `DataSource`, SQL metni, kullanıcı veya filtre
parmak izi içermez. Aynı Redis'i kullanan bağımsız PostgreSQL, SQL Server ve
Oracle veri kümelerini ayrı namespace/anahtar önekleriyle ayırın. Aynı kimliği
paylaşan tüm çağrılar aynı tanımı ve yetkili okuyucu kitlesini kullanmalıdır.
Şema, gruplama, filtre veya güvenlik anlamı değiştiğinde yeni model adı seçin;
bir çağrının başka bir kitlenin toplamını ezmesine izin vermeyin. Anahtarları
elle üretmek yerine yapılandırılmış stratejiyi kullanın; Redis Cluster için
namespace yönlendirmesini de bu strateji sağlar.

Tanımlanan her model/kapsam için kalıcı bir sıra sayacı anahtarı tutulur;
tenant alanlı modellerde her tenant ayrı sayaç kullanır. Saklama TTL'i,
yayımlamadan sonra snapshot'ı sona erdirir; sayacı silmez. Tanımlı kimlik
sayısını bütçeleyin; otomatik sayaç temizliği veya `TenantPurge` entegrasyonu
yoktur. Namespace'i kullanımdan kaldırırken ilgili tüm okuyucular ve
yenileyiciler durdurulmalı, ardından yönetici tarafından koordineli temizlik
yapılmalıdır. Etkin model nesneleri varken sayaçları silmek desteklenen bir
yaşam döngüsü işlemi değildir.

**`tenantPurgeEnabled()` etkin kaynaklar `UnsupportedOperationException` ile
reddedilir.** Mevcut entity purge katılımcıları aggregate hash'lerini envantere
almaz ve gecikmiş yayımlamayı engellemez. Yenileme lease'i ile saklama TTL'i
purge bariyeri değildir; korumalı purge sonrasında grup adları ve sayılarının
kalması veya yeniden yayımlanması güvenli değildir. API'yi kullanmak için
kaynağın korumasını kapatmayın. Korumasız kaynağı korumalı işletime geçirmeden
önce yenileyicileri durdurun, saklanan model verilerini açıkça kaldırıp
yokluğunu doğrulayın; otomatik korumalı model geçişi yoktur.

Bu isteğe bağlı API, ham aggregate davranışını değiştirmez ve ayrı
[T4 performans kapısını](performans-olcumleri.md) kapatmaz. İki kat hızlanma
veya sürümün yayına hazır olduğu iddiasını taşımaz.
