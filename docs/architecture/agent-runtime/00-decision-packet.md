# Agent runtime closure — decision packet (Slice 0.0)

Дата фиксации: 2026-08-16.
Репозиторий: `spring-boot-platform-starters`.
Ветка: `feature/PAAS-5186`.
Коммит evidence: `5ac5d5a82cc021ff1bc0777f2e8b0c0c4d28750e`.

## Toolchain hashes

| Инструмент | Значение |
|---|---|
| JDK | `21.0.6+8-LTS-188` (Oracle) |
| Gradle Wrapper | `8.14` (`34c560e3be961658a6fbcd7170ec2443a228b109`) |
| Kotlin (Gradle) | `2.0.21` |
| Groovy | `3.0.24` |
| OS | Windows 10 10.0 amd64 |
| Module versions | единый `rootProject.version` release train |

## FALSIFIED ASSUMPTION

```text
FALSIFIED ASSUMPTION

Previous concern:
The JUL option is blocked because classes transitively required by the
Agent from tracing-otel depend on org.slf4j / org.slf4j.MDC.

Verification result:
The transitive class closure of all tracing-otel classes actually imported
by the Agent contains zero references to org.slf4j and zero references to
org.slf4j.MDC.

Verified Agent imports from tracing-otel (production):
- space.br1440.platform.tracing.otel.sampling.*
- space.br1440.platform.tracing.otel.runtime.versioned.VersionedState
- space.br1440.platform.tracing.otel.mdc.remote.RemoteServiceTraceContext
- space.br1440.platform.tracing.otel.propagation.control.*
- space.br1440.platform.tracing.otel.propagation.RequestIdSupport
  (transitive via DefaultInboundTraceControlExtractor)

None of these types import org.slf4j or org.slf4j.MDC.
SLF4J/MDC types in tracing-otel (RemoteServiceMdc, AttributePolicy,
OtelTraceparentReaderImpl, OwningSpanScope, RemoteServiceNameResolver,
ThreadLocalIdentityStorage, OtelIdentityStorage) are not on the Agent
import graph.

Conclusion:
SLF4J/MDC presence in the current extension artifact is accidental
packaging surface caused by embedding whole module outputs, not a runtime
requirement of the Agent dependency closure.

Architectural consequence:
Logging backend selection must be evaluated against the cleaned Agent
runtime closure, not against the current hybrid JAR contents.
```

```text
Доказано:      JUL больше не заблокирован accidental SLF4J/MDC embedding.
Не доказано:   что residual SLF4J surface останется после миграции call-sites.
```

## Packaging defect (verified)

[`platform-tracing-otel-javaagent-extension/build.gradle`](../../../platform-starters-tracing/platform-tracing-otel-javaagent-extension/build.gradle)
втягивает `sourceSets.main.output` трёх модулей целиком:

- `spring-boot-starter-platform-tracing-api` — 61 класс
- `spring-boot-starter-platform-tracing-otel` — 87 классов
- `platform-starters-common-util` — 5 классов

Итого 153 класса плюс `slf4j-api` и `slf4j-simple`.

## Slice 0.2 decision (inventory-driven)

`platform-tracing-agent-runtime` **не создаётся**: inventory не обнаружил AGENT_ONLY
pure-типов, которые нельзя разместить в нейтральном shared-модуле.

`platform-tracing-runtime-policy` **создаётся** для SHARED_AGENT_APPLICATION
примитивов, которые сегодня лежат в `tracing-otel` и блокируют отказ агента
от зависимости на весь otel JAR.

## Slice 2 / 5 arbitration

Provisional and final candidate: `PLATFORM_STDERR_DIRECT + NONE`.

- Residual SLF4J call-sites after migration: 0.
- `JUL_DIRECT` component feasibility PASS, process isolation FAIL (missing exclusive handler → `SINK_FAILED`).
- Custom SLF4J provider: FORBIDDEN_AS_UNNECESSARY.
- ADR: `docs/architecture/agent-runtime/01-agent-logging-backend.md`.
