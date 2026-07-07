# ADR: MDC трассировки через OTel Agent + platform-logging (Wave 2)

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Дата | 2026-05-25 |
| Контекст | Wave 2 рефакторинга; `suppress-micrometer-tracing=true`; co-use `spring-boot-starter-platform-logging` |
| Стек | Spring Boot **3.5.5**, OTel Java Agent **2.27.x**, platform-logging **1.0.4+** |

## Проблема

При production-конфигурации `platform.tracing.suppression.suppress-micrometer-tracing=true`:

- HTTP Micrometer Observations становятся **NOOP** (см. [ADR-suppress-micrometer-tracing.md](./ADR-suppress-micrometer-tracing.md));
- `micrometer-tracing-bridge-otel` **не** публикует `traceId`/`spanId`/`traceFlags` в SLF4J MDC на HTTP-boundary threads;
- `spring-boot-starter-platform-logging` **только читает** MDC (`%maskedMDC`, `LogstashEncoder`) и ожидает ключи **`traceId`**, **`spanId`**, **`traceFlags`** (camelCase, v1 contract).

Interim-зависимость `micrometer-tracing-bridge-otel` (до Wave 2c — транзитивная `api`) не покрывала production path с Agent + suppress; с Wave 2c — `compileOnly`, opt-in для dev — [MIGRATION.md](../MIGRATION.md).

## Разделение ответственности

| Компонент | Роль |
|-----------|------|
| **platform-tracing** | Span'ы (Agent + extension), suppress, Reactor/async propagation, `platform.remote.service` в MDC |
| **OTel MDC writer** | Запись `traceId`/`spanId`/`traceFlags` в SLF4J MDC |
| **platform-logging** | Чтение MDC, маскирование, HTTP-ключи (`request_id`, …), appenders |

Cross-repo контракт (без Gradle-зависимости):

- `space.br1440.platform.tracing.api.mdc.TracingMdcKeys` (tracing)
- `space.br1440.platform.logging.mdc.TracingMdcLogKeys` (logging)

Snake_case (`trace_id`, `span_id`, `trace_flags`) в v1 **не поддерживаются** platform-logging.

## Отвергнутые альтернативы

| Подход | Почему нет |
|--------|------------|
| Только Agent auto-instrumentation `logback-mdc` | Agent injects **snake_case** (`trace_id`, …) — не совместимо с platform-logging v1 camelCase |
| Вернуть `TracingMdcContextWrapper` в tracing | Дублирует Agent; нарушает agent-first; удалён намеренно |
| platform-logging пишет trace-ключи | Нарушает passive-reader модель; tracing/logging coupling |
| Изменить контракт logging на snake_case | Ломает Micrometer/Spring Boot 3.x `%X{traceId}` и error-handling alignment |

## Решение

### Production MDC writer (при `suppress=true`)

1. **OpenTelemetry Logback MDC Appender** (`io.opentelemetry.instrumentation:opentelemetry-logback-mdc-1.0`) — оборачивает platform appenders.
2. **CamelCase keys** в logback overlay приложения:

```xml
<appender name="PLATFORM-CONSOLE-TEXT-OTEL"
          class="io.opentelemetry.instrumentation.logback.mdc.v1_0.OpenTelemetryAppender">
    <traceIdKey>traceId</traceIdKey>
    <spanIdKey>spanId</spanIdKey>
    <traceFlagsKey>traceFlags</traceFlagsKey>
    <appender-ref ref="PLATFORM-CONSOLE-TEXT"/>
</appender>
```

3. **Отключить** Agent bytecode MDC weave (избежать дублирования snake_case + camelCase):

```text
-Dotel.instrumentation.logback-mdc.enabled=false
```

Appender читает `Span.current()` — Agent bytecode instrumentation spans **не** затрагивается.

### Logback overlay (приложение / Helm)

Приложение с `spring-boot-starter-platform-logging` **должно** иметь `logback-spring.xml`:

```xml
<configuration>
    <include resource="space/br1440/platform/logging/logback-spring-defaults.xml"/>
    <!-- OTEL wrappers с camelCase keys — см. e2e logback-spring-mdc-e2e.xml -->
    <root level="INFO">
        <appender-ref ref="PLATFORM-CONSOLE-TEXT-OTEL"/>
    </root>
</configuration>
```

Tracing-стартер **не** поставляет logback layout — это ответственность logging + SRE overlay.

### Interim dev path (без Agent)

`micrometer-tracing-bridge-otel` + `suppress=false` — bridge пишет camelCase MDC через Micrometer Observation (dev/staging). Не production-standard v0.1.0.

## Checklist rollout (SRE / platform)

| # | Item |
|---|------|
| 1 | `spring-boot-starter-platform-tracing` + `spring-boot-starter-platform-logging` |
| 2 | `-javaagent:opentelemetry-javaagent.jar` + `otel.javaagent.extensions` |
| 3 | `platform.tracing.suppression.suppress-micrometer-tracing=true` |
| 4 | Dependency `opentelemetry-logback-mdc-1.0` (версия = **2.27.0-alpha**, Instrumentation alpha BOM) |
| 5 | `logback-spring.xml`: include platform defaults + `OpenTelemetryAppender` camelCase |
| 6 | `otel.instrumentation.logback-mdc.enabled=false` |
| 7 | platform-logging patch `TracingMdcLogKeys` applied (exclude list без `trace_flags`) |

## E2E sign-off

`AgentMdcPlatformLoggingAgentE2ETest` (G2-MDC-e2e): subprocess MVC + tracing + logging + Agent + suppress → stdout содержит `traceId=` в `%maskedMDC` block, совпадающий с active span.

## Wave 2c (выполнено 2026-05-25)

`micrometer-tracing-bridge-otel`: `api` → `compileOnly` в `platform-tracing-spring-boot-autoconfigure` (не транзитивен потребителям). Grace period и opt-in для dev/staging — [MIGRATION.md](../MIGRATION.md). PlantUML: `platform-tracing-11-mdc-writer-reader-split` в `docs/architecture/Components_v2.puml`.

## Wave 2d (выполнено 2026-05-25)

SRE runbook + финализация docs после slimming: [runbook/mdc-logging-production.md](../runbook/mdc-logging-production.md). Обновлены `SUPPORTED.md` (logging checklist, readiness gates), корневой `README.md`, e2e README. E2E sign-off post-2c: **13/13** green (`-PrunE2e` на Docker).

## Связанные ADR

- [ADR-suppress-micrometer-tracing.md](./ADR-suppress-micrometer-tracing.md)
- [ADR-otel-direct-integration.md](./ADR-otel-direct-integration.md)

## Связанные артефакты

- `docs/SUPPORTED.md` — required JVM flags + logging checklist
- `docs/MIGRATION.md` — Wave 2c bridge-otel opt-in + grace period
- `docs/runbook/mdc-logging-production.md` — SRE rollout (Wave 2d)
- `spring-boot-starter-platform-logging` README — «MDC-контракт с platform-tracing v1»
- `platform-tracing-e2e-tests/.../AgentMdcPlatformLoggingAgentE2ETest.java`
