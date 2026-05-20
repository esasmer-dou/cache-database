# Public Beta Yayın Paketi

Bu doküman, repoyu dış dünyaya açarken kullanılacak kısa ve tutarlı yayın
paketini toplar. Amaç, CacheDB'yi olduğundan büyük göstermeden, hangi
problem için tasarlandığını net anlatmaktır.

## GitHub About

### İsim

`cache-database`

### Kısa açıklama

`Redis merkezli Java persistence kütüphanesi: PostgreSQL kalıcı kaynak olarak kalır, ORM benzeri API'ler derleme zamanında üretilir.`

### Website

Ayrı bir site yoksa repo URL'si veya ana doküman giriş sayfası kullanılabilir.

### Önerilen etiketler

- `java`
- `redis`
- `postgresql`
- `orm`
- `persistence`
- `write-behind`
- `annotation-processor`
- `spring-boot`
- `low-latency`
- `read-model`
- `projection`
- `cache`

## Açık Beta Konumlandırması

Önerilen ana mesaj:

`cache-database, düşük gecikme ve ölçülebilir çalışma zamanı maliyeti isteyen Java ekipleri için Redis merkezli bir persistence katmanıdır. PostgreSQL kalıcı veri kaynağı olarak kalır; sıcak okuma ve yazma yolu Redis üzerinden tasarlanır.`

Mutlaka korunması gereken sınır:

`Açık beta: çok ilişkili ekranlarda, büyük liste ekranlarında ve global sıralı iş ekranlarında projection/okuma modeli disiplini isteğe bağlı bir iyileştirme değil, tasarımın parçasıdır.`

## Güncel Açık Beta Sürüm Başlığı

`cache-database v0.1.0-beta.2`

Güncel etiket:

`v0.1.0-beta.2`

## İlk Açık Beta Sürüm Notu Taslağı

```md
## cache-database açık beta

Bu sürüm, `cache-database` için ilk açık beta yayınıdır.

### CacheDB nedir

CacheDB, PostgreSQL'i kalıcı veri kaynağı olarak korurken uygulamanın sıcak
okuma ve yazma yolunu Redis üzerinden kuran bir Java persistence
kütüphanesidir. Hedefi, çalışma zamanı ek yükünü düşük tutmak ve buna rağmen
ORM benzeri, üretilebilir bir geliştirici deneyimi sunmaktır.

### Şimdiden güçlü olan taraflar

- Redis merkezli okuma/yazma yolu ve PostgreSQL kalıcılığı
- derleme zamanında üretilen entity metadata'sı ve ergonomik API'ler
- Spring Boot starter ve plain Java bootstrap yolu
- çok ilişkili ekranlar için projection/okuma modeli rehberi
- global sıralı iş ekranları için ranked projection desteği
- production kanıt iş akışları ve çok instance'lı koordinasyon smoke kapsamı

### Açık beta kapsamı

CacheDB açık beta kullanımı için hazırdır; ancak henüz "koşulsuz GA" olarak
duyurulmamalıdır.

Temel tasarım ilkesi açıktır:

- normal CRUD için önce üretilmiş module/binding API'leriyle başla
- ilişki yükü yüksek liste ekranlarında projection ve ilişki limiti kullan
- global sıralı veya aralık tabanlı ekranlarda ranked projection kullan
- yalnızca ölçümle kanıtlanmış darboğazları doğrudan repository seviyesine indir

### Production yayına geçmeden önce

- production reçeteleri rehberini oku
- tuning parameters dokümanını oku
- production kanıt iş akışını çalıştır
- ortak Redis/PostgreSQL kullanımında çok instance'lı koordinasyon smoke'unu çalıştır

### Önemli not

Çok ilişkili ve global sıralı ekranlarda projection/okuma modeli disiplini
CacheDB için opsiyonel değildir; tasarımın parçasıdır.
```

## Yayın Kontrol Listesi

- repo görünürlüğü bilinçli şekilde değiştirildi
- README ve doküman bağlantıları kontrol edildi
- release checklist gözden geçirildi
- `pom.xml` içindeki geçici maintainer alanları güncellendi
- GitHub security reporting açıldı
- branch protection açıldı
- `oss-release` ile artifact build alındı
