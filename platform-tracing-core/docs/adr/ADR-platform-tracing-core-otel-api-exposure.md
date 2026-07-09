# ADR: OpenTelemetry API Exposure in `platform-tracing-core`

**ID:** ADR-platform-tracing-core-otel-api-exposure  
**Status:** Accepted  
**Date:** 2026-07-09  
**Deciders:** Platform Architect, Lead Platform Engineer  
**PR:** `fix/tracing-runtime-spi-completeness`

---

## Context

`platform-tracing-core` implements `platform-tracing-api` on top of OpenTelemetry API.
The module has `api` (transitive) dependency on `io.opentelemetry:opentelemetry-api` in
`build.gradle`, making OTel types part of the compile-time surface of core.

During Slice 2 refactoring (`Fable_5`), `DefaultPlatformTracing` (facade) held three
convenience constructors accepting `OpenTelemetry` directly, and two `instanceof OtelTracingRuntime`
blocks to resolve `attributePolicy()` and to invoke `setKillSwitchEnabled(boolean)`. This caused:

- `core.facade` depending on `io.opentelemetry.api.OpenTelemetry` directly;
- `core.facade` depending on `core.runtime.otel.OtelTracingRuntime` (concrete bridge class);
- `TracingRuntime` SPI being incomplete: no `attributePolicy()`, no lifecycle control;
- kill-switch implemented as mutable state inside `OtelTracingRuntime` (not the facade's concern).

## Decision

**Outcome B** (as originally intended) is preserved: `opentelemetry-api` remains on the
`api` configuration of `platform-tracing-core`. OTel types are part of the module's
public surface.

However, the **exposure mechanism changes**:

| Before | After |
|---|---|
| `new DefaultPlatformTracing(OpenTelemetry, ...)` | `new DefaultPlatformTracing(OtelTracingRuntimeFactory.create(openTelemetry, ...))` |
| `DefaultPlatformTracing` imports `OtelTracingRuntime` | `DefaultPlatformTracing` imports only `TracingRuntime` SPI |
| Kill-switch via `OtelTracingRuntime.setKillSwitchEnabled()` | Kill-switch via atomic `RuntimeHolder` swap in facade |
| `TracingRuntime` SPI lacks `attributePolicy()` | `TracingRuntime` SPI declares `attributePolicy()` |

## Rationale

1. **SPI completeness** eliminates the need for `instanceof` casts in facade; facade works
   against the SPI contract, not against a specific implementation.

2. **Factory pattern** keeps OTel wiring in `OtelTracingRuntimeFactory` and Spring Boot
   autoconfigure (`TracingCoreAutoConfiguration`), not in the facade. The facade is
   OTel-unaware.

3. **RuntimeHolder swap** is a single `volatile` write, ensuring `traceContext()` and
   `manual()` are atomically consistent after a kill-switch toggle. The previous approach
   (mutable `AtomicBoolean` inside `OtelTracingRuntime`) left `ManualTracing` pointing to
   the original (live) runtime after disable.

4. `OtelTracingRuntime` becomes a stateless, immutable OTel adapter. Kill-switch is not
   its concern.

## Consequences

### Positive
- `core.facade` package has zero `io.opentelemetry` imports (enforced by `FacadeOtelIsolationArchTest`).
- `TracingRuntime` SPI is complete; decorators like `MeteredTracingRuntime` delegate
  `attributePolicy()` via `DelegatingTracingRuntime` default method.
- Kill-switch thread-safety is explicit and documented in `DefaultPlatformTracing`.
- Existing autoconfigure wiring (`TracingCoreAutoConfiguration.platformTracing(TracingRuntime)`) 
  is unchanged — it already injects `TracingRuntime`, not `OpenTelemetry`.

### Negative / Trade-offs
- ~30 test and bench call sites that used `new DefaultPlatformTracing(sdk)` must migrate
  to `new DefaultPlatformTracing(OtelTracingRuntimeFactory.create(sdk))`.
- `OtelTracingRuntimeFactory` is a new public class in `core.runtime.otel`; it must be
  kept in sync with `OtelTracingRuntime` constructor signature changes.

### Neutral
- `opentelemetry-api` remains `api`-scoped (transitive). Consumers of
  `platform-tracing-core` still see OTel types on classpath. This is intentional:
  enrichment, propagation, and bridge modules depend on OTel API.

## ArchUnit Enforcement

`FacadeOtelIsolationArchTest` in `platform-tracing-core` test sources enforces:
- `core.facade..` does not depend on `io.opentelemetry..`
- `core.facade..` does not depend on `core.runtime.otel..`

Violations of these rules are build failures.

## Amendment History

| Date | Change |
|---|---|
| 2026-07-09 | Initial ADR; documents post-Fable_5 Outcome B decision and factory-based wiring. |
