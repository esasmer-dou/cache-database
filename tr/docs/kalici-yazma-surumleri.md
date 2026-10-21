# Kalıcı Yazma Sürüm Koruması

[English](../../docs/durable-write-fences.md)

## Ayrı Tablo Neden Gerekli?

Bir satır fiziksel olarak silindiğinde satırdaki sürüm bilgisi de kaybolur. Daha
önce yola çıkmış eski bir UPSERT, silme tamamlandıktan sonra çalışırsa satırı
yeniden oluşturabilir. SQL öncesindeki Redis kontrolü, compaction veya yalnızca
Java içinde çalışan bir kilit, farklı worker ve pod'lar arasındaki bu yarışı
tek başına engelleyemez.

0.14.0'dan itibaren PostgreSQL, SQL Server ve Oracle yazma worker'ları
`cachedb_write_fence` tablosunu kullanır. İş tabloları ve public flusher
constructor'ları değişmez. Metadata, son uygulanan sürümü ve bu sürümün silme
işlemi olup olmadığını saklar. Bu tablo Redis önbelleği değil, kalıcı SQL verisidir.

| İşlem sırası | SQL sonucu |
| --- | --- |
| UPSERT v1, DELETE v3, geciken UPSERT v2 | Satır silinmiş kalır. |
| Olmayan satıra DELETE v3, ardından UPSERT v2 | Satır oluşmaz. |
| DELETE v3, ardından UPSERT v4 | Yeni sürüm satırı yeniden oluşturabilir. |
| UPSERT v3, DELETE v3, UPSERT v3 | Eşit sürümde silme işlemi önceliklidir. |
| İş tablosunda constraint hatası | İş verisi ve metadata birlikte geri alınır. |

## Kurulum

`cachedb-storage-jdbc` içindeki provider'a uygun script'i kullanın:

- [PostgreSQL migration](../../cachedb-storage-jdbc/src/main/resources/com/reactor/cachedb/jdbc/migrations/postgres/V014__durable_write_fence.sql).
- [SQL Server migration](../../cachedb-storage-jdbc/src/main/resources/com/reactor/cachedb/jdbc/migrations/mssql/V014__durable_write_fence.sql).
- [Oracle migration](../../cachedb-storage-jdbc/src/main/resources/com/reactor/cachedb/jdbc/migrations/oracle/V014__durable_write_fence.sql).

Script'i datasource'un varsayılan şemasında bir kez çalıştırın. Worker hesabına
bu tablo için SELECT, INSERT ve UPDATE yetkisi verin. Datasource'un catalog ve
varsayılan şemasını sabit tutun; başka bir catalog veya varsayılan şema için ayrı
datasource/flusher kullanın.
Aynı fiziksel iş tablolarına yazan bütün worker'lar tek bir ortak metadata
tablosunu/şemasını ve aynı artan entity sürümü/Redis anahtar düzenini kullanmalıdır.
Pod veya kullanıcı başına ayrı metadata kopyaları bu yazıcıları koordine etmez.

Çalışma sırasında kolonlar ve primary key doğrulanır. Tablo yoksa iş transaction'ı
için bağlantı alınmadan önce bir kez oluşturulmaya çalışılır. Production'da DDL
yetkisi sınırlı hesaplar için script'i önceden uygulayın. Kurulum başarısızsa
`CDB03` SQL durumu ile hata verilir; kuyruktaki yazma başarılı sayılmaz. Oracle'ın
DDL sırasında örtük commit yapması da iş transaction'ını etkileyemez.
Geçici bağlantı ve sorgu hatalarının özgün SQL hata sınıfı korunur; bu hatalar
metadata tablosu oluşturma gerekçesi sayılmaz ve mevcut retry mekanizmasına gider.

## Güvenli Sürüm Geçişi

1. Yeni yazma kabulünü durdurun. Bekleyen yazmaları mevcut operasyon prosedürünüzle
   tamamlayıp veriyi doğrulayın. Ardından eski worker'ların tamamını kapatın.
2. Metadata migration'ını uygulayın ve yetkileri kontrol edin.
3. Yalnızca yeni worker'ları başlatın. Aynı tablolara yazan eski ve yeni worker'ları
   birlikte çalıştırmayın; eski worker korumayı atlar.
4. Trafiği açmadan önce staging'de ekleme, güncelleme, silme, yeniden oynatma ve
   yeniden başlatma senaryolarını doğrulayın.

Migration, geçmişte silinmiş satırların sürümlerini yeniden kuramaz ve daha önce
yanlışlıkla geri oluşmuş veriyi düzeltemez. Geçmiş veriyi ve eski pending/retry/poison
kuyruklarını ayrıca kontrol edin. Koruma öncesinden kalan işlemleri incelemeden
yeniden oynatmayın.

## Transaction Ve Performans

Kimlikler transaction başına sıralanır ve en fazla 500 kimliklik parçalarla işlenir.
Metadata ekleme, kilitli okuma ve sürüm güncelleme işlemleri toplu SQL/prepared batch
yolunu kullanır. Kabul edilen işlemler giriş sırasını ve mevcut provider bulk SQL
yollarını korur. Metadata ve iş verisi aynı bağlantıda birlikte commit veya rollback
edilir. Commit öncesinde süreç kapanırsa ikisi de geri alınır.

Bu koruma, arka plandaki kalıcı yazma yoluna ek SQL işi getirir. Normal Redis
okumasına SQL çağrısı eklemez. Sorgu zaman aşımları korunur; SQL Server'ın lock
timeout ve isolation seçenekleri de geçerlidir. Deadlock veya bağlantı hatası,
mevcut worker retry/poison mekanizmasına gider; başarılı yazma gibi gösterilmez.

Kilit anahtarı fiziksel şema/tablo ve entity kimliğinden türetilir. Hash indeksinin
yanında tam kimlik de doğrulanır; çakışan veya bozulmuş metadata ile yazma yapılmaz.
Redis entity anahtarlarında olduğu gibi her SQL primary key'i uygulamada tek bir
standart kimlik gösterimine sahip olmalıdır. SQL collation'ı aynı kabul ediyorsa
büyük/küçük harf, aksan veya sondaki boşluk farklarını ayrı Redis kimlikleri gibi
kullanmayın. Sayısal kimlikleri veya standart biçimde UUID metinlerini tercih edin.
Yazma hedefi synonym değil, fiziksel tablo olmalıdır.

## Saklama Ve Kurtarma

Bu tabloyu truncate etmeyin, TTL ile temizlemeyin ve iş satırı silindi diye
metadata'yı silmeyin. Tablo, yazılan farklı kimlik sayısıyla büyür; SQL disk ve indeks
kapasitesini buna göre planlayın. Redis'teki kayıt sayısı sınırı bu tabloyu sınırlamaz.
Metadata ile iş tablolarını tutarlı biçimde yedekleyip geri yükleyin. Yalnızca birini
geri yüklemek, doğru bir yazmanın elenmesine veya eski işlemlerin yeniden uygulanmasına
yol açabilir.

Redis sürüm anahtarları da süre dolumuyla silinmemelidir. Redis kaybı veya geri
yüklemesi sonrasında, SQL'deki silme sürümleri dahil artan entity sürümlerini
kurtarmadan yazıcıları açmayın. SQL koruması eski sürümün kalıcılaşmasını engeller;
kaybolan Redis sayacını sıfırlamayı güvenli hâle getirmez ve dış SQL yazıcılarını
kendiliğinden koordine etmez. Mevcut CDC/outbox ve kurtarma gereklilikleri devam eder.

Dış SQL yazıcısı ile CacheDB write-behind aynı kimliğe eş zamanlı yazabiliyorsa,
commit sonrasında gelen CDC/outbox olayı tek başına SQL transaction'ını koruyamaz.
Tek bir yazma sahibi belirleyin veya her iki yazıcıyı aynı sürüm kaynağı ve aynı
transaction içindeki SQL korumasıyla açıkça koordine edin. CDC/outbox, yalnızca
okuma yapan CacheDB yollarını güncellemek için uygundur; kendiliğinden çoklu
yazıcı çakışma çözümü sağlamaz.
