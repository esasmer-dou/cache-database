# Framework Kullanım Deneyimi: Beşinci On İterasyonluk Mühendislik Raporu

English version: [../../docs/framework-ux-fifth-10-iteration-report.md](../../docs/framework-ux-fifth-10-iteration-report.md)

Bu rapor; CacheDB çekirdek API'leri, derleme sırasında üretilen repository
katmanı, Spring Boot operasyon yüzeyi, test desteği ve PostgreSQL ile SQL Server
örnekleri üzerinde yapılan beşinci tam inceleme ve uygulama döngüsünü kaydeder.
Mühendislik tabanı `v0.8.0` sürümüdür. Bu eklemeli değişiklikler `v0.9.0`
sürümüyle yayımlanmıştır.

## Korunan Ürün Sınırları

- HOT route'lar yalnızca Redis'ten okur; gizli SQL fallback eklenmemiştir.
- SOURCE route'lar açık, sınırlı, keyset sayfalı ve zaman aşımı olan SQL okumalarıdır.
- Yazma Redis-first kalır; çağıran açıkça beklemedikçe SQL kalıcılığı asenkrondur.
- Repository implementasyonları ve route metadata'sı derleme sırasında, reflection kullanılmadan üretilir.
- Büyüyen listelerde projection ve keyset sayfalama korunur; offset sayfalama eklenmemiştir.
- Coverage, iyi niyetli bir cache işareti değil, doğruluk sözleşmesidir.
- PostgreSQL ve SQL Server aynı provider-neutral uygulama yüzeyini kullanır.

## İterasyon Özeti

| İterasyon | Uygulanan sonuç |
| --- | --- |
| 1 | Cursor devamında sayfa sınırını koruyan ve sınırlı allocation yapan map API'si eklendi. |
| 2 | Kesin olan query parametreleri derleme sırasında çıkarıldı. |
| 3 | Lookup ve warm parametre rolleri sessiz seçim yapılmadan çıkarıldı. |
| 4 | Strict HOT ve sınırlı SOURCE route'ların doğrudan `CursorPage<T>` dönebilmesi sağlandı. |
| 5 | Coverage kapsamının her OR grubunda eşitlik koşulu olduğu kanıtlandı. |
| 6 | Her repository metodu için reflection kullanmayan tipli route referansı üretildi. |
| 7 | Warm, coverage ve test API'leri generated route referansını kabul edecek şekilde genişletildi. |
| 8 | HOT route bellek ve tasarım özeti Actuator ile Micrometer'a taşındı. |
| 9 | Tekil yazmalar için adı açık, timeout zorunlu SQL kalıcılık yardımcıları eklendi. |
| 10 | İki sample kısa API'ye geçirildi; CI kapıları ve çift dilli anlatım güncellendi. |

## İterasyon 1: Cursor Devamı Aynı Sınırı Koruyor

**Bulgu.** Sonraki sayfa isteği oluşturulabiliyordu; ancak limit yeniden elle
veriliyordu. DTO dönüşümünde de cursor değerini koruyarak yeni sayfa kurmak
gerekiyordu.

**Uygulama.** `WindowRequest.continueAfter`,
`WindowSlice.nextRequest(WindowRequest)` ve
`CursorPage.nextRequest(WindowRequest)` doğrulanmış limiti korur.
`CursorPage.map`, tek bir ön boyutlandırılmış liste kullanır ve cursor'ı taşır.

**Üretim etkisi.** Controller yanlış limit tekrarladığı için sayfa şekli değişmez.
Map işlemi stream zinciri veya sınırsız ara allocation oluşturmaz.

**Reddedilen yaklaşım.** `CursorPage` record'una üçüncü bir `limit` alanı eklemek
JSON biçimini ve public constructor'ı değiştireceği için reddedildi.

## İterasyon 2: Query Parametre Rolleri Güvenle Çıkarılıyor

**Bulgu.** Repository metotlarında `windowParameter = "window"`,
`limitParameter = "limit"` ve `parameter = "customerId"` gibi, tip ve isimden
zaten anlaşılan bilgiler tekrar ediliyordu.

**Uygulama.** Processor; alanla aynı adı taşıyan uyumlu predicate parametresini,
tek `WindowRequest` parametresini veya predicate tarafından kullanılmayan tek
integer limiti çıkarır. Açıkça yazılan değerler desteklenmeye ve öncelik almaya
devam eder.

**Üretim etkisi.** String bağı azalırken tip, parametre tüketimi, satır sınırı ve
predicate doğrulamaları derleme sırasında çalışmayı sürdürür.

**Reddedilen yaklaşım.** Runtime method incelemesi ve parametre adı reflection'ı
reddedildi. Birden fazla aday varsa tahmin yapılmaz; derleme durur.

## İterasyon 3: Lookup ve Warm Rolleri Çıkarılıyor

**Bulgu.** Point lookup ile warm planları; ID, relation preview, satır sınırı,
hedef ve coverage kapsamı adlarını tekrar ediyordu.

**Uygulama.** `@CacheLookup`, ID tipiyle uyumlu tek parametreyi ve kullanılmayan
tek integer relation limitini çıkarır. `@WarmRoute`, source route çözüldükten
sonra satır limiti ile `CacheWarmTarget` parametresini çıkarır; uygun olduğunda
HOT route'un coverage kapsamını devralır.

**Üretim etkisi.** Warm declaration artık bağlantı ayrıntısını değil politikayı
anlatır. Query filtresi yanlışlıkla satır limiti olarak seçilemez.

**Reddedilen yaklaşım.** Parametre sırasına göre rol belirlemek refactor sırasında
güvenilir olmadığı için reddedildi.

## İterasyon 4: Repository Doğrudan Transport Sayfası Döndürebiliyor

**Bulgu.** Coverage hakkında özel karar vermeyen application service'leri bile
`HotWindow.completePage()` veya `SourceWindow.page()` çağrısını tekrarlıyordu.

**Uygulama.** Generated `@HotRoute` ve `@SourceRoute` metotları
`CursorPage<T>` döndürebilir. HOT route'ta buna yalnızca resolved route
`strict=true` ise izin verilir; generated kod coverage'ın eksiksiz ve güncel
olduğunu doğrulamadan sayfa dönmez. Window dönüş tipleri korunmuştur.

**Üretim etkisi.** Yaygın REST akışı tek repository çağrısına iner. Coverage
durumunu ayrıca yorumlayan ileri seviye akışlar `HotWindow<T>` kullanabilir.

**Reddedilen yaklaşım.** Bütün route'ları zorunlu olarak sayfaya çevirmek
reddedildi. Eksik, eski veya hazırlanmamış coverage bilgisini yorumlayan kod bu
kanıtı kaybetmemelidir.

## İterasyon 5: Coverage Kapsamı Yanlış Bilgi Üretemiyor

**Bulgu.** Önceki doğrulama scope adının bir metot parametresi olduğunu
kanıtlıyordu; fakat sorgunun her OR kolunda bu scope ile gerçekten daraltıldığını
kanıtlamıyordu.

**Uygulama.** Kapsamlı bir HOT route, coverage parametresini her query grubunda
tam bir `EQ` predicate içinde kullanmak ve bütün gruplarda aynı entity alanını
daraltmak zorundadır.

**Üretim etkisi.** Müşteri, tenant, shipment veya order kapsamı; başka kapsama
ait satırları içeren bir sorgu kolu için eksiksiz işaretlenemez.

**Reddedilen yaklaşım.** `GTE`, `IN` ve yaklaşık kapsam tespiti tek bir kararlı
coverage kimliği üretmediği için reddedildi.

## İterasyon 6: Raw Route Adlarının Yerine Generated Referans Geldi

**Bulgu.** Runtime catalog üretilmesine rağmen uygulama ve test kodu
`"customer-order-timeline"` gibi string'ler kullanıyordu.

**Uygulama.** Her repository için `OrderRepositoryCacheDbRoutes` benzeri bir
companion üretilir. Metotları static generated catalog'dan çözülen, değişmez
`RepositoryRouteRef` değerleri döndürür.

**Üretim etkisi.** Route adı değişikliği derleme zamanı değişikliğine dönüşür.
Classpath scan, proxy veya reflective lookup eklenmemiştir.

**Reddedilen yaklaşım.** Route türleri ve metadata'sı enum adından daha zengin
olduğu için yalnızca enum üretmek reddedildi.

## İterasyon 7: Operasyon API'leri Route Referansını Koruyor

**Bulgu.** Tipli route referansı warm, coverage ve test katmanında hemen string'e
çevrilirse sağladığı güvence kayboluyordu.

**Uygulama.** `CacheWarmPlan.Builder`, `CacheDatabase` ve `CacheDbTestProbe`
`RepositoryRouteRef` kabul eder. Kind kontrolü; WARM, SOURCE veya COMMAND
referansının HOT coverage gibi kullanılmasını engeller. Projection warm adı da
generated metadata'dan alınabilir.

**Üretim etkisi.** Derleme zamanı sözleşmesi kimliğini kaybetmeden staging
kanıtına kadar taşınır.

**Reddedilen yaklaşım.** Global ve değiştirilebilir route registry eklenmedi.
Static generated catalog ile mevcut Spring inventory tek doğruluk kaynağıdır.

## İterasyon 8: Route Inventory Kapasite Kanıtı Üretiyor

**Bulgu.** Actuator route sayısını gösteriyordu; fakat HOT route'ların kaçının
projection kullandığını, kapsamlı olduğunu veya bellek bütçesi tanımlamadığını
özetlemiyordu.

**Uygulama.** `HotRouteAssessment`; HOT route sayısını, projection/entity
dağılımını, kapsamlı ve bütçeli/bütçesiz route sayılarını ve tanımlı bellek
bütçelerinin taşma güvenli toplamını üretir. Bu özet Actuator, startup log'u ve
sınırlı cardinality kullanan Micrometer gauge'larına eklenmiştir.

**Üretim etkisi.** Operasyon ekibi yük gelmeden önce tasarım borcunu görebilir.
Route adları metric tag'i yapılmadığı için cardinality sınırsız büyümez.

**Reddedilen yaklaşım.** Tanımlı bütçe toplamını gerçek Redis tüketimi saymak
reddedildi. Gerçek tüketimin doğruluk kaynağı Redis memory ve admission
metric'leridir.

## İterasyon 9: SQL Kalıcılığı Kısa Ama Açık

**Bulgu.** SQL kalıcılığını gerçekten beklemesi gereken tekil komut iki çağrı
gerektiriyordu. Yanlış adlandırılmış bir kolaylık ise senkron kalıcılığı varsayılan
yazma modeli gibi gösterebilirdi.

**Uygulama.** `saveDurably`, optimistic `saveDurably`, `saveAfterDurably`,
`deleteDurably` ve `updateHotDurably`; pozitif timeout'u zorunlu tutar ve aynı
tipli receipt'i döndürür.

**Üretim etkisi.** Tekil komutun kalıcılık sınırı kısa ve açıktır. Normal yazma
Redis-first ve asenkron kalmaya devam eder.

**Reddedilen yaklaşım.** Bulk import için satır başına durability beklemek
reddedildi. Bulk akış, batching ve backpressure için `CacheDurableBatchWriter`
kullanmalıdır.

## İterasyon 10: Sample ve Kalite Kapıları Ürün API'sini Kullanıyor

**Bulgu.** Public örnek eski tekrarlı biçimde kalır veya CI yeni dönüş tipini
tanımazsa framework yeteneği tamamlanmış sayılmaz.

**Uygulama.** İki provider sample'ı inferred parametre rolleri, doğrudan
`CursorPage<T>` route'ları, integration testlerde generated route referansı ve
application katmanında page conversion olmadan çalışır. Sample kalite kapısı
hem `HotWindow` hem `CursorPage` HOT metotlarını tanır ve gereksiz rol
binding'lerini reddeder. İngilizce ve Türkçe rehberler derlenen gerçek biçimi
gösterir.

**Üretim etkisi.** PostgreSQL ve SQL Server tek uygulama mimarisini korur.
Kod kısalırken veri yolu kararları gizli runtime davranışına taşınmamıştır.

**Reddedilen yaklaşım.** Generated controller, otomatik SQL fallback, offset
sayfalama ve runtime repository scan hâlâ reddedilmiştir. Bunlar kodu azaltırken
production'da incelenmesi gereken kararları saklar.

## Uyumluluk ve Sürüm Durumu

- `v0.9.0`, bu mühendislik döngüsünü içeren değişmez sürümdür.
- Repo ile iki sample sürümü aynı `0.9.0` paket hattını kullanır.
- Mevcut açık annotation alanları source-compatible kalır.
- Mevcut `HotWindow<T>` ve `SourceWindow<T>` imzaları geçerlidir.
- `CursorPage<T>` transport biçimi iki alanlı kalır: `items` ve `nextCursor`.
- Yeni API runtime reflection veya gizli source okuması eklemez.

## Doğrulama

Bu döngü IBM Semeru OpenJ9 Java 21.0.2 ve Maven 3.9.9 ile doğrulandı:

- Maven reactor koşusu `318` test, `0` başarısızlık, `0` hata ve mevcut
  ortam/profile koşullarına bağlı `12` atlama ile tamamlandı. Redis ve
  PostgreSQL, repository'nin yerel integration container'ları ve açık test
  bağlantı ayarlarıyla sağlandı.
- İlk reactor denemesindeki altyapı hatası kanıttan çıkarılmadı: varsayılan
  `6379` ve `5432` portlarında servis dinlemediği için testler başlayamadı.
  Resmî integration container'ları `PONG` ve `accepting connections` sonucunu
  verdikten sonra aynı reactor kapsamı `870,2 saniyede` geçti.
- Her iki sample derlenmeden önce `mvn -DskipTests install` ile bütün
  `0.9.0` artifact seti yerel Maven repository'sine kuruldu.
- PostgreSQL sample'ı, Testcontainers Redis 8.2.1 ve PostgreSQL 16 provider
  integration profiliyle `94,2 saniyede` geçti.
- SQL Server sample'ı, Testcontainers Redis 8.2.1 ve SQL Server 2022 provider
  integration profiliyle `67,3 saniyede` geçti.
- Public API karşılaştırmasında yalnızca yeni API eklemeleri bulundu. Bilinçli
  geliştirme baseline'ı yeniden üretildi ve compatibility kontrolü tekrar geçti.
- Framework prensipleri `807` Java dosyasında, deklaratif sample sınırları `120`
  Java dosyasında, provider eşitliği ise `63` ortak dosya ve paylaşılan
  integration sözleşmesinde doğrulandı.
- İngilizce/Türkçe README kalitesi, Türkçe dil kalitesi, Markdown bağlantıları
  ve iki provider için ayrı ayrı `59` istek içeren Postman collection'ları geçti.
- Üretilen sample kaynaklarında tipli `*CacheDbRoutes` companion sınıfları yer
  alır. Strict HOT/SOURCE sayfa tamamlama işi application service'lerinde değil,
  generated implementasyonlarda yapılır.

Runtime reflection, otomatik SQL fallback, offset sayfalama, generated
controller veya satır başına kalıcılık bekleyen bulk helper eklenmemiştir.
