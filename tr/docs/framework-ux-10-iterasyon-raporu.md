# Framework Kullanım Deneyimi: On İterasyonluk Mühendislik Raporu

English version: [../../docs/framework-ux-10-iteration-report.md](../../docs/framework-ux-10-iteration-report.md)

Bu rapor, CacheDB çekirdeği ile PostgreSQL ve MSSQL örnekleri üzerinde art arda
yürütülen on inceleme, geliştirme ve doğrulama turunu açıklar. Amaç, uygulama
kodunu azaltırken Redis kapsamını, SQL kalıcılığını, bellek maliyetini ve çok
pod'lu çalışma davranışını gizlememektir.

## Değişmeyen Tasarım Sınırları

- Hızlı erişim route'ları yalnızca Redis'ten okur; görünmeyen SQL fallback yoktur.
- Arşiv okumaları açık ve sınırlı source route üzerinden yapılır.
- Liste ekranlarında sınırlı projection ve keyset pencere kullanılır.
- Yazı önce Redis tarafından kabul edilir; SQL kalıcılığı receipt ile izlenir.
- Runtime reflection ve dinamik repository proxy'si yerine generated kod kullanılır.
- Warm işleri sınırlı, izlenebilir ve çok pod arasında koordinelidir.
- Eksik coverage, bulunamayan update state'i ve hatalı ayar açıkça hata verir.

## İterasyon Kanıt Matrisi

| İterasyon | Ana kanıt |
| --- | --- |
| 1 | Herkese açık API yüzeyi, üretilen implementasyonlar, veritabanı sağlayıcı starter'ları, örnekler ve Git'e kaydedilmiş `0.6.0` API temel çizgisi birlikte incelendi. |
| 2 | Çekirdek pencere testleri ve canlı örnek HTTP testleri, hazırlanmamış listenin yanıltıcı boş sonuç yerine hata verdiğini kanıtladı. |
| 3 | Processor derleme testleri sınırlı ilk sayfa üretimini doğruladı; sayfalı route'lar keyset işaretçisini korudu. |
| 4 | Repository sözleşme testleri, iyimser güncelleme sırasında görünmeyen SQL okuması yapılmadığını ve eksik Redis verisinin kabul edilmediğini kanıtladı. |
| 5 | Processor testleri ortak repository parçalarını derledi; hatalı route tanımları `[CacheDB]` mesajıyla reddedildi. |
| 6 | Spring ayar testleri hatalı bağlantı havuzu, zaman aşımı, lease, kuyruk, kimlik bilgisi ve MSSQL değerlerini açılışta reddetti. |
| 7 | Test paketi; tam coverage, silinmiş kayıt işareti, policy dışı kayıt, kontrollü warm ve sınırlı kalıcılık bekleme senaryolarını doğruladı. |
| 8 | CI, 120 örnek Java dosyasını, veritabanı sağlayıcısından bağımsız 62 dosyayı ve ortak PostgreSQL/MSSQL bütünleşme sözleşmesini inceledi. |
| 9 | CI, reflection ve generated kod allocation kuralları için 783 Java dosyasını taradı; üretilen zamanlanmış görev doğrudan tipli metodu çağırdı. |
| 10 | Doküman, API, paket, performans, Postman, veritabanı sağlayıcısı ve temiz reactor kapıları son doğrulama çevriminde geçti. |

## 1. İterasyon: Mimari ve API Sınırı

Repository yüzeyi, generated binding'ler, processor modeli, Spring Boot
auto-configuration, test paketi ve iki örnek proje uçtan uca incelendi.
Uygulama API'si ile düşük seviyeli uyumluluk API'leri ayrıldı. Provider seçimi,
Redis-first yazı, source okuma ve warm çalıştırma görünür bırakıldı.

Yapılmadı: route sözleşmesini metot adından sorgu üreten genel bir parser'a veya
görünmeyen ORM fallback'ine çevirmek. Bu yaklaşım maliyeti ve veri kaynağı
seçimini incelemeyi zorlaştırırdı.

## 2. İterasyon: Tam Sonuç Sözleşmesi

`HotWindow.completeItems()`, yalnızca route coverage güncel ve tam ise satırları
döndürür. Aksi durumda coverage kanıtını taşıyan
`HotRouteUnavailableException` fırlatır. Uygulama isterse mapper overload'u ile
bu kanıtı kendi hata tipine çevirebilir.

Örnek projelerde hızlı erişim endpoint'leri bu metodu kullanır ve eksik kapsamı
HTTP 503 olarak gösterir. Source ve arşiv route'ları sınırlı SQL sonucunu dönmeye
devam eder.

Kapatılan risk: Redis'teki eksik pencerenin geçerli fakat boş ya da kısa bir iş
sonucu gibi görünmesi.

## 3. İterasyon: İlk Sayfa ve Keyset Okuma Biçimleri

Top-N ve yalnızca ilk sayfayı döndüren route'lar, `limitParameter` üzerinden
basit bir `int limit` alır. Generated kod bunu sınırlı ilk pencere isteğine
çevirir. Zaman çizelgesi ve arşiv route'ları, keyset ile sayfalama yaptıkları için
`WindowRequest` almaya devam eder.

Kapatılan risk: basit endpoint'lerde gereksiz pagination kodu yazılması veya
çok sayfalı route'ların cursor bilgisini kaybedip offset taramasına dönmesi.

## 4. İterasyon: Güvenli Optimistic Update

`CacheDbRepository.updateHot`, Redis'teki güncel sürümü okuma, tam entity üretme
ve expected-version ile kaydetme adımlarını tek yerde toplar. Redis'te güncel
sürüm yoksa `HotUpdateUnavailableException` fırlatır. SQL'den gizlice okuyup
eksik komutu birleştirmez.

Örnek servisler bu sözleşmeye taşındı ve bu durum HTTP 409 olarak gösterildi.
Gerçek bir deduplication deposu olmadan güvence veremeyen `idempotent` annotation
alanı kaldırıldı.

Kapatılan risk: kayıp güncelleme, eksik entity yazımı ve gerçekte bulunmayan
idempotency garantisi.

## 5. İterasyon: Genişletilebilir Compile-Time Repository

Entity ve ID tipleri, generic üst interface'ler üzerinden doğru tip eşlemesiyle
çözülür. Ekipler ortak repository fragment'ları ve default yardımcı metotlar
tanımlarken generated implementasyon almaya devam eder. Default, static ve
private metotlar hatalı abstract route gibi değerlendirilmez.

Processor hata mesajları `[CacheDB]` ile başlar. Hatalı alan, parametre, SQL,
limit ve imza tanımları yine derleme sırasında reddedilir.

Kapatılan risk: ortak interface kullanıldığında compile-time kontrolün kaybı ve
repository boilerplate'inin kopyalanması.

## 6. İterasyon: Spring Ayarlarında Erken Hata

`CacheDbSpringProperties`; Redis pool sınırlarını, timeout'ları, leader lease
yenilemesini, scheduled warm thread ayarlarını, distributed job kuyruğunu,
admin güvenlik girdilerini ve MSSQL timeout değerlerini uygulama açılışında
doğrular.

Kapatılan risk: hatalı ayarın ancak trafik altında pool dolunca, lease düşünce
veya admin endpoint'i açılınca fark edilmesi.

## 7. İterasyon: Deklaratif Test Desteği

`CacheDbAssertions`; tam route, tombstone ve policy dışı lookup kontrollerini
destekler. `CacheDbTestProbe.warmAndRequireCoverage`, kontrollü warm ile coverage
kanıtını birleştirir. Tipli durability yardımcıları receipt'i geri döndürür.

Kapatılan risk: satırlar warm edildiği hâlde istemcinin okuyacağı route scope'u
tamamlanmadan integration testinin başarılı sayılması.

## 8. İterasyon: Örnek Projelerde Katman Sınırı

PostgreSQL ve MSSQL örnekleri aynı repository sözleşmesini, uygulama servislerini,
hata modelini ve generated kod sınırını kullanır. Controller repository veya
CacheDB iç sınıflarını import etmez. İş servisleri runtime bootstrap etmez ve
generated implementasyon sınıflarına bağlanmaz.

`check-sample-framework-usage.ps1` ve provider parity kontrolü bu kuralları CI
hatasına dönüştürür. Mimari kapısı ayrıca her örnek `HotWindow` metodu için
sınırlı ve eşleşen bir `@WarmRoute` bulunmasını zorunlu tutar. Okunabilen fakat
operasyonel olarak doldurulamayan route kabul edilmez.

Kapatılan risk: örnek projenin kullanıcıya public API'yi atlamayı veya provider'a
özel iş mantığı yazmayı öğretmesi.

## 9. İterasyon: Allocation ve Reflection Disiplini

Generated repository'ler route sort listelerini ve route contract nesnelerini
static sabit olarak yeniden kullanır. Bulk save, ID üretimi gerekmiyorsa verilen
collection'ı kullanır; gerekiyorsa tek ve önceden boyutlandırılmış liste oluşturur.
Generated write yolundan stream pipeline'ı ve raw cast kaldırıldı.

`@CacheScheduledWarm` artık source-retained bir annotation'dır. Processor metot
imzasını doğrular ve doğrudan çağrı yapan tipli Spring task adapter'ı üretir.
Runtime annotation taraması, dinamik scheduling proxy'si ve `Method.invoke`
kullanılmaz. Redis lease ve uzlaştırma davranışı değişmemiştir.

`check-framework-principles.ps1`, runtime reflection ve generated kod allocation
kuralları bozulursa CI'ı durdurur.

Kapatılan risk: açılışta reflection taraması, yansıtmalı çağrı, her sorguda route
metadata allocation'ı ve bulk komutlarında gereksiz çöp üretimi.

## 10. İterasyon: Doküman ve Release Kanıtı

İngilizce ve Türkçe rehberler artık `completeItems()` kullanımını, top-N ile
keyset sayfalama farkını, SQL'den gizli merge yapılmayan update davranışını ve
compile-time scheduled warm adapter'ını açıklar. Kaldırılan API alanları
kopyala-çalıştır örneklerinden temizlendi.

Doküman, public API, sample mimarisi, provider parity, release artifact'leri ve
framework prensipleri ayrı CI kapılarıdır. Böylece yeşil unit test sonucu,
bozulmuş onboarding veya mimari sözleşmeyi gizleyemez.
Örnek projelerin hızlı başlangıç adımları ile Postman koleksiyonları aynı sırayı
izler: kalıcı veriyi oluştur, ilgili route'un warm işini gönder, dağıtık iş
`COMPLETED` olana kadar bekle ve coverage zorunlu hızlı erişim endpoint'ini en
son çağır.

## Bilinçli Olarak Yapılmayanlar

| Yapılmayan değişiklik | Gerekçe |
| --- | --- |
| Hızlı erişim route'undan otomatik SQL fallback | Gecikmeyi, yükü ve eksik coverage durumunu gizler |
| Runtime repository proxy'si veya metot adından sorgu üretme | Compile-time kontrol ve öngörülebilir allocation hedefini bozar |
| Görünmeyen lazy relation | N+1 ve sınırsız aggregate yüklemeyi geri getirir |
| Eksik partial update için otomatik SQL merge | Alan kaybına yol açabilir; komut sahipliğini belirsizleştirir |
| Sınırsız warm veya tam tablo okuma | Bellek, pool, backpressure ve Kubernetes limitlerini bozar |
| Yalnızca annotation ile idempotency iddiası | Sabit anahtar ve kalıcı state olmadan deduplication garanti edilemez |

## Production Sonucu

Uygulama kodu azaldı; altyapı davranışı ise gizlenmedi, daha açık hâle geldi.
Önerilen yaşam döngüsü değişmedi: sınırlı route tanımla, warm çalıştır, coverage
ve parity'yi kanıtla, endpoint'te `completeItems()` kullan, metrikleri izle ve
arşiv erişimini açık source route olarak tut.

Bu değişiklikler CacheDB'yi genel amaçlı bir ORM gibi göstermeden ve Redis'in
kalıcı veritabanının tamamını içerdiğini varsaymadan framework deneyimini
iyileştirir.

## Son Doğrulama Kanıtı

| Kapı | Sonuç |
| --- | --- |
| Temiz reactor | 20 proje ve 283 test; başarısızlık veya hata yok, özel topoloji gerektiren 3 test bilinçli olarak atlandı |
| Çekirdek bütünleşme | Canlı Redis 8 ve PostgreSQL üzerinde 90 test; MSSQL outbox çok pod sözleşmesi de kapsamda |
| Canlı ortam hızlı doğrulaması | Çökme sonrası tekrar oynatma, hata enjeksiyonu, koordinasyon, sertifikasyon, uzun süreli çalışma, geri kazanım ve performans biçimlerini kapsayan 27 test |
| Bağımsız PostgreSQL örneği | CacheDB doctor geçti; 8 birim testi ile canlı PostgreSQL 16 + Redis 8 bütünleşme testi başarılı |
| Bağımsız MSSQL örneği | CacheDB doctor geçti; 8 birim testi ile canlı SQL Server 2022 + Redis 8 bütünleşme testi başarılı |
| Performans | Repository, relation biçimi ve ranked projection benchmark eşikleri geçti |
| Paketleme | 16 public modülün binary, source ve javadoc jar'ları ile BOM doğrulandı |
| Postman | Provider başına 59 istek, 15 zorunlu warm route'u, warm-before-hot sırası, iş tamamlanma kontrolü ve provider parity geçti |
| Herkese açık API | Git'e kaydedilmiş `0.6.0` temel çizgisiyle karşılaştırıldığında 507 imza satırı eklendi; yayımlanmış metot veya kurucu kaldırılmadı |
| Statik mimari | 120 örnek ve 783 framework Java dosyası katman, reflection ve allocation denetimlerinden geçti |

İlk temiz reactor koşusu, ayarlı test portlarında Redis ve PostgreSQL
çalışmadığı için açık biçimde hata verdi. Ayrı Redis 8, PostgreSQL 16 ve SQL
Server 2022 test container'ları açıldıktan sonra aynı temiz komut başarılı oldu.
Veritabanı sağlayıcısı gerektiren testler atlanarak ürün hatası gizlenmedi.
