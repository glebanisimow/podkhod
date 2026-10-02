# Подход

Личное офлайн-приложение для силовых тренировок под Android 10+: программа, журнал, замены при занятом
снаряде, отправка в Strava (позже). Без аккаунтов, облака и аналитики.

Статус: **этапы 0–4** (`0.4.0`): онбординг и профиль, генератор программы под инвентарь и расписание с
объяснениями, мезоцикл с разгрузкой, двойная прогрессия, чек-ин, разминочная лесенка и калькулятор блинов;
каталог из 90 упражнений с анимациями (87) и техникой, журнал подходов, таймер отдыха с сигналом при
погашенном экране, режим «Занято» с заменами и пересчётом веса, история с правкой, графики прогресса и
объём недели, бэкап в файл. Дальше — этап 5 (Strava). План и стек описаны в
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), решения — в [ASSUMPTIONS.md](ASSUMPTIONS.md), изменения —
в [CHANGELOG.md](CHANGELOG.md).

## Окружение (Windows, без Android Studio)

Уже установлено на этой машине:

- JDK: Temurin 21 в `%USERPROFILE%\.jdks\jdk-21.0.12.1+1` (`JAVA_HOME`).
- Android SDK: `%LOCALAPPDATA%\Android\Sdk` (`ANDROID_HOME`) — cmdline-tools 19.0, platform-tools,
  `platforms;android-37.0`, build-tools 37.0.0 и 36.0.0, эмулятор и образ `android-36;default;x86_64`.

Повторить на другой машине (PowerShell):

```powershell
# JDK 21 (portable zip, без прав администратора)
$info = Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
Invoke-WebRequest $info[0].binary.package.link -OutFile "$env:TEMP\jdk21.zip"
Expand-Archive "$env:TEMP\jdk21.zip" "$env:USERPROFILE\.jdks"

# Android command-line tools 19.0: в 23.0 sdkmanager заменён Android CLI, который падает на Windows
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
Invoke-WebRequest 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' -OutFile "$env:TEMP\clt.zip"
Expand-Archive "$env:TEMP\clt.zip" "$env:TEMP\clt"
New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
Move-Item "$env:TEMP\clt\cmdline-tools" "$sdk\cmdline-tools\latest"

$jdk = (Get-ChildItem "$env:USERPROFILE\.jdks" -Directory | Select-Object -First 1).FullName
[Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk, 'User')
[Environment]::SetEnvironmentVariable('ANDROID_HOME', $sdk, 'User')
$env:JAVA_HOME = $jdk; $env:ANDROID_HOME = $sdk

& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" "platform-tools" "platforms;android-37.0" "build-tools;37.0.0" "build-tools;36.0.0"
```

Проверка: `java -version` показывает 21, `echo $env:ANDROID_HOME` — путь к SDK.

## Сборка

```powershell
.\gradlew.bat :domain:test :app:testDebugUnitTest   # юнит-тесты
.\gradlew.bat :app:connectedDebugAndroidTest        # миграции, сохранность, UI-сценарий (нужен эмулятор или телефон)
.\gradlew.bat assembleDebug                         # app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat assembleRelease                       # app\build\outputs\apk\release\app-release.apk (подписан)
```

Первая сборка скачивает Gradle и зависимости (около 1–2 ГБ в `%USERPROFILE%\.gradle`).

- **debug** (`app.podkhod.debug`, «Подход (debug)») ставится рядом с основной версией. Без R8, большой.
- **release** (`app.podkhod`) — R8, около 10 МБ (из них 6,3 МБ анимации). Эту версию ставить на телефон.
- `versionCode` растёт сам с каждой сборкой (минуты с 2026-01-01 UTC), поэтому любая новая сборка
  ставится поверх старой.
- `-PallAbis` добавляет x86_64 в release, чтобы проверить его на эмуляторе. На телефон такую сборку
  ставить не нужно.

### Ключ подписи — сохрани его

Release подписывается ключом `%USERPROFILE%\.podkhod-keys\podkhod-release.jks`. Пароли лежат в
`local.properties` (`release.storeFile`, `release.storePassword`, `release.keyAlias`,
`release.keyPassword`). Файл в `.gitignore`.

**Сохрани `.jks` и пароль минимум в двух местах** (например, менеджер паролей и флешка). Без этого ключа
новую версию нельзя поставить поверх: придётся удалять приложение, а вместе с ним и все данные, если нет
бэкапа.

Новый ключ на чистой машине (только если старого нет):

```powershell
keytool -genkeypair -v -keystore "$env:USERPROFILE\.podkhod-keys\podkhod-release.jks" -storetype PKCS12 `
  -alias podkhod -keyalg RSA -keysize 4096 -validity 36500
```

## Установка на телефон (Android 15/16)

1. Скопируй `app-release.apk` на телефон: USB-кабелем в «Загрузки», через Telegram «Избранное» или облако.
2. Открой файл на телефоне из «Файлов» (или из Telegram).
3. Android спросит разрешение на установку из этого источника: «Настройки» → включи «Разрешить из этого
   источника» → назад.
4. Нажми «Установить».
5. Если появится Play Protect («Приложение не проверено» или «Неизвестный разработчик»), нажми
   «Подробнее» → «Всё равно установить». Отправлять приложение на проверку не нужно.
6. Обновление: поставь новый `app-release.apk` тем же способом. Данные сохраняются, если `applicationId`
   и ключ подписи те же.

Через USB с включённой отладкой: `adb install -r app\build\outputs\apk\release\app-release.apk`.

При первом «Начать тренировку» Android спросит разрешение на уведомления — разреши. Без него таймер в
приложении работает, но сигнал конца отдыха при погашенном экране не придёт. Звук и вибрацию сигнала
можно настроить в «Настройки» → «Уведомления».

## Бэкап данных

«Настройки → Данные → Сохранить копию данных» сохраняет всё (профиль, программу, историю, прогрессию,
настройки) в один JSON-файл через системный выбор файла: положи его в облако или перешли себе.
«Восстановить из копии» заменяет все данные на данные из файла; перед заменой текущие данные
сохраняются, и пункт «Вернуть данные до восстановления» отменяет последнее восстановление. Делай копию
перед сменой телефона и перед переустановкой. Кроме этого, работает автобэкап Android (БД и настройки;
токены Strava в него не попадают). Свои анимации из папки в копию не входят — импортируй их заново.

## Эмулятор (для проверок без телефона)

```powershell
& "$env:ANDROID_HOME\emulator\emulator.exe" -avd podkhod_api36 -no-window -no-audio -gpu swiftshader_indirect
.\gradlew.bat :app:connectedDebugAndroidTest
```

Ручные проверки через `adb`: `. .\tools\ui.ps1`, затем `Ui-Ids` (элементы экрана по test tag),
`Ui-TapId workout_log_set`, `Ui-Tap "Продолжить"`, `Ui-Shot shot.png`. Нижние шторки (например, «Занято»)
UI Automator не видит — по ним тапать координатами со скриншота.

Кадры: `adb shell dumpsys gfxinfo app.podkhod framestats | python tools/framestats.py` — медианы UI-потока и
рендера. Эмулятор рисует программно (SwiftShader), поэтому его цифры — только для сравнения сборок между
собой, не для оценки телефона.

Пересборка анимаций: `python tools/build_media/build_media.py` (см. [docs/MEDIA.md](docs/MEDIA.md)).

## Облачная сборка (когда появится GitHub)

`.github/workflows/build-apk.yml` собирает подписанный release на каждый push в `main` и прикладывает APK
к GitHub Release на тег `v*`. Пока не запускался.

1. Создай пустой **приватный** репозиторий на GitHub и запушь проект.
2. PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.podkhod-keys\podkhod-release.jks")) | Set-Clipboard`.
3. На GitHub: Settings → Secrets and variables → Actions → New repository secret.
4. `PODKHOD_KEYSTORE_BASE64` — вставь из буфера.
5. `PODKHOD_KEYSTORE_PASSWORD` и `PODKHOD_KEY_PASSWORD` — пароль из `local.properties`.
6. `PODKHOD_KEY_ALIAS` — `podkhod`.
7. Вкладка Actions → build-apk → Run workflow (или просто push).
8. Когда сборка позеленеет, открой её → Artifacts → `podkhod-release-apk` → скачай zip на телефон.
9. Распакуй и поставь APK по инструкции выше.

## Документы

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — стек, модули, схема БД, алгоритм генератора.
- [ASSUMPTIONS.md](ASSUMPTIONS.md) — допущения и отклонения от ТЗ.
- [CHANGELOG.md](CHANGELOG.md)
- [docs/STRAVA.md](docs/STRAVA.md) — настройка API-приложения Strava (этап 5).
- `tools/logo/build_logo.py` — пересобирает векторы знака (ярлык, знак, сцена) из SVG в
  `~/Downloads/ded-medved-geo`; запускать после правки исходников знака.
- [docs/MEDIA.md](docs/MEDIA.md) — источники и лицензии анимаций, конвейер сборки, свои файлы.
- [docs/screenshots/](docs/screenshots/) — экраны этапа 3.
