# Ai-Turbo — инструкции для Claude Code и агентов

Проект: Kotlin + Ktor + Koog + Kodein + Exposed ORM (стек зафиксирован в CODE_STYLE.md).

## Обязательные правила для ВСЕХ агентов

- Код-стайл: соблюдать `CODE_STYLE.md` в корне. Дифф проверяется агентом `code-style-checker`.
- **Билд/тесты — только вердикт**: запускай `./gradlew test` (или `./gradlew clean test`) и читай
  из вывода ТОЛЬКО `BUILD SUCCESSFUL` / `BUILD FAILED`, при падении — первые 20–40 строк ошибок.
  Запрещено читать или копировать в контекст весь вывод сборки, тестов и серверных логов.
- Серверные логи смотреть только через grep по `req=`/`stage=` или хвост файла `logs/ai-turbo.log`.
- Тесты — только офлайн (фейки, MockEngine, замороженный Clock, LogCapture): без реальных БД,
  без живого LLM, без сети.
- Секреты (DEEPSEEK_API_KEY, STAGE_DB_PASSWORD, пароль локальной БД) — только в git-ignored `.env`;
  в отслеживаемые файлы, логи и ответы API они не попадают.
- Коммит/пуш — только по явной просьбе пользователя.
- Спецификации фич — через `/feature-design` в `docs/features/<slug>/`, реализация — через
  `/feature-implementation` строго по `spec.md`.

## Команды

```bash
./gradlew test        # офлайн-тесты (вердикт: BUILD SUCCESSFUL/FAILED)
./gradlew run         # сервер на http://localhost:8080 (логи: консоль + logs/ai-turbo.log)
```

## Локальные инструменты агентов

`.claude/agents/`:
- `code-style-checker` — проверка диффа по CODE_STYLE.md;
- `reviewer-correctness` — баги и корректность;
- `reviewer-deduplication` — дублирование/избыточность/симплификация;
- `reviewer-git` — диффы, git-состояние, секреты.
