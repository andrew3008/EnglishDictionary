# Platform Tracing — Wire schema v1 (validated Map contract)

## Document status

```text
Status: PR-2 — Wire schema v1 / validated Map contract in platform-tracing-api
Scope: JDK-only wire constants, schema descriptors, and validators for future private JMX control path.
This PR defines schema and validators only.
This PR does not change PlatformTracingControl runtime behavior.
This PR does not change SamplingControlClient runtime behavior.
```

**Related documents:**

- [platform-tracing-preservation-first-migration-plan.md](platform-tracing-preservation-first-migration-plan.md)
- [platform-tracing-module-taxonomy.md](platform-tracing-module-taxonomy.md)
- [platform-tracing-current-codebase-inventory.md](platform-tracing-current-codebase-inventory.md)
- [pr-0-baseline-results-2026-06-12-complete-or-waived.md](pr-0-baseline-results-2026-06-12-complete-or-waived.md)

---

## 1. Purpose

PR-2 defines the **first stable JDK-only wire contract** for cross-classloader tracing control:

```text
Application ClassLoader
  -> SamplingControlClient (unchanged in PR-2)
  -> private in-process JMX (future PR-3)
  -> validated Map / OpenMBean-compatible payload
  -> PlatformTracingControl in Agent ClassLoader (unchanged in PR-2)
```

The contract lives in `platform-tracing-api` so **both** classloaders can share string keys and validation rules without importing each other's implementation types.

---

## 2. Scope of PR-2

| In scope | Out of scope |
|----------|--------------|
| `TracingControlProtocolKeys`, `TracingControlProtocolSchema`, `TracingControlProtocolValidator` (PR-2 names were `TracingControlWire*`; see §12) | JMX runtime migration (PR-3) |
| Unit tests in `platform-tracing-api` | `PlatformTracingControl` / `SamplingControlClient` changes |
| Key/type classification (policy vs topology vs diagnostics) | `TracingConfigReconciler` (PR-10) |
| Strict unknown-key rejection policy | Actuator mutation guardrails (PR-11) |

---

## 3. Non-goals

- No runtime behavior change
- No MBean method additions
- No Map wire round-trip through JMX (PR-3 spike)
- No extraction / hot-path refactoring (PR-6+)
- Full JMH baseline remains deferred (required before PR-5/PR-6/PR-7/PR-8)

---

## 4. Why raw DTOs across ClassLoader boundary are forbidden

OpenTelemetry Java Agent loads `platform-tracing-otel-extension` in the **Extension ClassLoader**. Spring starter code runs in the **Application ClassLoader**. A `Map<String, Object>` containing a custom Java object from App CL cannot be safely consumed in Agent CL (`ClassCastException`, `instanceof` failures, silent data loss).

Wire payloads must use **OpenMBean-compatible open types** only: primitives wrappers, `String`, `String[]`, and explicitly allowlisted maps (e.g. `sampling.routeRatios` as `Map<String, Double>`).

---

## 5. Allowed wire types

| Wire type | Java representation |
|-----------|---------------------|
| String | `java.lang.String` |
| Boolean | `java.lang.Boolean` |
| Integer | `java.lang.Integer` (also narrow `Long` where in range) |
| Long | `java.lang.Long` (also narrow `Integer`) |
| Double | `java.lang.Double` (ratio fields: `[0.0, 1.0]`) |
| String array | `java.lang.String[]` (not `List`) |
| Route ratios map | `Map<String, Double>` (explicit allowlist) |

---

## 6. Forbidden wire types

- Arbitrary Java DTOs / custom classes
- `Enum` instances (use `String` wire values, e.g. `validation.mode=WARN`)
- `Class`, `ClassLoader`, serialized blobs
- Collections with non-`String` keys
- Nested maps (except allowlisted `sampling.routeRatios`)
- `List` where `String[]` is specified

---

## 7. Runtime policy vs startup topology

| Category | Runtime apply via wire? | Examples |
|----------|-------------------------|----------|
| **Envelope** | Required metadata only | `contractVersion`, `operation`, `source` |
| **Runtime policy** | Yes (when present) | `sampling.ratio`, `scrubbing.enabled`, `validation.mode` |
| **Startup topology** | **Rejected** on runtime apply | `exporter.endpoint`, `sdk.mode`, `queue.size` |
| **Diagnostic** | Read requests only | `diagnostics.requestId`, `diagnostics.timestamp` |

Topology fields require redeploy / Helm / env changes — not JMX wire apply. PR-10 reconciler will use the same classification.

---

## 8. v1 keys table

### Envelope

| Key | Type | Required (runtime apply) | Notes |
|-----|------|--------------------------|-------|
| `contractVersion` | Integer or String `"1"` | Yes | Must equal `1` |
| `operation` | String | Yes | See operations below |
| `source` | String | No | Audit trail (`JMX`, `config-server`, …) |

### Operations

| Value | Use |
|-------|-----|
| `APPLY_RUNTIME_POLICY` | Future apply path (PR-3+) |
| `VALIDATE_RUNTIME_POLICY` | Dry-run validation |
| `READ_APPLIED_STATE` | Read request |
| `READ_SCHEMA` | Schema introspection |

### Runtime policy — sampling

| Key | Type | Maps to current MBean |
|-----|------|------------------------|
| `sampling.ratio` | Double `[0,1]` | `updateSamplingPolicy` / `setSamplingRatio` |
| `sampling.routeRatios` | `Map<String,Double>` | `routeRatios` |
| `sampling.killSwitch.enabled` | Boolean | `enabled` / `isSamplerEnabled` |
| `sampling.qaTrace.enabled` | Boolean | Future reconciler field |
| `sampling.forceHeader.enabled` | Boolean | Force-record gate |
| `sampling.forceHeader.values` | `String[]` | `forceValues` / `setForceRecordValues` |
| `sampling.dropPathPrefixes` | `String[]` | `dropPaths` / `setDropPathPrefixes` |

### Runtime policy — scrubbing / validation / enriching

| Key | Type | Maps to current MBean |
|-----|------|------------------------|
| `scrubbing.enabled` | Boolean | `updateScrubbingPolicy` |
| `scrubbing.mode` | String | **Requires manual review** (semantics TBD) |
| `scrubbing.ruleNames` | `String[]` | `updateScrubbingPolicy` ruleNames |
| `validation.enabled` | Boolean | `updateValidationPolicy` |
| `validation.mode` | String | `STRICT` / `WARN` / `DISABLED` |
| `validation.strict` | Boolean | `updateValidationPolicy` strict flag |
| `enriching.enabled` | Boolean | Future reconciler field |
| `export.enabled` | Boolean | `setExportEnabled` |
| `propagation.enabled` | Boolean | `setPropagationEnabled` |

### Diagnostics (read-only)

| Key | Type |
|-----|------|
| `diagnostics.requestId` | String |
| `diagnostics.timestamp` | Long (epoch ms) |

### Startup topology (known keys, rejected on runtime apply)

| Key | Type |
|-----|------|
| `exporter.endpoint` | String |
| `exporter.protocol` | String |
| `exporter.queue.size` | Integer |
| `sdk.mode` | String |
| `queue.size` | Integer |

---

## 9. Validation behavior

Entry point: `TracingControlProtocol.current().validator()` (`TracingControlProtocolValidator`):

- `validateRuntimePolicy(Map)` — apply/validate operations; policy + envelope keys
- `validateReadRequest(Map)` — read operations; envelope + diagnostic keys only

Failures return `TracingControlProtocolValidationResult` with `valid=false` and `TracingControlProtocolViolation` list (`key`, `code`, `reason`, `expectedType`, `actualType`). Normal validation does **not** throw.

Minimal required keys for runtime apply: **`contractVersion`**, **`operation`**. All policy fields are optional but validated when present.

---

## 10. Unknown key behavior

**Strict v1 (PR-2 decision):** unknown keys are **rejected**. This differs from a permissive "log and ignore" policy — strict rejection is chosen so PR-3/PR-10 can detect schema drift early. PR-3 spike may document alternative policies if runtime evidence requires relaxation.

---

## 11. Future usage

| PR | Usage |
|----|-------|
| **PR-3** | Cross-CL JMX wire spike: spike MBean + E2E proof — see [ADR-jmx-wire-map-contract.md](ADR-jmx-wire-map-contract.md) |
| **PR-10** | `TracingConfigReconciler` builds validated Map from `TracingProperties` policy fields |
| **PR-11** | Actuator mutation guardrails consume validation result before JMX invoke |

---

## 12. API package

```text
space.br1440.platform.tracing.api.control.protocol
  TracingControlProtocol                    // single public entrypoint
  version/TracingControlProtocolVersion
  schema/TracingControlProtocolKeys
  schema/TracingControlProtocolFieldType
  schema/TracingControlProtocolOperation
  schema/TracingControlProtocolFieldCategory
  schema/TracingControlProtocolFieldDescriptor
  schema/TracingControlProtocolSchema
  validation/TracingControlProtocolValidator
  validation/TracingControlProtocolViolationCode
  result/TracingControlProtocolValidationResult
  result/TracingControlProtocolViolation
```

> Historical note: PR-2 originally introduced `api.control.wire` with `TracingControlWire*` types. That package was removed; see [ADR-control-protocol-version-model.md](../decisions/ADR-control-protocol-version-model.md).

---

## 13. Explicit non-goals (PR-2)

```text
This PR defines schema and validators only.
This PR does not change PlatformTracingControl runtime behavior.
This PR does not change SamplingControlClient runtime behavior.
This PR does not change RuntimeConfigApplier or TracingActuatorEndpoint.
Full JMH baseline remains required before extraction PRs (PR-5/PR-6/PR-7/PR-8).
```

---

*PR-2 complete. Proceed to PR-3 (JMX wire spike) per preservation-first migration plan.*
