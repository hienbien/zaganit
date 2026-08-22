# CloudTVStream

CloudStream için Türkçe provider eklentileri projesi.

Projede şu anda yalnızca `Filmmirasim` modülü bulunur. Provider, T.C. Kültür ve
Turizm Bakanlığı Film Mirasım arşivindeki kategorileri, aramayı, film detaylarını
ve yayın bağlantılarını CloudStream içinde kullanılabilir hale getirir.

## Derleme

Windows üzerinde proje kökünde çalıştırın:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-23"
$env:ANDROID_HOME = "C:\Program Files (x86)\Android\android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = "$PWD\.gradle-user-home"
.\gradlew.bat --no-daemon Filmmirasim:make
```

Oluşan paket:

```text
Filmmirasim/build/Filmmirasim.cs3
```

## Repository

GitHub Actions başarılı çalıştıktan sonra kullanılacak repository adresi:

```text
https://raw.githubusercontent.com/hienbien/zaganit/refs/heads/builds/repo.json
```

Bu adres doğrulandıktan sonra `https://cutt.ly/zaganit` yönlendirmesi
oluşturularak `zaganit` kısa kodu aktif edilecektir.

## Geliştirici

`zaganit`

Bu proje içerik barındırmaz. Kullanım sırasında kaynak sitenin koşullarına ve
uygulanabilir yasalara uyulmalıdır.
