# spring-boot-platform-tracing

Платформенный Spring Boot 3.x стартер распределённой трассировки: **OTel Java Agent first**, Micrometer Observation (suppress в production), platform extension JAR, OpenTelemetry Collector.

## Состав репозитория

| Модуль | Назначение |
|---|---|
| `platform-tracing-bom` | BOM (Spring Boot 3.5.5, OTel SDK 1.62.0, Instrumentation 2.28.1). |
| `platform-tracing-api` | Публичный API: `TraceOperations`, `@Traced`, SPI, MDC-константы (`TracingMdcKeys`). |
| `platform-tracing-otel` | `DefaultTraceOperations`, `NoopTraceOperations`. |
| `platform-tracing-spring-boot-autoconfigure` | `TracingProperties`, авто-конфигурации, фильтры, observation conventions. |
| `platform-tracing-autoconfigure-webmvc` / `-webflux` | Servlet / WebFlux adapters. |
| `platform-tracing-spring-boot-starter-servlet` / `-reactive` | Точки подключения для сервисов. |
| `platform-tracing-otel-javaagent-extension` | Agent extension: `CompositeSampler`, `SpanProcessor`'ы, `ResourceProvider`. |
| `platform-tracing-collector-config` | YAML для OTel Collector (tail sampling, routing). |
| `platform-tracing-e2e-tests` | E2E: SDK/Agent → Collector → Jaeger (+ MDC smokes). |
| `platform-tracing-test` | JUnit 5 test utilities. |

## Целевой стек (v0.1.0)

- Java **21**, Spring Boot **3.5.5**
- OpenTelemetry Java Agent **2.28.x**, SDK **1.62.0**
- Production path: Agent + `platform-tracing-otel-javaagent-extension` + `suppress-micrometer-tracing=true`

Подробнее: [docs/SUPPORTED.md](docs/SUPPORTED.md).

## MDC и platform-logging (Wave 2)

Tracing-стартер **не** пишет `traceId`/`spanId`/`traceFlags` в SLF4J MDC в production. Разделение:

| Роль | Компонент |
|------|-----------|
| **Writer** | `OpenTelemetryAppender` + OTel Agent (`Span.current()`) |
| **Reader** | `spring-boot-starter-platform-logging` (`%maskedMDC`) |
| **Tracing** | Span'ы, suppress, Reactor/async, `platform.remote.service` |

Документация:

- [ADR-mdc-via-otel-agent-logback.md](docs/decisions/ADR-mdc-via-otel-agent-logback.md)
- [runbook/mdc-logging-production.md](docs/runbook/mdc-logging-production.md) — SRE rollout
- [runbook/actuator-tracing-diagnostics.md](docs/runbook/actuator-tracing-diagnostics.md) — `otelEffective` / `otelEnvHints`
- [MIGRATION.md](docs/MIGRATION.md) — bridge-otel opt-in (dev/staging)

## Сборка

```powershell
.\gradlew clean check
```

E2E (Docker):

```powershell
$env:DOCKER_HOST = "tcp://<host>:2375"
.\gradlew :platform-tracing-e2e-tests:test -PrunE2e
```

## Публикация

```powershell
.\gradlew publishAllToMavenLocal
.\gradlew publishAllToNexus   # корпоративный Nexus
```

## Статус

Фаза 1 + Wave 2 (MDC / context propagation) — реализовано. Матрица трассируемости: [docs/tracing/traceability.md](docs/tracing/traceability.md).
