# Tracing control protocol — implementation report

## Overview

Implementation of the protocol refactor (Phases 0–5) and unified naming hardening (Phase 6 / Variant A) for `space.br1440.platform.tracing.api.control.protocol`.

---

## Phases 0–5 (protocol refactor)

| Phase | Status | Summary |
|-------|--------|---------|
| 0 | Done | Characterization tests baseline |
| 1 | Done | `TracingControlProtocolVersion` record + parse |
| 2 | Done | `TracingControlProtocol` aggregate + transitional bridge |
| 3 | Done | Descriptor-driven schema, operation-aware requiredness |
| 4 | Done | Validator, violation codes, result types |
| 5 | Done | Removed `api.control.wire`, V1 singletons, `WireV1Bridge` |

---

## Unified Naming Phase

### Types renamed

| Old | New |
|-----|-----|
| `ControlWireProtocolVersion` | `TracingControlProtocolVersion` |
| `ControlWireFieldCategory` | `TracingControlProtocolFieldCategory` |
| `ControlWireFieldDescriptor` | `TracingControlProtocolFieldDescriptor` |
| `WireViolationCode` | `TracingControlProtocolViolationCode` |
| `TracingControlValidationResult` | `TracingControlProtocolValidationResult` |
| `TracingControlViolation` | `TracingControlProtocolViolation` |

Unchanged: `TracingControlProtocol`, `TracingControlProtocolSchema`, `TracingControlProtocolValidator`, `TracingControlProtocolKeys`, `TracingControlProtocolTypes`, `TracingControlProtocolOperation`.

### Field renamed

- `TracingControlProtocolFieldDescriptor.wireType` → `type`
- Javadoc: `type` means `TracingControlProtocolTypes`, not Java `Class<?>` and not arbitrary value type.

### ArchUnit rules added/fixed

**Added (shared `ArchitectureFitnessArchRules` + `ApiProtocolPackagePurityArchTest`):**

- `PROTOCOL_API_TYPES_USE_UNIFIED_PREFIX` — public top-level production types under `api.control.protocol..` must start with `TracingControlProtocol`
- `PROTOCOL_API_TYPES_DO_NOT_USE_WIRE_NAMING` — same scope; simple name must not contain `Wire`

**Fixed (replaced `.should().beInterfaces()` hack):**

- No public top-level `*Registry` in protocol package
- No typed DTO boundary (`*Command`, `*Dto`, `*Request`)
- No public V1 schema/validator singletons

**Renamed constants:** `API_WIRE_*` → `API_PROTOCOL_*`

### Docs / ADR updated

- `docs/architecture/platform-tracing-fitness-functions-implementation.md` — FF-01/02, FF-01b/01c, protocol package note
- `docs/architecture/platform-tracing-wire-schema-v1.md` — validation behavior, API package listing
- `docs/architecture/ADR-jmx-wire-map-contract.md` — protocol type references
- `docs/decisions/ADR-control-protocol-version-model.md` — created (naming taxonomy, Map boundary, version model, violations, registry, no DTO/shims)
- `docs/analysis/tracing-control-protocol-refactoring-plan.md` — §20 Phase 6 addendum

### Acceptance grep results

**Scope:** `src/main`, live `src/test` (excluding e2e harness class/package names), `docs/architecture/**`, `docs/decisions/**`

**Patterns:** `ControlWire`, `WireViolationCode`, `WireV1Bridge`, `TracingControlValidationResult`, `TracingControlViolation`, `TracingControlWire`, `api.control.wire`, `CURRENT_VERSON_STRING`, `CURRENT_VERSION_NUMBER`

**Result:** Zero matches in `src/main` and live `src/test` (ArchUnit legacy-package test intentionally references removed `api.control.wire`).

**`docs/architecture/**`:** Updated live docs (fitness functions, wire-schema §9/§12, ADR-jmx, core-extraction-readiness). Remaining matches are **historical narrative only** in migration/spike-purge plans (`TracingControlWireSpike*`, preservation-first migration plan PR-2 sections) — not current API guidance.

**`docs/decisions/**`:** ADR mentions old names only in before/after historical context (acceptable).

**Allowed exceptions:** historical `docs/analysis/*`, e2e `WireRoundTrip*` class names, e2e package `jmx.wire`.

### Tests run

```text
./gradlew.bat :platform-tracing-api:compileJava
./gradlew.bat :platform-tracing-api:compileTestJava
./gradlew.bat :platform-tracing-api:test
./gradlew.bat :platform-tracing-api:test --tests "*TracingControlProtocol*"
./gradlew.bat pr4ArchitectureFitnessVerify
./gradlew.bat :platform-tracing-spring-boot-autoconfigure:test --tests "*SamplingControlClientWireContract*"
./gradlew.bat :platform-tracing-api:javadoc
```

### Remaining risks

1. **Historical analysis docs** (`docs/analysis/tracing-control-wire-package-inventory.md`, etc.) still describe old `TracingControlWire*` names — intentional; not part of live grep gate.
2. **Other architecture docs** (preservation-first migration plan, core extraction readiness, jmx-spike plan) may retain historical wire references; update opportunistically if those docs are treated as live guidance.
3. **E2e Javadoc comments** in `WireRoundTripTestMBean.java` may still mention old validator names in narrative comments only.
4. **Coercion policy** for real MBean clients remains NEEDS_VERIFICATION per original plan.

---

## Before / after (production API)

**Before (removed):**

```text
space.br1440.platform.tracing.api.control.wire
  TracingControlWireValidator.V1
  TracingControlWireSchema.V1
  TracingControlWireValidationResult
  TracingControlWireViolation
```

**After:**

```text
space.br1440.platform.tracing.api.control.protocol
  TracingControlProtocol.current().validator()
  TracingControlProtocol.current().schema()
  TracingControlProtocolValidationResult
  TracingControlProtocolViolation
```

---

*Report last updated: unified naming Phase 6 completion.*
