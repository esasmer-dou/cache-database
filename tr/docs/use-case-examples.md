# Kullanım Senaryosu Örnekleri

English version: [../../docs/use-case-examples.md](../../docs/use-case-examples.md)

Bu sayfa CacheDB davranışını gerçek kullanım senaryoları üzerinden anlatır.
Amaç, uygulama geliştiricisinin hangi durumda entity repository, projection
repository, command route veya PostgreSQL soğuk veri yolu kullanacağını net
seçebilmesidir.

Örneklerde ortak müşteri/sipariş modeli kullanılır:

```text
customers
- customer_id
- tax_number
- customer_type
- status

orders
- order_id
- customer_id
- order_date
- order_amount
- currency_code
- order_type
- status
```

Production kuralı basittir:

- Redis sıcak okuma/yazma hızlandırma katmanıdır.
- PostgreSQL kalıcı doğruluk kaynağıdır ve tam geçmiş için doğru yerdir.
- Full entity okumaları küçük ve sınırlı kalmalıdır.
- Büyük liste ekranları projection/okuma modeli penceresi kullanmalıdır.
- Kısmi değişiklikler full entity update veya açık command route ile yapılmalıdır.

## Bu Sayfayı Nasıl Okumalısın?

Bu belgeyi baştan sona okumak zorunda değilsin. Önce kendi route tipini bul:

| Eğer aradığın şey buysa | Önce oku |
| --- | --- |
| Tek kayıt oluşturma, güncelleme veya silme | Insert, Update ve Delete örnekleri |
| Müşteri detayında son siparişler | Query 2 ve Projection 1 |
| Sipariş detayında satır önizleme | Relation Örneği 1 ve Projection 2 |
| Dashboard veya top-N ekranı | Projection 3 ve Dashboard örnekleri |
| Eski arşiv verisi | Query 4 ve Dashboard 3 |
| Redis memory davranışı | Bellek ve Sıcak Pencere Kuralları |
| Partial update riski | Partial Update Davranışı |

Kural: Önce route'un kullanıcıya ne gösterdiğini netleştir, sonra entity mi
projection mı kullanılacağına karar ver.

## Uçtan Uca Gerçek Hayat Senaryoları

### Senaryo A: E-Ticaret Müşteri Detayı

Ekran:

- müşteri kartı
- son 10 sipariş
- son 3 destek talebi
- toplam açık bakiye

BEST tasarım:

- `CustomerEntity` müşteri kartı için kullanılır.
- Son siparişler `CustomerOrderSummaryProjection` ile okunur.
- Destek talepleri `CustomerTicketSummaryProjection` veya küçük relation preview
  ile gösterilir.
- Açık bakiye ayrı bir finans projection'ı veya PostgreSQL reporting yolundan
  gelir.

ANTI-PATTERN:

- Müşteri açılırken tüm siparişler, tüm sipariş satırları, tüm ticket mesajları
  ve tüm ödemeleri tek graph olarak yüklemek.

### Senaryo B: Operasyon Dashboard'u

Ekran:

- SLA riski en yüksek 100 ticket
- stok riski olan 50 ürün
- son 15 dakikadaki başarısız ödeme denemeleri

BEST tasarım:

- Ticket listesi ranked projection olmalıdır.
- Stok kartları `ProductStockSummary` projection'ından gelmelidir.
- Ödeme denemeleri time-window projection veya PostgreSQL event query ile
  okunmalıdır.
- Dashboard ilk açılışında full entity hydration yapılmamalıdır.

ANTI-PATTERN:

- Dashboard render sırasında milyonlarca satırdan uygulama içinde top-N
  hesaplamak.

### Senaryo C: Finans veya Fatura Sistemi

Ekran:

- fatura detay
- son ödeme denemeleri
- aylık mutabakat raporu

BEST tasarım:

- Fatura detayında `InvoiceEntity` kullanılır.
- Son ödeme denemeleri `withRelationLimit("payments", 5)` veya payment preview
  projection ile gösterilir.
- Aylık mutabakat PostgreSQL/reporting yolunda kalır.

ANTI-PATTERN:

- Aylık raporu CacheDB entity query'leriyle üretmeye çalışmak.

### Senaryo D: Çok Tenant SaaS

Ekran:

- tenant bazlı aktif işler
- kullanıcı başına bildirim kutusu
- admin için global health dashboard

BEST tasarım:

- Her hot route tenant bilgisini taşımalıdır.
- Tenant quota ile tek tenant'ın Redis memory'yi tüketmesi engellenmelidir.
- Kullanıcı notification inbox projection window olarak tutulmalıdır.
- Admin global dashboard ranked projection veya ayrı telemetry projection ile
  beslenmelidir.

ANTI-PATTERN:

- Tenant ayrımı olmayan global hot entity limit ile tüm müşterileri aynı Redis
  bütçesine koymak.

## Route Karar Haritası

| İhtiyaç | BEST route | Neden |
| --- | --- | --- |
| Tek full entity oluşturmak veya değiştirmek | `EntityRepository.save(fullEntity)` | Redis öncelikli yazım, PostgreSQL'e write-behind kalıcılık |
| Sıcak tek entity'yi id ile okumak | `EntityRepository.findById(id)` | Hızlı Redis lookup |
| Küçük full-entity page okumak | `EntityRepository.findPage(PageWindow)` | Sınırlı full payload okuması |
| Büyük müşteri sipariş listesi okumak | `ProjectionRepository<OrderSummary>` | Özet payload, sınırlı sıcak pencere |
| Global top-N dashboard satırı okumak | Ranked projection | Tek ranked index yolu |
| Eski geçmiş veya arşiv verisi okumak | PostgreSQL soğuk veri yolu | Soğuk geçmiş Redis'i kirletmez |
| Redis'te olmayabilecek entity üzerinde tek alan değiştirmek | Açık command route | Eksik/bozuk Redis entity'si üretilmez |
| Tek entity silmek | `deleteById(id)` | Idempotent delete, tombstone, write-behind ile PostgreSQL delete |

## Minimal Entity Şekli

Örnek entity'ler bilerek küçük tutuldu. Gerçek uygulamada entity davranışını
açık tut; ilişki yüklemeyi görünmez lazy loading arkasına saklama.

```java
@CacheEntity(table = "customers", redisNamespace = "customers")
public class CustomerEntity {
    @CacheId(column = "customer_id")
    public Long customerId;

    @CacheColumn("tax_number")
    public String taxNumber;

    @CacheColumn("customer_type")
    public String customerType;

    @CacheColumn("status")
    public String status;
}
```

```java
@CacheEntity(table = "orders", redisNamespace = "orders")
public class OrderEntity {
    @CacheId(column = "order_id")
    public Long orderId;

    @CacheColumn("customer_id")
    public Long customerId;

    @CacheColumn("order_date")
    public Instant orderDate;

    @CacheColumn("order_amount")
    public BigDecimal orderAmount;

    @CacheColumn("currency_code")
    public String currencyCode;

    @CacheColumn("order_type")
    public String orderType;

    @CacheColumn("status")
    public String status;
}
```

## Daha Fazla Gerçek Dünya Entity Modeli

Müşteri/sipariş yalnızca tek bir route örneğidir. Gerçek sistemlerde çoğu zaman
birden fazla birden çoğa ilişki bulunur. Aynı kural hepsi için geçerlidir: root
entity küçük kalmalı, önizleme ilişkileri sınırlandırılmalı, büyük listeler
projection ile okunmalıdır.

### Model A: Order -> Order Lines

```text
orders
- order_id
- customer_id
- order_date
- status

order_lines
- line_id
- order_id
- product_id
- quantity
- unit_price
- line_amount
```

BEST:

- `OrderEntity` order detayının root entity'sidir.
- `OrderLineEntity` child entity'dir.
- İlk order detay ekranı yalnızca ilk 8 veya 20 line kaydını preview olarak yükler.
- Tam line listesi yalnızca kullanıcı line detail sekmesini açarsa yüklenir.

```java
Optional<OrderEntity> order =
        OrderEntityCacheBinding.using(session)
                .repository()
                .withRelationLimit("orderLines", 8)
                .findById(orderId);
```

ANTI-PATTERN: order liste ekranındaki her order satırı için bütün order line
kayıtlarını yüklemek.

### Model B: Product -> Inventory Movements

```text
products
- product_id
- sku
- product_name
- status

inventory_movements
- movement_id
- product_id
- movement_time
- movement_type
- quantity_delta
- warehouse_id
```

BEST:

- `ProductEntity` Redis'te sıcak kalabilir.
- Güncel stok özeti projection olmalıdır.
- Tam inventory movement geçmişi PostgreSQL'de kalmalıdır.
- Dashboard widget'ları kompakt `ProductStockSummary` projection'ını okur.

```java
ProjectionRepository<ProductStockSummary, Long> stockSummary =
        ProductEntityCacheBinding.using(session).projections().stockSummary();

List<ProductStockSummary> lowStock = stockSummary.query(
        QuerySpec.where(QueryFilter.lt("available_quantity", 10))
                .orderBy(QuerySort.asc("available_quantity"))
                .limitTo(50)
);
```

ANTI-PATTERN: her istekte güncel stok hesaplamak için binlerce inventory
movement satırını Redis'e çekmek.

### Model C: Invoice -> Payments

```text
invoices
- invoice_id
- customer_id
- invoice_date
- total_amount
- payment_status

payments
- payment_id
- invoice_id
- paid_at
- paid_amount
- payment_method
- payment_status
```

BEST:

- `InvoiceEntity` detay entity'sidir.
- `PaymentEntity` child entity'dir.
- Invoice ekranı son birkaç ödeme denemesini preview olarak gösterebilir.
- Muhasebe raporları PostgreSQL veya özel reporting projection kullanmalıdır.

```java
EntityRepository<InvoiceEntity, Long> invoices =
        InvoiceEntityCacheBinding.using(session)
                .repository()
                .withRelationLimit("payments", 5);

Optional<InvoiceEntity> invoice = invoices.findById(invoiceId);
```

ANTI-PATTERN: ay sonu muhasebe aggregation için CacheDB entity okumalarını
kullanmak.

### Model D: Shipment -> Shipment Events

```text
shipments
- shipment_id
- order_id
- carrier_code
- current_status

shipment_events
- event_id
- shipment_id
- event_time
- event_type
- location
```

BEST:

- Güncel shipment status Redis'te sıcak olabilir.
- Son event preview sınırlı relation veya projection olabilir.
- Tracking ekranı aşırı sıcak değilse tam tracking geçmişi PostgreSQL'de kalır.

```java
ProjectionRepository<ShipmentTimelinePreview, Long> timeline =
        ShipmentEntityCacheBinding.using(session).projections().timelinePreview();

List<ShipmentTimelinePreview> latestEvents = timeline.query(
        QuerySpec.where(QueryFilter.eq("shipment_id", shipmentId))
                .orderBy(QuerySort.desc("event_time"))
                .limitTo(10)
);
```

ANTI-PATTERN: her shipment için bütün tracking event'lerini Redis'te sürekli tutmak.

### Model E: Support Ticket -> Messages

```text
support_tickets
- ticket_id
- customer_id
- priority
- status
- opened_at

ticket_messages
- message_id
- ticket_id
- sender_type
- message_time
- body
```

BEST:

- Ticket inbox ekranları `TicketSummaryProjection` kullanmalıdır.
- Ticket detail root ticket ve son mesaj preview ile açılmalıdır.
- Eski veya nadir okunan tam mesaj geçmişi PostgreSQL'den sayfalanabilir.

```java
ProjectionRepository<TicketSummary, Long> ticketSummaries =
        SupportTicketEntityCacheBinding.using(session).projections().ticketSummary();

List<TicketSummary> urgentOpenTickets = ticketSummaries.query(
        QuerySpec.where(QueryFilter.eq("status", "OPEN"))
                .orderBy(QuerySort.desc("priority_score"), QuerySort.asc("opened_at"))
                .limitTo(25)
);
```

ANTI-PATTERN: inbox satırı göstermek için her ticket mesajının body alanını
decode etmek.

### Model F: Customer -> Addresses, Orders, Tickets

Bir müşteri çoğu zaman birden fazla child collection taşır.

```text
customer
  -> addresses
  -> orders
  -> support_tickets
```

BEST:

- Customer profile root customer entity'sini okur.
- Address list doğal olarak küçükse sınırlı relation olabilir.
- Orders için order-summary projection kullanılır.
- Support tickets için ticket-summary projection kullanılır.

```java
Optional<CustomerEntity> customer =
        CustomerEntityCacheBinding.using(session).repository().findById(customerId);

List<OrderSummaryReadModel> latestOrders = orderSummaryRepository.query(
        QuerySpec.where(QueryFilter.eq("customer_id", customerId))
                .orderBy(QuerySort.desc("order_date"))
                .limitTo(10)
);

List<TicketSummary> tickets = ticketSummaryRepository.query(
        QuerySpec.where(QueryFilter.eq("customer_id", customerId))
                .orderBy(QuerySort.desc("opened_at"))
                .limitTo(10)
);
```

ANTI-PATTERN: tek customer endpoint'inde customer, bütün address kayıtları,
bütün order kayıtları, bütün order line kayıtları, bütün ticket kayıtları ve
bütün ticket message kayıtlarını birlikte hydrate etmek.

## Genişletilmiş Route Örnekleri

### Relation Örneği 1: Product Detail Page

Kullanıcı product detail sayfasını açar.

BEST route:

- Sabit product alanları için `ProductEntity.findById(productId)`.
- Güncel stok için `ProductStockSummary` projection.
- Eski inventory movement geçmişi için PostgreSQL soğuk veri yolu.

Neden: product detail küçük entity ve kompakt summary ister; tam inventory
defteri istemez.

### Relation Örneği 2: Invoice Detail Page

Kullanıcı tek invoice açar.

BEST route:

- Invoice header için `InvoiceEntity.findById(invoiceId)`.
- Son ödeme denemeleri için `withRelationLimit("payments", 5)`.
- Kullanıcı audit sekmesini açarsa tam payment audit için PostgreSQL.

Neden: son denemeler ilk ekran için değerlidir; tam payment geçmişi ilk ekran
için gerekli değildir.

### Relation Örneği 3: Shipment Tracking Page

Kullanıcı tek shipment tracking ekranını açar.

BEST route:

- Güncel shipment status Redis'ten gelir.
- Son 10 timeline event projection'dan gelir.
- Tam carrier geçmişi yalnızca istenirse PostgreSQL'den okunur.

Neden: tracking ilk ekranı sınırlı timeline'dır; tam arşiv export değildir.

### Relation Örneği 4: Support Inbox

Agent support inbox ekranını açar.

BEST route:

- Priority ve açılış zamanına göre sıralı `TicketSummaryProjection`.
- Inbox içinde message body hydration yoktur.
- Ticket detail son mesaj preview ile açılır.

Neden: inbox satırı summary alanları ister, tam konuşma gövdesi istemez.

### Relation Örneği 5: Admin Customer 360 Screen

Kullanıcı "customer 360" yönetim ekranını açar.

BEST route:

- Root `CustomerEntity`.
- Son 10 order summary.
- Son 10 ticket summary.
- Aktif invoice özeti.
- Tam order, ticket, invoice ve audit geçmişi için açık sekmeler.

Neden: geniş 360 ekranı summary-first olmalıdır. Full aggregate hydration,
child sayıları büyüdükçe sürekli yavaşlar.

## Insert Örnekleri

### Insert 1: Müşteri Oluşturma

İstek full müşteri durumunu taşıyorsa bu yolu kullan.

```java
CustomerEntity customer = new CustomerEntity();
customer.customerId = 42L;
customer.taxNumber = "1234567890";
customer.customerType = "CORPORATE";
customer.status = "ACTIVE";

CustomerEntityCacheBinding.using(session).repository().save(customer);
```

Çalışma zamanı davranışı:

- Müşteri payload'ı önce Redis'e yazılır.
- Query index'leri ve hot-set takibi güncellenir.
- Redis Stream'e write-behind event'i yazılır.
- Write-behind worker satırı PostgreSQL'e flush eder.

BEST: geçerli full entity oluşturabiliyorsan `save(fullEntity)` kullan.

### Insert 2: Müşteri İçin Sipariş Oluşturma

Yeni sipariş oluşurken gerekli bütün alanlar biliniyorsa bu yolu kullan.

```java
OrderEntity order = new OrderEntity();
order.orderId = 9001L;
order.customerId = 42L;
order.orderDate = Instant.now();
order.orderAmount = new BigDecimal("1250.00");
order.currencyCode = "TRY";
order.orderType = "ONLINE";
order.status = "CREATED";

OrderEntityCacheBinding.using(session).repository().save(order);
```

Çalışma zamanı davranışı:

- Sipariş Redis'te hemen sıcak hale gelir.
- PostgreSQL kalıcılığı write-behind ile tamamlanır.
- Kayıtlı projection'lar yenilenerek liste ekranlarına yeni sipariş yansıtılır.

BEST: siparişleri full entity olarak oluştur.

### Insert 3: Generated Command Helper İle Oluşturma

Entity içinde kararlı bir uygulama komutu varsa generated command helper kullan.

```java
UserEntity activated =
        GeneratedCacheModule.using(session)
                .users()
                .commands()
                .activateUser(84L, "alice");
```

Çalışma zamanı davranışı:

- Generated command geçerli bir entity üretir.
- Generated helper repository `save(...)` çağırır.
- Yazma yolu Redis öncelikli kalır.

BEST: her zaman geçerli full entity üreten yaygın create/update işlemlerinde
command helper kullan.

### Insert 4: Toplu Seed veya Warm

Bu yolu kontrollü startup, staging ön ısıtma veya migration provası için kullan.

```java
for (OrderEntity order : ordersToWarm) {
    OrderEntityCacheBinding.using(session).repository().save(order);
}
```

Çalışma zamanı davranışı:

- Redis seçilen sıcak veri setiyle doldurulur.
- PostgreSQL yazımları yine normal write-behind yolundan geçer.
- Bu bir migration warm-up ise sıcak setin planlı route'a göre oluşması için
  Migration Planner warm runner tercih edilmelidir.

BEST: yalnızca Redis'ten servis etmek istediğin route'ları warm et.

ANTI-PATTERN: bütün veritabanını kör şekilde Redis'e yüklemek.

### Insert 5: Projection Kaynak Entity'si Oluşturma

Projection çoğu zaman uygulama kodu tarafından doğrudan insert edilmez. Önce
base entity yazılır, ardından CacheDB projection'ı yeniler.

```java
OrderEntity order = createOrder(...);
OrderEntityCacheBinding.using(session).repository().save(order);

ProjectionRepository<OrderSummaryReadModel, Long> summaries =
        OrderEntityCacheBinding.using(session).projections().orderSummary();
```

Çalışma zamanı davranışı:

- Base entity yazımı kaynak event'tir.
- Projection daha kompakt bir okuma modeli payload'ı alır.
- Liste ekranları full order yerine summary okur.

BEST: base entity yaz, liste ekranını projection'dan oku.

ANTI-PATTERN: summary satırlarını elle yazıp base entity ile zamanla
tutarsızlaşmasına izin vermek.

## Read ve Query Örnekleri

### Query 1: Sıcak Müşteriyi Id İle Okuma

Kök entity'nin sıcak olması beklenen detay ekranlarında kullan.

```java
Optional<CustomerEntity> customer =
        CustomerEntityCacheBinding.using(session)
                .repository()
                .findById(42L);
```

Çalışma zamanı davranışı:

- CacheDB Redis'te `customer_id=42` payload'ını arar.
- Payload varsa ve tombstone yoksa entity döner.
- Payload yoksa bu repository çağrısı otomatik PostgreSQL scan yapmaz.

BEST: hot detail kayıtları için `findById` kullan.

ACCEPTABLE: kayıt eskiyse ve Redis'te yoksa açık PostgreSQL soğuk veri yolu
repository'si çağır.

### Query 2: 1.000 Satırlık Sıcak Projection Penceresinden Son 10 Siparişi Okuma

Redis müşteri başına son 1.000 order summary tutarken ekran yalnızca 10 kayıt
istiyorsa bu yolu kullan.

```java
ProjectionRepository<OrderSummaryReadModel, Long> summaries =
        OrderEntityCacheBinding.using(session).projections().orderSummary();

List<OrderSummaryReadModel> latest10 = summaries.query(
        QuerySpec.where(QueryFilter.eq("customer_id", 42L))
                .orderBy(QuerySort.desc("order_date"), QuerySort.desc("order_id"))
                .limitTo(10)
);
```

Çalışma zamanı davranışı:

- CacheDB full `OrderEntity` repository değil, projection repository kullanır.
- Redis 1.000 satırlık sıcak summary penceresi tutabilir.
- Sorgu yalnızca istenen 10 summary kaydını döndürür.
- Bu sıcak liste route'u için PostgreSQL gerekmez.

BEST: Redis'te büyük projection penceresi, kullanıcıya küçük cevap.

ANTI-PATTERN: 10 full `OrderEntity` sorgulayıp CacheDB'nin order-summary
projection'ını kendiliğinden tahmin etmesini beklemek.

### Query 3: Küçük Full-Entity Page Okuma

Küçük ve sınırları net full entity listelerinde kullan.

```java
List<CustomerEntity> customers =
        CustomerEntityCacheBinding.using(session)
                .repository()
                .findPage(new PageWindow(0, 25));
```

Çalışma zamanı davranışı:

- Page isteği read-shape guardrail tarafından kontrol edilir.
- Page güvenli limite sığıyorsa Redis page cache kullanılabilir.
- Page loader tanımlıysa ve read-through açıksa page yüklenip cache'e alınabilir.

BEST: page size değerini entity hot window altında tut.

ANTI-PATTERN: 1.000+ satırlık iş ekranları için full entity page kullanmak.

### Query 4: Eski Geçmişi PostgreSQL Soğuk Veri Yolundan Okuma

Kullanıcı sıcak projection penceresi dışındaki eski veriyi istiyorsa bu yolu kullan.

```java
List<OrderEntity> februaryOrders = jdbcTemplate.query(
        """
        SELECT *
        FROM orders
        WHERE customer_id = ?
          AND order_date >= ?
          AND order_date < ?
        ORDER BY order_date DESC, order_id DESC
        LIMIT ?
        """,
        orderRowMapper,
        42L,
        LocalDate.parse("2026-02-01"),
        LocalDate.parse("2026-03-01"),
        100
);
```

Çalışma zamanı davranışı:

- Bu route bilinçli olarak Redis'i pas geçer.
- Redis sıcak pencereleri soğuk arşiv okumalarıyla kirlenmez.
- PostgreSQL tam geçmiş için kalıcı kaynak olarak kalır.

BEST: eski geçmiş ve audit ekranları PostgreSQL veya özel archive read-model kullanır.

ANTI-PATTERN: bir kez okunduğu için her eski arşiv verisini Redis'e yüklemek.

### Query 5: Relation Preview Okuma

Detay ekranı yalnızca küçük bir ilişki önizlemesi istiyorsa kullan.

```java
EntityRepository<CustomerEntity, Long> repository =
        CustomerEntityCacheBinding.using(session)
                .repository()
                .withRelationLimit("orders", 8);

Optional<CustomerEntity> customer = repository.findById(42L);
```

Çalışma zamanı davranışı:

- Müşteri root entity olarak okunur.
- Sadece 8 ilişkili sipariş önizleme olarak yüklenir.
- Tam sipariş geçmişi ilk ekranın dışında kalır.

BEST: önce summary, açık preview, ihtiyaç olunca detail.

ANTI-PATTERN: ilk ekranda bütün child satırlarını eager-load etmek.

### Query 6: Canlıya Geçmeden Önce Explain

Route'un güvenli olup olmadığını görmek için `explain(...)` kullan.

```java
QuerySpec query = QuerySpec.where(QueryFilter.eq("customer_id", 42L))
        .orderBy(QuerySort.desc("order_date"))
        .limitTo(100);

QueryExplainPlan plan =
        OrderEntityCacheBinding.using(session).repository().explain(query);
```

Çalışma zamanı davranışı:

- Plan route şeklini ve uyarıları gösterir.
- Production cutover öncesinde riskli entity okumalarını yakalamaya yardım eder.

BEST: önemli route'ları staging ortamında explain et ve karşılaştır.

## Update Örnekleri

### Update 1: Sıcak Entity'yi Full State İle Değiştirme

Uygulama geçerli full state'e sahipse bu yolu kullan.

```java
OrderEntity order = existingOrder;
order.status = "PAID";
order.orderAmount = new BigDecimal("1250.00");

OrderEntityCacheBinding.using(session).repository().save(order);
```

Çalışma zamanı davranışı:

- Redis geçerli full replacement payload'ı alır.
- Index'ler ve projection'lar full state üzerinden yenilenir.
- PostgreSQL write-behind ile güncellenir.

BEST: full entity oluşturabiliyorsan full entity update kullan.

### Update 2: Redis'te Olmayan Eski Entity'yi Güncelleme

Yalnızca istek yine geçerli full entity taşıyorsa bu yolu kullan.

```java
OrderEntity order = loadOrderFromTrustedCommandPayload();
order.orderId = 500L;
order.customerId = 42L;
order.orderDate = Instant.parse("2026-02-10T10:15:30Z");
order.orderAmount = new BigDecimal("1000.00");
order.currencyCode = "TRY";
order.orderType = "ONLINE";
order.status = "CANCELLED";

OrderEntityCacheBinding.using(session).repository().save(order);
```

Çalışma zamanı davranışı:

- Redis'te eski payload'ın bulunması gerekmez.
- CacheDB yeni geçerli full state'i yazar.
- PostgreSQL güncellemesi write-behind ile yapılır.

BEST: eski kayıt güncellemesi, yeni full state verildiğinde güvenlidir.

ANTI-PATTERN: `save(...)` içine yalnızca `order_id` ve `status` göndermek.

### Update 3: Command Route İle Kısmi Status Değişikliği

Sadece tek alan değişiyorsa ve entity Redis'te olmayabilirse bu yolu kullan.

```java
cancelOrderCommandHandler.cancelOrder(500L, "CUSTOMER_REQUEST");
```

Güvenli command route şunu yapabilir:

```sql
UPDATE orders
SET status = 'CANCELLED'
WHERE order_id = :order_id;
```

Redis davranışı:

- Sipariş Redis'te hot ise entity/projection invalidate edilir veya yenilenir.
- Sipariş Redis'te yoksa kısmi Redis entity'si oluşturulmaz.
- Gerekirse eski cache fill'in kaydı diriltmesini engelleyen invalidation marker yazılır.

BEST: kısmi update, partial entity save değil command işlemidir.

### Update 4: Customer Type Değiştirme

Customer entity küçükse ve mevcut state biliniyorsa full state kullan.

```java
CustomerEntity customer =
        CustomerEntityCacheBinding.using(session).repository()
                .findById(42L)
                .orElseThrow();

customer.customerType = "VIP";

CustomerEntityCacheBinding.using(session).repository().save(customer);
```

Çalışma zamanı davranışı:

- Mevcut customer sıcak olduğu için Redis'ten okunur.
- Güncellenmiş full entity geri yazılır.
- PostgreSQL write-behind ile takip eder.

ACCEPTABLE: hot entity'yi oku, değiştir, full entity olarak kaydet.

ANTI-PATTERN: bunu binlerce müşteri için batch veya SQL command route olmadan
döngü içinde yapmak.

### Update 5: Toplu Fiyat veya Status Değişikliği

PostgreSQL batch SQL kullan, ardından etkilenen sıcak route'ları açıkça yenile
veya invalidate et.

```sql
UPDATE orders
SET status = 'EXPIRED'
WHERE status = 'CREATED'
  AND order_date < now() - interval '30 days';
```

Çalışma zamanı davranışı:

- Toplu mutation PostgreSQL'de yapılır.
- CacheDB etkilenen projection'ları invalidate etmeli veya rebuild etmelidir.
- Her etkilenen order'ı full entity olarak yükleyip `save(...)` çağırma.

BEST: toplu mutation için set-based SQL, ardından açık cache/projection refresh.

ANTI-PATTERN: geniş tarihsel update için Redis üzerinden N+1 update döngüsü.

## Delete Örnekleri

### Delete 1: Sıcak Siparişi Silme

Tek bilinen entity silinecekse `deleteById` kullan.

```java
OrderEntityCacheBinding.using(session).repository().deleteById(9001L);
```

Çalışma zamanı davranışı:

- Redis entity key'i silinir.
- Hot-set ve query index kayıtları temizlenir.
- Eski verinin geri dirilmesini engellemek için tombstone yazılır.
- PostgreSQL delete write-behind ile yazılır.

BEST: primary key ile idempotent delete.

### Delete 2: Redis'te Olmayan Eski Siparişi Silme

Aynı delete çağrısını kullan.

```java
OrderEntityCacheBinding.using(session).repository().deleteById(500L);
```

Çalışma zamanı davranışı:

- Redis entity yoksa hata değildir.
- CacheDB yine tombstone yazar.
- PostgreSQL delete yine kuyruğa alınır.
- PostgreSQL'de satır zaten yoksa işlem güvenli no-op olarak kalmalıdır.

BEST: delete işlemi entity'nin Redis'te hot olmasına bağlı değildir.

### Delete 3: Müşteri ve Siparişlerini Silme

CacheDB içinde görünmez cascade davranışına güvenme.

```java
customerDeletionService.deleteCustomer(customerId);
```

Güvenli servis orkestrasyonu:

```text
1. Müşteri için yeni yazımları durdur veya işlemi idempotent yap.
2. Siparişleri kontrollü command/batch route ile sil veya soft-delete et.
3. Customer entity'yi id ile sil.
4. Customer order projection'larını invalidate et.
5. PostgreSQL durumunu doğrula.
```

BEST: aggregate seviyesindeki delete işlemleri açık domain orkestrasyonu ister.

ANTI-PATTERN: customer root kaydını silip tüm child projection, history ve
dashboard read-model kayıtlarının kendiliğinden doğru kalacağını varsaymak.

### Delete 4: Soft Delete

Satır PostgreSQL'de kalacaksa full entity update veya command route kullan.

```java
OrderEntity order = loadFullOrderForSoftDelete(9001L);
order.status = "DELETED";

OrderEntityCacheBinding.using(session).repository().save(order);
```

Çalışma zamanı davranışı:

- Route hâlâ hot olmalıysa Redis yeni full state'i tutar.
- Projection'lar ekran ihtiyacına göre satırı kaldırabilir veya işaretleyebilir.
- PostgreSQL satırı korur.

BEST: soft delete fiziksel delete değil, update işlemidir.

### Delete 5: Query İle Geniş Silme

Entity repository üzerinden geniş delete-by-query yapma.

ANTI-PATTERN:

```java
List<OrderEntity> oldOrders = orderRepository.query(
        QuerySpec.where(QueryFilter.lt("order_date", cutoff)).limitTo(10_000)
);

for (OrderEntity order : oldOrders) {
    orderRepository.deleteById(order.orderId);
}
```

BEST:

```sql
DELETE FROM orders
WHERE order_date < :cutoff
  AND status = 'ARCHIVED';
```

Ardından etkilenen ekranlar için kontrollü projection rebuild veya invalidation çalıştır.

Neden:

- Geniş tarihsel delete bir veritabanı işlemidir.
- Redis büyük arşiv temizliğinin execution engine'i olmamalıdır.
- CacheDB yalnızca sınırlı sıcak pencereyi ve okuma modellerini tutarlı tutmalıdır.

## Projection ve Okuma Modeli Örnekleri

### Projection 1: Müşteri Son Siparişleri

Müşteri timeline ekranlarında kullan.

```java
ProjectionRepository<OrderSummaryReadModel, Long> summaries =
        OrderEntityCacheBinding.using(session).projections().orderSummary();

List<OrderSummaryReadModel> rows = summaries.query(
        QuerySpec.where(QueryFilter.eq("customer_id", customerId))
                .orderBy(QuerySort.desc("order_date"), QuerySort.desc("order_id"))
                .limitTo(100)
);
```

BEST:

- Müşteri başına son 1.000 summary sıcak tutulur.
- Kullanıcıya yalnızca istenen sayfa döner: 10, 25 veya 100.
- Full order detail, kullanıcı tek sipariş seçince okunur.

### Projection 2: Order Detail ve Line Preview

Summary/detail ayrımını kullan.

```java
Optional<OrderEntity> order =
        OrderEntityCacheBinding.using(session)
                .repository()
                .withRelationLimit("orderLines", 8)
                .findById(orderId);
```

BEST:

- İlk ekran order ve 8 preview line alır.
- Full line geçmişi yalnızca detay ekranı istediğinde yüklenir.

ANTI-PATTERN: liste ekranındaki her order için tüm line kayıtlarını yüklemek.

### Projection 3: İş Skoruna Göre Top Orders Dashboard

Ekran global sıralıysa ranked projection kullan.

```java
ProjectionRepository<HighValueOrderReadModel, Long> highValueOrders =
        OrderEntityCacheBinding.using(session).projections().highValueOrders();

List<HighValueOrderReadModel> top50 = highValueOrders.query(
        QuerySpec.builder()
                .sort(QuerySort.desc("rank_score"))
                .limit(50)
                .build()
);
```

BEST:

- `rank_score` önceden hesaplanır.
- Projection `rankedBy("rank_score")` ile işaretlenir.
- Tek ranked pencere sorgulanır.

ANTI-PATTERN: full order entity'lerini tarayıp uygulama belleğinde sıralamak.

### Projection 4: Raporlama Snapshot'ı

Sık açılan rapor için read-model kullan.

```java
ProjectionRepository<CustomerRevenueSummary, Long> revenue =
        CustomerEntityCacheBinding.using(session).projections().customerRevenueSummary();

List<CustomerRevenueSummary> rows = revenue.query(
        QuerySpec.where(QueryFilter.eq("period", "2026-05"))
                .orderBy(QuerySort.desc("total_revenue"))
                .limitTo(100)
);
```

BEST:

- Sık kullanılan operasyonel raporlarda projection/read-model kullan.
- Yalnızca aktif raporlama penceresini sıcak tut.
- Ham tarihsel gerçekler PostgreSQL'de kalsın.

### Projection 5: Mevcut ORM Route Geçişi

Production route'u dönüştürmeden önce Migration Planner kullan.

```text
1. Kaynak veritabanı şemasını keşfet.
2. Root tablo ve child tablo seç.
3. Entity ve projection scaffold üret.
4. Dry-run warm çalıştır.
5. Staging warm çalıştır.
6. Side-by-side comparison çalıştır.
7. Yalnızca parity ve latency kabul edilebilirse cutover yap.
```

BEST: tablo tablo değil, route route geçiş yap.

ANTI-PATTERN: her tabloyu CacheDB entity yapıp tüm uygulamanın hazır olduğunu
varsaymak.

## Dashboard ve Raporlama Örnekleri

### Dashboard 1: Son Siparişler Widget'ı

Küçük limitli projection kullan.

```java
List<OrderSummaryReadModel> latest = orderSummaryRepository.query(
        QuerySpec.builder()
                .sort(QuerySort.desc("order_date"))
                .limit(25)
                .build()
);
```

BEST: sık yenilenen dashboard widget'ı için Redis projection.

### Dashboard 2: Müşteri Risk Sıralaması

Ranked projection kullan.

```java
List<CustomerRiskReadModel> riskyCustomers = riskRepository.query(
        QuerySpec.builder()
                .sort(QuerySort.desc("risk_score"))
                .limit(50)
                .build()
);
```

BEST: önceden hesaplanmış skor, sınırlı top-N okuma.

### Dashboard 3: Aylık Finans Raporu

Rapor geniş tarih aralığı tarıyorsa PostgreSQL veya ayrı raporlama tablosu kullan.

```sql
SELECT currency_code, sum(order_amount)
FROM orders
WHERE order_date >= :month_start
  AND order_date < :next_month
GROUP BY currency_code;
```

BEST: geniş tarihsel aggregation için set-based PostgreSQL raporlama.

ANTI-PATTERN: aylık tüm order kayıtlarını Redis'e çekip Java içinde toplamak.

### Dashboard 4: Audit Trail

Nadir audit okumaları için PostgreSQL soğuk veri yolunu kullan.

```sql
SELECT *
FROM order_audit_events
WHERE order_id = :order_id
ORDER BY recorded_at DESC;
```

BEST: audit trail PostgreSQL'de kalıcı ve sorgulanabilir kalsın.

ACCEPTABLE: audit ekranı gerçekten hot ve penceresi sabitse dar bir audit
projection oluştur.

### Dashboard 5: Operasyonel Sağlık Ekranı

Uygulama iş verisi için değil, runtime sağlığı için CacheDB admin metriklerini kullan.

Önemli sinyaller:

- Redis used memory ve maxmemory yüzdesi.
- Write-behind backlog.
- Dead-letter backlog.
- Projection refresh failure sayısı.
- Read-shape guardrail ihlalleri.

BEST: operasyonel telemetriyi iş raporlamasından ayrı tut.

## Partial Update Davranışı

Partial update, magic davranıştan kaçınmanın en önemli yeridir.

Şu satır PostgreSQL'de var ama Redis'te yok kabul edelim:

```text
order_id = 500
customer_id = 42
order_date = 2026-02-10
order_amount = 1000
currency_code = TRY
order_type = ONLINE
status = CREATED
```

İstek yalnızca şunu söylüyorsa:

```text
order_id = 500
status = CANCELLED
```

CacheDB şu Redis payload'ını oluşturmamalıdır:

```text
order_id = 500
customer_id = null
order_date = null
order_amount = null
currency_code = null
order_type = null
status = CANCELLED
```

Bu Redis'i kirletir, index ve projection doğruluğunu bozar.

BEST seçenekler:

- `save(...)` içine geçerli full `OrderEntity` gönder.
- `CancelOrderCommand` gibi açık command route kullan.
- Entity Redis'te varsa güncelle veya invalidate et.
- Entity Redis'te yoksa PostgreSQL'i güncelle, kısmi Redis entity'si üretme.

ANTI-PATTERN:

```java
OrderEntity partial = new OrderEntity();
partial.orderId = 500L;
partial.status = "CANCELLED";

orderRepository.save(partial);
```

## Bellek ve Sıcak Pencere Kuralları

CacheDB bütün veritabanını Redis'te tutmak zorunda değildir.

BEST:

- Sık okunan root entity'ler sıcak kalır.
- Büyük listeler sınırlı projection kullanır.
- Eski geçmiş PostgreSQL'de kalır.
- Redis `maxmemory` ile sınırlandırılır.
- CacheDB guardrail'ları güvensiz full-entity okumalarını durdurur.

Entity hot policy örnekleri:

```java
CachePolicy latestAccessed = CachePolicy.builder()
        .hotEntityLimit(10_000)
        .hotPolicy(EntityHotPolicy.countWindow())
        .build();
```

`COUNT_WINDOW`, sıcak set son erişilen veya son yazılan kayıtlardan oluşuyorsa kullanılır.

```java
CachePolicy recentOrders = CachePolicy.builder()
        .hotEntityLimit(100_000)
        .hotPolicy(EntityHotPolicy.builder()
                .mode(EntityHotPolicyMode.TIME_WINDOW)
                .timeColumn("order_date")
                .hotForDays(90)
                .admitOnRead(false)
                .evictWhenRejected(true)
                .build())
        .build();
```

`TIME_WINDOW`, sıcak set iş zamanına göre belirleniyorsa kullanılır. Örnek: "son 90 günün order kayıtları". Bu kural için `entityTtlSeconds` kullanma; TTL, Redis'e yazılma anından itibaren işler.

```java
CachePolicy openTickets = CachePolicy.builder()
        .hotEntityLimit(50_000)
        .hotPolicy(EntityHotPolicy.stateWindow("status", List.of("OPEN", "PENDING")))
        .build();
```

`STATE_WINDOW`, yalnız belirli iş durumlarının sıcak kalması gerektiğinde kullanılır.

```java
CachePolicy activeRecentOrders = CachePolicy.builder()
        .hotEntityLimit(100_000)
        .hotPolicy(EntityHotPolicy.allOf(List.of(
                EntityHotPolicy.timeWindow("order_date", Duration.ofDays(90).toSeconds()),
                EntityHotPolicy.stateWindow("status", List.of("OPEN", "PENDING"))
        )))
        .build();
```

`COMPOSITE`, gerçek route birden fazla sıcaklık kuralına bağlıysa kullanılır.
Örnek: destek ekranında "son 90 gün" tek başına yeterli olmayabilir; son 90 gün
içinde olan ve hâlâ `OPEN/PENDING` durumundaki order kayıtları Redis'te
kalmalıdır. VIP müşteri kayıtları da sıcak kalacaksa VIP kuralını küçük ve
deterministik bir custom predicate olarak `EntityHotPolicy.anyOf(...)` içine
ekleyebilirsin.

Müşteri order timeline route contract örneği:

```java
RouteCacheContract customerOrdersTimeline = RouteCacheContract.builder()
        .routeName("CustomerOrdersTimelineRoute")
        .entityName("OrderEntity")
        .projectionName("CustomerOrderSummaryHot")
        .pageSize(100)
        .hotWindow(1_000)
        .projectionRequired(true)
        .maxColdReadSize(100)
        .memoryBudgetBytes(256L * 1024L * 1024L)
        .strictMode(RouteCacheStrictMode.FAIL_FAST)
        .tenantQuota(new TenantCacheQuota("tenant_id", 50_000, 128L * 1024L * 1024L, true))
        .build();
```

Route çalışırken contract'ı context'e bağla:

```java
List<OrderSummaryReadModel> page = RouteCacheContext.supplyWithContract(
        customerOrdersTimeline,
        () -> summaries.query(customerOrdersQuery(customerId, 100))
);
```

Route contract, "projection yoksa entity query'ye düş" davranışının güvenli
olmadığı ekranlarda kullanılır: müşteri timeline'ı, finansal hareket listesi,
notification inbox, destek ticket kuyruğu, stok event akışı, leaderboard ve KPI
dashboard gibi.

BEST:

- Page size sıcak pencerenin altında kalır.
- Büyük liste route'larında projection zorunlu olur.
- Tenant quota tek tenant'ın Redis bütçesini tüketmesini engeller.
- Cold read üst sınırı bellidir ve eski veri PostgreSQL/archive yolundan okunur.
- Staging warm sonrasında Redis bellek tahmini gerçek `MEMORY USAGE`
  örnekleriyle kalibre edilir.
- Route seviyesindeki tenant memory budget gerçek entity payload byte ölçümünü
  sayar; aynı entity tekrar yazıldığında tenant bütçesi yapay olarak şişmez.

ANTI-PATTERN:

- Redis'i tüm veritabanının kopyası gibi görmek.
- Ekran yavaşladıkça sürekli `hotEntityLimit` artırmak.
- Dashboard ve raporlama sayfalarında full entity okumak.
- Kötü okuma şeklini temizlemek için Redis eviction policy'ye güvenmek.
- `entityTtlSeconds` değerini "son 90 iş günü" gibi yorumlamak.
- Production route'un projection yerine sessizce entity scan'e düşmesine izin vermek.
- Büyük backfill/warm işini checkpoint, resume ve rate limit olmadan çalıştırmak.
- PostgreSQL CacheDB dışında güncellenirken CDC, outbox veya projection refresh feed'i kurmamak.

## Son Kontrol Listesi

Yeni bir CacheDB route eklemeden önce şu soruları cevapla:

- Bu tek entity mi, küçük page mi, büyük liste mi, rapor mu?
- Veri sıcak mı, eski geçmiş mi?
- Ekran full entity payload mı istiyor, yoksa summary alanları yeterli mi?
- Maksimum result size nedir?
- Bu route entity, projection, ranked projection, command veya PostgreSQL soğuk veri yolu mu olmalı?
- Redis kaydı içermiyorsa ne olacak?
- İşlem tekrar denenirse ne olacak?
- Cutover öncesinde projection parity nasıl doğrulanacak?

Bu sorular cevaplanamıyorsa route henüz production hot path'e alınmamalıdır.
