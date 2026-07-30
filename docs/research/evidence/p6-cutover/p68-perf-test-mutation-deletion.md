# P6.8 — Residual mutation cleanup (perf/test helpers)

**Date:** 2026-07-29  
**Task:** P6.8 residual mutation cleanup  
**Verdict:** PASS (focused gates)

## Scope completed

### A) Production mutation helpers deleted
- `SafeSpanExporter.setExportEnabled` — deleted; tests mutate via `AcceptedPolicyAccess.updateExporter` on an isolated store passed into the constructor
- `ValidatingSpanProcessor.updateValidationPolicy` / `tryApplyPolicyUpdate` — deleted; unit tests and JMH `@Setup` use `AcceptedPolicyAccess.isolatedValidation(...)` + `updateValidation(...)` / constructor injection
- Store primitives retained: `AcceptedPolicyAccess.updateSampling` / `updateValidation` / `updateExporter`

### B) Stale platform-JMX write consumers rewritten
- `PrivateMBeanServerRemoteClientMain` — no longer invokes `resetSamplerCounters`; attempts `setAttribute(SamplingRatio)` and emits `platformWriteRejected=true`
- `PrivateMBeanServerIsolationE2ETest` — asserts platform write rejected
- `PlatformAutoConfigurationCustomizerProcessorsTest` — SamplingRatio `setAttribute` must fail; attribute unchanged
- `RuntimeSamplingControlSmokeMain` — `/admin/sampling-ratio` rewritten to private `applySnapshot` (same family as `PerfPrivateControlController`)

### C) Hygiene
- Spring `SamplingRuntimeConfig` / `ValidationRuntimeConfig` javadoc no longer references deleted JMX mutation APIs
- Inventory N12 note: `PerfPrivateControlController` replaced deleted `PerfAdminController`
- `PerfAdminController` remains absent (not recreated)
- `PerfPrivateControlController` and M10 private-control scripts left as-is

## Verification (focused)

| Gate | Result |
|---|---|
| `:platform-tracing-otel-javaagent-extension:compileJava` + `compileTestJava` | PASS |
| Agent focused tests (SafeSpanExporter / Validation* / ValidatingSpanProcessor / PlatformAutoConfigurationCustomizerProcessors) | PASS |
| `:platform-tracing-e2e-tests:compilePrivateMBeanServerAppJava` + `compileTestJava` | PASS |
| `:platform-tracing-bench:compileJmhJava` | PASS |
| Spring autoconfigure compile (transitive via e2e; RuntimeSamplingControlSmokeMain + javadoc) | PASS |

## Files changed (this slice)

### Production
- `SafeSpanExporter.java` — delete `setExportEnabled`
- `ValidatingSpanProcessor.java` — delete `updateValidationPolicy` / `tryApplyPolicyUpdate`
- `SamplingRuntimeConfig.java`, `ValidationRuntimeConfig.java` — javadoc hygiene

### Tests / bench / e2e
- `SafeSpanExporterTest.java`
- `ValidationRuntimeFoundationTest.java`, `ValidationRuntimePolicyCharacterizationTest.java`
- `ValidationPolicyRuntimeTest.java`, `ValidationPolicyCharacterizationTest.java`
- `ValidatingSpanProcessorCharacterizationTest.java`
- `PlatformAutoConfigurationCustomizerProcessorsTest.java`
- `ValidatingSpanProcessorBenchmark.java`
- `PrivateMBeanServerRemoteClientMain.java`, `PrivateMBeanServerIsolationE2ETest.java`
- `RuntimeSamplingControlSmokeMain.java`, `RuntimeSamplingControlSmokeTest.java`
- `TracingConfigurationInventoryGeneratorTest.java` (N12 note)

### Evidence
- `docs/research/evidence/p6-cutover/p68-perf-test-mutation-deletion.md`

## Residual mutation APIs (production)

Intended survivors (not deleted by this slice):
- `AcceptedPolicyAccess.updateSampling` / `updateValidation` / `updateExporter` (and related Category A store primitives)
- Private control plane `applySnapshot`
- Spring Actuator / Config Server / Perf private-control delivery adapters (typed complete-snapshot path)

Deleted in this slice (no production survivors):
- `SafeSpanExporter.setExportEnabled`
- `ValidatingSpanProcessor.updateValidationPolicy`
- `ValidatingSpanProcessor.tryApplyPolicyUpdate`
