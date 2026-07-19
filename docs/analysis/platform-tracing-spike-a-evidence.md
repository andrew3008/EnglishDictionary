# Platform Tracing Spike A Evidence Packet

> Дата: 2026-07-19
> Branch: `feature/runtime-control-hardening`
> Baseline: `c9737fd78b8e3e465d956795f45c4306d6c6ffdc`
> Статус: **PASS**
> C1 gate: **UNLOCKED after commit**

## 1. Цель

Spike A должен до C1 доказать две независимые части:

1. application-side `OtelTraceparentReader` можно передавать через composition root без static `ServiceLoader` holder;
2. Spring composition надёжно отличает Agent от external/direct SDK и fail-fast обрабатывает несовместимые режимы.

Production C1 delta в рамках evidence-фазы не выполняется.

## 2. Agent marker decision

**Решение: использовать существующий `OtelAgentDetector` как authoritative runtime marker.** Он проверяет agent-installed bootstrap-visible класс `io.opentelemetry.javaagent.OpenTelemetryAgent`, а не наличие обычных OTel API/SDK классов в application classpath.

Evidence:

- `OtelAgentDetectorTest` на полном autoconfigure test classpath без `-javaagent`: marker=`false`;
- `ClassLoaderVisibilityE2ETest` в отдельной JVM с реальным pinned `-javaagent`: application-side marker=`true`;
- тот же packaged test подтверждает разные API class identities в `AppClassLoader` и `ExtensionClassLoader`;
- application и extension probe взаимно невидимы; marker не требует cross-CL object reference;
- валидный current span виден application plane через `GlobalOpenTelemetry`/`Span.current()`.

Functional `GlobalOpenTelemetry` без marker не считается Agent. Это external SDK ownership и effective mode `EXTERNAL`.

## 3. Normative mismatch matrix

| Configured mode | Runtime evidence | Outcome | Application owner | Agent owner |
|-----------------|------------------|---------|-------------------|-------------|
| `AUTO` | marker=true, user bean=false | `AGENT` | facade, SpanFactory, app reader, request context | SDK hooks, sanitizer, processors |
| `AUTO` | marker=true, user bean=true | FAIL | none | none |
| `AUTO` | marker=false, user bean=true | `EXTERNAL` | facade, reader, external SDK bridge | none |
| `AUTO` | marker=false, functional global=true | `EXTERNAL` | facade, reader, external global SDK bridge | none |
| `AUTO` | marker=false, no functional SDK | `STARTER` | Spring bootstrap owner; implementation gap remains for Slice E | none |
| `AGENT` | marker=false | FAIL | none | none |
| `AGENT` | marker=true, user bean=false | `AGENT` | facade, reader, request context | SDK hooks, sanitizer, processors |
| `STARTER` | marker=true | FAIL | none | none |
| `STARTER` | marker=false, external SDK present | FAIL | none | none |
| `STARTER` | marker=false, no external SDK | `STARTER` | Spring bootstrap owner | none |
| `EXTERNAL` | marker=true | FAIL | none | none |
| `EXTERNAL` | marker=false, external SDK present | `EXTERNAL` | facade, reader, external SDK bridge | none |
| `EXTERNAL` | marker=false, no external SDK | FAIL | none | none |
| `DISABLED` | marker=false | `DISABLED` | no-op facade | none |
| `DISABLED` | marker=true | `DISABLED` | no-op facade | agent instrumentation remains active |

Exact diagnostics proposed for the prototype:

- `platform.tracing.sdk.mode=AGENT requires an active OpenTelemetry Java Agent marker`;
- `platform.tracing.sdk.mode=STARTER conflicts with an active OpenTelemetry Java Agent; use AUTO or AGENT`;
- `platform.tracing.sdk.mode=EXTERNAL conflicts with an active OpenTelemetry Java Agent; use AUTO or AGENT`;
- `OpenTelemetry bean and active Java Agent detected simultaneously; remove the bean or disable the Agent`;
- `platform.tracing.sdk.mode=STARTER conflicts with an external OpenTelemetry runtime; use AUTO or EXTERNAL`;
- `platform.tracing.sdk.mode=EXTERNAL requires a functional external OpenTelemetry runtime`.

## 4. Reader composition inventory

Current static resolution has two independent entry points:

- API `DefaultSpanSpecBuilder.fromTraceparent()` calls `OtelTraceparentReaders.get()`;
- core `AbstractSemanticSpanBuilder` stores `OtelTraceparentReaders.get()` in a static field.

Target proof shape:

- remove `SpanSpecBuilder.fromTraceparent()` in C1 because static `SpanSpec.builder()` has no composition root;
- inject reader into `DefaultSpanFactory`, then into semantic/transport builders;
- Spring and direct composition roots use app-side `OtelTraceparentReaderImpl`;
- Spring+Agent still uses an app-side reader in `AppClassLoader`; no agent-side object is injected;
- disabled mode uses a no-op reader and does not parse transport input;
- agent-only mode has no facade, SpanFactory or reader, therefore reader composition is `N/A`.

Public `SpanFactory` must not expose `OtelTraceparentReader` in its ABI. Injection belongs to implementation constructors/factories.

## 5. Plan consistency finding

The short Spike A prototype list still says `Agent: extension-side factory`, but normative sections 7.0, 7.1 and 7.2 prohibit an application-facing factory/reader in the Agent plane. The normative plane model wins:

- Spring+Agent: reader belongs to application plane;
- agent-only: reader is absent;
- agent extension owns only SDK hooks, sanitizer, processors, sampler and JMX.

No agent-side reader/facade prototype will be introduced.

## 6. Verification

- `ReaderCompositionPrototypeTest`: 4 tests, PASS;
- `SdkModeDecisionPrototypeTest`: 6 tests covering the mandatory matrix, PASS;
- no-agent `OtelAgentDetectorTest`: PASS;
- real-agent `ClassLoaderVisibilityE2ETest`: tests=1, skipped=0, failures=0, errors=0; marker=true;
- core and autoconfigure full tests: PASS;
- `verifyAgentJarContents`, `verifyExtensionSpiRegistration`: PASS;
- `pr4ArchitectureFitnessVerify`, `pr1ModuleTaxonomyVerify`: PASS.

Valid and invalid traceparent values are processed through an explicitly supplied reader. Disabled mode uses a no-op reader. Agent-only has no reader. No prototype code calls `OtelTraceparentReaders` or relies on `META-INF/services` initialization.

## 7. Final decision

**Spike A PASS.** Existing bootstrap marker is accepted, mismatch/fail-fast semantics are fixed by the matrix above, application/agent ownership is consistent with sections 7.0-7.2, and F5 did not trigger.

C1 may start after this evidence and its tests are committed. C1 must implement the proven application-side injection shape and must not introduce an agent-side reader/facade.
