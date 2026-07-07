# platform-tracing-e2e-tests

E2E-тесты цепочки трассировки **OpenTelemetry SDK / Java Agent → OpenTelemetry Collector → Jaeger** через Testcontainers.

## Как запустить

```powershell
# Требуется Docker. По умолчанию модуль исключён из `gradle test`:
$env:DOCKER_HOST = "tcp://<docker-host>:2375"   # опционально, remote Docker
.\gradlew :platform-tracing-e2e-tests:test -PrunE2e
```

**26 тестов** (SDK pipeline + Agent smokes), sign-off Wave 2 MDC + train bump 2.28.1.

## Покрытие

### SDK → Collector → Jaeger (`TracingE2ETest`)

| Сценарий | Назначение |
|---|---|
| `happy_path_спан_доходит_до_Jaeger` | базовый пайп + `X-Trace-On=on` / forced-record |
| `error_span_всегда_сохраняется_tail_sampling` | политика `errors-always` |
| `force_on_контракт_без_заголовка_не_попадает_с_заголовком_попадает` | контракт `X-Trace-On` |
| `scrubbing_атрибут_password_маскируется_до_попадания_в_Jaeger` | `ScrubbingSpanProcessor` |

### Agent smokes

| Класс | ID | Назначение |
|---|---|---|
| `AgentMdcPlatformLoggingAgentE2ETest` | **G2-MDC-e2e** | MVC + tracing + platform-logging + Agent + suppress → `traceId` в `%maskedMDC` |
| `ReactorContextPropagationAgentE2ETest` | **G2-05-e2e** | WebFlux `publishOn` + Agent + suppress |
| `ForceSamplingAgentSmokeTest` | — | `X-Trace-On` force sampling при ratio=0 |
| `DbSemconvAgentSmokeTest` | — | JDBC semconv через Agent 2.28 |
| `PlatformExtensionAgentSmokeTest` | — | extension JAR загружается Agent'ом |
| `OtelCollectorFileExporterSmokeTest` | — | file exporter sanity |

## Окружение

- `jaegertracing/all-in-one:1.62` — OTLP + v3 Query API (16686);
- `otel/opentelemetry-collector-contrib:0.154.0` — tail_sampling (`e2e/otel-collector-e2e.yaml`, Track B+ pin);
- Agent smokes: subprocess с `-javaagent` + `platform-tracing-otel-extension-*-agent.jar`.

## G2-MDC-e2e артефакты

- `logback-spring-mdc-e2e.xml` — эталон production logback overlay
- `AgentMdcPlatformLoggingAgentE2ETest` — sign-off writer/reader split

Runbook для SRE: [docs/runbook/mdc-logging-production.md](../docs/runbook/mdc-logging-production.md).

## Почему отдельный модуль

- Heavy deps (Testcontainers, OkHttp, platform-logging composite build);
- **Не публикуется** в Nexus;
- `gradle test` без `-PrunE2e` пропускает модуль (CI без Docker).

## Production vs e2e Collector

E2E-конfig — минимальный срез production (`platform-tracing-collector-config`): укороченный `decision_wait`, без resilience-обвязки. SRE валидирует production YAML отдельно.
