# Konumlandırma Duyurusu Taslağı

CacheDB, Redis merkezli sistemler için tasarlanmış düşük ek yüklü bir Java
persistence kütüphanesidir. Amaç, ekipleri her yerde düşük seviye repository
koduna zorlamadan, düşük gecikmeli veri akışlarını açık ve ölçülebilir tutmaktır.

## Tek Cümlelik Mesaj

> CacheDB, Redis merkezli Java servisleri için düşük çalışma zamanı ek yükü,
> açık okuma modeli kontrolü ve ORM'e yakın generated API deneyimi sunar.

## Güvenilirlik Sınırı

> CacheDB persistence davranışını gizlemeye çalışmaz. Sık erişilen veri yolunu daha
> açık, daha sınırlı ve daha kolay ölçülebilir hale getirir.

## Güçlü Olduğu Yerler

- Redis'in production mimarisinde birinci sınıf bağımlılık olduğu servisler
- düşük gecikmeli okuma yoluna ihtiyaç duyan ürün API'leri
- projection kullanan dashboard ve liste ağırlıklı uygulamalar
- runtime reflection istemeyen Java ekipleri
- normal kodu ergonomik tutarken ölçülen darboğazlarda kaçış hattı isteyen ekipler

## Uygun Olmadığı Yerler

- ana yükü SQL join ve ilişkisel raporlama olan uygulamalar
- ORM davranışının büyük ölçüde görünmez kalmasını isteyen ekipler
- Redis'in gerçek runtime tasarımının parçası olmadığı sistemler
- okuma modeli ve projection disiplinini sahiplenmek istemeyen ekipler

## Kanıt Cümleleri

- Repository recipe benchmark, generated API yüzeyinin doğrudan repository yoluna yakın kaldığını gösterir.
- Read-shape benchmark, summary/detail ve preview desenlerinin full aggregate okumaya göre neden gerekli olduğunu görünür kılar.
- Çok podlu koordinasyon smoke'u, Redis stream consumer kimliği ve singleton operasyon döngülerini doğrular.

## Dışa Dönük Açıklama

CacheDB, düşük gecikmeli okuma/yazma katmanını Redis üzerinden kurar; kalıcı doğruluk
kaynağını ise seçilen SQL provider üzerinde tutar. PostgreSQL varsayılan
provider yoludur; MSSQL ise kendi SQL Server evidence hattı olan açık provider
olarak bilinçli şekilde seçilir. Derleme zamanında üretilen metadata sayesinde runtime reflection'a
ihtiyaç duymaz. Normal servis kodu generated API'lerle ergonomik kalır; çok
ilişkili veya global sıralı ekranlarda ise projection ve okuma modeli disiplini
kullanılır.

Bu nedenle CacheDB, "her şeyi otomatik gizleyen ORM" değil; düşük gecikmeli veri yolunu
bilinçli biçimde tasarlamak isteyen ekipler için düşük ek yüklü bir persistence
katmanıdır.
