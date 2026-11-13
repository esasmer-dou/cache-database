# Spring Data Geçiş Rehberi

CacheDB 0.19 için cachedb-spring-data isteğe bağlı bir modüldür. Spring Data Commons sürümü Spring Boot 3.3.5
BOM'u ile yönetilir. Mevcut core/processor kullanıcılarına Spring Data bağımlılığı eklenmez.

## Geçiş

Önceki Spring Data arayüzü:

~~~java
interface Customers extends ListCrudRepository<Customer, Long>,
        ListPagingAndSortingRepository<Customer, Long> {
    Page<Customer> findByActive(Boolean active, Pageable pageable);
}
~~~

Yeni arayüz ve aynı annotation-processing derlemesindeki entity:

~~~java
@CacheEntity(table = "customer", redisNamespace = "customer")
public record Customer(@CacheId(column = "id") Long id,
        @CacheColumn("active") Boolean active,
        @CacheColumn("name") String name) {}

@CacheRepository(entity = Customer.class)
public interface Customers extends CacheDbSpringDataRepository<Customer, Long> {
    Page<Customer> findByActive(Boolean active, Pageable pageable);
    Window<Customer> findFirst100ByActiveOrderByIdAsc(Boolean active, KeysetScrollPosition position);
}
~~~

CacheDB BOM'u ile cachedb-spring-data eklenir; processor yolunda cachedb-processor tutulur.
CustomersCacheDbConfiguration, uygulamanın Spring yapılandırmasına import edilir.
Üretilen fabrika CacheSession alır; normal CacheDatabase bean'i bu sözleşmeyi uygular.
Entity binding mevcut runtime'a kaydedilir. Bu paketler JPA veya diğer Spring Data runtime repository taramalarına dahil edilmemelidir.

Somut CustomersCacheDbImplementation, sınırlı adaptörü genişletir ve arayüzü doğrudan uygular.
Query proxy, reflection ile alan erişimi, runtime metot adı ayrıştırıcısı, repository classpath taraması veya yeni executor yoktur.
Spring dışında CacheSession ile doğrudan kurulabilir. Aynı iş parçacığı ve arka uç üzerinde kiracı, yönlendirme ve hata denetimleri korunur.
Nedensel tekil okumalar ve findAll, yerel sürüm doğrulamasını korur. Sorgu, sayfa, count, türetilmiş silme ve aggregate
işlemleri yalnızca bağlam taşındığı için güncel sayılmaz. Uygulanmış sürümü doğrulayan açık bir protokol yoksa ilgili
etkin nedensel gereksinimler reddedilir. Yazma makbuzu, her türetilmiş sorgunun kendi yazdığını okuma garantisi taşıdığı anlamına gelmez.
Bu modül, JPA/SQL geçmişi için şeffaf bir alternatif değildir: sorgu/count/delete-all mevcut sınırlı CacheDB veri politikasına tabidir.
SQL kalıcılığı, kaynak okuma, snapshot veya transaction garantisi yalnızca metot adlarıyla güçlendirilmez.

## Desteklenen Sözleşme

- Okuma önekleri find/read/get/query, By ve isteğe bağlı Top/First + pozitif N'dir. AllBy ve filtresiz Top/First de desteklenir.
- Operatörler Equals, Is, IsEquals, Not/IsNot, In/IsIn, NotIn/IsNotIn, GreaterThan, GreaterThanEqual,
  LessThan, LessThanEqual, Between/IsBetween, IsNull, IsNotNull, True/IsTrue, False/IsFalse ve StartingWith'tir.
- And desteklenir. Or, Like, IgnoreCase, iç içe ilişki yolu veya tanımsız özel metot isteği derleme hatası üretir.
- OrderBy, birden fazla Java property adını Asc/Desc ile alır. Dinamik Sort, üretilen property/kolon eşlemesini kullanır.
  Bilinmeyen alan, çelişen yön, IgnoreCase ve açık null sıralaması isteği sessizce yok sayılmaz; hata üretir.
- Dönüşler entity, Optional<Entity>, List<Entity>, Page<Entity>, Slice<Entity>, Window<Entity>, long/Long ve boolean/Boolean'dır.
  deleteBy; long/Long, void veya List<Entity> döndürür. Stream, raw container, projection ve çözümlenmemiş generic tip reddedilir.
- Page/Slice için Pageable zorunludur; List de Pageable alabilir. Sınırlı List okumaları Sort ve Limit destekler.
  Window, ScrollPosition/KeysetScrollPosition gerektirir; Limit veya sabit Top/First boyutu alabilir.
- Pageable ile Sort/Limit ve Top/First ile Limit birlikte kullanılamaz.
  Tek sonuç sorgusu Sort ve Top/First1 destekler; Pageable, Limit veya TopN>1 desteklemez.
  Count/exists/delete metotları paging, sorting, scrolling veya limit parametresi almaz.
- First/Top olmayan tek sonuç sorgusu iki satır ister; birden fazla sonuç varsa hata üretir.
  Sonuç yoksa entity null, Optional boş olur.
- Somut repository üst seviyede tanımlanmalı; entity/ID generic tipleri çözümlenmiş olmalıdır.
  Generic parent/fragment ve yeniden bildirilen CRUD metotları derlemede çözümlenir. Generic derived metotlar ve throws bildirimi desteklenmez.
- En çok 256 kalıcı property, 256 derived metot, 32 predicate/operand, ID dışında 15 sabit sort,
  toplam 16 sort ve 1.024 karakterlik metot adı kabul edilir.
- Predicate/sort alanı desteklenen skaler tiplerden biri olmalıdır: String, Boolean, boxed sayılar, BigDecimal/BigInteger, UUID,
  desteklenen java.time değerleri veya enum. Karmaşık kalıcı alanlar saklanabilir; bunlar derived predicate alanı olamaz.
  Enum operandları, mevcut codec'in saklama biçimiyle uyumlu name() değerine derlenir.
  Özel codec kullanan veya şifreli alanlar bu üreteçte türetilmiş sorgulara açılmaz; ayrı onaylı tipli API kullanılmalıdır.
  IN girdisi doğru boxed tipte Iterable olmalıdır. Java array, raw veya uyumsuz koleksiyon derleme hatası üretir.

Core'da yerel NOT IN olmadığı için NotIn, AND/NE ağacına; Between ise GTE/LTE koşullarına dönüştürülür.
IN en çok 512 değer alır. NotIn, planlama ve sonuç oluşturma maliyetini sınırlamak için bilinçli olarak en çok 32 dışlama kabul eder.
Dışlama ağırlıklı sorgularda pozitif, indeksli bir bölümleme filtresi ve temkinli aday sınırı kullanılmalıdır.
Sınırsız veya 512 değerli NOT IN açılımı üretim önerisi değildir; core planlayıcının maliyeti gerçek iş yükünde ölçülmelidir.

StartingWith için açık kaynak sözleşmesi gerekir:

~~~java
@CacheRepository(entity = Customer.class)
@CacheDbSpringDataOptions(prefixIndexedColumns = {"name"})
public interface Customers extends CacheDbSpringDataRepository<Customer, Long> {
    List<Customer> findByNameStartingWith(String prefix);
}
~~~

Property indeksli String olmalı; runtime'da queryIndex.prefixIndexEnabled=true seçilmelidir.
Üretilen Spring fabrikası gerçek CacheDatabase ayarını denetler.
Elle kurulan veya özel CacheSession uygulamasında prefix indeksinin etkin olduğu açıkça bildirilmelidir.
Yalnızca CacheSession alan constructor, prefix gerektiren repository için hata verir.
Eski veri varsa indeksler etkinleştirilmeli ve yeniden kurulmalıdır.
Annotation ve etkinlik denetimi, Redis indekslerinin eksiksizliğini kanıtlamaz; bağımsız bir prefix-readiness SPI bulunmaz.

## Sınırlar Ve Davranış

CRUD, EntityRepository'ye; üretilen ID ise mevcut CacheSession ID generator'ına devredilir.
Yazmada yalnızca üretilen entity'nin tam runtime tipi kabul edilir; entity subclass/proxy nesneleri değişiklikten önce reddedilir.
saveAll bütün girdiyi denetler ve en çok 1.000 satırı backend'in bulk-save API'sine gönderir; N ayrı save çağrısı yapmaz.
findAllById en çok 10.000 ID kabul eder ve 512'lik batch'ler kullanır.
Null üyeler ve Iterable sınırları yazma başlamadan denetlenir.
Silme girdisi ile deleteBy/deleteAll sonucu en çok 10.000 kayıt olabilir. Seçim sorgusu en fazla
min(etkin varlık sorgusu kapasitesi, 10.001) satır ister. Sonuç bu sınırı tam doldurursa eşleşen kümenin
tamamının seçildiği kanıtlanamaz; gerçek sayı sınıra eşit olsa bile ilk silmeden önce hata verilir.
Seçilen bütün kimlikler ve kiracılar bulkDelete çağrısından önce doğrulanır; silinen kayıtlar nedeniyle
daralan bir indeks üzerinde sayfalayarak silme yapılmaz.

EntityRepository.entityQueryCapacity(), UNKNOWN, UNRESTRICTED veya FINITE(maxRows) döndürür;
FINITE(0) açıkça sıfır kapasitedir. Redis bu bilgiyi geçerli okuma sınırlarından ve etkin politikadan,
G/Ç yapmadan hesaplar. Yerel denetimler yine uygulanır. Bu bilgi yetkilendirme, sorgu anlık görüntüsü
veya kaynak verinin ya da indekslerin eksiksiz olduğuna ilişkin kanıt değildir.
Eski ve özel uygulamalarda varsayılan UNKNOWN değeridir: açık Pageable/Limit istekleri backend'in
mevcut denetimlerinden geçer; ancak kapasite açıkça bildirilene kadar sayfalanmamış List/Page ve
türetilmiş silme seçimleri güvenli biçimde reddedilir. Altyapısız InMemory uygulaması UNRESTRICTED
bildirir; bu davranış Redis kabul ve taşıma sınırlarını taklit etmez. Sarmalayıcılar sözleşmeyi devretmelidir.

Sayfalanmamış sorgu en fazla min(kapasite, 100.000) satır seçer; yalnızca sınırdan az sonuç varsa listeyi
döndürür. Sınır tam dolarsa Pageable veya Limit kullanılması istenir; sonuç sessizce kırpılmaz.
Açıkça sınırlandırılmış okumalar, Slice/Window için gereken ek kontrol satırı da dâhil olmak üzere,
bilinen sonlu kapasiteye karşı sorgu veya sayım G/Ç'sinden önce denetlenir. UNKNOWN, backend denetimlerini
atlatmaz. Sıfır kapasitede pozitif boyutlu hiçbir varlık sorgusu kabul edilmez; küçük gruplara bölmek
bunu çözmez. Güvenilir ve bilinen kimliklerle bulkDelete/deleteAllById veya ayrıca yetkilendirilmiş,
sınırlı bir yönetim işi kullanılmalıdır. UNRESTRICTED yalnızca satır politikası sınırının bulunmadığını
belirtir; QuerySpec, taşıma, bellek, kiracı, nedensellik ve şifreleme sınırlarını kaldırmaz.
Seçim ve değişiklik ayrı aşamalardır: eşzamanlı yazmalar dondurulmaz ve tüm çağrı bir transaction değildir.
Toplu silme, eklenen EntityRepository.bulkDelete yeteneğine devredilir; yerel yol en fazla 128 kimliklik gruplar kullanır.
Bu işlem CacheDatabase.writeBatch değildir: her satır ayrı kabul edilir; çağrının tamamı için geri alma garantisi yoktur.
Girdinin tamamı ve kayıt sınırı değişiklikten önce denetlenir. Kiracı sahiplik işaretleri ilk silmeden önce sınırlı gruplarda
doğrulanır; sürüm, silinme işareti ve yazma kuyruğu kabulüyle aynı atomik işlemde yeniden denetlenir.
Şifreli kiracılar ve soğuk kimlikler dâhil, ön planda SQL sahiplik başlatması yapılmaz. Eksik işaret reddedilir.
Sahipliği, uygulamanın güvenilir başlangıç/yazma protokolüyle hazırlayın; sorgudan dönen varlığı tek başına yetki kanıtı saymayın.
Yerel yol mevcut silme gövdesini, indeks kaldırma planlayıcısını, L1 geçersizleştirmesini ve izleyici sözleşmelerini kullanır.
Eşzamanlı projection temizliği, asıl kaydın tam makbuz sürümünü ve silinme işaretini kaldırma işlemiyle atomik olarak denetler.
Daha yeni asıl kayıt sürümü eski temizliği hükümsüz kılar; yeni projection silinmez.
Toplu silmede, projection'ın olağan yenileme kipi asenkron olsa da temizlik eşzamanlı ve en çok 128 kimliklik gruplarla tamamlanır.
Kimlik başına kuyruk olayı üretilmez. Gecikme bütçesine projection sayısını da dâhil edin.
Asenkron yenileme işleyicileri, okudukları asıl kayıt sürümünü, silinme işaretini ve sahipliğini payload ile indeksleri
aynı atomik işlemde yayımlamadan önce doğrular. Eski iş, daha yeni veriyi silemez veya silinen veriyi geri getiremez;
sürüm değişmişse sonuç yayımlanmaz, olay sonraki kurtarma geçişi için beklemede tutulur.
Eski DELETE olayı da güncel durumu uzlaştırır; sonradan yeniden oluşturulan kaydı silmez. Tekil silmenin kuyruğa gönderim yolu korunur.
Asenkron eşlemelerin ön plandaki ısıtma ve zorunlu yayımlama yolları, yalnızca kimlik içeren yenileme isteklerini en çok
128'lik gruplarla kuyruğa gönderir; eski bir projection doğrudan yayımlanmaz. İşleyici güncel asıl kaydı yeniden okur.
Bu davranışı kullanmadan önce bütün projection işleyicilerini yükseltin. Mevcut sorgu önbelleğinin tutarlılık ve nedensel
olmayan projection okuma sözleşmeleri değişmez.
Temizleme protokolüyle korunan eşlemeler bu eski toplu silme yolunu desteklemez; etkin kiracıdan koruma nesli türetilmez.
Temizleme koruması kapalı, tüm şifreli alanları REDIS_ONLY olan kayıtlı eşlemeler; bağlı codec, Q1 ile kanonik kimlik/bağlam denetimi,
kanonik tipli kimlik, ayrı kimlik/sürüm rolleri ve atomik kiracı sahipliği denetimleriyle desteklenir. Şifreli alan değeri alınmaz; DELETE kuyruğu kimlik/sürüm
bilgisiyle mevcut sözleşmeyi kullanır. Kaynağı da şifreleyen alanlar bu yetenek için desteklenmez.
Eksik sahiplik işareti veya bilinmeyen/sıfır Redis sürümü reddedilir; kaynak sorgusu ya da sahiplik başlatması yapılmaz.
İndeks ve sıcak küme bakım anahtarları aynı kayıtlı eşlemeye ait olmalıdır. İzleyici varsa şifreli önceki değer, değişiklikten
önce çözülerek kimlik ve kiracı denetlenir; önceki değer eksikse çağrı o grup için değişiklikten önce reddedilir.
Anahtar sağlayıcı bağlama sırasında doğrulanır. İzleyicisiz DELETE, mevcut şifreli alanları okumaz veya yeniden şifrelemez;
bu nedenle her silme için yeni AES/HMAC işlemi gerekmez. Tekil silme davranışı değişmez. Yerleşik atomik işlev kitaplığı, çalışma zamanı okuma durumu ve kiracılı eşlemelerde
açık kiracı kapsamı gerekir. Özel işlev kaynakları sessizce yorumlanmaz; ayrı bir toplu silme yeteneği sağlamalıdır.
Çalışma zamanı başına en fazla iki toplu silme etkindir. Anlık görüntü ve komut çerçevesi ayrı ayrı 16 MiB ile;
grup başına bakım komutları 8192 ile sınırlıdır. Mevcut sonlu okuma süresi ile istemci zaman aşımları geçerlidir.
Cluster atomik anahtarları yapılandırılmış ortak hash-tag kapsamını gerektirir; desteklenmeyen slot düzeni değişiklikten önce reddedilir.
BULK_DELETE sağlamayan eski/özel repository tek kimliği güvenle silebilir; çok kimlikli çağrı değişiklikten önce reddedilir.
BulkDeleteFailureException, Redis'in kabul ettiği makbuzları, kesin reddedilen ve sonucu belirsiz kimlikleri,
bakımı bekleyen kimlikleri ve denenmeyen kayıt sayısını bildirir. Ağ hatasında bilinmeyen bir alt küme kabul edilmiş olabilir.
Sonucu belirsiz kimlikleri körlemesine yeniden silmeyin; önce sürüm ve kalıcılık durumunu uzlaştırın.
Redis kabulü, SQL kalıcılığını veya sonraki bakımın başarıyla tamamlandığını kanıtlamaz.
Bulk save de backend'in kısmi hata davranışını devralır. Null ID ile save tekrarlandığında yeni kimlik üretilebilir.
Açık toparlanma için sabit/önceden ayrılmış ID'ler ile mevcut receipt/version API'leri kullanılmalıdır; adaptör yeni idempotency-key protokolü eklemez.

Page boyutu QuerySpec.MAX_LIMIT=100.000'i aşamaz. Offset 10.000'i geçerse I/O öncesinde hata ve Window önerisi verilir.
Page toplam sayısı count(QuerySpec) ile alınır ve sabit Top/First sınırına uyar.
Count ve veri okuması ayrı gözlemlerdir; snapshot değildir.
Slice, count çalıştırmadan bir ileri bakış satırı ister; açık boyut sınırı 99.999'dur.
Unpaged List, 100.000 satırlık güvenlik penceresi tam dolarsa hata verir; eksik olabilecek sonuç sessizce döndürülmez.
Açık Top/Limit, bilinçli sonuç sınırıdır. Backend'in daha düşük aday/kabul sınırları geçerliliğini korur.

Window, mevcut KeysetPagination ve WindowCursor'u kullanır; kararlılık için ID eşitlik bozucusu ekler.
Boyut 1..1.000 satırdır ve bir ileri bakış satırı alınır.
İleri/geri konum desteklenir; offset konumu reddedilir.
Devam etmek için Window.positionAt(index) kullanılmalıdır; kullanıcının elle oluşturduğu Spring key map'i kabul edilmez.
Konum; repository/metot, kiracı/yönetim kapsamı, sıralama ve gerçek koşul ağacı ile argüman değerlerinin
sınırlandırılmış, tip bilgili sözleşmesine bağlanır. Şifresiz eşlemelerde mevcut SHA-256 biçimi korunur.
Şifreli eşlemelerde çalışma zamanına bağlı codec'in alanı ayrılmış HMAC-SHA256 yeteneği ve AES'ten bağımsız
anahtar malzemesi kullanılır. Mantıksal operandların yalnızca SHA ile üretilmiş bir ara özeti dışarı verilmez
veya konuma eklenmez. Anahtarlı sözleşme; varlık/tablo/ad alanını, şema/sütun tiplerini ve şifreleme tanımlarını
da bağlar. Başvurulan korunan EQ değerlerinin kanonik kör belirteçleri de sözleşmeye eklenir;
böylece kullanılan her anahtar halkasının HMAC malzemesi bağlanır. Bağlama ya da parmak izi yeteneği eksikse ilk ve devam Window çağrıları, arka uç sorgusundan önce
reddedilir. Aynı metodun Boolean veya ID filtresi dahil herhangi bir
filtre değiştirilirse eski konum, arka uç sorgusundan önce reddedilir. Tip işaretleri ve uzunluk önekleri belirsiz metin
birleştirmelerini önler. Desteklenmeyen nesneler genel Object.toString çağrılmadan reddedilir.
Parmak izi koşul sırasını, IN değerlerinin sırasını ve ondalık ölçeği korur. Anlamca eşdeğer fakat farklı yazılmış koşullar
temkinli biçimde farklı kabul edilebilir; kaydırma boyunca aynı tipli filtre argümanlarını koruyun.
HMAC malzemesi aynı kaldığında AES anahtarı dönüşümü korunan konumları değiştirmez. HMAC anahtarı
değişirse imleç/indeks geçişi birlikte yürütülmelidir. Korunan sözleşmede, olağan sorgu sınırlarına ek olarak
16.384 çerçeveli parça ve temkinli 4 MiB içerik sınırı uygulanır. Bozuk korunan UTF-16 metni reddedilir.
Yalnızca yerel sözleşme temsili imzalanır; mantıksal kalan koşullar ve kaynak filtreleri değiştirilmez.
Bu davranış, desteklenmeyen şifreli türetilmiş koşulları veya korunan sıralama rollerini etkinleştirmez.

Konumlar sorgu sözleşmesi belirtecidir, kimlik doğrulama aracı değildir; kiracı yetkilendirmesi her zaman zorunludur.
Null veya WindowCursor ile geri çözümlenemeyen sort değeri deterministik hata üretir.
Tersinir tipler String, boxed primitive sayılar, BigDecimal, Boolean, Instant, LocalDate, LocalDateTime, OffsetDateTime ve UUID'dir.
Codec desteklenen temsili sunmuyorsa ham enum/BigInteger cursor değeri reddedilir.
Mevcut 8.192 karakter/16 değer token sınırı ve konum içeriği için toplam 16 MiB sınırı korunur.

Operand en çok 4.096 karakter olabilir. Core sayısal precision/scale sınırları, 4.096 yapısal node ve temkinli
16 MiB toplam operand içeriği sınırı geçerlidir. Sonsuz/NaN sayılar reddedilir. Hata yutma veya sessiz kesme eklenmez.
Sorgular, mevcut provider'a 30 saniyelik query timeout iletilmesini ister; Redis socket/bağlantı zaman aşımları da geçerlidir.
Bu süre uçtan uca işlem süresi garantisi değildir; toplu silme ayrıca çalışma zamanının sonlu süresini denetler.
Yeni retry döngüsü, thread havuzu, ortak cache veya sınırsız kuyruk yoktur.
Mevcut CacheDB gecikme, hata, aday sayısı ve havuz baskısı metrikleri izlenmeli; çağıran eşzamanlılık ve backpressure uygulamalıdır.

## Derleme Ve Kabul

SOURCE retention'lı entity annotation'ları processor tarafından görülebilmelidir.
Kaynak sözleşmeleri olmadan ayrı derlenmiş entity JAR'ı, derived metot üretimi için yeterli değildir.
Mevcut generator sözleşmesine uygun olarak entity ve binding birlikte yeniden üretilmelidir.

Kaynak derlemesinde modül reactor/BOM'a eklenmiş, parent sürümleri hizalanmış ve zorunlu testler full-environment CI kapısına kaydedilmiş olmalıdır.
SpringDataRepositoryTest, SpringDataProcessorContractTest, SpringDataQueryBudgetTest,
SpringDataQueryFingerprintTest ve SpringDataExecutionLimitsTest;
CRUD, gerekli bütün operatörler, kalıtım, derleme hataları, sayfalama, cursor yalıtımı ve sınırları kapsar.
SpringDataRedisIntegrationTest, cachedb.snapshot.redis=true ve mevcut host/port ayarlarıyla çalışır; varsayılan 127.0.0.1:16383'tür.
Gerçek Redis, yalıtılmış H2 sahiplik kaynağı fixture'ı ve üretilen Spring bean'leri kullanılır.
Temizlik prefix kapsamındadır ve sınırlıdır; ortak Redis veya function library silinmez.

Yayım kabulü; Java 21 derlemesini, bütün zorunlu testleri, değiştirilmemiş regresyon testlerini ve tam reactor/runtime doğrulamasını gerektirir.
Yalnızca üretilen kaynak metnini denetlemek, Redis veya runtime kabul kanıtı değildir.

API eşlemesi resmî
[Spring Data Commons 3.3.5 Window API](https://github.com/spring-projects/spring-data-commons/blob/3.3.5/src/main/java/org/springframework/data/domain/Window.java)
ve [ScrollPosition API](https://github.com/spring-projects/spring-data-commons/blob/3.3.5/src/main/java/org/springframework/data/domain/ScrollPosition.java)
üzerinden denetlenmiştir.
