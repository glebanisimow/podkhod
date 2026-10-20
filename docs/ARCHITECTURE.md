# Архитектура «Подход»

Личное офлайн-приложение для силовых тренировок под Android. Без бэкенда, аккаунтов, аналитики и
рекламы. Сеть нужна только для отправки тренировок в Strava (этап 5) и, возможно, для догрузки медиа.

## Стек (сверено 2 октября 2026)

| Компонент | Версия | Где сверено |
|---|---|---|
| Android Gradle Plugin | 9.4.1 | `dl.google.com/.../com/android/tools/build/gradle/maven-metadata.xml` |
| Совместимость AGP 9.4 | Gradle ≥ 9.6, JDK ≥ 17, Build Tools 36, API до 37 | developer.android.com/build/releases/gradle-plugin |
| Gradle (wrapper) | 9.8.0 | services.gradle.org/versions/current |
| Kotlin | 2.4.20 (встроенный Kotlin AGP 9, без плагина `kotlin-android`) | Maven Central; developer.android.com/build/migrate-to-built-in-kotlin |
| KSP | 2.3.12 (нужен вместо kapt: kapt несовместим со встроенным Kotlin) | GitHub releases google/ksp |
| Compose BOM / Material 3 | 2026.09.00 | Google Maven |
| Compose Multiplatform | 1.12.1 (Material 3 — 1.9.0; на Android сводятся к androidx-артефактам BOM) | Maven Central, 8 октября 2026 |
| Room | 2.8.5 KMP + Room Gradle plugin (экспорт схем в `shared/schemas`); `sqlite-bundled` 2.6.2 на iOS | Google Maven |
| Navigation Compose | 2.10.2 на Android; в `:shared` — JetBrains 2.9.2 (типобезопасные маршруты на kotlinx.serialization) | Google Maven, Maven Central |
| Lifecycle (ViewModel) | JetBrains 2.11.0 в `:shared` | Maven Central |
| DataStore Preferences | 1.2.1 | Google Maven |
| kotlinx-datetime / kotlinx-io | 0.8.0 / 0.9.1 | Maven Central, 8 октября 2026 |
| Koin (DI) | BOM 4.2.2 | Maven Central |
| kotlinx.serialization / coroutines | 1.11.0 / 1.11.0 | Maven Central |
| compileSdk / targetSdk / minSdk | 37 (Android 17) / 37 / 29 (Android 10) | `repository2-3.xml`: `platforms;android-37.0` стабильная |
| JDK | Temurin 21.0.12 LTS | api.adoptium.net |

Почему так:

- **Koin, а не Hilt.** Нет кодогенерации, проще сборка и меньше времени на KSP. Для одного Android-модуля
  его гарантий хватает. Граф зависимостей описан в `di/AppModule.kt`.
- **Встроенный Kotlin AGP 9.** Плагин `org.jetbrains.kotlin.android` больше не применяется. Версию Kotlin
  задают плагины `kotlin.plugin.compose`/`serialization`, которые кладут KGP 2.4.20 в classpath.
- **Coil 3.6.3 (`coil-compose`, `coil-gif`)** показывает анимированный WebP/GIF через системный
  `AnimatedImageDecoder` (API 28+). Статичный кадр — тот же файл через `BitmapFactoryDecoder`.
  `coil-gif` есть только для Android: на iOS Coil декодирует через Skia и показывает первый кадр.
- **WorkManager** (2.12.x) подключается на этапе 5 для очереди Strava, **Ktor/OkHttp** там же.

## Модули

Три модуля: логика, общее приложение и Android-оболочка. Отдельный `:domain` даёт быстрые JVM-тесты и
запрещает тащить платформу в логику на уровне компилятора; `:shared` — всё приложение один раз для
Android и iPhone.

- `:domain` — Kotlin Multiplatform без Android и без JVM-only API (таргеты `jvm`, `iosArm64`,
  `iosSimulatorArm64`; даты — `kotlinx-datetime`): генератор программы, прогрессия, округление весов,
  калькулятор блинов, расчёт 1ПМ, логика замен. Код лежит в `commonMain`, JUnit-тесты — в `jvmTest`
  (`./gradlew :domain:jvmTest`). `./gradlew :domain:compileKotlinIosArm64` проверяет, что общий код
  собирается под iPhone; работает и на Windows, Mac нужен только для сборки самого приложения.
- `:shared` — Kotlin Multiplatform (`android`, `iosArm64`, `iosSimulatorArm64`): Compose Multiplatform
  интерфейс, Room KMP, DataStore, Koin, Coil, бэкап. Строки, векторные картинки и JSON-ассеты лежат в
  `shared/src/commonMain/composeResources` (`values/`, `drawable/`, `files/`), доступ через `Res`.
  Платформенные места — пакет `platform`: интерфейсы `HoldSignals`, `WorkoutTracker`, `AppInfo`, `AppDirs`
  и `expect`-функции для системных диалогов, «не гасить экран», уведомлений и анимаций; реализации в
  `androidMain` и `iosMain`. Транзакции и сырой SQL бэкапа — тоже `expect`: на Android остались прежние
  `withTransaction` и `SupportSQLiteDatabase`, на iOS — соединения Room с `BundledSQLiteDriver`.
  Множественные формы — `ui/format/RuPlurals.kt`, а не ресурсы: форма всегда по русским правилам.
- `:app` — Android-оболочка: `PodkhodApp` (Koin, загрузчик Coil), `MainActivity`, `AndroidModule`
  (база через `Room.databaseBuilder` с системным SQLite, DataStore `settings`, `filesDir`), таймер
  отдыха (`WorkoutService`, foreground service) и звуки удержания (`AndroidHoldSignals`). Ресурсы
  `app/src/main/res` — только иконка, тема окна и строки уведомлений.
- `iosApp/` — Xcode-проект (генерируется XcodeGen из `project.yml`): SwiftUI-обёртка вокруг
  `MainViewController()` из `:shared`. Сборка и установка — на Mac.

Пакеты в `:shared`: `data/db` (Room), `data/catalog` (JSON каталога и шаблонов, валидатор, загрузка в БД),
`data/program`, `data/workout`, `data/profile`, `data/plan` (репозитории), `data/settings` (DataStore),
`di`, `platform`, `ui/theme`, `ui/navigation`, `ui/components`, `ui/format`, `ui/<feature>` (экран + ViewModel рядом).
JSON-файлы ниже (`assets/...`) лежат в `shared/src/commonMain/composeResources/files/`.

## Тренировка и таймер отдыха (этап 1)

- **Источник истины — БД.** Активная тренировка — строка `workout_session` со `status = active`,
  `current_position`, `rest_started_at`, `rest_ends_at`. «Подход выполнен» в одной транзакции пишет
  `set_log`, по `WorkoutFlow.afterSet` (`:domain`) выбирает следующее упражнение и отдых и сохраняет
  их в сессию. Экран и сервис только читают это состояние, поэтому после убийства процесса ничего
  не теряется: при следующем запуске «Сегодня» предлагает продолжить.
- **`WorkoutService`** — foreground service `specialUse`, живёт, пока есть активная тренировка.
  Уведомление показывает обратный отсчёт системным хронометром (`setChronometerCountDown`), поэтому
  его не нужно обновлять каждую секунду. На время отдыха берётся partial wakelock с таймаутом
  «остаток + 15 с», по окончании публикуется уведомление «Отдых окончен» на канале с высоким приоритетом.
  После sticky-перезапуска системой сервис сам возвращается в foreground.
- **Каталог** лежит в `assets/catalog.json` и заливается в Room при росте `catalogVersion` (хранится в
  `app_meta`). Упражнения только обновляются и не удаляются, чтобы история не ссылалась в пустоту.
- **Программа.** До создания профиля — шаблон `fullbody_ab` из `assets/templates.json`; после онбординга — программа генератора (см. ниже).

## Медиа и «Занято» (этап 3)

- **Медиа.** `assets/media/*.webp` + `assets/media_manifest.json`, собираются `tools/build_media` (см.
  `docs/MEDIA.md`). `MediaRepository` отдаёт для упражнения свой файл пользователя (`files/media_user`),
  иначе встроенный. `ExerciseAnimation` запускает анимацию только на активной странице пейджера при
  `Lifecycle ≥ STARTED`; при выключенной настройке или системном масштабе анимаций 0 декодирует первый кадр.
  Техника — `assets/technique.json`, заливается в `exercise.technique_json` вместе с каталогом.
- **Логика «Занято»** — в `:domain/busy`, без Android:
  - `Substitution.options` — пул из цепочки замен исходного и цепочек его замен. Отсекаются упражнения,
    уже стоящие в тренировке, и требующие недоступного оборудования. Сортировка: не нужен занятый снаряд →
    тот же паттерн и основные мышцы → не перегружает мышцу сверх `sessionMuscleCap` подходов за тренировку →
    меньший риск занятости → близкая нагрузка → порядок в цепочке. Берутся первые 3.
  - `Substitution.estimate` — своя история → пересчёт по e1RM и соотношениям силы семейства × 0,9 с
    округлением вниз до шага → «свой вес» → «подбери вес на разминочном подходе».
  - `BusyQueue.moveToEnd` переносит слот (с партнёрами по суперсету) в конец; `whileWaiting` ищет
    ближайшее несделанное упражнение, которому не нужен занятый снаряд, и до 3 коротких заполнителей.
- **Параметры** — `assets/substitution.json`: станции, недоступное оборудование, лимит подходов на мышцу,
  запас, семейства и соотношения силы, заполнители. Проверяется `CatalogValidator.validateSubstitution`
  в JVM-тестах.
- **Запись.** `WorkoutRepository.replaceExercise` в одной транзакции меняет слот, а если подходы уже
  были — делит его на два. «Навсегда» меняет и `planned_exercise`. Каждое действие пишется в `busy_event`.

## Схема БД (версия 4; таблицы Strava — этап 5)

Все веса хранятся в килограммах (`Double`), время — epoch millis UTC, длительности — секунды.
Каждое изменение схемы: новая версия, `Migration`, JSON схемы в `app/schemas`, тест миграции.
Деструктивная миграция запрещена. Миграции 1 → 2 → 3 → 4 — ручные `Migration` в `Migrations.kt` (SQL, который
сгенерировал Room для бывших `AutoMigration`: Kotlin/Native не линкует `AutoMigration` в аннотации). 3 → 4 —
только новые таблицы и колонки с `DEFAULT`.

Каталог (заливается из `assets/catalog.json` при смене `catalog_version`):

- `exercise` — `id` (slug), `name_ru`, `name_en`, `pattern` (squat/hinge/h_push/v_push/h_pull/v_pull/
  lunge/isolation/core/carry), `kind` (compound/accessory/isolation), `measure` (reps/seconds),
  `load` (external/bodyweight_plus/none), `difficulty`, `fatigue`, `occupancy_risk` (low/medium/high),
  `rep_min`, `rep_max`, `rest_sec`, `step_kg`, `technique_json`, `strava_category`, `media_path`,
  `reviewed`, `catalog_version`.
- `exercise_equipment`, `exercise_muscle(…, role)`, `exercise_tag`, `exercise_substitute(…, rank)`.

Профиль и инвентарь (**версия 4**):

- `profile` — одна строка (`id = 1`): цель, приоритетные группы, тренировок в неделю, минуты, удобные дни,
  дни длинной нагрузки и кардио, стиль, длина мезоцикла, вес грифа, оборудование, теги ограничений и
  текст, масса тела, исключённые упражнения, единицы (пока только `kg`). Списки — строки кодов через
  запятую: их не ищут по элементу, а схема остаётся плоской.
- `plate(weight_kg, pairs, color?)`, `kettlebell(weight_kg, count)`, `dumbbell(weight_kg, pairs)`.
- `baseline_lift(movement, weight_kg?, reps?, e1rm_override_kg?, skipped)`.
- `progression_state(exercise_id, working_weight_kg?, misses_in_row, e1rm_history_json, note?, updated_at)` —
  ключ по упражнению, а не по программе: прогресс переживает пересборку программы.

Программа:

- `program(id, name, source (template/generated), template_id?, status, created_at, next_day_index,
  weeks, week_index, mesocycle_index, style?, explanation_json?, deload_offer?, deload_sessions_left)`.
- `program_day(id, program_id, seq_index, title)` — шаблон дня; неделя мезоцикла применяется при старте.
- `planned_exercise(id, program_day_id, position, exercise_id, superset_group, sets, rep_min, rep_max,
  rir_min, rir_max, rest_sec, target_weight_kg, note, locked, role?, marker, explanation_json?)`.

Журнал (сохраняется после каждого подхода, переживает убийство процесса):

- `workout_session(id, program_id?, program_day_id?, day_index?, title, started_at, finished_at?, status,
  current_position, rest_started_at?, rest_ends_at?, feeling?, note?, week_index?, deload, checkin_sleep?,
  checkin_legs?, checkin_ready?, checkin_long?, plan_note?, progress_json?)`.
- `session_exercise(id, session_id, position, exercise_id, planned_exercise_id?, superset_group, sets,
  rep_min, rep_max, rir_min, rir_max, rest_sec, target_weight_kg, note)`.
- `set_log(id, session_exercise_id, position, kind (warmup/work/failure/drop), weight_kg, reps,
  duration_sec, rir, completed_at)`.
- `busy_event(id, session_id?, exercise_id, action, replacement_id?, created_at)`.
- `app_meta(meta_key, meta_value)`.

Strava (этап 5) будет хранить только токены (в отдельном зашифрованном DataStore вне бэкапа) и статус
отправки на `workout_session`.

## Генератор, план тренировки и прогрессия (этап 2)

Вся логика — в `:domain/plan` и `:domain/weights`, без Android. Детерминированно: одинаковые профиль,
каталог, конфиг и история дают одинаковую программу. Параметры — `assets/generator_config.json`;
пользователь может экспортировать его, поправить и импортировать (Настройки → Программа). Файл
проверяется `CatalogValidator.validateGeneratorConfig`, с ошибками не применяется.

1. **Сплит** по числу дней: 2 → полное тело A/B, 3 → A/B/C, 4 → верх/низ ×2. Неделя программы — полный
   круг дней, а не календарная неделя: число тренировок в календарной неделе у пользователя меняется.
2. **Слоты.** Число упражнений — по стилю и минутам (`exercisesPerSession`, `timing`). Сначала базовые,
   затем вспомогательные и изоляция. Кандидат отсекается по инвентарю, тегам ограничений, «не делаю» и
   исключениям; сортировка — паттерн и мышцы, приоритет оборудования, риск занятости, утомляемость,
   затем `id`. Если строгий фильтр не дал никого, условия ослабляются в три уровня, и это пишется в объяснение.
   Зафиксированные пользователем слоты переносятся без изменений.
3. **Объём.** Недельные рабочие подходы по группам (основная мышца = 1, вторичная — по
   `secondaryWeight`/`secondaryWeightByGroup`). Раунды: срезать сверх жёсткого максимума, добрать до
   минимума, подрезать сверх мягкой цели. Приоритетным группам +`priorityBonus`. Что не удалось добрать,
   показывается как «недобор» в «Почему так?» и в диалоге изменений.
4. **Суперсеты** — в интенсивном стиле для вспомогательных и изоляции, без двух занятых станций сразу.
5. **Веса.** Старт = e1RM (из базовых весов или истории, Эпли и Бжицкий в среднем) → процент под повторы
   и RIR × `startFactor` → округление вниз до инвентаря (`Loads.roundDown`: штанга — по таблице блинов,
   гири и гантели — ближайшая не тяжелее).
6. **План на сегодня** (`SessionPlanner`, при старте тренировки):
   - разгрузка (последняя неделя мезоцикла или принятая внеплановая) — подходы ×0,55, вес ×0,9;
   - чек-ин: один ответ «плохо» — запас +1; «очень тяжело» — запас +1 и −1 подход;
   - уставшие ноги или длинная нагрузка завтра — у ног −1 подход, запас +1, тяжёлые базовые ×0,9.
7. **Прогрессия** (`Progression`, после «Завершить»): все рабочие подходы на верхней границе без
   провала запаса — следующий шаг (+2,5 кг жимы и тяги, +5 кг присед и становая, следующая гиря,
   у «своего веса» — повторы или усложнение). Две тренировки подряд ниже нижней границы — −10 %.
   В разгрузку прогрессии нет.
8. **Внеплановая разгрузка** (`DeloadAdvisor`) — только предложение. Повод: e1RM базового упражнения падает
   2 тренировки подряд или чек-ин «очень тяжело» 2 раза подряд. Принятая длится один круг дней.
9. **Мезоцикл** 4–8 недель (по умолчанию 6 = 5 + разгрузка). «Новый мезоцикл» меняет индекс ротации:
   вариации меняются, маркерные упражнения (первые базовые) остаются для сравнения.
10. **Пересчёт профиля** строит новую программу и `ProgramDiff`: что добавилось, ушло, сменило подходы
    или вес. В редакторе профиля это показывается до применения. Неделя и разгрузка внутри мезоцикла
    сохраняются.
11. **Разминка** (`Warmup`): пустой гриф ×8–10, ~45 % ×5, ~65 % ×3, ~82 % ×1–2 с округлением к блинам;
    перед вспомогательными — 0–2 лёгких подхода (настройка). Калькулятор блинов (`PlateTable`) — точный
    набор либо два ближайших набираемых веса снизу и сверху.
12. **Адаптация по истории** (`AdaptivePlanner`, `PlanRepository.refreshAdaptivePlan`). При генерации
    в `explanation_json` сохраняется неизменяемая основа (`baseline`), версия планировщика и ключ настроек.
    После запуска, «Завершить», отзыва, правки или удаления истории, пропуска дня и смены настроек план
    пересчитывается заново от основы, а не от вчерашнего плана, поэтому правки не копятся. Правила:
    два тяжёлых выхода упражнения подряд за 28 дней — −1 подход (не больше двух на день); недобор
    в текущем круге — до +2 подходов на день, по одному на упражнение, в пределах времени и максимума
    группы; после облегчённой или тяжёлой тренировки недобор не догоняется; новый круг начинается без
    долга. Закреплённые слоты и ручные подходы не трогаются. Смена версии или настроек (минимум подходов,
    разминка, отдых в суперсете, конфиг) перестраивает программу целиком: старая архивируется, история
    архивных ревизий того же мезоцикла учитывается. Пока идёт тренировка, план не меняется.
    `Workload` — единый расчёт объёма, времени и «тяжести» дня для генератора, адаптации и «Почему так?».

## Бэкап, прогресс и правка истории (этап 4)

- **Бэкап** (`data/backup`). `BackupRepository.export` читает каждую пользовательскую таблицу
  (`BackupFormat.TABLES`, в порядке вставки) как строки «колонка → значение» через `SupportSQLiteDatabase`,
  добавляет настройки и свой конфиг генератора. Незавершённые тренировки отбрасываются
  (`BackupFormat.withoutActiveSessions`). `read` проверяет формат, версию схемы (не новее текущей), имена
  таблиц и наличие упражнений в каталоге. `restore` в одной транзакции удаляет все пользовательские
  таблицы (дети раньше родителей) и вставляет строки, беря только колонки, которые есть в текущей таблице:
  у колонок, добавленных автомиграциями, есть значения по умолчанию, поэтому копия старой схемы
  восстанавливается без конвертации. **Ручная миграция, которая переименует или разобьёт колонку, обязана
  добавить конвертацию в `BackupFormat` по `schemaVersion`.** Перед заменой текущее состояние пишется в
  `filesDir/backup/before_restore.json` — это отмена последнего восстановления.
- **Прогресс** (`domain/stats/ProgressStats`, `data/stats/ProgressRepository`). Точка графика — лучшее за
  тренировку (e1RM по Эпли и Бжицкому, верхний вес, повторы, секунды). Объём недели — рабочие подходы
  по группам с тем же `GeneratorConfig.credit`, что у генератора, по `week_index` тренировок программ
  текущего `mesocycle_index`; цель — `ProgramExplanation.volume` активной программы.
- **Правка истории.** `WorkoutRepository.updateFinishedSet / deleteFinishedSet / addFinishedSet /
  deleteFinished` в одной транзакции меняют журнал и вызывают `PlanRepository.recomputeProgression`:
  состояние прогрессии затронутых упражнений строится заново по всем завершённым тренировкам по порядку
  (`progressSession` — общий код с `afterFinish`), строки прогрессии в тренировках начиная с правленой
  переписываются, рабочие веса активной программы обновляются.
- **Экспорт под Strava** (`domain/export/StrengthExport`). Тренировка → `StrengthActivity` (рабочие
  подходы, оценка начала подхода, активное время) → тело JSON-загрузки Strava. Категория упражнения —
  поле `strava` в каталоге (`ExerciseCategory`, с номером FIT `exercise_category`). Отправки пока нет.

## Сборка и версии

- `versionCode` = минуты с 2026-01-01 UTC на момент сборки, поэтому строго растёт с каждой сборкой,
  локальной и CI. Переопределяется через `-PversionCode=<n>`. `versionName` семантический, `0.<этап>.<патч>`.
- Debug: `applicationId` `app.podkhod.debug`, ставится рядом с release.
- Release: R8 и сжатие ресурсов, подпись ключом вне репозитория (см. README), ABI arm64-v8a и
  armeabi-v7a (нативные только вспомогательные .so из AndroidX).
- Конфигурационный кэш Gradle выключен намеренно: `versionCode` читается на этапе конфигурации.
