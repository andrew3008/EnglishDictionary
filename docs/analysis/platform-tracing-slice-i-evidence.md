# Slice I: Targeted Architecture Boundary Hardening Evidence

> Дата: 2026-07-22
> Ветка: `feature/slice-i-boundary-hardening`
> Base: `master@c591c921a4f0222e8b25939683c208d8c3968f1c`
> Implementation commit: `cddc777`
> Итоговый вердикт: **SLICE I PASS**

## 1. Baseline и границы работы

Base является merge-коммитом PR #19:

```text
c591c921a4f0222e8b25939683c208d8c3968f1c
Merge pull request #19 from andrew3008/feature/slice-m-identity-model
```

В base присутствуют CP-1 R2 API, internal identity store, `ReactiveCorrelationOperations`,
F0 sanitizer, Kafka/WebMVC/WebFlux integration и
`docs/analysis/platform-tracing-slice-m-evidence.md`. Следовательно, Slice M подтверждён как
вмёрженный и закрытый prerequisite Slice I.

Slice I не меняет production-код, публичный ABI, runtime behavior, Gradle publication metadata
или package taxonomy. Изменения ограничены architecture-test infrastructure, module-level wiring
правил, synthetic negative fixtures и одной C3 test-fixture property.

## 2. Verify-first inventory

| Candidate edge | Классификация | Evidence / действие |
|---|---|---|
| API → core/autoconfigure/otel implementation | `ABSENT`, `ENFORCEMENT GAP` | Production imports отсутствуют; добавлен `API_MAIN_NO_IMPLEMENTATION_DEPENDENCY`. |
| API → OTel/Spring/Reactor/Servlet/Kafka/gRPC/SLF4J | `ABSENT`, частично `ALREADY ENFORCED` | Старый gate покрывал только `io.opentelemetry.api`; добавлен полный `API_MAIN_NO_OTEL_OR_FRAMEWORK_TYPES`. |
| WebMVC/WebFlux → core или future `tracing.otel..` | `ABSENT`, `ENFORCEMENT GAP` | Добавлен общий `WEB_MAIN_NO_IMPLEMENTATION_DEPENDENCY`. Approved autoconfigure boundary-support не запрещён. |
| WebMVC/WebFlux → `io.opentelemetry.context..` | `ABSENT`, `ENFORCEMENT GAP` | Добавлен `WEB_MAIN_NO_OTEL_CONTEXT`. |
| Web → injector/context keys/traceparent reader | `ABSENT`, `ENFORCEMENT GAP` | Добавлен FQN-based `WEB_MAIN_NO_INTERNAL_PROPAGATION_TYPES`. |
| Public `SpanFactory`/builders → `OtelTraceparentReader` | `ABSENT`, `ENFORCEMENT GAP` | Добавлен FQN-based `SPAN_FACTORY_API_NO_TRACEPARENT_READER`. |
| `ManualSpanBuilder.fromTraceparent` | `FALSE POSITIVE` | Это сохранённый C1 manual contract; запрещённый static builder path не возвращён. Reader остаётся implementation-side. |
| CP-C2 port contract/implementation ownership | `ALREADY ENFORCED`, gate strengthened | API port подтверждён как interface; existing implementation ownership rule переведён в non-empty enforce mode. |
| Slice M internal identity types в public surface | `ABSENT`, `ENFORCEMENT GAP` | Добавлен `IDENTITY_INTERNAL_TYPES_NOT_PUBLIC`; approved public identity API не затронут. |
| WebMVC ↔ WebFlux/Servlet mixing | `ALREADY ENFORCED` | Оба существующих направления оставлены и повторно пройдены. |
| API static `ServiceLoader` | `ALREADY ENFORCED` | Удалён `allowEmptyShould(true)`; production API classes существуют. Agent extension SPI не относится к запрету. |
| Core facade/runtime/state/MDC/package taxonomy | `ALREADY ENFORCED` | Existing rules GREEN; broken edge, требующий move, не найден. |
| Starter compile metadata | `ALREADY ENFORCED` | C3 isolated consumer и `pr0StarterDependencySmoke` GREEN. |

`REAL VIOLATION` не найдено. Production correction и package move не требуются.

## 3. Правила и negative characterization

В shared `ModuleTaxonomyArchRules` добавлены:

- `API_MAIN_NO_IMPLEMENTATION_DEPENDENCY`;
- `API_MAIN_NO_OTEL_OR_FRAMEWORK_TYPES`;
- `WEB_MAIN_NO_IMPLEMENTATION_DEPENDENCY`;
- `WEB_MAIN_NO_OTEL_CONTEXT`;
- `WEB_MAIN_NO_INTERNAL_PROPAGATION_TYPES`;
- `SPAN_FACTORY_API_NO_TRACEPARENT_READER`;
- `PROPAGATION_PORT_OWNERSHIP`;
- `IDENTITY_INTERNAL_TYPES_NOT_PUBLIC`.

Правила подключены к API, core, Spring autoconfigure, WebMVC и WebFlux architecture suites.
`SliceIBoundaryArchRulesTest` с synthetic forbidden dependencies доказывает, что новые API,
web, OTel Context, internal propagation и traceparent reader gates действительно падают на
нарушении.

Report-to-enforce conversions:

- `API_NO_SERVICE_LOADER`: удалён `allowEmptyShould(true)`;
- `API_MAIN_NO_OTEL_API`: удалён `allowEmptyShould(true)`;
- `CONTROL_IMPLS_ONLY_IN_CORE`: удалён `allowEmptyShould(true)` после подтверждения
  `DefaultTraceControlHeaderInjector` как production implementation.

Broad exclusions и ignored failures не добавлялись. Проверки известных infrastructure types
используют FQN, а не случайное совпадение simple name.

## 4. Gradle и publication boundary

| Module | Существенный scope | Вывод |
|---|---|---|
| `platform-tracing-api` | BOM + Jakarta annotations `api`; Lombok `compileOnly` | Нет OTel/framework/implementation project dependency. |
| `platform-tracing-core` | API project и OTel API — `api`; SLF4J — `implementation` | Implementation module intentionally OTel-native. |
| Spring autoconfigure | API project — `api`; core — `implementation`; OTel API intentional `api` | C3 доказывает: OTel API видим consumer, core отсутствует на compile classpath и присутствует runtime. |
| WebMVC/WebFlux | Spring autoconfigure и API — `api`; framework web types преимущественно `compileOnly` | Прямого project edge на core/extension нет. |
| `platform-tracing-test` | API/core/OTel SDK/ArchUnit — test infrastructure `api` | Не production publication surface. |

`c3PublishedMetadataConsumerVerify` сначала выявил stale fixture: после Slice E
`platform.tracing.enabled=false` требует явного `platform.tracing.sdk.mode=DISABLED`, иначе
AUTO fail-fast корректно отклоняет конфигурацию. Исправлена только C3 fixture property; production
mode semantics не менялась. Повторная isolated publication verification GREEN.

## 5. Scope и ABI

- production `src/main` files changed: **0**;
- Gradle build/publication files changed: **0**;
- renamed/moved files: **0**;
- broad package moves: **0**;
- API/CP-C2/identity ABI snapshot diff против `master`: **0**;
- wildcard imports в изменённых Java files: **0**;
- UTF-8 BOM в изменённых Java files: **0**;
- `git diff --check`: GREEN.

## 6. Verification

### Focused architecture/module suites

```powershell
.\gradlew.bat :platform-tracing-test:test `
  :platform-tracing-core:test `
  :platform-tracing-api:test `
  :platform-tracing-autoconfigure-webmvc:test `
  :platform-tracing-autoconfigure-webflux:test --no-daemon
```

Результат: `BUILD SUCCESSFUL`, 26 actionable tasks. Synthetic negative checks GREEN.

### C3, starter и architecture gates

```powershell
.\gradlew.bat c3PublishedMetadataConsumerVerify `
  pr0StarterDependencySmoke `
  pr1ModuleTaxonomyVerify `
  pr4ArchitectureFitnessVerify --no-daemon
```

Результат после fixture correction: `BUILD SUCCESSFUL`, 75 actionable tasks;
published consumer, negative compile, generated POM/Gradle module metadata, starter visibility,
module taxonomy и architecture fitness GREEN.

### Aggregate

```powershell
.\gradlew.bat :platform-tracing-api:test `
  :platform-tracing-core:test `
  :platform-tracing-spring-boot-autoconfigure:test `
  :platform-tracing-autoconfigure-webmvc:test `
  :platform-tracing-autoconfigure-webflux:test `
  :platform-tracing-test:test `
  pr0StarterDependencySmoke pr1ModuleTaxonomyVerify `
  pr4ArchitectureFitnessVerify build --no-daemon
```

Результат: `BUILD SUCCESSFUL in 1m 12s`, 128 actionable tasks. Agent distribution JAR contents
и extension SPI registration GREEN. Aggregate `build` ожидаемо показывает opt-in E2E task как
`SKIPPED`; этот skip не используется как обязательное E2E evidence.

Existing P2 warnings: три Javadoc warning в `platform-tracing-test`; collector-config Docker
validation использовал существующий non-blocking fallback из-за недоступного remote daemon.

### Mandatory packaged controlled-Agent E2E

Каноническая команда:

```powershell
$env:DOCKER_HOST='tcp://192.168.100.70:2375'
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --rerun-tasks --no-daemon
```

После восстановления Docker API (`/_ping`: HTTP 200) project owner выполнил mandatory packaged
Agent E2E в том же worktree на implementation HEAD `cddc777`:

```text
BUILD SUCCESSFUL (~9m 29s)
suites=28 tests=65 failures=0 errors=0 skipped=0
```

Codex не выполнял успешный retry. Codex проверил сохранённые JUnit XML artifacts: 28 файлов,
65 tests, 0 failures, 0 errors, 0 skipped; earliest/latest timestamps
`2026-07-22T11:52:42+03:00`, после commit timestamp `cddc777` =
`2026-07-22T11:33:32+03:00`. Все 28 suite names присутствуют; Gradle `test` task не содержит
class/tag filter и активируется только `-PrunE2e`, поэтому mandatory suite не был молча исключён.

Прогон покрывает extension SPI/attestation, packaged JAR/classloader isolation и разные class
identities, Spring+Agent composition/current-context visibility, readiness/JMX Open-Type wire,
F0 fail-closed sanitizer, identity/WebMVC/WebFlux/Kafka, exactly-one platform ownership,
no-second-SDK и startup/shutdown paths.

Первоначальный Docker timeout был разрешённым environmental interruption, а не product defect.

## 7. Findings и статус

- P0: нет.
- P1 в production/architecture: нет.
- P2: pre-existing Javadoc warnings; временный collector validation fallback при недоступном
  remote Docker. Docker route восстановлен до обязательного E2E; product defect отсутствует.
- Release gates: `RG-IDENTITY-TRUST OPEN`, `RG-CONTROLLED-AGENT OPEN`;
  `PRODUCTION ROLLOUT FORBIDDEN`.
- `SLICE G BLOCKED BY CP-2`.

**SLICE I PASS.** Это boundary enforcement, а не package redesign: массовый repackage не
обоснован, существующая semantic taxonomy остаётся валидной, production dependency correction не
потребовалась, runtime behavior не изменён. Усиленные architecture rules предотвращают возврат
запрещённых зависимостей.

Следующий шаг dependency graph — `SLICE J UNBLOCKED`; Slice J в рамках этой работы не начинается.
