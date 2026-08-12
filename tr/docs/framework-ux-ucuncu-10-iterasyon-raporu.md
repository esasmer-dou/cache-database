# Framework Kullanım Deneyimi: Üçüncü On İterasyonluk Mühendislik Raporu

English version: [../../docs/framework-ux-third-10-iteration-report.md](../../docs/framework-ux-third-10-iteration-report.md)

Bu rapor, CacheDB çekirdeği, Spring Boot entegrasyonu ve bağımsız PostgreSQL ile
SQL Server örnekleri üzerinde yürütülen üçüncü on turluk inceleme, geliştirme ve
doğrulama çalışmasını açıklar. Başlangıç noktası yayımlanmış `0.7.1` sürümüdür.
Bu mühendislik kaydındaki geliştirmeler `0.8.0` sürümüne alınmıştır; dağıtım
kapsamının resmi özeti `v0.8.0` sürüm notudur.

## Korunan Ürün Sınırları

- Hızlı erişim route'u yalnızca Redis'ten okur; eksik kayıt görünmeyen SQL sorgusu başlatmaz.
- Kaynak route açık, sınırlı, indeksli ve zaman aşımıyla korunmuş kalır.
- Büyüyen listelerde tam aggregate yerine projection ve keyset pencere kullanılır.
- Yazı önce Redis tarafından kabul edilir; SQL kalıcılığı tipli receipt ile izlenir.
- Repository ve runtime entegrasyonu reflection kullanmadan derleme sırasında üretilir.
- Ön yükleme, uzlaştırma ve dağıtık işler sınırlı ve çok pod'lu çalışmaya uygundur.
- Operasyon kanıtı sınırlıdır; route, müşteri ve tenant adları metric etiketi olmaz.
- PostgreSQL ve SQL Server örnekleri aynı uygulama mimarisini korur.

## İterasyon Özeti

| İterasyon | Sonuç |
| --- | --- |
| 1 | Ön yükleme satır sayıları düzeltildi, sonuç kuralları sertleştirildi. |
| 2 | Repository yetenekleri generic hata yerine tipli ve düşük maliyetli sözleşmeye taşındı. |
| 3 | Hızlı erişim route'unun nasıl doldurulacağı derleme zamanı sözleşmesi oldu. |
| 4 | Global route kimliği güvenli hâle getirildi, önceden indekslenen operasyon envanteri kuruldu. |
| 5 | Ayrı entity/projection warm metotları tek tipli hedefte birleştirildi. |
| 6 | Uygulama katmanı için standart ön yükleme sonucu eklendi. |
| 7 | Dağıtık iş üreticisi ve handler aynı tipli tanımı kullanmaya başladı. |
| 8 | İki sample'ın ön yükleme akışı tek komut ve tek plan seçimine indirildi. |
| 9 | Ön yükleme REST API'leri doğrulanan, asenkron ve izlenebilir hâle getirildi. |
| 10 | Test araçları, sınırlı metrikler, CI kuralları ve EN/TR belgeler tamamlandı. |

## 1. İterasyon: Satır Sayıları Doğru ve Denetlenebilir

### Bulgu

Başarılı ön yükleme yolunda kaynaktan okunan ve Redis'e gönderilen satır sayıları
`CacheWarmResult` kurucusuna ters sırada veriliyordu. Sayılar eşit olduğunda hata
gizleniyor, admission reddi veya deneme modunda yanlış operasyon kanıtı
üretilebiliyordu.

### Uygulama

Kurucu çağrısı düzeltildi. `CacheWarmResult` ve `CacheWarmSummary`; negatif
değerleri, okunan satırdan fazla gönderim sayısını ve negatif süreyi reddediyor.
Not listeleri değişmez kopya olarak saklanıyor.

### Production Etkisi

Operasyon ekranları ve iş sonuçları artık kaynaktan okunan satır ile gerçekten
Redis'e kabul edilen satırı doğru ayırıyor. Geçersiz kanıt sessizce yayılmıyor.

### Yapılmayan

Yanlış sayıları sıfıra veya üst sınıra çekmek reddedildi. Bu yaklaşım framework
hatasını gizlerdi.

## 2. İterasyon: Repository Yetenekleri Tipli

### Bulgu

İsteğe bağlı repository işlemleri ancak çağrılıp generic
`UnsupportedOperationException` alındığında anlaşılabiliyordu. Entegrasyon
katmanı optimize edilmiş işlemin desteklenip desteklenmediğini önceden göremiyordu.

### Uygulama

`RepositoryCapability`, değişmez `RepositoryCapabilities` ve
`RepositoryCapabilityUnavailableException` eklendi. Yetenek kontrolü bit maskesi
kullanıyor; okuma yolunda set veya liste üretmiyor. Redis repository tek ve
yeniden kullanılan sabit yetenek kümesi yayımlıyor.

### Production Etkisi

Adapter'lar doğru işlem adıyla erken hata verebilir. Kontrol maliyeti sabittir;
reflection ve istek başına allocation eklenmemiştir.

### Yapılmayan

Metotları runtime'da deneyerek veya reflection ile tarayarak yetenek keşfetmek
reddedildi.

## 3. İterasyon: Route'un Redis'e Nasıl Alınacağı Açık

### Bulgu

Hızlı erişim route'u sorguyu ve sınırları tanımlıyor, ancak Redis'teki temsil
edici veri kümesinin nasıl oluşacağını söylemiyordu. Bu bilgi ekip hafızasında
kalabiliyordu.

### Uygulama

`@HotRoute.population`; `ON_DEMAND`, `DECLARED_WARM`, `WRITE_FED` ve `EXTERNAL`
seçeneklerini aldı. Generated route metadata bu stratejiyi taşıyor.
`DECLARED_WARM` seçilen route için eşleşen `@WarmRoute(from=...)` yoksa derleme
duruyor. İki sample'daki bütün hızlı erişim route'ları açıkça
`DECLARED_WARM` kullanıyor.

### Production Etkisi

Kod incelemesinde her route'un nasıl doldurulacağı görülebiliyor. Eksik ön
yükleme sözleşmesi boş production ekranı yerine build hatası üretiyor.

### Yapılmayan

Uygulama açılırken bütün veritabanını kendiliğinden Redis'e taşımak reddedildi.
Bu davranış SQL yükünü, Redis belleğini ve pod başlangıcını sınırsız bağlardı.

## 4. İterasyon: Route Kimliği ve Envanteri Güvenli

### Bulgu

Kataloglar repository düzeyindeydi. Global hızlı erişim route adı çakışması Redis
coverage anahtarlarını üst üste bindirebilirdi. Actuator okumaları da descriptor
nesnelerini yeniden kuruyordu.

### Uygulama

`CacheDbRouteInventory`; route'ları `repository#method` ile, hızlı erişim
route'larını global adla indeksliyor. Çakışan adları reddediyor ve
`DECLARED_WARM` bilgisini başlangıçta ikinci kez doğruluyor. Sıralı descriptor
listesi bir kez üretiliyor; sınırlı Actuator okumalarında yeniden kullanılıyor.

### Production Etkisi

Belirsiz coverage üretime giremiyor. Arama sabit maliyetli, başlangıç kontrolü
öngörülebilir ve operasyon sorguları gereksiz nesne grafiği oluşturmuyor.

### Yapılmayan

Çakışan adlara sessizce prefix eklemek reddedildi. Bu, mevcut Redis anahtarlarını
değiştirir ve gerçek sözleşme hatasını gizlerdi.

## 5. İterasyon: Tek ve Tipli Ön Yükleme Hedefi

### Bulgu

Sample'larda aynı route için projection-only ve entity-plus-projection olmak
üzere iki generated metot gerekiyordu. Bu metotların sorgu, kapsam veya sınırları
zamanla ayrışabilirdi.

### Uygulama

`@WarmRoute.targetParameter` ve `CacheWarmTarget` eklendi. Processor, parametre
tipini doğruluyor; statik `projectionsOnly=true` ile çelişen tanımı reddediyor ve
runtime hedef seçimini yalnızca projection kullanan route'larda kabul ediyor.
İki sample artık `warmCustomerTimeline(customerId, maxRows, target)` gibi tek
metot kullanıyor.

### Production Etkisi

Sorgu, kapsam ve limit tek generated tanımda kalıyor. Uygulama veri şeklini
string veya metot adı yerine enum ile seçiyor.

### Yapılmayan

Serbest bir options map reddedildi. Hatalı kombinasyonları derleme zamanından
runtime'a taşırdı.

## 6. İterasyon: Tek Ön Yükleme Sonuç Modeli

### Bulgu

Çağıranlar işlem adı, route, kapsam, hedef, mod ve satır sayılarını plan ile
sonuç nesnesinden ayrı ayrı birleştiriyordu.

### Uygulama

`CacheWarmSummary`; işlem, plan, route, entity, kapsam, istenen/okunan/gönderilen
satır, süre, hedef, mod ve sınırlı not listesini tek değişmez modelde topluyor.
`CacheWarmExecution.summary(...)` uygulama katmanındaki tekrarları kaldırıyor.

### Production Etkisi

REST işleri, loglar, testler ve yönetim araçları aynı sonuç dilini kullanıyor.
Deneme, yalnızca projection ve tam gönderim durumları açıkça görülebiliyor.

### Yapılmayan

Framework'ten `Map<String,Object>` döndürmek reddedildi. Alan hatalarını runtime'a
taşır ve API uyumluluğunu zayıflatırdı.

## 7. İterasyon: Dağıtık İş Tanımı Tipli

### Bulgu

İş üreticisi string route ve herhangi bir nesne gönderirken handler route ile
parametre tipini ayrı tanımlıyordu. Uyumsuzluk ancak serialization veya claim
sonrasında ortaya çıkabiliyordu.

### Uygulama

`CacheDistributedJobDefinition<A>`, cluster genelinde sabit route ile parametre
sınıfını birleştiriyor. Tipli submit, Redis kuyruğuna yazmadan önce parametreyi
doğruluyor. Handler kaydı ve deserialize aynı tanımı kullanıyor; uyuşmazlık erken
hata veriyor. Küçük factory, dinamik proxy olmadan kısa handler yazımını sağlıyor.

### Production Etkisi

Bütün pod'lar aynı açık sözleşmeyi kaydediyor. Geçersiz iş kuyruğa girmiyor;
yarım kalan iş aynı handler kümesine sahip başka pod tarafından alınabiliyor.

### Yapılmayan

Java sınıf adını kuyruk route'u yapmak reddedildi. Sınıf taşıma ve yeniden
adlandırma rolling deployment uyumluluğunu bozardı.

## 8. İterasyon: Sample Ön Yükleme Akışı İnce

### Bulgu

İki sample; çok sayıda repository alanını, her route için wrapper metodu, hedef
koşullarını ve handler dispatch kodunu tekrar ediyordu. Kullanıcı framework
ayrıntısına gereğinden fazla giriyordu.

### Uygulama

Her sample'da tek ve doğrulanan `SampleWarmCommand`, tek route enum'u, tek
`SampleWarmBackfillService.execute` ve tek generated-plan switch bulunuyor.
`SampleRepositories`, repository bağımlılıklarını grupluyor. Job handler yalnızca
checkpoint yazıyor, servise devrediyor ve `CacheWarmSummary` döndürüyor.

### Production Etkisi

Yeni örnek route eklemek için bir command factory ve bir plan eşlemesi yeterli.
Servis ad-hoc SQL üretmiyor, Redis client açmıyor ve kaynak fallback gizlemiyor.
PostgreSQL ile SQL Server kodu aynı yapıyı koruyor.

### Yapılmayan

Servis metotlarını runtime annotation taramasıyla çağıran dispatcher reddedildi.
Reflection ve görünmeyen kontrol akışı oluştururdu.

## 9. İterasyon: HTTP Doğrulanan ve Asenkron

### Bulgu

Controller'larda limit kontrolleri tekrar ediyordu. Kabul edilen iş yanıtı,
istemcinin sonucu hangi adresten izleyeceğini standart biçimde göstermiyordu.

### Uygulama

Sayısal sınırlar, kimlikler, metinler ve skorlar Bean Validation ile korunuyor.
Controller yalnızca tipli komut gönderiyor. Kabul edilen her istek `202 Accepted`,
iş özeti ve `Location: /api/warm/jobs/{jobId}` döndürüyor. Ağır JDBC/Redis işi
HTTP iş parçacığında çalışmıyor.

### Production Etkisi

Hatalı istek kuyruğa girmeden reddediliyor. İstemcinin standart polling adresi
var; request thread sınırlı kalıyor ve çok pod'lu job çalışması korunuyor.

### Yapılmayan

HTTP çağrısında warm tamamlanana kadar beklemek reddedildi. Thread tüketir,
gateway timeout riskini büyütür ve istemci tekrarlarını tehlikeli hâle getirirdi.

## 10. İterasyon: Tanılama ve Regresyon Kapıları

### Bulgu

Testler coverage'ı doğruluyor, ancak generated population sözleşmesini
göremiyordu. Metrikler route toplamını gösteriyor, route'ların nasıl beslendiğini
göstermiyordu. Bazı README örnekleri kaldırılan çift warm metotlarını anlatıyordu.

### Uygulama

`CacheDbTestProbe`, generated envanteri ve beklenen population stratejisini
doğrulayabiliyor. Actuator population sayılarını gösteriyor. Micrometer,
yalnızca dört sabit strateji değeri taşıyan
`cachedb.routes.hot.population{strategy=...}` metriğini ekliyor; route, müşteri
ve tenant etiketi üretmiyor. Sample entegrasyon testleri declared-warm route'u ve
kabul edilen işin `Location` başlığını kontrol ediyor.

Framework prensip kapısı; tipli warm hedefini, açık population tanımını, tipli
sample işlerini ve eski warm API'lerinin kaldırılmasını koruyor. İngilizce ve
Türkçe çekirdek/sample belgeleri gerçek generated metot imzalarını kullanıyor.

### Production Etkisi

Eksik population sözleşmesi ve doküman sapması build sırasında yakalanıyor.
Operasyon ekibi, sınırsız metric kardinalitesi oluşturmadan route stratejisi ile
coverage ve zamanlanmış işleri karşılaştırabiliyor.

### Yapılmayan

Her route için ayrı Micrometer etiketi reddedildi. Route ve kapsam ayrıntısı
sınırlı Actuator çıktısı, log veya trace içinde kalmalıdır.

## Doğrulama Kanıtı

Nihai kaynak; Java 21 OpenJ9 ile Docker üzerinde çalışan Redis ve PostgreSQL test
servisleri kullanılarak aşağıdaki kapılardan geçti:

- tam Maven reaktörü: `305` test, `0` başarısızlık, `0` hata, `3` atlanan test
- production kanıt modülü: `27` test, `0` başarısızlık, `0` hata
- PostgreSQL sample: `10` test, `0` başarısızlık, `0` hata
- MSSQL sample: `10` test, `0` başarısızlık, `0` hata
- public API uyumluluğu: kaldırılan imza yok
- framework prensipleri: `800` runtime Java dosyası kontrol edildi
- sample framework sınırları: `122` Java dosyası kontrol edildi
- provider eşliği: `64` provider bağımsız Java dosyası kontrol edildi
- Postman eşliği: her provider için `59` istek doğrulandı
- Türkçe doküman, README kalitesi, Markdown bağlantıları ve
  `git diff --check`: başarılı

İlk tam reaktör koşusu, crash/replay testinde kararsız bir bekleme aralığını
ortaya çıkardı. Ürün replay yolu bağımsız koşuda geçti. Test bütçesi daha sonra
yapılandırılabilir ve monotonic hâle getirildi; pending claim eşiği ile bloklu
okuma döngüsünü kapsayacak güvenli pay eklendi. Son tam reaktör koşusu geçti.

## Bilinçli Olarak Eklenmeyenler

| Öneri | Karar |
| --- | --- |
| Redis kaydı yoksa görünmeyen SQL fallback | Reddedildi: maliyet ve erişilebilirlik öngörülemez olur. |
| Uygulama açılırken tüm veritabanını warm etme | Reddedildi: SQL, Redis ve pod başlangıç yükü sınırsız olur. |
| Runtime repository proxy veya annotation tarama | Reddedildi: derleme zamanı üretim ve reflection kullanmama ilkesiyle çelişir. |
| Route/customer/tenant metric etiketi | Reddedildi: kardinalite güvenli biçimde sınırlanamaz. |
| Senkron warm HTTP çağrısı | Reddedildi: request thread'i bloke eder ve gateway arkasında kırılgandır. |
| Orkestrasyon için Rust/JNI | Reddedildi: bu yol I/O ve kontrol akışı ağırlıklıdır; Java allocation zaten sınırlandı. |

## Production Yorumu

Kullanım deneyimi, bu ürün için anlamlı alanlarda olgun Java framework'lerine
yaklaştı: derleme zamanı doğrulama, tipli repository, starter auto-configuration,
Actuator kanıtı, test desteği ve sade sample uygulama kodu. Redis öncelikli
doğrulukla çelişen ORM davranışları bilinçli olarak kopyalanmadı.

Operasyon sırası açık kalır:

1. sınırlı hızlı erişim veya kaynak route'u tanımla;
2. her hızlı erişim route'unun nasıl doldurulacağını belirt;
3. gerekiyorsa tipli ön yükleme planını üret;
4. deneme çalıştır, ardından dayanıklı arka plan işini gönder;
5. coverage, veri eşitliği, gecikme, bellek ve kalıcılığı ayrı ayrı kanıtla;
6. yalnızca bu kapılar geçtikten sonra Redis route'una trafik ver.
