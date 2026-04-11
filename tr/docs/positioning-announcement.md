# CacheDB Konumlandirma Taslagi

CacheDB, production runtime overhead'i onemseyen ama tum kodu alt seviye altyapi stiline dusurmek istemeyen ekipler icin Redis-first bir persistence kutuphanesidir.

Kisa hikaye:

- Redis-first okuma ve yazma
- async write-behind ile PostgreSQL kaliciligi
- runtime reflection yerine compile-time generated metadata
- kolay onboarding icin generated domain ve binding surface
- gercek hotspot'lar icin olculmus kacis hatlari

Bu konumlandirmayi guvenilir yapan seyler:

- proje, Hibernate/JPA'nin hala daha dogru tercih oldugu yerleri acikca soyluyor
- generated ergonomi yuzeyleri minimal repository kullanimina karsi benchmark'lanmis durumda
- relation-agir read pattern'ler sadece anlatiyla degil, ayri bir read-shape benchmark ile destekleniyor

Repo icindeki guncel kanitlar:

- repository recipe benchmark:
  - generated entity binding, minimal repository'ye gore ortalama yaklasik `+4.84%`
  - JPA-style domain module, minimal repository'ye gore ortalama yaklasik `+16.83%`
- read-shape benchmark:
  - production'a cikmadan once summary/detail, preview ve full aggregate maliyetlerini gorunur kilar

Onerilen disa donuk konumlandirma cumlesi:

> CacheDB, Redis-merkezli sistemler icin daha dusuk production overhead, explicit read-model kontrolu ve ekipleri her yerde alt seviye repository koduna zorlamayan ergonomik bir ORM alternatifi sunar.

Onerilen guardrail cumlesi:

> CacheDB persistence davranisini gizlemeye calismaz. Dusuk-overhead yolunu daha kolay tuketilir hale getirmeye calisir.

Onerilen hedef kitle:

- sicak read path'i olan urun servisleri
- Redis'i birinci sinif bagimlilik olarak isleten ekipler
- projection'dan fayda saglayan dashboard ve liste-agir uygulamalar
- generated API yuzeyi isteyen ama hotspot kacis hattina da ihtiyac duyan ekipler

Uygun olmayan hedef kitle:

- ana yuku SQL join ve iliskisel raporlama olan uygulamalar
- ORM davranisinin buyuk olcude implicit kalmasini isteyen ekipler
- Redis'in gercek runtime tasariminin parcasi olmadigi sistemler
