# Bluetooth Internet Proxy (J2ME + Android)

Проект позволяет устройствам на Java ME (например, Nokia X2-00) выходить в современный интернет через Bluetooth-прокси, размещенный на Android-устройстве. Android-сервер берет на себя установление защищенных соединений (TLS 1.2/1.3), в то время как телефон отправляет обычные HTTP-запросы.

## 🏗 Архитектура сборки

Проект использует **GitHub Actions** в качестве билд-фермы. Это позволяет собирать как J2ME MIDlet (требует JDK 8), так и Android APK (требует JDK 17 + Android SDK) без необходимости устанавливать тяжелые IDE локально.

### Как получить готовые файлы:
1. Сделайте `git push` в этот репозиторий.
2. Дождитесь завершения workflow "Build BT Proxy".
3. Скачайте артефакты через GitHub CLI:
```bash
   gh run list --workflow=build.yml
   gh run download <RUN_ID> -n j2me-midlet -D ~/bt-proxy/artifacts/j2me
   gh run download <RUN_ID> -n android-apk -D ~/bt-proxy/artifacts/android
```

## ⚠️ Важное примечание про Windows-сервер (Fallback)

Если вы решите использовать Python-скрипт на ПК вместо Android-приложения:
- На **Windows 10/11** использование нативного сокета `AF_BTH` не регистрирует сервис в SDP автоматически. J2ME-клиент с `searchServices()` не найдет устройство.
- **Решение:** Либо используйте библиотеку `PyBlueZ` (которая корректно регистрирует SDP), либо найдите свободный RFCOMM-канал перебором в Python и пропишите его жестко в J2ME-клиенте (`btspp://MAC:КАНАЛ`), отключив поиск сервисов.

## 📱 Использование

1. Установите `app-debug.apk` на Android-устройство.
2. Запустите приложение и разрешите доступ к Bluetooth.
3. Узнайте MAC-адрес Bluetooth вашего Android-устройства (Настройки -> О телефоне).
4. В файле `j2me/src/BTProxyMidlet.java` замените `BT_MAC` на реальный адрес.
5. Соберите проект через CI, перенесите `BTProxy.jar` и `BTProxy.jad` на Nokia X2-00 и запустите.
