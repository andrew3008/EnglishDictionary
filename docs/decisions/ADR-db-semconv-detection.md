# ADR: детекция DB-span'ов — `db.system` vs `db.system.name`

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Дата | 2026-05-23 |
| Контекст | Step 2.3.1 (spike R4), `EnrichingSpanProcessor` |
| Стек | OTel Java Agent **2.27.0**, OTel BOM **1.61.0**, Spring Boot **3.5.5** |
| Re-validated | **Agent 2.28.1 / SDK 1.62.0** (train bump 2026-06-08): `DbSemconvAgentSmokeTest` 3/3 green на Gentoo Docker; поведение default legacy `db.system` без изменений |

## Проблема

Для reverse-mapping `SpanKind.CLIENT` → `platform.type=database` процессор должен распознавать авто-инструментованные JDBC-span'ы по каноническому OTel-атрибуту СУБД. В semconv произошёл переход:

- **legacy (≤1.27):** `db.system` (например `postgresql`, `h2`)
- **stable (≥1.28):** `db.system.name` (например `postgresql`, `h2database`, `microsoft.sql_server`)

Неверный выбор атрибута ломает детекцию в production: span остаётся с `platform.type=http_client`.

## Метод spike

### 1. Анализ исходников Agent 2.27.0 (primary)

Исследованы:

- `SemconvStability.java` (v2.27.0) — логика флагов `emitOldDatabaseSemconv()` / `emitStableDatabaseSemconv()`
- `DbClientAttributesExtractor.java` — какие атрибуты пишутся на `onStart`
- `JdbcAttributesGetter.java`, `DbInfo.java` — оба значения доступны в модели

**Ключевая логика (`SemconvStability`, default без opt-in):**

| `otel.semconv-stability.opt-in` | `db.system` (legacy) | `db.system.name` (stable) |
|--------------------------------|----------------------|---------------------------|
| *(не задан)* | **да** | **нет** |
| `database` | **нет** | **да** |
| `database/dup` | **да** | **да** |

Property: `OTEL_SEMCONV_STABILITY_OPT_IN` / `otel.semconv-stability.opt-in`.

### 2. Unit-тесты репозитория (secondary)

`EnrichingSpanProcessorTest` покрывает оба атрибута:

- `overridesPlatformTypeToDatabaseWhenStableDbSystemNamePresent` — `db.system.name=postgresql`
- `overridesPlatformTypeToDatabaseWhenLegacyDbSystemPresent` — `db.system=postgresql`

### 3. Live spike (PostgreSQL + Agent + Jaeger)

**Выполнен:** [`DbSemconvAgentSmokeTest`](../../platform-tracing-e2e-tests/src/test/java/space/br1440/platform/tracing/e2e/smoke/DbSemconvAgentSmokeTest.java), Agent **2.27.0**, `postgres:16-alpine`, Docker gate (Gentoo `DOCKER_HOST=tcp://192.168.100.70:2375`).

Regression gate:

```bash
./gradlew :platform-tracing-e2e-tests:test -PrunE2e --tests "*DbSemconvAgentSmokeTest*"
```

| `OTEL_SEMCONV_STABILITY_OPT_IN` | Ожидаемые атрибуты | Smoke-тест | Статус |
|--------------------------------|-------------------|------------|--------|
| *(не задан)* | `db.system` only | `agent_default_config_*` | PASS |
| `database` | `db.system.name` only | `agent_stable_semconv_*` | PASS |
| `database/dup` | **оба** | `agent_dup_semconv_*` | PASS |
| + extension JAR | `platform.type=database` | `agent_with_extension_*` | PASS |

Live spike подтверждает вывод §1 (semconv flags) на реальной JDBC-инструментации Agent'а.

Extension smoke: [`PlatformExtensionAgentSmokeTest`](../../platform-tracing-e2e-tests/src/test/java/space/br1440/platform/tracing/e2e/smoke/PlatformExtensionAgentSmokeTest.java) использует self-contained артефакт `:platform-tracing-otel-extension:agentExtensionJar` (внутри `platform-tracing-api` + `slf4j-api`): у Agent изолированный classloader на один URL, внешние JAR из каталога не подмешиваются автоматически.

## Решение

**Сценарий: оба атрибута возможны; детектировать оба с приоритетом stable.**

Реализация в `EnrichingSpanProcessor.hasDbSystemAttribute()`:

```java
return span.getAttribute(DB_SYSTEM_NAME_KEY) != null
        || span.getAttribute(DB_SYSTEM_KEY) != null;
```

Переопределение `platform.type` на `database` выполняется в `onEnding` только для `SpanKind.CLIENT`, если текущий тип `null` или `http_client` (дефолт для CLIENT на `onStart`). Явно выставленный прикладной код `platform.type` не перезаписывается.

## Production-ожидания (Platform, Agent 2.27.x, default config)

| Окружение | Ожидаемый атрибут на JDBC-span |
|-----------|-------------------------------|
| Prod без opt-in (типичный кейс) | **`db.system` only** |
| Staging с `otel.semconv-stability.opt-in=database` | **`db.system.name` only** |
| Migration mode `database/dup` | **оба** |

Значения **различаются** для некоторых СУБД (legacy `h2` → stable `h2database`; legacy `mssql` → stable `microsoft.sql_server`). Для детекции `platform.type=database` достаточно **наличия** любого из атрибутов, значение не парсится.

## Backlog

| Версия | Действие |
|--------|----------|
| v1.0 | Текущая dual-detection — **финальна** |
| v1.1 | После platform-wide включения `otel.semconv-stability.opt-in=database` — рассмотреть удаление fallback на `db.system` |
| v1.1+ | Мониторинг: доля span'ов только с legacy-атрибутом → 0% перед удалением fallback |

## Связанные артефакты

- `EnrichingSpanProcessor.java` — константы `DB_SYSTEM_NAME_KEY`, `DB_SYSTEM_KEY`
- `EnrichingSpanProcessorTest.java` — тесты обоих путей
- `platform-tracing-otel-extension` — Gradle `agentExtensionJar` (classifier `agent`) для `otel.javaagent.extensions`
- `docs/semconv-mapping.md` — таблица SpanCategory ↔ semconv

## Deploy: артефакты extension для Agent

| Артефакт Maven | Назначение |
|----------------|------------|
| `platform-tracing-otel-extension-{version}.jar` | SDK / unit-тесты / compileOnly |
| `platform-tracing-otel-extension-{version}-agent.jar` | **Production:** `-Dotel.javaagent.extensions=/path/to/*-agent.jar` |

**Почему `-agent`, а не thin JAR:** OTel Java Agent загружает extension в **изолированный classloader с одним URL**. Thin JAR без embedded deps → `NoClassDefFoundError` (spike: `org/slf4j/LoggerFactory`). Self-contained JAR — [официальный паттерн OTel extension examples](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/examples/extension/README.md) (`-all.jar` в community); classifier `agent` — эквивалент с явной Maven-семантикой.

CI: Gradle task `verifyAgentJarContents` (в lifecycle `check`) проверяет наличие `LoggerFactory` и классов `platform-tracing-api` внутри `-agent` JAR.
