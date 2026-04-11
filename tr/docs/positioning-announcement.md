# CacheDB Konumlandırma Taslagi

CacheDB, production runtime overhead'i önemseyen ama tüm kodu alt seviye altyapi stiline düşurmek istemeyen ekipler için Redis-first bir persistence kütüphanesidir.

Kısa hikaye:

- Redis-first okuma ve yazma
- async write-behind ile PostgreSQL kalıciligi
- runtime reflection yerine compile-time generated metadata
- kolay onboarding için generated domain ve binding surface
- gerçek hotspot'lar için ölçülmüş kaçış hatları

Bu konumlandırmayi güvenilir yapan şeyler:

- proje, Hibernate/JPA'nin hala daha doğru tercih olduğu yerleri açıkça soyluyor
- generated ergonomi yüzeyleri minimal repository kullanımina karşı benchmark'lanmis durumda
- relation-ağır read pattern'ler sadece anlatiyla değil, ayrı bir read-shape benchmark ile destekleniyor

Repo içindeki güncel kanıtlar:

- repository recipe benchmark:
  - generated entity binding, minimal repository'ye göre ortalama yaklasik `+4.84%`
  - JPA-style domain module, minimal repository'ye göre ortalama yaklasik `+16.83%`
- read-shape benchmark:
  - production'a çıkmadan önce summary/detail, preview ve full aggregate maliyetlerini görünur kilar

Önerilen disa donuk konumlandırma cumlesi:

> CacheDB, Redis-merkezli sistemler için daha düşük production overhead, explicit read-model kontrolu ve ekipleri her yerde alt seviye repository koduna zorlamayan ergonomik bir ORM alternatifi sunar.

Önerilen guardrail cumlesi:

> CacheDB persistence davranisini gizlemeye çalışmaz. Düşük-overhead yolunu daha kolay tüketilir hale getirmeye çalışir.

Önerilen hedef kitle:

- sıcak read path'i olan urun servisleri
- Redis'i birinci sınıf bağımlilik olarak isleten ekipler
- projection'dan fayda sağlayan dashboard ve liste-ağır uygulamalar
- generated API yüzeyi isteyen ama hotspot kaçış hattına da ihtiyaç duyan ekipler

Uygun olmayan hedef kitle:

- ana yuku SQL join ve iliskisel raporlama olan uygulamalar
- ORM davranisinin büyük ölçude implicit kalmasini isteyen ekipler
- Redis'in gerçek runtime tasarıminin parcasi olmadigi sistemler
