# Kavramlar ve Kabuller

English version: [../../docs/concepts-and-assumptions.md](../../docs/concepts-and-assumptions.md)

Bu doküman, CacheDB kullanırken bilinmesi gereken temel kavramları ve tasarım
kabullerini açıklar. Amaç, yeni bir kullanıcının "hangi davranış otomatik, hangi
davranış bilinçli tasarım gerektiriyor?" sorusunu netleştirmektir.

CacheDB, klasik ORM davranışını bire bir kopyalamaz. Tasarımın merkezinde açık
route kararı, sınırlı sıcak veri, projection/read-model disiplini ve production
kanıtı vardır.

## En Kısa Özet

| Kavram | Kısa açıklama |
| --- | --- |
| Entity | PostgreSQL tablosunu ve Redis'teki hot entity payload'unu temsil eder |
| Repository | Entity üzerinde save, find, query ve delete işlemlerini yapar |
| Hot set | Redis'te tutulmasına izin verilen sıcak veri kümesi |
| Hot policy | Bir satırın Redis'e kabul edilip edilmeyeceğini belirleyen kural |
| Projection | Ekranın ihtiyaç duyduğu küçük okuma modeli |
| Ranked projection | Global sıralama veya top-N ekranı için önceden sıralanmış okuma modeli |
| Relation | Entity'ler arası ilişki metadata'sı ve explicit fetch davranışı |
| FetchPlan | Hangi relation'ın okunacağını açıkça belirten plan |
| RelationBatchLoader | Relation verisini tek tek değil, batch halinde yükleyen sınıf |
| Route contract | Bir endpoint veya ekran için page size, hot window, projection zorunluluğu ve limit sözleşmesi |
| Warm | Redis hot set'in production öncesi kontrollü doldurulması |
| Dry-run warm | Redis'i değiştirmeden warm planının ve SQL maliyetinin görülmesi |
| Side-by-side comparison | PostgreSQL baseline ile CacheDB sonucunun veri ve gecikme açısından karşılaştırılması |
| Cutover | Belirli bir route'un gerçek trafikte CacheDB yoluna alınması |

## PostgreSQL ve Redis Rolleri

CacheDB'de PostgreSQL ve Redis'in rolleri karışmamalıdır.

| Katman | Rol |
| --- | --- |
| PostgreSQL | Kalıcı doğruluk kaynağı, tam geçmiş, arşiv, replay ve reporting tabanı |
| Redis | Sıcak entity, projection window, index, stream, coordination ve telemetry katmanı |

PostgreSQL'i devreden çıkarmak hedef değildir. Redis, sıcak yolun düşük
gecikmeli çalışması için kullanılır; kalıcı geçmiş PostgreSQL'de kalır.

ANTI-PATTERN: Tüm veritabanını Redis'e taşımaya çalışmak.

BEST: Her production route için "Redis'te ne kalacak, PostgreSQL'de ne
kalacak?" kararını açıkça vermek.

## Entity

Entity, CacheDB'nin tabloya ve Redis payload'una bağladığı ana modeldir.

```java
@CacheEntity(table = "orders", redisNamespace = "orders")
public class OrderEntity {
    @CacheId(column = "order_id")
    public Long orderId;

    @CacheColumn("customer_id")
    public Long customerId;

    @CacheColumn("order_date")
    public Instant orderDate;

    @CacheColumn("status")
    public String status;
}
```

Kural:

- Her entity'de bir `@CacheId` olmalıdır.
- Persisted alanlar `private` veya `final` olmamalıdır.
- Kolon adı `@CacheColumn` veya `@CacheId` ile açıkça yazılmalıdır.
- Entity küçük ve anlaşılır kalmalıdır.
- Büyük ekranlar için entity'yi şişirmek yerine projection kullanılmalıdır.

## Repository

Repository, entity üzerinde temel veri işlemlerini yapar:

- `save`
- `findById`
- `findAll`
- `findPage`
- `query`
- `deleteById`

Davranış:

- Yazma Redis'e girer ve PostgreSQL write-behind hattına alınır.
- Okuma önce Redis sıcak yolundan yapılır.
- Read-through veya hydrate ile gelen veri hot policy'ye göre Redis'e kabul
  edilir ya da reddedilir.
- Büyük page/query talepleri guardrail ile sınırlandırılmalıdır.

## Hot Set

Hot set, Redis'te kalmasına izin verilen veri kümesidir. Bu küme sınırsız
olmamalıdır.

Gerçek hayatta sıcaklık genelde tek kriter değildir:

- son 90 gün
- açık veya bekleyen durumlar
- VIP müşteri
- tenant kotası
- dashboard için ilk 1000 satır
- kullanıcı başına son 50 bildirim

Bu yüzden CacheDB'de sıcak veri sadece TTL ile düşünülmemelidir.

## Hot Policy

Hot policy, bir entity'nin Redis'e kabul edilip edilmeyeceğini belirler.

| Policy | Ne zaman kullanılır? |
| --- | --- |
| `COUNT_WINDOW` | En yeni veya en sık kullanılan belirli sayıda kayıt hot kalacaksa |
| `TIME_WINDOW` | İş tarihine göre son 7 gün, 30 gün, 90 gün gibi pencere gerekiyorsa |
| `STATE_WINDOW` | Sadece `OPEN`, `PENDING`, `ACTIVE` gibi durumlar hot olacaksa |
| `COMPOSITE` | Birden fazla sıcaklık kuralı birlikte gerekiyorsa |
| `CUSTOM_PREDICATE` | Domain'e özel admission kararı gerekiyorsa |

Örnek karar:

| İhtiyaç | Doğru yaklaşım |
| --- | --- |
| Son 90 günlük sipariş Redis'te kalsın | `TIME_WINDOW`, kolon: `order_date` |
| Sadece açık ticket'lar Redis'te kalsın | `STATE_WINDOW`, kolon: `status`, değer: `OPEN` |
| Son 90 gün ve `OPEN/PENDING` işler hot olsun | `COMPOSITE` |
| Tek tenant Redis'i doldurmasın | Tenant quota |

TTL ile karıştırma:

- TTL, Redis'e yazıldıktan sonra geçen süreyi kontrol eder.
- `TIME_WINDOW`, iş verisinin tarih kolonunu kontrol eder.

Son 90 günlük iş verisi istiyorsan TTL değil, `TIME_WINDOW` kullan.

## Relation

Relation, entity'ler arası ilişki bilgisidir. CacheDB bunu görünmez lazy loading
olarak çalıştırmaz.

```java
@CacheRelation(
        targetEntity = "OrderEntity",
        mappedBy = "customerId",
        kind = CacheRelation.RelationKind.ONE_TO_MANY,
        batchLoadOnly = true
)
public List<OrderEntity> orders;
```

Kural:

- Relation metadata'da tanımlanır.
- Relation yükleme açıkça `FetchPlan` ile istenir.
- Loader yoksa relation otomatik ve güvenli biçimde yüklenemez.
- Preview gerekiyorsa `withRelationLimit(...)` kullanılır.
- Büyük listelerde relation yerine projection tercih edilir.

BEST:

```java
customerRepository
        .withRelationLimit("orders", 10)
        .findById(customerId);
```

ANTI-PATTERN:

- müşteri listesindeki her satır için bütün sipariş geçmişini yüklemek
- relation alanına erişince otomatik DB/Redis çağrısı beklemek
- relation limit olmadan geniş object graph üretmek

## Projection

Projection, ekran veya API için özel okuma modelidir. Entity'nin tamamını
taşımak yerine sadece ihtiyaç duyulan alanları tutar.

Örnek:

```text
CustomerOrderSummary
- order_id
- customer_id
- order_date
- order_amount
- currency_code
- status
```

Ne zaman projection şarttır?

- ilk ekran çok sayıda child satırı gösteriyorsa
- sıralama global veya iş skoruna göreyse
- entity payload'u büyükse
- relation fan-out zamanla büyüyorsa
- detay açılmadan tam entity yüklenmemeliyse

BEST: Liste ekranı projection, detay ekranı entity.

ANTI-PATTERN: Liste ekranında full aggregate entity graph.

## Ranked Projection

Ranked projection, top-N veya global sıralı ekranların özel halidir.

Örnek:

- en riskli müşteriler
- en yüksek tutarlı siparişler
- SLA süresi yaklaşan ticket'lar
- stok riski en yüksek ürünler

Bu route'larda veriyi geniş candidate set olarak çekip uygulama içinde sıralamak
pahalıdır. Sıralama alanı projection üzerinde önceden hazırlanmalıdır.

```text
TicketRiskProjection
- ticket_id
- tenant_id
- priority
- sla_deadline
- rank_score
```

## Route Contract

Route contract, bir ekran veya API için çalışma sözleşmesidir.

Şunları netleştirir:

- maksimum page size
- hot window boyutu
- projection zorunlu mu?
- cold read limiti nedir?
- tenant memory bütçesi nedir?
- production strict mode açık mı?

Örnek karar:

| Route | Contract |
| --- | --- |
| `/customers/{id}` | Tek `CustomerEntity`, relation yok |
| `/customers/{id}/orders` | Projection gerekli, müşteri başına son 1000 summary |
| `/orders/{id}` | Tek `OrderEntity`, küçük line preview |
| `/dashboard/risk` | Ranked projection, global top 100 |
| `/reports/monthly` | PostgreSQL/reporting yolu, Redis hot set değil |

## Warm ve Dry-Run

Warm, Redis hot set'in production öncesi veya deploy sonrası kontrollü
doldurulmasıdır.

Dry-run warm ise Redis'i değiştirmez. SQL, satır sayısı, kök id sayısı ve
tahmini bellek etkisini gösterir.

BEST sıra:

1. Planı oluştur.
2. Dry-run warm çalıştır.
3. SQL ve satır sayısını incele.
4. Staging warm çalıştır.
5. Redis memory calibration sonucunu kontrol et.
6. Side-by-side comparison çalıştır.
7. Cutover kararını rapora bağla.

## Side-by-Side Comparison

Bu adım, PostgreSQL baseline sonucu ile CacheDB sonucunu karşılaştırır.

Kontrol edilen ana başlıklar:

- aynı id listesi geldi mi?
- sıralama aynı mı?
- page size aynı mı?
- projection route gerçekten kullanıldı mı?
- CacheDB p95 kabul edilebilir mi?
- route cutover için hazır mı?

Veri eşleşmesi yoksa latency iyi olsa bile cutover yapılmamalıdır.

## Outbox ve CDC

CacheDB dışındaki sistemler PostgreSQL'i değiştiriyorsa Redis hot set stale
kalabilir. Bu durumda outbox/CDC gerekir.

Kural:

- CacheDB üzerinden yazılan veri write-behind hattına girer.
- CacheDB dışından yazılan veri ancak outbox/CDC ile CacheDB'ye bildirilirse
  hot set güncel kalır.

BEST: PostgreSQL değişikliklerini outbox tablosuna veya CDC stream'ine yaz,
CacheDB adapter ile oku, idempotent apply runner ile entity/projection
yenile.

ANTI-PATTERN: PostgreSQL'i başka sistemler değiştirirken Redis'in kendiliğinden
güncel kalmasını beklemek.

## Production Kabulleri

CacheDB production kullanımında şu kabuller geçerlidir:

- Redis HA gerçek bir altyapı bağımlılığıdır.
- PostgreSQL tam geçmişin ve kalıcı doğruluğun sahibidir.
- Admin UI güvenli ağ veya gateway arkasında olmalıdır.
- Projection gereken route, production'da entity scan'e düşmemelidir.
- Hot set büyümesi policy, quota ve Redis maxmemory ile sınırlandırılmalıdır.
- Her migration route'u warm, compare ve rollback kanıtı üretmelidir.
- Full system migration için route coverage %100 olmalıdır.

## Karar Sınıflandırması

BEST:

- sıcak route'u açıkça seçmek
- projection gereken yerde projection kullanmak
- hot policy ve route contract tanımlamak
- staging warm ve side-by-side comparison ile cutover yapmak

ACCEPTABLE:

- küçük detail ekranlarında sınırlı relation preview kullanmak
- kontrollü pilotta yalnızca birkaç route'u CacheDB'ye almak
- eski arşiv sorgularını PostgreSQL cold path'te bırakmak

ANTI-PATTERN:

- bütün tabloyu Redis'e doldurmak
- relation-heavy ekranları full entity graph ile açmak
- route contract olmadan büyük page okumak
- PostgreSQL dış yazımlarını outbox/CDC olmadan yok saymak
- benchmark ve parity kanıtı olmadan canlıya geçmek
