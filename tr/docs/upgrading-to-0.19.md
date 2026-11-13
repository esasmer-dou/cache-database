# CacheDB 0.19.0 Geçişi - UNRELEASED

[English](../../docs/upgrading-to-0.19.md) | [Taslak sürüm notları](releases/v0.19.0.md)

**Yayımlanmamış UNRELEASED geçiş taslağıdır. Sınırlı F3 aday kabulü tamamdır; nihai paket eşleştirmesi,
genel kabul ve özel depodan kanıt aktarımı BEKLEMEDEDİR. Üretime geçiş onayı veya 0.19.0 paketlerinin
yayım kanıtı değildir.**
Bir özelliği yalnızca sınıfları derleniyor diye etkinleştirmeyin. Önce nihai ve birbiriyle uyumlu
paketler, sağlayıcı/topoloji kanıtı ve özelliğe özgü geçiş sözleşmesi onaylanmalıdır.

10 Kasım 2026 tarihli `long-acceptance-20261110e` adayı F3 188/188 ve bildirilen dört özelliklik örnek
kapsamını geçti: PostgreSQL/MSSQL/Oracle üzerinde dokuz hücre, 141 Java testi ve 381 HTTP doğrulaması
(hata/skip sıfır). [Aday özetlerini ve kabul sınırını](releases/v0.19.0.md#sınırlı-aday-kabulü) inceleyin.
Bu sonuç; ana HTTP senaryoları, genel özellik kabulü veya uygulamanın mevcut veriyi korumaya alma onayı değildir.
Yalnız ham COUNT/FACET için zorunlu iki kat işlem hacmi hedefi (`rawT4target`), 9 Kasım 2026 tarihli
açık kullanıcı kararıyla kaldırıldı: PASS / KABUL İSTİSNASI, sayısal başarı değil. Geçmiş başarısızlıklar;
doğruluk, yalıtım, sınırlar, nihai paket regresyonları ve diğer performans koşulları korunur.

Native-thread fixture hatası, sahipsiz kalan BusyBox timeout readiness watchdog'ları yerine fork-free
TCP readiness kullanılarak giderildi; NPROC 256/256 yükseltilmedi. Kimlik doğrulamalı Service PING
zorunludur; yalnız TCP readiness Redis kabulü değildir. E'nin `target` dışına rapor yazma hatası korunur;
bağımsız doğrulama, değişmez kanıtları testleri tekrarlamadan kabul etti.

## 1. Java Araç Zincirini Yükseltin

0.19.0 için en düşük sürüm Java 21'dir; kütüphane bytecode'u ve üretilen uygulama binding'leri de
buna dâhildir. Kaynak koddan derlemek için Maven 3.9+ gerekir. Derleyiciyi, araç zincirini, IDE'yi,
CI ortamını, konteyner taban imajını ve çalışan JVM'i birlikte güncelleyin. Yalnız derleme görevindeki
JVM'i değil, gerçek konteynerin içindekini de doğrulayın. Java 17'de kalan uygulamalar 0.18.x üzerinde
kalmalıdır. Eski JAR'ları yeniden etiketlemeyin; eski sürümlerin gereksinimlerini Java 21 gibi göstermeyin.

```xml
<properties>
    <maven.compiler.release>21</maven.compiler.release>
</properties>
```

Uygulamanın olağan temiz derleme sürecinde eski üretilmiş çıktıları kaldırıp annotation processing'i
yeniden çalıştırın. İşlemciyi açıkça yapılandırın; çalışma zamanı reflection'ına veya derleyicinin
örtük işlemci keşif davranışına güvenmeyin. `ojdbc17`, Oracle sürücüsünün paket adıdır; CacheDB çalışma
zamanını Java 17'de tutma talimatı değildir. Önizleme bayrakları gerekmez.

CacheDB'nin asenkron, yenileme ve geçersizleştirme yürütücülerinin kendilerine ait sınırlı yaşam döngüsü
vardır. `spring.threads.virtual.enabled`, Spring davranışını ayarlar; CacheDB kabul sınırlarını kaldırmaz
ve bu sanal thread'leri kapatmaz. Sanal thread, sınırsız bağlantı veya CPU bütçesi değildir.
JVM üreticisine özgü ayarları ayrı değerlendirin: HotSpot ZGC seçenekleri OpenJ9'a taşınamaz;
bu taslak herhangi bir çöp toplayıcının performans üstünlüğünü kanıtlamaz.

## 2. Paketleri Ve Çalışma Zamanı Seçeneklerini Eşitleyin

Yayım onayından sonra BOM, annotation'lar, processor, seçili sağlayıcı starter'ı, worker'lar ve isteğe
bağlı modüller aynı onaylı sürümde olmalıdır. Yalnız seçili SQL sağlayıcısını ve JDBC sürücüsünü kullanın;
başka sağlayıcıya sessiz geçiş yoktur. İsteğe bağlı `cachedb-reactor` ve `cachedb-spring-data`, core'un
zorunlu bağımlılığı olmaz. Örneklerinin bulunması, paketlerin bugün yayımlanmış olduğu anlamına gelmez.

Mevcut 0.18.1 hızlı başlangıç koordinatları ve indirme başvuruları o sürümün örnekleri olarak korunur;
0.19.0'ın erişilebilir olduğunu kanıtlamaz. Bağımlılık sürümünü değiştirmeden önce gerçek yayım
bildirimini ve sağlama toplamlarını doğrulayın. Henüz oluşmamış indirme adresi uydurmayın veya eski
adayın JAR'ını nihai paket yerine kullanmayın.

Yeni seçenekler değişmez `CacheRuntimeOptions` üzerinden; `builder()` / `toBuilder()`,
`CacheDatabase(..., baseConfig, options)` veya Spring çalışma zamanı seçenekleri entegrasyonuyla verilir.
`CacheDatabaseConfig` mevcut record biçimini korur. Bileşen eklemeyin, statik yan yapılandırma haritaları
oluşturmayın ve mevcut izleme bağlamı taşıyıcısını değiştirmeyin.

## 3. Etkinleştirmeden Önce Varsayılanları İnceleyin

| Özellik | Varsayılan / etkinleştirme | Yapılması gereken inceleme |
| --- | --- | --- |
| Tekil okuma birleştirme | `cachedb.redis.read-coalescing.enabled=false` | Açık/kapalı durumları ölçün. Birleştirme kapalıyken de ham asenkron FIFO sınırlıdır. Çalışma zamanı başına yanıt ve kuyruk belleğini hesaplayın. |
| L1 geçersizleştirme | L1 isteğe bağlı; AUTO ve eski bildirim yayını true | Ayrı bağlantılar, ACL, bütün primary'lerin hazır olması, topoloji toparlanması ve her tüketicinin yayın bağımlılığı. |
| Asenkron / Reactor | API açıkça kullanılır; çalışma zamanına ait yürütücü, varsayılan eşzamanlılık 1024 | Havuz ve pod sınırlarına göre boyutlandırın; çağıranın kapsamını koruyun, aynı thread'de çalıştıran ödünç yürütücüyü reddedin ve sahibi olduğunuz kaynakları kapatın. Reactor işi abonelikte başlatır. |
| Gözlem | Core etkisiz; Spring uyarlayıcısı registry/ayarlara bağlı | Sınırlı işleyici/dışa aktarıcı ve izinli, düşük çeşitlilikte etiketler kullanın. `cachedb.observation.enabled=false` otomatik yapılandırmayı kapatır. |
| Sıralı bileşik indeksler | Açık `sortBy` tanımı ve üretilmiş sözleşmeler | Bütün yazıcıları güncelleyin, indeksi yeniden kurun, kapsamı/skor alanını doğrulayın; kesin ve sınırlı geri dönüş ile tipli koşulları koruyun. |
| Spring Data | İsteğe bağlı modül ve üretilen repository | Etkin sorgu kapasitesini bildirin/devredin, Pageable/Limit kullanın, toplu sahiplik ve kısmi hata davranışını doğrulayın. |
| Spring Cache | `cachedb.spring-cache.enabled=true`, `@EnableCaching`, tanımlı önbellekler | Sonlu TTL/değer/L1/yükleme bütçeleri, kiracı kapsamı ve açık değer codec'leri; annotation sonuçları yalnız eşzamanlı olabilir. |
| Şifreleme / saklama süresi / kiracı temizleme | Açık annotation, çalışma zamanı ve şema kaydı | Önce anahtarlar ve uyumlu veri; sonra kaynak kapsamı, tüm katılımcılar ve güvenli devreye alma. Onaylı geçiş olmadan eski veride korumayı açmayın. |

Kesin sınırlar için [tekil okuma](redis-read-coalescing.md), [yerel asenkron](redis-native-async.md),
[takip yapılandırması](yakin-onbellek-izleme-yapilandirmasi.md), [gözlem](cache-gozlemlenebilirlik.md),
[Spring Data](spring-data-migration.md) ve [Spring Cache](spring-cache.md) rehberlerini inceleyin.

## 4. Kitaplıkları, Anahtarları Ve İndeks Kanıtlarını Koruyun

0.19.0 için yan yana sürümlenen Redis Function adlarını birlikte kullanın: `cachedb_v019`,
`entity_upsert_v019`, `entity_delete_v019`, `compaction_complete_v019`. Toplu silme adı, silme
işlevinin adından türetilir. Eski veya geri dönüş çağıranları varsa eski kitaplıkları, adları ve
işaretçileri koruyun. Açık özel adlar, kendilerine ait farklı kitaplık ve giriş noktaları ister;
yalnız sürüm metnini değiştirmek ayırma sağlamaz. Başlangıcı zorlamak için FUNCTION FLUSH kullanmayın,
içerik uyuşmazlığı denetimini kapatmayın, sürümleri geri almayın veya eski kitaplık gövdesini değiştirmeyin.
[Function geçişini](redis-functions-upgrade.md) uygulayın.

Bileşik metadata'yı yeniden üretin, bütün yazıcıları ve veri doldurma yollarını güncelleyin, ardından
kilitle korunan yeniden kurmayı çalıştırın. İndeks imzası, eski yazıcının yeni Long alan sözleşmesine
uyduğunu kanıtlamaz. Doğrulama bayrağını elle vermeyin; yuvarlanmış Redis double değerinden kesin Long
sıralaması çıkarmayın. Eksik veya güvensiz kanıt, tüm adayların kesin ve sınırlı değerlendirilmesini
ya da açık hatayı gerektirir. Atomik batch ve yarım şema geçişi, kısmi hızlı yol sonucunu haklı çıkarmaz.
[Bileşik aralık geçişini](bilesik-aralik-indeksleri.md) inceleyin.

## 5. Sorgu Kapasitesini Sözleşme Olarak Ele Alın

`EntityRepository.entityQueryCapacity()`, UNKNOWN, UNRESTRICTED ve FINITE(maxRows) durumlarını ayırır;
FINITE(0) buna dâhildir. Yayımlanmış etkin satır bütçesini açıklar; yetki, atomik anlık görüntü, indeks
eksiksizliği veya SQL geçmişi değildir. Gerçek sorgu asıl denetimleri yeniden yapar. Politika,
kapasite okunduktan sonra değişirse sorgu reddedilebilir; limiti büyüterek veya korumayı kapatarak yinelemeyin.

Özel repository ve sarmalayıcılar sözleşmeyi devretmeli veya UNKNOWN bırakmalıdır. Açıkça sınırlanmış
eski istekler backend denetimlerini kullanır; yeni sayfalanmamış/tam küme silme seçimleri UNKNOWN
durumunda reddedilir. InMemory kapasitesi Redis performans veya kabul benzetimi değildir.
Etkin sıfır, sınırsız değildir ve gruplara bölünerek çözülemez: sıcak sınır 8, ayrılan pay 10 ise hiçbir
pozitif varlık sorgusu geçerli değildir. Toplu silmede önceden güvenilir biçimde bilinen kimlikleri veya
ayrıca onaylanmış yönetim işini kullanın. Sorgu sonucu, kalıcı sahipliği SQL üzerinden başlatma yetkisi vermez.

Türetilmiş silme en fazla min(kapasite, 10.001) satır seçer; seçilen bütün kimlik ve kiracıları ilk
değişiklikten önce doğrular. Seçim tam dolarsa gerçek sayı sınıra eşit olsa bile tüm eşleşenlerin
bulunduğu kanıtlanamaz; ilk silmeden önce hata verilir. Sayfalanmamış List/Page en fazla
min(kapasite, 100.000) satır seçer ve sınır dolunca Pageable/Limit ister; sessiz kırpma yoktur.
Açık sonlu isteğin ön denetimine Slice/Window ek kontrol satırı da dâhildir. Yerel toplu değişiklik,
en çok 128 kimliklik gruplar ve gerçek satır makbuzları kullanır; tüm çağrı için atomik transaction
değildir. Kabul, ret, belirsiz sonuç ve bekleyen temizlik bilgilerini koruyun; belirsiz çağrıyı tümüyle
körlemesine yinelemeyin.

Daralan indekste sayfalayarak silmek, geniş SQL sahiplik taraması veya N+1 sahiplik başlatması geçiş
çözümü değildir. Seçim, eşzamanlı yazmaları dondurmaz. Transaction dışı davranışı gerçek sağlayıcıda doğrulayın.

## 6. Korunan Erişimi Doğrulamadan Açmayın

Mevcut üretim verisini kiracı nesli korumasına alma süreci bu taslakta hâlâ beklemededir. Annotation,
yapılandırılmış producer veya başarılı bileşen testi, doğrulanmış nesli açmaz. Testlerdeki sahiplik
işareti ya da nesil/sürüm aralığı hazırlama adımlarını üretime kopyalamayın.

Erişimi açmadan önce hangi geçiş sürecini uyguladığınızı belirleyin:

| Süreç | Kapsam | Erişim açılmadan önce gerekenler |
| --- | --- | --- |
| İlk korumalı veri alanını oluşturma | Sınırlı INITIAL `POINT_NO_QUOTA` profili; ilişkileri, projection'ları veya tüm geçmişi kapsamaz. | İlk doldurmayı, yarım kalan sayfanın kurtarılmasını, sonucu belirsiz SQL aktivasyonunu ve temizlemeyi seçilen sağlayıcıda doğrulayın. Harici kaynak yazıcılarını ayrıca durdurun veya güvenilir biçimde engelleyin. |
| Mevcut kimliklerin sahiplik hazırlığı | Açıkça incelenmiş, kapalı bir iş listesi; tüm SQL geçmişini kendiliğinden tarayan bir süreç değildir. | Sahiplik envanterini tamamlayın. Hazırlık tek başına bir nesli etkinleştirmez veya erişimi açmaz. |
| Açık EMPTY yeniden açılışı | Kanonik `Long` kimlikler ve özel kimlik alanıyla aday kabulü tamamlanan standalone `POINT_NO_QUOTA` profili. | Açık aktivasyonu, çakışmayan yeni kimlik kabulünü ve ayrı OPEN sürüm yenilemeyi uygulayın; nesil-1 replay çitlerini koruyun. Aday kabulü, uygulamanın üretime geçiş onayı değildir. |
| Eski veri alanını taşıma ya da geri yükleme | Korunan sürümleri, silinme işaretlerini, nesilleri ve şifreleme anahtarlarını dikkate alan ayrı bir geçiş. | Uygulamaya özel geçiş ve geri dönüş yöntemini onaylayıp doğrulayın. INITIAL veya sınırlı EMPTY kabulü, rastgele eski veriyi yeniden açma ya da geri yükleme yetkisi vermez. |

Desteklenen türler, indeksler, sınırlar, kaynak yazıcılarının durdurulması ve yönetim adımları için
[ilk kaynak doldurma rehberini](initial-source-bootstrap.md) izleyin. Devreye almayı tamamlamak için
mevcut güvenlik korumasını kapatmayın; gereksinimler karşılanana kadar ilgili korumalı veri alanına
erişimi kapalı tutun.

Şifreleme için kayıttan önce onaylı `CacheKeyProvider` sağlayın; dönüşüm boyunca gerekli eski okuma
anahtarlarını tutun. Sınırlı veri geçişini ve yeniden indekslemeyi ayrı planlayın. Şifreli olarak
işaretlenen eski düz metin reddedilir; kendiliğinden taşınmaz. REDIS_ONLY SQL'de düz metin bırakır;
REDIS_AND_SOURCE uyumlu metin kolonları ve onaylı kaynak codec/loader'ları ister. Kör indeksler eşitlik
ilişkilerini açığa çıkarır. Aralık, sıralama, serbest SQL ve desteklenmeyen kaynak davranışı reddedilir.
Şifreli imleç/filtre sözleşmesini anahtarsız özetle aşmayın; imleci yetki kanıtı saymayın.

Koruma; seçili sağlayıcının `V019__tenant_protection.sql`, `V019_1__purge_facade.sql` ve
`V019_2__purge_list_scan_kind.sql` dosyalarının uygulamanın geçiş sistemiyle sırayla kurulmasını ister;
çalışma zamanı DDL çalıştırmaz. INITIAL için ayrıca sırasıyla `V019_3__source_owner_onboarding.sql`,
`V019_4__initial_source_bootstrap.sql` ve `V019_5__namespace_bootstrap_once.sql` gerekir.
Şema geçişleri tek başına sahiplik envanteri, özgün nesil damgası,
güvenli sürüm aralığı kurtarması veya dağıtık okuma/doldurma kapanışı sağlamaz. Koruma kapanışı gerçek
JDBC iş transaction'larını çevrelemelidir; bütün yazıcılar, yeniden oynatma kaynakları, okumalar ve
tanımlı depolar katılmalıdır. Eski payload'a güncel nesli takmayın, korunan en yüksek sürüm değerini sıfırlamayın;
yerel kapalı bayrağını veya Pub/Sub alındı bilgisini eksiksiz dağıtık kapanış saymayın.

Temizleme varlık alanı ve kiracıyla sınırlıdır. Eksik sahiplik/katılımcı veya doğrulanamayan karışık
eski girdi güvenli biçimde reddedilir. Yalnız Redis temizliği SQL DELETE çalıştırmamalı veya kuyruğa
almamalıdır; korunan kaynak geçmişini sessizce yeni nesle kabul edemez. Yeniden açma ayrı doğrulanmış
geçiş ister. Kaynak silme sınırlı ve açıktır; tamamlanma kabulü yine kapalı bırakır. Tutulan güvenlik
çitleri, yedekler, redo/WAL, dışa aktarımlar ve ilgisiz Spring Cache değerleri otomatik silinmez.
Saklama süresi denetimi, yerleşik indeks bakımıdır; soğuk geçmiş temizliği değildir. EVICT_CACHE SQL'i
korur; DELETE olağan write-behind yolunu ve onun kalıcılık sözleşmesini kullanır.

[Şifreleme](alan-sifreleme.md), [saklama süresi](retention.md),
[producer yaşam döngüsü](tenant-producer-lifecycle.md) ve [varlık kapsamlı koruma](tenant-veri-koruma.md)
rehberlerini izleyin. Bunlar hukuki uyumluluk sertifikası veya onaylı eski veri devreye alma yöntemi değildir.

## 7. Hata, Bellek Ve Geri Dönüşü Doğrulayın

- Adil ve sınırlı bağlantı kabulünü, socket/bağlantı/havuz zaman aşımını ve uygulama geri basıncını
  koruyun. Ödünç istemciler kendiliğinden yeniden ayarlanmaz; sanal thread kapasite yaratmaz.
- Tekil okumalarda canlı ham içerik ayırması çalışma zamanı başına varsayılan 256 MiB'dır; toplam heap
  sınırı değildir. Kuyruk, çözülmüş nesne, RESP tamponu, L1 ve uygulamanın tuttuğu future'lar ek yer
  kaplar. Pod'un tümünü ve çalışma zamanı sayısını hesaplayın; bağımsız içerik sınırlarını heap garantisi saymayın.
- İptal veya sonuç teslim zaman aşımı, fiziksel Redis/JDBC I/O'sunu zorla bitirmez; kabul edilmiş yazmayı
  geri almaz. İş sürerken kaynak kredisini erken bırakmayın. Callback'leri kısa tutun; çalışma zamanını
  havuzlarından önce kapatın. Ödünç yürütücünün yaşam döngüsü sahibinde kalır.
- Kiracı/yönetici, nedensellik, yapılandırılmış izleme ve yönlendirme kapsamını açıkça taşıyın.
  Etkin işlem birimini başka thread'e taşımayın. Asenkron işlem birimi ve desteklenmeyen nedensel tekil
  olmayan okumalar güvenli biçimde reddedilir.
- Tam nihai adayı ve korunan referansı; aynı adil havuz, JVM, CPU/bellek, veri ve topoloji koşullarında
  karşılaştırın. Bütün başarısızlıkları koruyun; başlangıç hatası sayısal ölçüm değildir. Yalnız ham T4 iki kat
  hedefi açık istisnayla kabul edilmiştir, sağlanmış değildir. İşlevsel test veya eski aday skoru, kalan
  performans ve nihai paket regresyon kapılarını kapatmaz.
- Bildirilen sürüm/sağlayıcı/Cluster/örnek kapsamını, zorunlu testlerin atlanmama denetimini ve desteklenen
  JFR pinning kanıtını isteyin; iddia edilen uygulama geri yükleme/replay davranışını ayrıca doğrulayın.
  Sınırlı F3 aday sonucu evrensel geri yükleme/HA kanıtı değildir. JFR olay kaynağının yokluğu sıfır pinning değildir.
- Yalnız doğrulanmış paket/anahtar/kitaplık/veri birleşimine geri dönün. Sürümleri, silinme işaretlerini,
  SQL yazma çitlerini, anahtarları ve nesil/korunan en yüksek sürüm metadata'sını koruyun. Yeni şifreleme/nesil
  protokolü etkinse eski Function kitaplığını tutmak, eski binary'lerin yeniden yazabileceğini kanıtlamaz.

[Yayım onayını](release-checklist.md), sağlama toplamlarını ve iki dildeki belgeleri uyumlu tutun.
Bütün zorunlu kapılar kapanana kadar UNRELEASED etiketini koruyun; üretime hazır olunduğunu duyurmayın.
