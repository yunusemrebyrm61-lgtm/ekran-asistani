# Ekran Asistanı

Android için yüzen butonlu ekran asistanı: butona dokun → o anki ekran Claude'a gider → cevap ekranın altında çıkar.

## Kurulum

1. **Releases → latest** altındaki `EkranAsistani.apk` dosyasını telefondan indir ve kur
   (tarayıcıya "bilinmeyen uygulamaları yükle" izni vermen gerekebilir).
2. Uygulamayı aç, Claude API anahtarını gir, **Anahtarı test et**'e bas.
3. **Pil kısıtlamasını kaldır**'a bas (Samsung'un arka planda kapatmaması için).
4. **Başlat**'a bas, "üstte göster" ve ekran paylaşımı izinlerini ver.

## Kullanım

- **Dokun:** ekrandaki soruyu sor
- **Sürükle:** butonu taşı
- **Basılı tut:** cevap panelini gizle/göster
- Panelde **↻ Tekrar:** aynı ekran görüntüsünü yeniden gönder
- Bildirimdeki **Durdur:** uygulamayı kapat

API anahtarı yalnızca telefonda saklanır; bu repoda yoktur.

Her `main` gönderiminde GitHub Actions APK'yı otomatik derleyip "latest" sürümüne koyar.
