# Runbook: MDC трассировки в production (SRE / platform)

| Поле | Значение |
|------|----------|
| **Audience** | SRE, platform team, service owners |
| **Version** | platform-tracing **0.1.0-SNAPSHOT**, platform-logging **1.0.4+** |
| **Дата** | 2026-05-25 |
| **ADR** | [ADR-mdc-via-otel-agent-logback.md](../decisions/ADR-mdc-via-otel-agent-logback.md) |

---

## Контракт (writer / reader)

| Слой | Компонент | Действие |
|------|-----------|----------|
| **Writer** | OTel Java Agent + `OpenTelemetryAppender` | Пишет `traceId`, `spanId`, `traceFlags` (camelCase) в SLF4J MDC из `Span.current()` |
| **Reader** | `spring-boot-starter-platform-logging` | Читает MDC через `%maskedMDC` / `LogstashEncoder`; **не** пишет trace-ключи |
| **Tracing** | `platform-tracing-*` | Span'ы, suppress, Reactor propagation; **не** пишет `traceId`/`spanId`/`traceFlags` при `suppress=true` |

`micrometer-tracing-bridge-otel` **не** входит в transitive classpath стартера (Wave 2c). Production path **не** зависит от bridge.

---

## Gradle (сервис)

```gradle
dependencies {
    implementation 'space.br1440.platform.tracing:platform-tracing-spring-boot-starter-servlet' // или -reactive
    implementation 'space.br1440.platform.starters:spring-boot-starter-platform-logging:1.0.4'
    implementation 'io.opentelemetry.instrumentation:opentelemetry-logback-mdc-1.0:2.28.1-alpha'
}
```

Версии OTel — из `platform-tracing-bom` / [SUPPORTED.md](../SUPPORTED.md).

Dev/staging без Agent: явный opt-in bridge — [MIGRATION.md](../MIGRATION.md).

---

## JVM / Agent (Helm / Deployment)

```yaml
env:
  JAVA_TOOL_OPTIONS: >-
    -javaagent:/otel/opentelemetry-javaagent.jar
    -Dotel.javaagent.extensions=/otel/platform-tracing-otel-extension-agent.jar
    -Dotel.instrumentation.http.server.capture-request-headers=X-Trace-On,X-QA-Trace
    -Dotel.instrumentation.logback-mdc.enabled=false
```

**Обязательно** `logback-mdc.enabled=false` — иначе Agent inject'ит snake_case (`trace_id`, …), несовместимый с platform-logging v1.

---

## Spring (application.yml)

```yaml
platform:
  tracing:
    enabled: true
    suppression:
      suppress-micrometer-tracing: true
```

---

## Logback overlay (`logback-spring.xml`)

Минимальный production overlay (эталон — e2e `logback-spring-mdc-e2e.xml`):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="space/br1440/platform/logging/logback-spring-defaults.xml"/>

    <appender name="PLATFORM-CONSOLE-TEXT-OTEL"
              class="io.opentelemetry.instrumentation.logback.mdc.v1_0.OpenTelemetryAppender">
        <traceIdKey>traceId</traceIdKey>
        <spanIdKey>spanId</spanIdKey>
        <traceFlagsKey>traceFlags</traceFlagsKey>
        <appender-ref ref="PLATFORM-CONSOLE-TEXT"/>
    </appender>

    <root level="INFO">
        <appender-ref ref="PLATFORM-CONSOLE-TEXT-OTEL"/>
    </root>
</configuration>
```

Для JSON — обернуть `PLATFORM-CONSOLE-JSON` аналогично (`PLATFORM-CONSOLE-JSON-OTEL`).

---

## Pre-rollout checklist

| # | Check | Как проверить |
|---|-------|---------------|
| 1 | Agent + extension JAR на pod | `kubectl exec … -- ls /otel/` |
| 2 | `suppress-micrometer-tracing=true` | Spring env / ConfigMap |
| 3 | `otel.instrumentation.logback-mdc.enabled=false` | JVM flags |
| 4 | `logback-spring.xml` с `OpenTelemetryAppender` camelCase | ConfigMap / image |
| 5 | platform-logging ≥ 1.0.4 с `TracingMdcLogKeys` | dependency insight |
| 6 | Нет ожидания транзитивного `bridge-otel` | `./gradlew dependencies` |

---

## Smoke после деплоя

1. Выполнить HTTP-запрос к сервису (с `X-Trace-On: on` при низком sampling ratio).
2. В stdout / ELK найти строку с блоком `%maskedMDC` или JSON-полями `traceId`, `spanId`.
3. `traceId` в логе **совпадает** с trace ID в Jaeger для того же запроса.

Автоматический sign-off: `AgentMdcPlatformLoggingAgentE2ETest` (G2-MDC-e2e).

```powershell
$env:DOCKER_HOST = "tcp://<docker-host>:2375"
.\gradlew :platform-tracing-e2e-tests:test -PrunE2e --no-daemon
```

---

## Troubleshooting

| Симптом | Вероятная причина | Fix |
|---------|-------------------|-----|
| `traceId` пустой в логах | Нет `OpenTelemetryAppender` или root ссылается на необёрнутый appender | logback overlay |
| В MDC `trace_id` (snake_case) | Agent `logback-mdc` не отключён | `-Dotel.instrumentation.logback-mdc.enabled=false` |
| Дубли snake + camelCase | Agent MDC + Appender одновременно | отключить Agent MDC weave |
| Span в Jaeger есть, MDC пустой | `suppress=true` без Appender (bridge не помогает) | добавить Appender + logging starter |
| Локально работало, prod — нет | Забыли logback ConfigMap в Helm | SRE overlay |

---

## Связанные документы

- [SUPPORTED.md](../SUPPORTED.md) — compatibility matrix
- [MIGRATION.md](../MIGRATION.md) — bridge-otel opt-in (dev only)
- [traceability.md](../tracing/traceability.md) — G2-MDC-e2e, G2-05-e2e
