# Ai-Turbo Code Style

Единый код-стайл проекта. Любой агент, меняющий код, обязан следовать этому документу;
агент `code-style-checker` проверяет каждый дифф по нему.

## Стек

Только: **Kotlin, Ktor, Koog, Kodein, Exposed ORM**. Никакого Spring, Koin, сырого JDBC.
Логирование — logback (инфраструктура, не бизнес-стек). Секреты — только в git-ignored `.env`.

## Структура пакетов

| Пакет | Содержимое |
|---|---|
| `com.aiturbo.tools` | **Все Koog-тулы** (`GetWeatherTool`, `FindFuelingTool` и новые) — ни один тул не лежит в доменном пакете |
| `com.aiturbo.weather` / `com.aiturbo.fueling` | доменные клиенты, агенты, рендеры, валидация |
| `com.aiturbo.db` | Exposed-репозитории и конфиги БД |
| `com.aiturbo.llm` | Koog-клиенты, модели, роутинг провайдеров |
| `com.aiturbo.plugins` | Ktor-плагины и маршруты |
| `com.aiturbo.log` | трассировка |

## БД — только Exposed ORM

- Никаких `DriverManager`, `PreparedStatement`, `ResultSet` в коде.
- Таблицы описываются объектами `Table`; запросы — DSL (`select`, `insert`, `deleteWhere`).
- Параметры — только bind-значения через DSL (инъекции нет по построению).
- Блокирующие вызовы — только внутри `withContext(Dispatchers.IO)` / `newSuspendedTransaction`.

## DI — только Kodein

- Модули: `kodein { bind<...>() with singleton { ... } }`; никаких аннотаций Koin.
- Конфиги (`DeepseekConfig`, `DbConfig`, `StageDbConfig`, …) читаются из application.conf/env/.env
  и биндятся как singleton-ы.

## Инструменты (Koog tools)

- Класс тула в `com.aiturbo.tools`, имя/описание — из JSON-ресурса `src/main/resources/tools/*.json`
  (файл — источник истины, fail-fast при старте).
- Аргументы — `@Serializable` data class с `@LLMDescription`; результат — компактный текст
  (структурированный, без JSON в ответе пользователю).
- Тул не логирует секреты и не выполняет запись в чужие БД.

## Логирование

- Единый логгер `com.aiturbo.trace` (`stage=…`, `req=<id>`), обрезка тел до 4096 символов,
  секреты (ключи API, пароли БД) в логах запрещены — никогда не передавать конфиг-объекты в TraceLog.
- `stage=db` для результата работы с БД; порядок цепочки: inbound → deepseek-request →
  deepseek-response → db → tool → deepseek-request → deepseek-response → outbound.

## Тесты

- Только офлайн: фейки, `MockEngine`, замороженный `Clock`, `LogCapture`; без реальных БД/LLM/сети.
- Один тест — один сценарий; имена — читаемые фразы в backticks.
- Существующие тесты не удалять; правки только там, где меняется конструктор/контракт.

## Дисциплина билда и тестов (для всех агентов)

- Прогон: `./gradlew test` (или `./gradlew clean test`). Читать из вывода **только** итог:
  строки `BUILD SUCCESSFUL` / `BUILD FAILED` и, при FAILED, блоки ошибок (`e: …`, `FAILED`).
- Запрещено копировать в контекст весь вывод сборки/тестов/серверных логов — нужен вердикт,
  а не дамп. При падении — взять 20–40 строк вокруг первой ошибки и всё.
- Серверные логи для отладки — только через grep по конкретным `req=`/`stage=`, не целиком.

## Git

- Коммит/пуш — только по явной просьбе пользователя; сообщения в стиле репозитория.
- В ревью всегда проверяется `git diff` между коммитами: что реально изменилось,
  нет ли случайных файлов, нет ли секретов.
