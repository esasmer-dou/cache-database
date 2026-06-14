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
- İlişki yükleme, projection ve sıcak veri penceresi açıkça tasarlanabiliyorsa.
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

- Uygulamanın sıcak okuma/yazma yolu Redis üzerinden ilerler.
- Seçilen SQL provider kalıcı veri deposu olarak kalır.
- Entity metadata'sı derleme zamanında üretilir.
- İlişki yükleme açık fetch plan ve loader'larla yapılır.
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
| İlişki yükleme | Açık `FetchPlan`, loader ve projection | Çoğu zaman implicit lazy/eager graph davranışı |
| Darboğaz kaçış yolu | Binding veya doğrudan repository'ye inilebilir | Çoğu zaman ORM içinde kalınır veya custom SQL yazılır |
| En iyi uyum | Düşük gecikmeli servisler, read-heavy API'ler, Redis merkezli sistemler | İlişkisel alanlar, SQL merkezli sistemler, join-heavy uygulamalar |
| Runtime ek yük hedefi | Çok düşük | Genelde kabul edilebilir, ama birincil tasarım hedefi değil |

## CacheDB En Çok Nerede Güçlü?

CacheDB şu alanlarda güçlü uyum verir:

- sıcak okuma yolu olan ürün servisleri
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

- normal iş kodu generated domain veya binding yüzeyini kullanır
- sıcak okuma yolları projection ve açık fetch limitleriyle kurulur
- global sorted/range ekranları geniş entity sorgusu yerine projection'a özel ranked alan kullanır
- ranked alanlar `rankedBy(...)` ile tanımlanır ve projection repository top-window yolunu kullanabilir
- yalnızca kanıtlanmış darboğazlar doğrudan repository'ye iner
- foreground repository trafiği, background worker ve admin trafiğinden ayrılır

CacheDB kötü kullanıldığında sorun genelde şöyle görünür:

- liste ekranlarında geniş aggregate hydrate edilir
- Redis sihirli ve bedava cache gibi düşünülür
- projection kullanılmaz
- foreground ve background yolları aynı Redis pool'da toplanır
- tüm kod ölçüm yapılmadan minimal repository stiline indirilir

CacheDB açıklığı ödüllendirir. Object graph'ın bedelsiz olduğunu varsaymayı
ödüllendirmez.

## Önerilen Geçiş Yolu

JPA/Hibernate'ten gelen ekipler için önerilen geçiş:

1. `GeneratedCacheModule.using(session)...` ile başla.
2. CRUD ve normal servis endpoint'lerini generated yüzeyde bırak.
3. Liste ekranlarını projection ve summary/detail modeline çek.
4. Önizleme ilişkilerine `withRelationLimit(...)` ekle.
5. Sadece ölçülmüş darboğazları `*CacheBinding.using(session)...` tarafına indir.
6. Doğrudan repository'yi ancak profiling hâlâ gerekli diyorsa kullan.

Bu yol onboarding'i kolay tutarken düşük ek yük hedefini de korur.

## Ekip Tipine Göre Kısa Kural

| Ekip veya yük tipi | Önerilen yüzey |
| --- | --- |
| Normal ürün servis kodu | `GeneratedCacheModule.using(session)...` |
| Açıkça sıcak olduğu ölçülmüş endpoint'ler | `*CacheBinding.using(session)...` |
| Worker, replay, recovery, altyapı kodu | doğrudan `EntityRepository` / `ProjectionRepository` |
| Çok ilişkili liste veya yönetim paneli okumaları | projection + `withRelationLimit(...)` |
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
