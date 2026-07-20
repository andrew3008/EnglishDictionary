# Platform Tracing Slice E Evidence

> Дата: 2026-07-20
> Ветка: `feature/runtime-control-hardening`  
> Статус: `CP-E PASS`; `RG-CONTROLLED-AGENT OPEN`

## 1. Implemented architecture

Принято решение B1-C: Controlled Agent-first, fail-closed. Production `SdkMode` содержит ровно
`AGENT` и `DISABLED`, default `AGENT`. `AUTO`, `STARTER`, `EXTERNAL` удалены без aliases.
Application `OpenTelemetry`, `TracingRuntime` и `TraceOperations` beans отклоняются как второй
runtime. Starter не создаёт SDK и после `READY` использует agent-owned global.

`AGENT` успешен только при compatible readiness protocol, lifecycle `READY`, пустом failure code,
полном capability set и согласованных component flags. `INITIALIZING` ожидается только до bounded
monotonic deadline. Missing/incompatible/failed/capability-missing/dual-SDK не дают NoOp.

`DISABLED` является единственным успешным NoOp и требует `enabled=false` плюс отсутствие Agent,
extension, global и application SDK. Живой Agent при `DISABLED` отклоняется.

## 2. Spring and architecture evidence

| Gate | Результат |
|---|---|
| Strict resolver and property matrix | PASS |
| Default/explicit AGENT with READY test contract | PASS |
| Missing/marker-only/extension missing/INITIALIZING/incompatible/FAILED/capability missing | PASS, fail-closed |
| Application SDK/custom runtime | PASS, startup rejected |
| DISABLED absence/live Agent/application SDK and contradictory properties | PASS |
| Removed property values | PASS, binding failure |
| Full autoconfigure module | PASS, 291 tests before final broad build |
| Architecture rule: exact mode surface/default/no SDK parameter/no Agent message leak | PASS |

Test SDK используется только в `src/test` harness или изолированном behavior fixture. Он не
публикуется как production bean/property/metadata.

## 3. Packaged Agent evidence

Fresh child-JVM attestation и composition:

- stock Agent без platform extension завершается fail-closed;
- incomplete, timed-out, failed, incompatible и capability-missing extension отклоняются;
- dual application SDK отклоняется до runtime wiring;
- final Controlled Agent distribution достигает `AGENT_READY`;
- Spring создаёт ноль `OpenTelemetry` beans;
- application facade видит agent-owned current OTel context через поддерживаемый OTel Context;
- разные app/Agent class identities не пересекаются объектами;
- `DISABLED` с живым Controlled Agent отклоняется.

Security differential использует stock Agent только как изолированный контроль без platform
autoconfiguration: stock export содержит Authorization, final Controlled Agent удаляет sensitive
value до Collector. Это не объявляет stock Agent поддерживаемым runtime.

Fresh closing suite через IP-based remote Docker выполнил 16 тестов: failures `0`, errors `0`,
skipped `0`. Protected WebMVC экспортировал ровно один SERVER span на запрос и подтвердил ноль
Spring `OpenTelemetry` beans; fixture не создаёт HTTP client/manual spans. WebFlux обслужил четыре
конкурентных запроса: ровно четыре различных traceId, `caller == worker` после `publishOn`, ровно
четыре SERVER span без duplicate и ноль Spring SDK beans.

Kafka final distribution подтвердила producer `publish`, delivery, intentional retry/redelivery,
single/batch consumer, manual batch links и отсутствие Spring SDK bean. Экспортировано ровно семь
messaging spans: 3 producer, 2 single-process attempts и 2 batch-process spans (Agent delivery +
platform manual linked span); linked span содержит минимум два различных upstream traceId.
Invalid/missing traceparent и выбор только валидных различных W3C contexts закреплены
детерминированным `KafkaBatchAspectMigrationTest`. Arbitrary Kafka sensitive-header assertion остаётся
`NOT_APPLICABLE_TO_CURRENT_INSTRUMENTATION`: upstream instrumentation не экспортирует проверяемый
header; security proof выполняется на реально экспортирующей HTTP boundary.

## 4. Configuration cache

| Область | Provenance | Статус |
|---|---|---|
| Production Controlled Agent distribution: `platformExtendedAgentJar`, `preparePlatformAgentDistribution`, verifier | Slice E; execution-time Gradle object capture исправлен | **GREEN**: cache entry stored и повторно использован |
| Existing production `agentExtensionJar` (`platform-tracing-otel-extension/build.gradle:103-104`) | Pre-existing с исходного commit | **P2 existing production build debt** |
| Existing opt-in E2 fixtures (`testClassLoaderProbeJar`, `testJmxWireExtensionJar`, `test.onlyIf`) | Pre-existing test infrastructure | **P2 existing test-infrastructure debt** |
| `testE2FailureAgentJar` и его opt-in E2 wiring (`platform-tracing-e2e-tests/build.gradle:259` и связанные строки) | Добавлено в Slice E | **P2 new test-infrastructure limitation** |

Основной build без configuration cache GREEN. Ограничения E2 находятся в opt-in test source/task
plane, не входят в published production distribution и не блокируют Slice F. Полная repository-wide
configuration-cache совместимость не заявляется. Техническая задача с владельцем и exit criteria:
`TD-SLICE-E-CC-01` в `platform-tracing-slice-e-configuration-cache-debt.md`.

## 5. Gate separation

`CP-E` закрыт: финальный build, architecture gates, packaged E2E и post-fix audit прошли.
Внутренняя разработка может перейти к Slice F.

`RG-CONTROLLED-AGENT` остаётся **OPEN** и блокирует pilot/production, но не Slice F после `CP-E`.
Не выполнены repository-independent signing, SBOM/provenance, immutable registry, mandatory pre-JVM
verifier wiring, Helm/init-container integration, admission policy, stock Agent/external extension
override prohibition и fleet rollout/rollback proof.

**Production rollout запрещён, пока `RG-CONTROLLED-AGENT` открыт.**

## 6. Closing verification

- `pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify pr0StarterDependencySmoke build` — PASS,
  129 tasks, `BUILD SUCCESSFUL`;
- packaged closing suite — PASS, 16 tests, failures/errors/skipped = `0/0/0`;
- strict Spring mode/configuration suite и полный autoconfigure test — PASS;
- Controlled Agent distribution verifier — PASS, cache entry stored and reused;
- `git diff --check`, production-mode scan, metadata inspection и Java BOM scan — обязательная
  часть финального post-fix audit.
