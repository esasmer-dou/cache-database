# Release Akışı

Her public beta release sonrasında bu akış kullanılmalıdır.

## Önerilen ritim

1. mevcut beta release'i yayınla
2. release asset ve GitHub release sayfasini doğrula
3. bir sonraki geliştirme döngüsünü hemen aç
4. `main` branch'ini bir sonraki `-SNAPSHOT` sürümünde tut

## Sonraki beta döngüsünü başlat

Şu komutu çalıştır:

```powershell
./tools/release/start-next-beta-cycle.ps1 `
  -CurrentVersion 0.1.0-beta.1 `
  -NextVersion 0.1.0-beta.2-SNAPSHOT `
  -CreateReleaseNotesTemplate
```

Bu komut şunları yapar:

- root ve modüllerdeki `pom.xml` sürümlerini günceller
- `CHANGELOG.md` içine yeniden `Unreleased` bölümü açar
- isterse `docs/releases/` altında bir sonraki release note taslağını oluşturur

## Önerilen branch akışı

- `main` branch'i bir sonraki `-SNAPSHOT` sürümünde kalır
- kısa ömürlü işler `codex/*` branch'lerinde yapılır
- beta release tag'i gerçekten yayınlamak istediğin commit'ten kesilir

## Sonraki beta kesilmeden önce

- production evidence workflow'lerinin yeşil olduğunu doğrula
- coordination smoke sonucunun yeşil olduğunu doğrula
- release note'ları güncelle
- `CHANGELOG.md` dosyasının freeze'e hazır olduğunu kontrol et
- release bundle'ın doğru commit'ten yeniden üretildiğini doğrula

## Önemli not

Public release sonrasında `main` branch'ini önceki beta sürümünde bırakma.
Bir sonraki döngüyü hemen aç ki dependency kullanan ekipler ve dış katkıcılar aktif geliştirme hattını görebilsin.
