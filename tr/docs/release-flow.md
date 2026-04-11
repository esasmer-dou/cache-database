# Release Akisi

Her public beta release sonrasinda bu akis kullanilmalidir.

## Onerilen ritim

1. mevcut beta release'i yayinla
2. release asset ve GitHub release sayfasini dogrula
3. bir sonraki gelistirme dongusunu hemen ac
4. `main` branch'ini bir sonraki `-SNAPSHOT` surumunde tut

## Sonraki beta dongusunu baslat

Su komutu calistir:

```powershell
./tools/release/start-next-beta-cycle.ps1 `
  -CurrentVersion 0.1.0-beta.1 `
  -NextVersion 0.1.0-beta.2-SNAPSHOT `
  -CreateReleaseNotesTemplate
```

Bu komut sunlari yapar:

- root ve modullerdeki `pom.xml` surumlerini gunceller
- `CHANGELOG.md` icine yeniden `Unreleased` bolumu acar
- isterse `docs/releases/` altinda bir sonraki release note taslagini olusturur

## Onerilen branch akisi

- `main` branch'i bir sonraki `-SNAPSHOT` surumunde kalir
- kisa omurlu isler `codex/*` branch'lerinde yapilir
- beta release tag'i gercekten yayinlamak istedigin commit'ten kesilir

## Sonraki beta kesilmeden once

- production evidence workflow'lerinin yesil oldugunu dogrula
- coordination smoke sonucunun yesil oldugunu dogrula
- release note'lari guncelle
- `CHANGELOG.md` dosyasinin freeze'e hazir oldugunu kontrol et
- release bundle'in dogru commit'ten yeniden uretildigini dogrula

## Onemli not

Public release sonrasinda `main` branch'ini onceki beta surumunde birakma.
Bir sonraki donguyu hemen ac ki dependency kullanan ekipler ve dis katkicilar aktif gelistirme hattini gorebilsin.
