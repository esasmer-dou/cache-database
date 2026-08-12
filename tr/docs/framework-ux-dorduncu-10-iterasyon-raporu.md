# Framework Kullanım Deneyimi: Dördüncü On İterasyonluk Mühendislik Raporu

English version: [../../docs/framework-ux-fourth-10-iteration-report.md](../../docs/framework-ux-fourth-10-iteration-report.md)

Bu rapor; CacheDB çekirdeği, derleme sırasında kod üretimi, Spring Boot
entegrasyonu, test araçları ve PostgreSQL ile SQL Server örnekleri üzerinde
yürütülen dördüncü tam inceleme, geliştirme ve doğrulama döngüsünü açıklar.
Yayımlanmış başlangıç sürümü `0.7.1`'dir. Bu mühendislik kaydındaki
geliştirmeler `0.8.0` sürümüne alınmıştır; dağıtım kapsamının resmi özeti
`v0.8.0` sürüm notudur.

## Korunan Ürün Sınırları

- Hızlı erişim route'ları yalnızca Redis'ten okur; coverage uygun değilse açık hata verir.
- Kaynak route'ları açık, sınırlı, indeksli ve zaman aşımıyla korunan SQL okumalarıdır.
- Yazma önce Redis tarafından kabul edilir; SQL kalıcılığı asenkron ve izlenebilirdir.
- Repository implementasyonları runtime reflection kullanmadan derleme sırasında üretilir.
- Büyüyen listeler offset ve tam aggregate yerine projection ile keyset pencere kullanır.
- Ön yükleme ve arka plan işleri sınırlı, devam ettirilebilir, idempotency kurallarına uygun ve çok pod'lu çalışabilir.
- PostgreSQL ile SQL Server aynı uygulama mimarisini, kendi provider davranışlarıyla kullanır.

## İterasyon Özeti

| İterasyon | Sonuç |
| --- | --- |
| 1 | Yeni cursor'lar route, kapsam ve sıralama sözleşmesine bağlandı. |
| 2 | `CursorPage<T>` ile keyset devam bilgisi uygulama ve HTTP katmanında korundu. |
| 3 | Metot önceliğini koruyan compile-time repository varsayılanları eklendi. |
| 4 | Ham byte değerleri yerine adlandırılmış derleme zamanı bellek sabitleri eklendi. |
| 5 | Sample'lardaki manuel limit yardımcıları Bean Validation ile değiştirildi. |
| 6 | Dağıtık iş handler'ları tek tanım kullanan tipli sözleşmeye taşındı. |
| 7 | Sınırlı ve yapısal checkpoint/ilerleme modeli eklendi. |
| 8 | Kalıcı toplu aktarım ve receipt backpressure framework'e taşındı. |
| 9 | Deneme, uygulama ve coverage adımlarını birleştiren test yolculuğu eklendi. |
| 10 | Regresyon kapıları, örnekler, iki dilli belgeler ve gerçek provider kanıtı güncellendi. |

## 1. İterasyon: Cursor Doğru Sözleşmeyle Kullanılıyor

### Bulgu

Cursor kararlı sıralama değerlerini taşıyor, fakat hangi route ve parent kapsamı
için üretildiğini belirtmiyordu. Müşteri 42 için geçerli token, müşteri 43
isteğine verildiğinde yanlış sorgu sözleşmesiyle yorumlanabiliyordu.

### Uygulama

Yeni `WindowCursor` token'ları; sürüm ile generated route adı, normalize edilmiş
kapsam ve sıralı alan/yön listesinin SHA-256 özetini taşıyor.
`KeysetPagination`, sözleşmeyi generated repository kodundan alıyor.
`CursorContractMismatchException` yanlış kullanımı açıkça bildiriyor. Eski
cursor'lar istemcileri aniden bozmamak için okunmaya devam ediyor.

### Production Etkisi

Cursor'ın yanlış route veya kapsamda kazara kullanılması sorgu çalışmadan
reddedilir. Bu özet doğruluk sözleşmesidir; HMAC veya yetkilendirme sınırı
değildir. API yine istenen tenant veya müşteri kapsamının yetkisini doğrulamalıdır.

### Yapılmayan

Derin sayfalarda maliyeti büyüdüğü ve eş zamanlı yazılarda sonuç kaydırdığı için
offset pagination eklenmedi. Sunucu tarafında oturumlu cursor da pod bağımlılığı,
temizlik ve ek durum yönetimi gerektireceği için seçilmedi.

## 2. İterasyon: HTTP Katmanı Devam Bilgisini Kaybetmiyor

### Bulgu

Generated repository `nextCursor` üretiyor, fakat sample uygulama servisleri
sonucu yalın listeye çeviriyordu. Örneği kullanan kişi ilk keyset sayfasından
sonrasına geçemiyordu.

### Uygulama

`CursorPage<T>`, değişmez `items` ve isteğe bağlı `nextCursor` alanlarını taşır.
`HotWindow.completePage()`, dönüşümden önce Redis coverage'ın güncel ve tam
olmasını zorunlu kılar. `SourceWindow.page()`, açık SQL kaynağı anlamını korur.
`WindowRequest.of`, isteğe bağlı HTTP `after` değerini dallanma olmadan işler.
Sayfalanan sample endpoint'leri artık `after` alır ve bu modeli döndürür.

### Production Etkisi

HTTP katmanı güvenli devam token'ını kaybetmez. Hızlı erişim route'u eksik
coverage ile başarılı yanıt vermez; SQL route'u da satırların Redis'e alındığı
izlenimini oluşturmaz.

### Yapılmayan

Bütün endpoint'lere zorunlu generic HTTP zarfı eklenmedi. Bu, web kullanmayan
istemcilere HTTP kararı dayatır ve repository sözleşmesiyle ilgisiz alanlar eklerdi.

## 3. İterasyon: Repository Varsayılanı Compile-Time Kuraldır

### Bulgu

Her hızlı erişim metodu aynı doldurma stratejisini, SQL route'ları da benzer
satır ve zaman aşımı değerlerini tekrarlıyordu. Bu tekrar, özel route'ları
görmeyi zorlaştırıyor ve değerlerin zamanla ayrışmasına yol açabiliyordu.

### Uygulama

`@CacheRepositoryDefaults`; hızlı erişim doldurma/sayfa/pencere/bellek/
güncellik/strictness, SQL satır/zaman aşımı ve warm satır varsayılanlarını
tanımlar. Processor, bir annotation alanının yazılmadığını veya metotta açıkça
verildiğini ayırır. Metot değeri her zaman önceliklidir. Geçersiz varsayılanlar
derleme hatasıdır.

### Production Etkisi

Sample'lar `DECLARED_WARM` kararını repository başına bir kez verir. Projection,
kapsam, aktif pencere ve bellek gibi route'a özel kararlar görünür kalır.
Çözülen değerler generated metadata'ya yazılır; runtime ayar araması yapılmaz.

### Yapılmayan

Global ve değiştirilebilir varsayılan eklenmedi. Aynı artifact'in derlendiği
sözleşmeden farklı bir sözleşmeyle çalışması kabul edilmedi.

## 4. İterasyon: Bellek Bütçesi Okunabilir

### Bulgu

`16_777_216L` doğru olsa da kod incelemesinde anlaması zor ve yanlış yazılması
kolay bir annotation değeriydi.

### Uygulama

`CacheMemoryBudget.MIB_1` ile `MIB_256` arasında primitive derleme zamanı
sabitleri eklendi. İki sample `MIB_8`, `MIB_16` ve `MIB_32` gibi adları kullanıyor.

### Production Etkisi

Route bütçeleri zihinden byte dönüşümü yapılmadan karşılaştırılabilir. Runtime
allocation, metin çözümleme veya gizli yuvarlama yoktur.

### Yapılmayan

`"16MiB"` gibi metin değerleri seçilmedi. Bu yaklaşım normal Java sabit
kontrolünü zayıflatır ve çözümleme hatalarını ek koda taşırdı.

## 5. İterasyon: HTTP Sınırları Deklaratif

### Bulgu

Sample controller'ları `ApiLimits.requireInRange` çağrılarını tekrarlıyordu.
Yardımcı sınıf kodu uzatıyor, endpoint sınırını metot imzasında göstermiyordu.

### Uygulama

Controller'lar `@Validated`, `@Min`, `@Max`, `@Positive`, `@Size` ve mevcut body
doğrulamasını kullanıyor. Cursor girdisi 8 KiB ile sınırlandı. `ApiLimits`
kaldırıldı; gerçek provider testleri büyük isteğin HTTP hatasına dönüştüğünü
doğrulamaya devam ediyor.

### Production Etkisi

Endpoint sözleşmesi parametrenin yanında okunur. Spring ihlali mevcut hata
yüzeyine taşır. Çekirdek API de sınırı tekrar doğruladığı için HTTP kontrolü
tek savunma katmanı değildir.

### Yapılmayan

Limit değeri sessizce üst sınıra çekilmedi. İstemcinin istediğinden farklı
sayfa aldığını bilmemesi pagination hatası üretirdi.

## 6. İterasyon: Dağıtık İşin Tek Tanımı Var

### Bulgu

`CacheDistributedJobDefinition<A>` üretici sözleşmesi olduktan sonra bile
class tabanlı handler route ve argument tipi metotlarını tekrar yazıyordu.

### Uygulama

`CacheDistributedJobHandler.Typed<A>`, `route()` ve `argumentType()` değerlerini
zorunlu `definition()` metodundan çıkarır. Mevcut handler'lar kaynak uyumluluğunu
korur. Sample seed ve warm işleri tipli sözleşmeyi, üreticiler de aynı tanımı kullanır.

### Production Etkisi

Üretici, handler kaydı, deserialize adımı ve bütün pod'lar aynı route/payload
kaynağına bağlıdır. Rolling deployment yine uyumlu handler kümesi ister; ancak
yerel metin ve tip ayrışması ortadan kalkar.

### Yapılmayan

Classpath taraması ve runtime proxy ile handler üretimi eklenmedi. Bu yaklaşım
başlangıçta gizli davranış oluşturur ve reflection kullanmayan modele aykırıdır.

## 7. İterasyon: Checkpoint Yapısal ve Sınırlı

### Bulgu

Checkpoint API serbest map kabul ediyordu. Sample'lar farklı alan adları veya
Redis'te gereksiz büyüyen operasyon payload'ları üretebilirdi.

### Uygulama

`CacheDistributedJobProgress`; aşamayı, pozitif attempt değerini, isteğe bağlı
0-100 yüzdeyi, 512 karakterlik mesajı ve en fazla 16 sınırlı attribute'u
doğrular. `CacheDistributedJobContext` tipli overload sağlar ve sample handler'lar
bunu kullanır. Geriye uyumluluk ve domain resume bilgisi için nesne overload'u korunur.

### Production Etkisi

Normal iş ilerlemesi kararlı anlam ve sınırlı serialize boyutu taşır. Domain'e
özel devam bilgisi daha zengin olabilir; ancak pod ve sürümler arasında açıkça
yönetilmelidir.

### Yapılmayan

Mevcut devam ettirilebilir işleri gereksiz yere kırmamak için serbest nesne API'si
hemen kaldırılmadı.

## 8. İterasyon: Kalıcı Batch Backpressure Framework İçinde

### Bulgu

`SampleSeedService`; buffer, receipt biriktirme, backpressure eşiği ve kalıcılık
bekleme kodunu kendi içinde taşıyordu. Kullanıcı desteklenen framework API'si
yerine sample'a özel yardımcı kodu kopyalayabilirdi.

### Uygulama

`CacheDurableBatchWriter<T, ID>`; repository `saveAll` çağrılarını batch'ler,
her satır için bir receipt ister, bekleyen receipt sayısını sınırlar, SQL
kalıcılığını bekler ve `CacheDurableBatchResult` döndürür.
`CacheDatabase.durableBatchWriter(...)`; işlem adı, batch boyutu, bekleyen
receipt sınırı ve timeout ile writer oluşturur. Sample iç sınıfı kaldırıldı.

### Production Etkisi

Büyük aktarımlar sınırlı bellek ve write-behind backpressure ile aynı davranışı
kullanır. `finish()` kalıcılık sınırıdır. Timeout, SQL sonucunun bilinmediğini
gösterir; tekrarın güvenli olduğunu kanıtlamaz.

### Yapılmayan

Sınırsız receipt biriktirme ve her satır için ayrı SQL kalıcılık sorgusu; bellek,
Redis round-trip ve veritabanı throughput maliyeti nedeniyle reddedildi.

## 9. İterasyon: Warm Testi Tek Yolculuk Kanıtı Üretiyor

### Bulgu

Testler deneme, uygulama ve coverage adımlarını ayrı çalıştırabiliyordu. Bir
adımı atlamak veya farklı plan/kapsam kullanmak kolaydı.

### Uygulama

`CacheDbTestProbe.dryRunApplyAndRequireCoverage`; aynı planı `DRY_RUN` ve `APPLY`
modunda çalıştırır, denemede sıfır Redis gönderimi ister, güncel ve tam coverage
doğrular, `CacheDbWarmRouteEvidence` döndürür. İki provider integration testi
generated projection warm planını bu metotla çalıştırır.

### Production Etkisi

Ekipler plan güvenliğini ve route hazırlığını tek tekrarlanabilir test
sözleşmesiyle kanıtlar. Bu kanıt veri eşitliği, gecikme, bellek uyumu, failover
ve uzun süreli kararlılık iddiasında bulunmaz.

### Yapılmayan

Coverage tek başına cutover hazır kabul edilmedi. Coverage, baseline eşitliği
veya SLO uygunluğu hakkında bilgi vermez.

## 10. İterasyon: Regresyon Kapısı ve Belge API'yi İzliyor

### Bulgu

CI her hızlı erişim metodunun `population=DECLARED_WARM` değerini tekrar
yazdığını varsayıyordu. Sample belgeleri ham byte, yalnızca liste döndüren
cursor ve yerel batch kalıcılık kodunu gösteriyordu.

### Uygulama

Framework ilkeleri kontrolü repository varsayılanını anlıyor; cursor bağlama,
adlandırılmış bellek sabitleri, tipli handler, yapısal progress, deklaratif
girdi doğrulama ve batch backpressure kurallarını denetliyor. İngilizce ve
Türkçe çekirdek/sample belgeleri yeni API'ler için kopyalanabilir örnekler içeriyor.

### Production Etkisi

Gelecekteki sadeleştirme bu güvenlik sözleşmelerini fark edilmeden kaldıramaz.
PostgreSQL ile SQL Server örnekleri aynı uygulama modelini anlatır ve gerçek
provider container'ları üzerinde doğrulanır.

### Yapılmayan

Çalıştırılabilir CI kontrolü olmadan yalnızca belgeye kural yazmak yeterli
görülmedi. Kod, generated çıktı, sample ve test birlikte güncel kalmalıdır.

## Doğrulama Kanıtı

- Çekirdek, processor, starter, Spring ve testkit modüllerinin birlikte testi: başarılı.
- Mevcut kaynaklarla tam reactor kurulumu: başarılı.
- PostgreSQL sample unit testleri ile gerçek PostgreSQL + Redis provider hattı: başarılı.
- SQL Server sample unit testleri ile gerçek SQL Server + Redis provider hattı: başarılı.
- Tam reactor, public API, framework ilkeleri ve doküman kapılarının son sonucu bu çalışma ağacının tamamlanma raporunda ayrıca kaydedilir.

## Son Değerlendirme

Bu döngü, açık veri kaynağı davranışını zayıflatmadan framework kullanımını
sadeleştirdi. Kazanç yalnızca daha az kod değildir. Tekrar eden kural, ancak
compile-time çözüm deterministik kaldığında merkezileştirildi; route kapsamı,
projection, sınır, kalıcılık ve coverage kararları açık tutuldu.

Bilinçli olarak eklenmeyen fikirler: görünmeyen SQL fallback, başlangıçta tüm
veritabanını otomatik hazırlama, offset pagination, runtime repository proxy,
sınırsız checkpoint, sınırsız batch receipt ve route adı taşıyan yüksek
cardinality metric'lerdir.
