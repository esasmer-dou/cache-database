# Framework Kullanım Deneyimi: İkinci On İterasyonluk Mühendislik Raporu

English version: [../../docs/framework-ux-second-10-iteration-report.md](../../docs/framework-ux-second-10-iteration-report.md)

Bu rapor, CacheDB çekirdeği ile bağımsız PostgreSQL ve SQL Server örnekleri
üzerinde yürütülen ikinci on turluk inceleme, geliştirme ve doğrulama çalışmasını
açıklar. Başlangıç noktası yayımlanmış `0.7.1` sürümüdür. Bu mühendislik
kaydındaki geliştirmeler `0.8.0` sürümüne alınmıştır; dağıtım kapsamının resmi
özeti `v0.8.0` sürüm notudur.

## Değişmeyen Ürün Sözleşmesi

Her iterasyonda şu sınırlar korundu:

- hızlı erişim route'u yalnızca Redis'ten okur; görünmeyen SQL fallback yoktur;
- source route açık, sınırlı, indeksli ve zaman aşımıyla korunmuş olmalıdır;
- büyüyen listelerde full aggregate yerine projection ve keyset pencere kullanılır;
- yazı önce Redis tarafından kabul edilir, SQL kalıcılığı receipt ile izlenir;
- repository davranışı runtime reflection olmadan derleme sırasında üretilir;
- warm ve uzlaştırma işleri sınırlıdır, çok pod arasında koordine edilir;
- operasyon bilgisi metrik kardinalitesini sınırsız büyütmeden görünür olur;
- PostgreSQL ve SQL Server örnekleri aynı uygulama sözleşmesini korur.

## İterasyon Özeti

| İterasyon | Sonuç |
| --- | --- |
| 1 | Predicate grupları yanlışlıkla OR sorgusu üretemez; bilinçli OR için `explicitDisjunction = true` gerekir. |
| 2 | `HotWindow` ve `SourceWindow`, ortak `WindowSlice` cursor sözleşmesini kullanır. |
| 3 | `HotLookup`, durumları açıkça sorgulamayı ve değeri güvenli biçimde dönüştürmeyi sağlar. |
| 4 | Warm çalıştırma, entity/projection kararını plandan alır; çağıran yalnızca apply veya dry-run seçer. |
| 5 | Kalıcılık yardımcıları receipt, batch receipt listesi, timeout ve işlem bağlamını korur. |
| 6 | CacheDB'nin Redis client'ı, uygulamanın primary Redis bean'ini ezmez. |
| 7 | Generated repository'ler reflection kullanmayan, Spring bean adları çakışmaya karşı korunan bir route kataloğu yayımlar. |
| 8 | Route ve periyodik warm bilgisi startup logu, Actuator ve sınırlı Micrometer metriklerinde görünür. |
| 9 | Generated projection ve batch kalıcılık yolları daha az allocation ile çalışır; iki örnek de bu API'leri kullanır. |
| 10 | İngilizce/Türkçe rehberler ve CI prensip kontrolü yeni sözleşmeleri korur. |

## 1. İterasyon: Sorgu Niyeti Derleme Sırasında Korunuyor

### Sorun

`@CachePredicate.group` alanı OR mantığını temsil eder. Aynı gruptaki koşullar
AND, farklı gruplar ise OR ile birleştirilir. Yeni bir grup eklemek, masum görünen
bir düzenlemede sorgunun kapsamını fark edilmeden genişletebilirdi.

### Yapılan Değişiklik

`@CacheRouteQuery.explicitDisjunction` eklendi. Bir sorguda birden fazla grup
varsa ve bu alan açıkça `true` yapılmamışsa processor derlemeyi durdurur. Testler,
hem örtük OR tanımının reddedildiğini hem de bilinçli OR tanımının kabul edildiğini
kanıtlar.

Sample incelemesinde davranış kaybı da önlendi. Aktif sipariş politikası bilinçli
olarak "son 90 gün veya aktif durum" şeklindedir. İki grup korundu ve OR niyeti
açıkça işaretlendi. Arşiv keyset koşulları ile pasif ürün arşivi de aynı görünür
sözleşmeyi kullanır.

### Canlı Ortama Etkisi

Sorgu kapsamının büyümesi artık annotation ayrıntısı olarak gizlenemez.
Bilinçli OR sorguları ise desteklenmeye devam eder.

### Yapılmayan

Bütün çok gruplu sorguları AND'e çevirmek reddedildi. Bu, iş sonucunu değiştirir
ve örnekteki birleşik admission policy ile çelişirdi.

## 2. İterasyon: Ortak Cursor Pencere Sözleşmesi

### Sorun

`HotWindow` ve `SourceWindow` aynı item/cursor davranışını taşımasına rağmen
uygulama kodu sayfalama işlemini iki kez yazıyordu.

### Yapılan Değişiklik

İki record da `WindowSlice<T>` uygular. Ortak API; `size`, `isEmpty`, `hasNext`
ve `nextRequest(limit)` metotlarını sağlar. `map`, mevcut cursor'u korur;
`HotWindow.map` ayrıca route coverage bilgisini de kaybetmez.
`HotWindow.requireComplete`, coverage kontrolünü akıcı bir kullanım hâline getirir.

### Canlı Ortama Etkisi

Controller ve uygulama servisleri ortak cursor kodunu kullanabilir. Buna rağmen
Redis kapsamı ile kalıcı SQL sonucu arasındaki fark gizlenmez.

### Yapılmayan

Hot ve source sonuçlarını tek, tipsiz bir page nesnesinde birleştirmek reddedildi.
Bu yaklaşım coverage bilgisini siler ve iki farklı veri yolunu aynıymış gibi gösterirdi.

## 3. İterasyon: HotLookup Durumları Açık Kaldı

### Sorun

Redis'te bulunmayan kayıt, tombstone ve policy dışında kalan kayıt aynı anlama
gelmez. Yalnızca `Optional.empty()` kontrolü yapan kod, SQL'de var olan bir satır
için yanlışlıkla HTTP 404 dönebilir.

### Yapılan Değişiklik

`HotLookup`; `isNotCached`, `isTombstoned` ve `isOutsideHotPolicy` metotlarını
sunuyor. `map`, yalnızca hit durumundaki değeri dönüştürüyor ve diğer durumları
aynen koruyor. Mapper ve exception factory `null` döndüremiyor.

### Canlı Ortama Etkisi

Uygulama; veri yokluğu, cache erişilebilirliği ve admission policy sonucunu
birbirine karıştırmadan daha kısa hata eşleme kodu yazabilir.

### Yapılmayan

`NOT_CACHED` sonucunda otomatik SQL sorgusu çalıştırmak reddedildi. Böyle bir
davranış bağlantı havuzu kullanımını, gecikmeyi ve route maliyetini gizlerdi.

## 4. İterasyon: Warm Planı Kendi Veri Şeklini Belirliyor

### Sorun

Çağıran önce generated warm planını seçiyor, sonra aynı entity/projection kararını
ikinci bir `projectionOnly` değeriyle tekrar veriyordu. İki karar birbiriyle
çelişebilirdi.

### Yapılan Değişiklik

`CacheWarmExecutionMode` yalnızca `APPLY` ve `DRY_RUN` seçeneklerini içerir.
`CacheDatabase.executeWarm(plan, mode)`, entity veya projection kararını planın
kendisinden alır. Dönen `CacheWarmExecution`; planı, modu, sonucu, route'u ve
scope'u birlikte tutar. Test probe aynı API'yi sunar.

İki sample artık bu tek çalıştırma yolunu kullanır. HTTP isteği hangi planın
oluşturulacağını seçebilir; fakat execution aşaması planın projection kararını
ikinci kez üretmez.

### Canlı Ortama Etkisi

Dry-run Redis'i değiştirmez, apply açıkça seçilir ve projection-only plan yanlışlıkla
full entity warm olarak çalıştırılamaz.

### Yapılmayan

Uygulama açılırken bütün mevcut SQL verisini kendiliğinden içeri almak reddedildi.
Warm, sınırlı ve operatör tarafından izlenebilir bir işlem olarak kalmalıdır.

## 5. İterasyon: Kalıcılık Hatası Kanıtını Kaybetmiyor

### Sorun

Boolean kalıcılık kontrolleri, her uygulamanın timeout hatasını yeniden yazmasına
neden oluyordu. Batch işleminde receipt kimlikleri ve hangi işin zaman aşımına
uğradığı kaybolabiliyordu.

### Yapılan Değişiklik

Tek receipt ve batch için `awaitDurableOrThrow` yardımcıları eklendi. Başarılı
olduğunda aynı tipli nesneyi döndürür; başarısız olduğunda receipt bilgilerini,
timeout değerini ve `sample seed/orders` gibi işlem adını taşıyan açık bir hata
üretir.

Generated SQL-durable batch command, her receipt için ayrı tam timeout beklemek
yerine tek `awaitAll` işlemi kullanır.

### Canlı Ortama Etkisi

Retry, dead-letter incelemesi ve destek kaydı için gereken kanıt korunur. Batch
işleminin toplam bekleme süresi tek komut deadline'ı ile sınırlı kalır.

### Yapılmayan

`REDIS_ACCEPTED` sonucunu SQL commit olmuş gibi göstermek reddedildi. SQL
kalıcılığı ayrı ve görünür bir durum geçişidir.

## 6. İterasyon: Spring Altyapısı Kullanıcı Bean'ini Ele Geçirmiyor

### Sorun

Starter içindeki `cacheDbJedisPooled` bean'i `@Primary` idi. Uygulamanın CacheDB
dışındaki bir iş için enjekte ettiği `JedisPooled`, fark edilmeden CacheDB
client'ına dönüşebilirdi.

### Yapılan Değişiklik

Primary işareti kaldırıldı. CacheDB'nin foreground ve background client'ları
zaten sabit bean adı ve qualifier ile seçiliyor. Starter testi ve CI kuralı bu
davranışın geri gelmesini engelliyor.

### Canlı Ortama Etkisi

Uygulama kendi primary Redis bean'inin sahibi olmaya devam eder. CacheDB ise
kendi açıkça adlandırılmış bağlantı havuzlarını kullanır.

### Yapılmayan

Birden fazla Redis client arasından tipe göre ilk bulunanı seçmek reddedildi.
Bu yaklaşım çok client'lı Spring uygulamalarında öngörülebilir değildir.

## 7. İterasyon: Generated Route Kataloğu

### Sorun

Repository interface'leri derleme sırasında güvenliydi; ancak operasyon ekibi
hot, source, source-SQL, warm, lookup ve command yüzeylerini runtime annotation
taraması yapmadan listeleyemiyordu.

### Yapılan Değişiklik

Processor, her generated repository için değişmez bir `RepositoryRouteCatalog`
üretir. `RepositoryRouteDefinition`; metot ve route adını, route türünü,
projection'ı, sayfa/pencere/satır sınırlarını, timeout'u, bellek bütçesini,
coverage kapsamını ve projection-only bilgisini taşır. Paket adıyla birlikte
tutulan repository ve entity adları isim çakışmasını önler.

Spring configuration bu kataloğu bean olarak yayımlar. Spring kullanmayan kod,
generated implementasyonun statik `routeCatalog()` metoduna erişebilir.
Classpath taraması, dinamik proxy ve reflection eklenmemiştir.

Farklı paketlerde aynı kısa repository adı kullanıldığında Spring'in varsayılan
bean adı çakışabilirdi. `@CacheRepository.springBeanName` açık bir kaçış yolu
sağlar; processor aynı compilation içinde oluşan duplicate varsayılan veya özel
bean adlarını derleme sırasında reddeder. Route catalog bean adı da paket adıyla
qualified üretilir. Mevcut, çakışmayan repository'lerin varsayılan bean adı
değişmediği için kaynak uyumluluğu korunur.

### Canlı Ortama Etkisi

Tanımlanan route topolojisi makine tarafından okunabilir. Bu bilgi coverage,
warm schedule, runbook ve deployment policy ile karşılaştırılabilir.

### Yapılmayan

Runtime annotation taraması reddedildi. Aynı sözleşmenin derleme ve çalışma
zamanında iki farklı yorumunun oluşmasına izin verilmedi.

## 8. İterasyon: Sınırlı Operasyon Kanıtı

### Sorun

Actuator; kuyruk, projection ve Redis baskısını gösteriyordu, ancak uygulamanın
tanımlı repository topolojisini ve periyodik warm durumunu göstermiyordu. Her
route için ayrı metrik üretmek ise kontrolsüz kardinalite oluştururdu.

### Yapılan Değişiklik

`CacheDbRouteInventory`, generated katalogları reflection olmadan birleştirir.
Startup logu repository, toplam route, hot route ve warm route sayılarını yazar.
`cachedb` Actuator endpoint'i route türü sayılarını, en fazla 250 route ayrıntısını
ve en fazla 100 warm işi ayrıntısını döndürür. Sonuç kesilmişse bunu ayrıca belirtir.
Warm registry ayrıntıları önce bütünü kopyalanmadan, üst sınırı 100 kayıt olan
bir seçim algoritmasıyla hazırlanır; çok sayıda iş tanımı endpoint scrape'inde
ani ve sınırsız bir liste belleği tahsisi oluşturmaz.

Micrometer; tanımlı repository, tanımlı route, çalışan warm işi, warm hatası ve
atlanmış warm sayısı için toplu metrik üretir. Route, scope, müşteri veya tenant
adı tag olarak kullanılmaz.

Meter state referansları registry ömrü boyunca güçlü tutulur. Tekrarlı testte
tespit edilen, `MeterBinder` nesnesi GC tarafından erken toplandığında gauge'ın
`NaN` üretmesi riski framework içinde kapatıldı.

### Canlı Ortama Etkisi

SRE ekibi route tanımlarının ve zamanlanmış işlerin uygulamada bulunduğunu
kanıtlayabilir. Scrape boyutu ve time-series sayısı sınırlı kalır.

### Yapılmayan

Her route veya tenant için metrik serisi üretmek reddedildi. Bu ayrıntı sınırlı
log, trace veya drill-down endpoint üzerinden incelenmelidir.

## 9. İterasyon: Daha Az Allocation Üreten Generated ve Sample Yolları

### Sorun

Generated SQL projection route'ları stream/map/filter/toList zinciri kuruyordu.
SQL-durable batch command receipt'leri tek tek bekliyordu. Sample kodu warm
çalıştırma kararını ve kalıcılık hatasını framework dışında tekrar ediyordu.

### Yapılan Değişiklik

Generated projection mapping, kapasitesi baştan ayrılmış tek `ArrayList` ve bir
kez alınan projector function kullanır. Generated local adları repository
parametreleriyle çakışma riskini azaltan framework öneki taşır. Durable batch
command tek ve sınırlı batch helper kullanır.

PostgreSQL ve SQL Server sample'ları `executeWarm` ile işlem bağlamını koruyan
batch kalıcılık yardımcısına taşındı. İki proje, yerel Maven deposuna kurulmuş
framework artifact'lerini bağımsız olarak tüketip derlendi.

### Canlı Ortama Etkisi

Source projection okuması daha az kısa ömürlü nesne üretir. Batch durability
daha az polling ve bookkeeping yapar. Sample kodu framework kararlarını yeniden
yazmak yerine herkese açık abstraction'ı öğretir.

### Yapılmayan

JNI veya Rust eklenmedi. Bu yol I/O ağırlıklıdır; görülen sorun CPU yoğun
serileştirme değil, önlenebilir Java allocation ve tekrarlı beklemeydi.

## 10. İterasyon: Dokümantasyon, CI ve Doğrulama

### Sorun

Örnekler hâlâ örtük OR mantığı, iki kez verilen warm kararı veya sınırsız
operasyon metriği öğretiyorsa yeni güvenlik sözleşmeleri tamamlanmış sayılmaz.

### Yapılan Değişiklik

İngilizce ve Türkçe repository rehberleri; açık OR gruplarını, ortak pencere
kullanımını, planın yönettiği warm execution'ı, receipt bilgisini koruyan
kalıcılık beklemesini ve route envanterini anlatır. İki sample README'si aynı
operasyon sözleşmesini gösterir.

`check-framework-principles.ps1`; örtük OR koruması, route katalog üretimi,
repository bean adı çakışma koruması, kapasitesi baştan ayrılmış projection
mapping, sınırlı batch durability veya Spring bean izolasyonu kaldırılırsa CI'ı
durdurur.

Starter auto-configuration factory metotlarının önceki public imzaları deprecated
uyumluluk overload'ları olarak korundu. Böylece yeni route inventory bağımlılıkları
eklenirken mevcut derlenmiş uygulamalar gereksiz bir `NoSuchMethodError` riskiyle
karşılaşmaz.

### Doğrulama Kanıtı

Son kaynak hali Semeru/OpenJ9 JDK 21 ve Docker Desktop üzerindeki gerçek Redis,
PostgreSQL ve SQL Server container'larıyla doğrulandı:

- temiz build sonrasında son kaynak üzerinde 20 modüllü tam reactor tekrar koşusu:
  `298` test, `297` geçti, failure/error yok; yalnızca
  özel listener topolojisi isteyen bir SQL Server testi koşullu atlandı;
- bağımsız PostgreSQL sample: `9/9`, Testcontainers entegrasyonu dahil;
- bağımsız SQL Server sample: `9/9`, gerçek SQL Server ve Redis entegrasyonu dahil;
- koşullu atlanan SQL Server listener testi izole Docker lane'inde ayrıca geçti:
  sabit JDBC endpoint'i primary'den secondary'ye
  çevrildikten sonra eski bağlantı beklenen biçimde düştü, yeni bağlantı farklı
  backend'e bağlandı ve provider evidence tekrar geçti;
- public API compatibility kontrolü geçti;
- framework prensibi, Türkçe doküman, README, sample sınırı, provider parity,
  Markdown linki ve iki adet 59 istekli Postman koleksiyonu kapıları geçti;
- üç çalışma ağacında `git diff --check` geçti.

Böylece koşullu listener testi ve iki standalone sample dahil toplam `316` test
senaryosu başarıyla çalıştırıldı. Bu sayı aynı testin iki kez sayıldığı bir toplama
değil; tam reactor, iki bağımsız sample ve reactor'da koşullu atlanan listener
testinin ayrı evidence lane'inin birleşimidir.

Docker listener testi SQL Server Always On replikasyonu veya quorum sertifikası
değildir. Kanıtlanan sözleşme, stable listener adresi arkasındaki backend
değişiminden sonra JDBC pool'un yeni bağlantıyla toparlanması ve CacheDB provider
işlemlerinin yeniden çalışmasıdır.

## Bilinçli Olarak Değiştirilmeyen Sınırlar

| Sınır | Gerekçe |
| --- | --- |
| Görünmeyen SQL fallback yok | Cache miss, veritabanı maliyetine veya iş sonucuna güvenle karar veremez. |
| Otomatik full database warm yok | Redis belleği, DB pool'u ve Kubernetes kaynak sınırlarını aşabilir. |
| Lazy relation loading yok | N+1 ve sınırsız aggregate yüklemeyi yeniden oluşturur. |
| Runtime repository proxy yok | Compile-time kod daha kolay incelenir, ölçülür ve işletilir. |
| Route başına metrik tag'i yok | Route ve scope kardinalitesi güvenli bir üst sınır olmadan büyüyebilir. |
| CacheDB Redis bean'i zorla `@Primary` değil | Dependency injection varsayılanının sahibi uygulamadır. |

## Production Yorumu

Bu çalışma güvenliği ve kullanım kolaylığını artırır; CacheDB'yi şeffaf cache
veya genel amaçlı ORM hâline getirmez. Canlı ortam akışı değişmemiştir:

1. sınırlı hot veya source route tanımla;
2. OR kullanılan her predicate grubunu açıkça işaretle;
3. entity veya projection admission kararını warm planında ver;
4. dry-run ve apply çalıştır, ardından coverage kanıtını kontrol et;
5. hızlı erişim route'unu yalnızca tam coverage sonrasında aç;
6. toplu route ve warm metriklerini izle;
7. arşiv erişimini ve geri dönüş yolunu açık tut.

Route kataloğu uygulamanın ne tanımladığını kanıtlar. Redis'te o anda eksiksiz
veri bulunduğunu kanıtlamaz. Coverage, veri eşitliği, gecikme, bellek ve
kalıcılık kanıtları ayrı production kapıları olmaya devam eder.
