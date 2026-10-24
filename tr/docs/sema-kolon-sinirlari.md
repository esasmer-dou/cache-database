# Veritabanına Göre Kolon ve Alan Sınırları

English: [Database-aware column limits](../../docs/schema-column-limits.md)

Kapsam: 0.15.1 ve sonraki sürümler. Yayımlanmış
0.15.0 paketlerinde codec ve kod üreticisi için önceki 1024 alan sınırı geçerlidir.

## İki Ayrı Sınır Var

CacheDB, serileştirilen alan sayısını ve SQL tablosundaki kolon sayısını ayrı
denetler. Birincisini artırmak, veritabanının kapasitesini artırmaz; çok geniş
tabloları tercih edilen tasarım hâline de getirmez.

| Katman | Sınır | Davranış |
| --- | --- | --- |
| Entity verisi, projection şeması, Redis indeks güncellemesi, yönetim paneli dışa aktarımı | 4096 alan | Daha büyük girdi reddedilir; veri boyutu ve diğer bütçeler korunur |
| Oracle, varsayılan | 1000 tablo kolonu | JDBC pozitif bir kapasite bildirirse o değer kullanılır |
| SQL Server, standart tablo | 1024 tablo kolonu | Sparse/wide-table DDL'i kendiliğinden oluşturulmaz |
| PostgreSQL | 1600 tablo kolonu | Satır boyutu ve silinmiş kolonların kapladığı yer daha düşük sınır getirebilir |
| H2, ANSI/özel dialect için varsayılan | 1024 tablo kolonu | Özel dialect kendi sınırlı varsayılanını tanımlayabilir |

Bağlantı bulunan işlemlerde aktif sınır, JDBC'nin
`DatabaseMetaData.getMaxColumnsInTable()` metodundan alınır. Pozitif değer
önceliklidir, ancak 4096'yı geçemez. Sıfır, negatif değer veya
`SQLFeatureNotSupportedException` durumunda sağlayıcının varsayılanı kullanılır.
Diğer SQL hataları üst katmana iletilir; kopan bağlantı, bilinmeyen kapasite gibi
ele alınmaz.

CacheDB, bu sınırı bulmak için ek bir katalog sorgusu göndermez. Bilgi, her şema
işleminde veya yazma transaction'ında bir kez çözülür; satır başına tekrar
alınmaz. JDBC metodunun önbellekten mi yoksa ağ üzerinden mi çalışacağını sürücü
belirler. Bu denetim, önerilen her DDL'in mutlaka başarılı olacağını kanıtlayan
bir katalog taraması değildir.

## Hangi Kolonlar Sayılır?

ID dâhil tüm fiziksel kolonlar sayılır. Ayrı eklenen sürüm ve mantıksal silme
kolonları da toplama katılır. Bu kolonlar zaten eşlemede varsa ikinci kez
sayılmaz. Migration planı, `ADD COLUMN` önermeden önce tabloda bulunan ancak
entity'ye eşlenmemiş kolonları da hesaba katar.

| Örnek | Toplam | Varsayılan kapasiteyle sonuç |
| --- | --- | --- |
| Oracle: ID dâhil 998 eşlenmiş kolon, ayrıca sürüm ve silinme kolonu | 1000 | Kolon sayısı sınırı içinde |
| Oracle: ID dâhil 999 eşlenmiş kolon, ayrıca sürüm ve silinme kolonu | 1001 | İş tablosuna DDL gönderilmeden reddedilir |
| PostgreSQL: ID dâhil 1199 eşlenmiş kolon, ayrıca sürüm | 1200 | Kolon sayısı sınırı içinde |
| PostgreSQL: 1600 eşlenmiş kolon, ayrıca sürüm | 1601 | Reddedilir |
| Yalnızca Redis'te kullanılan, 2000 alanlı projection | 2000 | Codec kabul eder; PostgreSQL'de 2000 kolonlu tablo oluşturma izni vermez |

SQL komutundaki parametre sayısı ayrı bir sınırdır. Tek satır bile parametre
bütçesine sığmıyorsa işlem reddedilir; batch boyutu bire düşürülerek sorun
gizlenmez. Bütçeye sığan yazılar mevcut sağlayıcıya özgü toplu yazma yolunu
kullanmaya devam eder.

## Dağıtımdan Önce Doğrula

Kayıtlı entity metadata'sını ve uygulamanın DataSource'unu kullan. Bu doğrulama
iş tablolarını oluşturmaz:

```java
import com.reactor.cachedb.core.config.SchemaBootstrapConfig;
import com.reactor.cachedb.starter.CacheDatabaseSchemaAdmin;

var schema = new CacheDatabaseSchemaAdmin(
        dataSource, entityRegistry, SchemaBootstrapConfig.defaults());
var result = schema.validate();
if (!result.success()) {
    throw new IllegalStateException("Schema validation failed: " + result.issues());
}
```

Çözülen kapasiteyi ayrıca görmek için:

```java
import com.reactor.cachedb.jdbc.JdbcSchemaDialects;

try (var connection = dataSource.getConnection()) {
    var dialect = JdbcSchemaDialects.resolve(connection);
    int limit = dialect.resolveMaxColumnsInTable(connection.getMetaData());
    System.out.println(dialect.name() + " tablo kolon sınırı: " + limit);
}
```

İlgili CacheDB sağlayıcısı classpath'te bulunmalıdır. Metadata denetimi eksik
sağlayıcıyı kendiliğinden etkinleştirmez. DDL dışa aktarımı ve migration planı,
sınırı aşan tanımlarda `IllegalArgumentException` üretir. Bootstrap bunu şema
sorunu olarak raporlar. Write-behind, iş tablosuna SQL ve transaction içindeki
sürüm koruma güncellemesi gönderilmeden önce sınırı denetler. Sürüm koruma
tablosunun ilk kurulumu bundan önce yapılmış olabilir. Geçersiz kuyruk kaydı
incelenmelidir; değişmeyen bir şema hatasını geçici bağlantı hatası gibi sürekli
yeniden denemek doğru değildir.

## Geniş Entity ve Oracle Extended Modu

Değiştirilebilir `@CacheEntity` sınıfları en fazla 4096 kalıcı alan tanımlayabilir.
Kod üreticisi, 1000 alan aşıldığında sağlayıcı sınırlarının kontrol edilmesini
isteyen bir not verir. Büyük codec ve metamodel sınıflarında kod küçük yardımcı
metotlara bölünür; böylece JVM'in metot başına bytecode sınırı aşılmaz. Küçük
entity'lerin mevcut doğrudan yolu korunur; çalışma anında reflection eklenmez.

Java record'larında ek bir JVM sınırı vardır: constructor için, nesnenin kendisi
dâhil, 255 parametre yuvası. Primitive `long` ve `double` iki yuva kullanır. Çok
geniş entity için değiştirilebilir sınıf kullan. Veri alanı sınırını artırmak bu
JVM kuralını kaldırmaz.

Oracle'ın genişletilmiş sınırı, uygun veritabanı ayarını ve bunu bildiren uyumlu
bir JDBC sürücüsünü gerektirir. CacheDB `MAX_COLUMNS` ayarını değiştirmez ve
sadece sürüm numarasına bakarak extended modunu varsaymaz. Sürücü 1000 bildirirse
veya kapasiteyi bilinmiyor olarak döndürürse temkinli sınır korunur. Testte 4096
döndüren sahte metadata, yalnızca o kod yolunu doğrular; gerçek Oracle extended
kurulumunu kanıtlamaz.

Şema keşfi, salt okunur view'ları 4096 alan tavanına kadar korur; view tablo
kapasitesini aşıyorsa uyarı verir. View ve SQL sonuçlarının sınırları, fiziksel
tablo sınırıyla aynı olmak zorunda değildir. Doğrudan tablo oluşturma sınırı
değişmez.

## Yükseltme ve Tasarım

1. Core, sağlayıcı ve annotation processor sürümlerini eşitle; binding'leri
   yeniden üret.
2. 1024 alanı aşan verileri yayımlamadan önce tüm okuyucu ve worker'ları yükselt.
   Eski 0.15.0 okuyucuları bu verileri reddetmeye devam eder. Daha küçük mevcut
   verilerin biçimi, null davranışı ve tekrarlanan alanda son değerin kullanılması
   kuralı korunur.
3. Şemayı, satır genişliğini ve SQL parametre bütçesini gerçek veritabanı/sürücü
   birleşimi üzerinde doğrula.
4. Liste ve dashboard için dar, ekrana özgü projection kullan. Geniş bir eski
   şemayı desteklemek, ilk ekranda tüm kolonları yüklemeyi önerdiğimiz anlamına
   gelmez.

4096 alan sınırı; Redis bellek politikasını, sorgu bütçesini, projection
penceresini, yazma batch sınırını veya veri boyutu sınırını kaldırmaz. Bu
değişiklik kendiliğinden SQL okuması, warm veya periyodik eşitleme eklemez.

Kaynaklar: [PostgreSQL sınırları](https://www.postgresql.org/docs/17/limits.html),
[SQL Server kapasitesi](https://learn.microsoft.com/en-us/sql/sql-server/maximum-capacity-specifications-for-sql-server),
[Oracle MAX_COLUMNS](https://docs.oracle.com/en/database/oracle/oracle-database/26/refrn/MAX_COLUMNS.html),
[JVM metot tanımları](https://docs.oracle.com/javase/specs/jvms/se17/html/jvms-4.html#jvms-4.3.3).
