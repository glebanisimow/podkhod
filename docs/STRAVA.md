# Strava (этап 5, отправка ещё не реализована)

Интеграция необязательная и односторонняя: приложение только отправляет тренировки и ничего не читает
из Strava. Пока это план. Перед реализацией каждый пункт заново сверяется по документации Strava, и в
отчёте этапа 5 будет указано, что изменилось.

## Что понадобится от тебя

1. Подписка Strava: без неё нельзя создать API-приложение.
2. strava.com → Settings → My API Application → создать приложение:
   - Application Name — любое нейтральное (не «Подход Strava»);
   - Category — Training;
   - Authorization Callback Domain — `localhost` (точное значение уточню на этапе 5 после проверки
     возврата кода на телефоне).
3. Client ID и Client Secret. **В репозиторий не кладутся:** либо `local.properties`
   (`strava.clientId`, `strava.clientSecret`), либо ввод в настройках приложения.

## Сверено на этапе 4 (2 октября 2026)

Источники: developers.strava.com/docs/uploads, /docs/changelog, /docs/reference; FIT SDK profile 21.217.

- `POST /uploads`, `multipart/form-data`, scope `activity:write`; `data_type`: `fit`, `tcx`, `gpx`, `json`
  (и `.gz`); поля `name`, `description`, `sport_type`, `external_id`. Статус — `GET /uploads/{id}`.
- **JSON для силовых** (changelog 21 мая 2026; только WeightTraining, HighIntensityIntervalTraining,
  Workout, Crossfit): `version` = "1.0", `start_time` (ISO 8601 с зоной), `utc_offset` (с), `elapsed_time`,
  `sets[]`; необязательно `active_time`, `total_calories`, `creator`, `streams`. Подход: `exercise_type`
  (обязательно, например `BARBELL_BENCH_PRESS`, у каждой категории есть `*_GENERIC`), `repetitions`,
  `weight` (всегда кг), `duration` (с), `start_time`. Признака разминки или отдыха в подходе нет.
- **FIT**: сообщение `set` (225) — `timestamp` (конец), `duration` (мс), `repetitions`, `weight` (uint16,
  1/16 кг), `set_type` (0 отдых, 1 активный), `start_time`, `category`, `category_subtype`. Strava
  читает из FIT упражнение, повторы, вес, длительность и начало подхода.
- Дубликаты отклоняются; `external_id` — `podkhod-<id тренировки>`.
- Новый базовый URL `https://api-v3.strava.com` **доступен** с 4 января 2027; о выключении старого
  в changelog не сказано.
- Не проверено: как Strava показывает подходы отдыха из FIT, лимиты числа подходов и размера файла,
  условия API Agreement о кэше и ИИ (сверить перед этапом 5).

Что уже есть в приложении (`StrengthExport`): категория у каждого из 90 упражнений (`strava` в
`catalog.json`), сборка активности (только рабочие подходы, оценка начала подхода) и тело JSON.
Хранимых данных достаточно для обоих форматов: вес в кг, повторы или секунды, время окончания подхода.
Время начала подхода не хранится и оценивается — если Strava будет показывать его криво, на этапе 5
начнём записывать начало подхода при старте отсчёта.

## Что планируется (по ТЗ, перепроверить)

- OAuth 2.0 через `https://www.strava.com/oauth/mobile/authorize`, только scope `activity:write`;
  проверка поля `scope` в ответе.
- Access token живёт 6 часов, refresh token меняется при каждом обновлении — сохраняется самый свежий.
- Отправка: JSON-формат силовых тренировок с подходами (changelog от 21 мая 2026) или FIT с сообщениями
  `set`; резервно — `POST /activities` с описанием подходов в тексте.
- `POST /uploads` → опрос `GET /uploads/{id}` не чаще раза в секунду; `external_id` = id тренировки.
- Очередь WorkManager с экспоненциальной паузой. 401 → refresh; 429 → отложить; 5xx → повторить.
- Базовый URL в конфиге: `https://www.strava.com/api/v3`, с 4 января 2027 — `https://api-v3.strava.com`.
- Отзыв: `POST /oauth/revoke`, удаление токенов и всего полученного от Strava.

## Ограничения API Agreement и Policy 2026

- Данные Strava показываются только владельцу; не передаются третьим лицам и в ИИ-модели.
- Нельзя использовать в ИИ, аналитике и агрегации, поэтому импорта активностей в приложении нет.
- Кэш данных Strava — не дольше 7 дней; храним только токены и статус отправки.
- Название и значки Strava не используются в названии и иконке приложения; кнопка подключения — по
  Brand Guidelines.
