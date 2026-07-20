# ADR: Controlled Agent-first SDK ownership и fail-closed mode resolution

| Поле | Значение |
|------|----------|
| Статус | **Принято; заменяет редакцию от 2026-06-09** |
| Дата решения | 2026-07-20 |
| Стек | OTel SDK **1.62.0**, Agent **2.28.1**, Spring Boot **3.5.6**, Java 21 |
| Связанные | [ADR-otel-direct-integration.md](ADR-otel-direct-integration.md), `platform-tracing-slice-e-spring-sdk-decision-packet.md` |

## Контекст

Старая модель `AUTO|AGENT|STARTER|EXTERNAL|DISABLED` описывала режимы без подтверждённого
владельца runtime. `STARTER` фактически давал успешный NoOp без SDK, а `EXTERNAL` принимал
произвольный application SDK без platform sampler, processors, sanitizer и защищённого export path.
Это создавало ложную доступность telemetry и возможность второго несовместимого SDK.

## Решение B1-C

Production ownership принадлежит **Controlled Platform Agent Distribution**. Agent владеет SDK,
auto-instrumentation, sampler, обязательными processors, sanitizer, propagation hooks, exporter и
lifecycle. Spring starter владеет application facade, web/Kafka adapters, binding и наблюдением
readiness; starter не создаёт и не принимает application `OpenTelemetry` SDK.

Поддерживаются ровно два значения `platform.tracing.sdk.mode`:

| Режим | Контракт |
|---|---|
| `AGENT` | Значение по умолчанию. Startup успешен только при совместимом readiness protocol, `READY` и полном mandatory capability profile |
| `DISABLED` | Успешный intentional NoOp только при отсутствии Agent, extension, global и application runtime |

`AUTO`, `STARTER`, `EXTERNAL` удалены без compatibility aliases. Их передача является ошибкой
property binding. Инвариант конфигурации: `enabled=true` требует `AGENT`; `enabled=false` требует
`DISABLED`. Противоречия завершают startup ошибкой.

## Readiness и collision semantics

- Marker или наличие MBean сами по себе не означают готовность.
- Решение ожидает только переход из `INITIALIZING` до монотонного bounded deadline.
- Protocol, version/profile, lifecycle, failure code, capability set и component flags проверяются.
- `AGENT_MISSING`, `EXTENSION_MISSING`, `EXTENSION_INITIALIZING`, `EXTENSION_INCOMPATIBLE`,
  `EXTENSION_FAILED`, `CAPABILITY_MISSING`, `DUAL_SDK_DETECTED` являются diagnostic states и
  никогда не преобразуются в успешный NoOp.
- Application `OpenTelemetry`, `TracingRuntime` или `TraceOperations` bean отклоняется до wiring.
- После `READY` facade использует agent-owned `GlobalOpenTelemetry`; fallback на произвольный
  functional global отсутствует.

Application diagnostics публикует configured mode, observed state и безопасный failure code.
Полный Agent failure message через application boundary не передаётся. Health читает startup
snapshot и не вызывает `GlobalOpenTelemetry.get()` повторно.

## Gate separation

`CP-E APPROVED`; `SLICE E CLOSED`; `SLICE F UNBLOCKED`. Implementation audit, Spring matrix,
packaged E2E, architecture/build gates и синхронизация документов завершены. Источник решения:
Architecture Committee approval communicated by the project owner.

`RG-CONTROLLED-AGENT` является внешним production release gate. Он блокирует pilot/production,
но после закрытия `CP-E` не блокирует Slice F. Gate остаётся **OPEN** до signing, SBOM/provenance,
immutable registry, обязательного pre-JVM verifier, Helm/init-container wiring, admission policy,
запрета stock Agent/external extension overrides и fleet rollout/rollback proof.

Spring application startup отклоняет stock Agent без compatible extension. Этот fail-fast не является
pre-JVM security boundary и не способен предотвратить ранний незащищённый Agent export. Stock Agent
остаётся неподдерживаемым и небезопасным; production protection требует
[RG-CONTROLLED-AGENT](../architecture/rg-controlled-agent-release-gate.md).

## Последствия

- `DISABLED` является единственным успешным NoOp.
- Недоступный Collector после валидного `READY` pipeline считается observable runtime degradation,
  а не причиной создавать второй SDK или останавливать приложение.
- Local/unit tests используют SDK только в структурно изолированных test fixtures/source sets.
- Произвольный stock Agent не является поддерживаемым production runtime.

## Отклонённые альтернативы

- Starter-owned SDK: создаёт второй composition plane и требует отдельного parity/lifecycle продукта.
- Certified `EXTERNAL`: не может быть доказан одним `OpenTelemetry` bean и сохраняет неоднозначное
  владение pipeline.
- `AUTO`: не несёт смысла при единственном enabled owner и скрывает ошибки deployment.
- NoOp fallback для неполного Agent: диагностически ложно сообщает безопасное отключение.
