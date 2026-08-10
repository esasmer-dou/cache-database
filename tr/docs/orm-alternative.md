# CacheDB Bir ORM Alternatifi Olarak

Bu sayfa şu soruya cevap verir:

Bir ekip, geleneksel JPA/Hibernate benzeri ORM yerine ne zaman CacheDB
kullanmalı?

Kısa cevap: Redis production mimarisinin gerçek bir parçasıysa, düşük gecikmeli
okumalar önemliyse ve ekip okuma modelini açık biçimde tasarlamaya hazırsa
CacheDB güçlü bir alternatiftir.

## Ne Zaman CacheDB Seçilir?

Şu durumlarda CacheDB iyi uyum verir:

- Redis zaten gerçek production mimarisinin parçasıysa.
- Düşük gecikmeli okuma yolu ürün için önemliyse.
- İlişki yükleme, projection ve sık erişilen veri penceresi açıkça tasarlanabiliyorsa.
- Runtime reflection istemiyor, derleme zamanında üretilen metadata istiyorsan.
- Normal servis kodu ergonomik kalsın ama ölçülen darboğazlarda daha düşük seviyeye inilebilsin istiyorsan.

## Ne Zaman ORM'de Kalmak Daha Doğru?

Şu durumlarda JPA/Hibernate tarafında kalmak daha doğal olabilir:

- Uygulamanın ana okuma modeli yoğun SQL join ve raporlama üzerine kuruluysa.
- Ekip persistence davranışının büyük ölçüde görünmez kalmasını istiyorsa.
- Lazy loading ve entity graph davranışları ürün geliştirme modelinin parçasıysa.
- Redis production runtime planının parçası değilse.
- Darboğazlar okuma gecikmesinden çok SQL modelleme veya raporlama tarafındaysa.

Bu ayrım zayıflık değildir. CacheDB'nin hangi problem için tasarlandığını açık
tutmak, ürünü daha güvenilir konumlandırır.

## CacheDB Nedir?

CacheDB, Hibernate'in birebir kopyası olmaya çalışmaz.

CacheDB şu modeli kullanır:

- Yazmalar önce Redis tarafından kabul edilir, ardından write-behind ile kalıcılaştırılır.
- Kritik okumalar yalnızca Redis'ten çalışan route sözleşmeleriyle yapılır.
- Arşiv ve geçmiş okumaları sınırlı SQL route'larında açıkça tanımlanır.
- Seçilen SQL provider kalıcı veri deposu olarak kalır.
- Entity metadata'sı derleme zamanında üretilir.
- İlişki yükleme sınırlı lookup veya projection ile açıkça yapılır.
- Write-behind, kalıcı yazımı foreground uygulama yolunun dışına taşır.

Bu nedenle CacheDB şu şekilde değerlendirilmelidir:

- açık kontrol isteyen ekipler için düşük ek yüklü bir ORM alternatifi
- Redis merkezli uygulamalar için production odaklı persistence kütüphanesi
- gerçek darboğazlarda daha düşük seviyeli repository kullanımına izin veren bir çalışma modeli

## Karşılaştırma

| Konu | CacheDB | Geleneksel JPA / Hibernate |
| --- | --- | --- |
| Birincil okuma yolu | Redis öncelikli | Veritabanı öncelikli |
| Metadata modeli | Derleme zamanında üretilir | Genelde runtime reflection ve ORM metadata |
| İlişki yükleme | Açık ve sınırlı lookup veya projection | Çoğu zaman örtülü lazy/eager graph davranışı |
| Uygulama API'si | Derleme zamanında üretilen `@CacheRepository` implementasyonu | Runtime ORM repository/session |
| Darboğaz kaçış yolu | Ölçülmüş altyapı işi için provider repository'si veya adaptörü | Çoğu zaman ORM içinde kalınır veya özel SQL yazılır |
| En iyi uyum | Düşük gecikmeli servisler, read-heavy API'ler, Redis merkezli sistemler | İlişkisel alanlar, SQL merkezli sistemler, join-heavy uygulamalar |
| Runtime ek yük hedefi | Çok düşük | Genelde kabul edilebilir, ama birincil tasarım hedefi değil |

## CacheDB En Çok Nerede Güçlü?

CacheDB şu alanlarda güçlü uyum verir:

- düşük gecikmeli okuma ihtiyacı olan ürün servisleri
- projection kullanan yönetim paneli ve liste ağırlıklı uygulamalar
- Redis'i production'da birinci sınıf bağımlılık olarak işleten sistemler
- runtime reflection istemeyen ama generated API ergonomisi isteyen ekipler
- normal kod ile ölçülmüş darboğazları net ayırmak isteyen servisler

## Nerede Daha Zayıf Uyum Verir?

Ekip şu beklentilere sahipse CacheDB doğru araç olmayabilir:

- persistence davranışının tamamen görünmez kalması
- ekranların varsayılan olarak geniş relational join'lerle kurulması
- payload boyutu düşünülmeden otomatik graph traversal beklenmesi
- ana uygulama deseninin ağır ilişkisel raporlama olması

Bu durumda Hibernate/JPA daha doğal ve daha az sürtünmeli araç olabilir.

## Production Ekipleri Ne Beklemeli?

CacheDB doğru kullanıldığında production resmi genelde şöyle olur:

- normal iş kodu deklaratif repository arayüzlerini enjekte eder
- düşük gecikmeli okuma akışları `@HotRoute`, projection, açık pencere ve coverage ile kurulur
- arşiv ve geçmiş okumaları sınırlı `@SourceRoute` metotlarıyla yapılır
- global sorted/range ekranları geniş entity sorgusu yerine projection'a özel ranked alan kullanır
- ranked alanlar `rankedBy(...)` ile tanımlanır ve projection repository top-window yolunu kullanabilir
- yalnızca ölçülmüş altyapı yolları provider repository'sine iner
- foreground repository trafiği, background worker ve admin trafiğinden ayrılır

CacheDB kötü kullanıldığında sorun genelde şöyle görünür:

- liste ekranlarında geniş aggregate hydrate edilir
- Redis sihirli ve bedava cache gibi düşünülür
- projection kullanılmaz
- foreground ve background yolları aynı Redis pool'da toplanır
- uygulama kodunun tamamında route sözleşmeleri düşük seviyeli repository çağrılarıyla atlanır

CacheDB açıklığı ödüllendirir. Object graph'ın bedelsiz olduğunu varsaymayı
ödüllendirmez.

## Önerilen Geçiş Yolu

JPA/Hibernate'ten gelen ekipler için önerilen geçiş:

1. Tablo eşlemesini ve ilişki metadata'sını entity üzerinde tut.
2. Her aggregate veya route grubu için bir `@CacheRepository` arayüzü ekle.
3. Detay okumalarını `@CacheLookup`, Redis liste okumalarını `@HotRoute` olarak tanımla.
4. Liste ekranlarını projection ve özet/detay modeline taşı.
5. Arşiv ve geçmiş okumalarını sınırlı `@SourceRoute` veya gözden geçirilmiş `@SourceSql` metotlarıyla tanımla.
6. Kritik route'lardan `@WarmRoute` türet ve canlı geçişten önce coverage kanıtla.
7. Veri eşitliği, gecikme, bellek ve rollback kontrolleri geçene kadar eski ORM route'unu açık tut.

Bu yol onboarding'i kolay tutarken düşük ek yük hedefini de korur.

## Ekip Tipine Göre Kısa Kural

| Ekip veya yük tipi | Önerilen yüzey |
| --- | --- |
| Normal ürün servis kodu | enjekte edilen `@CacheRepository` |
| Yalnızca Redis'ten çalışan detay ve liste endpoint'leri | `@CacheLookup` / `@HotRoute` |
| Kalıcı arşiv ve geçmiş | sınırlı `@SourceRoute` / gözden geçirilmiş `@SourceSql` |
| Worker, replay, recovery ve altyapı kodu | düşük seviyeli repository veya provider adaptörü |
| Çok ilişkili liste veya yönetim paneli okumaları | projection döndüren `@HotRoute` ve eşleşen `@WarmRoute` |
| Global sıralı veya ranked ekranlar | ranked projection |

## Benchmark Nasıl Okunmalı?

Repo içindeki recipe benchmark dar kapsamlıdır.

Kanıtladığı şey:

- generated ergonomi, doğrudan repository kullanımıyla aynı düşük ek yük bandında kalabiliyor
- API yüzeyi maliyeti ölçülebiliyor

Kanıtlamadığı şey:

- CacheDB'nin her yükte Hibernate'den hızlı olduğu
- Redis gecikmesinin ortadan kalktığı
- çok ilişkili ekranların okuma modeli disiplini olmadan ucuz olacağı

Bu benchmark'i marketing iddiası için değil, API yüzeyi dürüstlüğü için kullan.

Son yerel ölçüm özeti:

- `Generated entity binding`: bu yerel koşuda ortalamada en hızlı yüzey
- `Minimal repository`: bu yerel koşuda en düşük p95
- `JPA-style domain module`: gruplanmış ergonomik yüzey, makul wrapper maliyeti

Çıkarım:

- ergonomik yüzeyler bedelsiz değildir
- ama doğrudan repository yoluna yeterince yakındır
- bu yüzden çoğu ekip okunabilirlikten erken vazgeçmemelidir

## Devam Et

- [Production Recipes](./production-recipes.md)
- [Spring Boot Starter](./spring-boot-starter.md)
- [Tuning Parameters](./tuning-parameters.md)
- [Production Tests](../../cachedb-production-tests/README.md)
