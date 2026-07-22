# Tracing Control Protocol — Wire Specification v1

> **Status:** Stable · **Module:** `platform-tracing-api`  
> **Source of truth:** `TracingControlProtocolSchema.v1()` (package-private)  
> **Wire transport:** JMX `CompositeData` → `Map<String,Object>` / in-process `Map<String,Object>`  
> **Maintainer:** Platform Tracing team

---

## 1. Purpose and scope

This document describes the **wire contract** of the platform-tracing JMX/Map
control protocol — the Map-based, classloader-neutral protocol used to manage
runtime tracing policy across all services via JMX or direct in-process calls.

This spec is intentionally **not** exposed as a runtime `READ_SCHEMA`
operation. Consumers should rely on this document, generated tooling, and
the `TracingControlProtocolDecoder` API to interact with the protocol.
IntroSpection at the MBean level is available via standard JMX
`MBeanServer.getMBeanInfo()` / `OpenType` on the `PlatformControlProtocolMBean`.

---

## 2. Transport

| Transport | Payload type | Entry point |
|---|---|---|
| JMX | `javax.management.openmbean.CompositeData` | `PlatformControlProtocolMBean.applyPolicy(CompositeData)` |
| In-process (Map) | `java.util.Map<String, Object>` | `TracingControlProtocolDecoder.decode(Map)` |

For JMX transport, the `CompositeData` is converted to a `Map<String,Object>`
by the MBean adapter before decode. Key names and value types are identical
in both transports.

---

## 3. Versioning

The protocol uses a single **`contractVersion`** integer field in every request.

| Field | Type | Required | Description |
|---|---|---|---|
| `contractVersion` | `Integer` | **Yes** | Protocol major version. Current: **`1`** |

Version compatibility rules:

- A missing `contractVersion` produces `MISSING_REQUIRED_KEY`.
- A present but non-numeric / unparsable `contractVersion` produces
  `INVALID_VALUE`.
- The decoder accepts **major version `1`** only. Any other parsed numeric
  value causes an `UNSUPPORTED_VERSION` violation and the decode is rejected.
- Minor/patch evolution within v1 is backwards-compatible (new optional keys
  may be added; existing keys are not removed).
- A new major (v2) will require a new `TracingControlProtocolDecoder.v2()` and
  a separate schema definition. v1 and v2 decoders may coexist in the same JVM.

---

## 4. Envelope fields

Every request **must** carry these fields, regardless of operation:

| Key | Wire type | JMX OpenType | Required | Description |
|---|---|---|---|---|
| `contractVersion` | `Integer` | `SimpleType.INTEGER` | **Yes** | Protocol version. Must be `1`. |
| `operation` | `String` | `SimpleType.STRING` | **Yes** | Operation name. See §5. |
| `source` | `String` | `SimpleType.STRING` | No | Caller identifier, e.g. `"ops-console"`. Used for audit logging. |

All other fields are operation-specific (see §6 and §7).

---

## 5. Operations

| Operation name | Description | Mutates state |
|---|---|---|
| `APPLY_RUNTIME_POLICY` | Decode, domain-validate, then apply the supplied policy to the live runtime. | **Yes** |
| `VALIDATE_RUNTIME_POLICY` | Decode and domain-validate the policy without applying it. Dry-run. | No |
| `READ_APPLIED_STATE` | Read the currently applied runtime policy as a `CompositeData` snapshot. No policy fields required. | No |

Unknown operation names produce an `OPERATION_NOT_ALLOWED` violation.

### 5.1 Runtime mutation policy

`APPLY_RUNTIME_POLICY` is fail-closed by default. The runtime adapter evaluates
the mutation policy only after successful decode and domain validation, and
before invoking an applier. With the default startup configuration, the JMX
result is `MUTATION_REJECTED APPLY_RUNTIME_POLICY` and includes the reason;
the applied snapshot, version, and source remain unchanged.

Enable unified control-protocol mutations explicitly at JVM startup:

```text
-Dplatform.tracing.control.runtime-mutation.enabled=true
```

The equivalent environment variable is
`PLATFORM_TRACING_CONTROL_RUNTIME_MUTATION_ENABLED=true`. `READ_APPLIED_STATE`
and `VALIDATE_RUNTIME_POLICY` remain available when mutations are disabled;
`VALIDATE_RUNTIME_POLICY` never invokes an applier.

For rollback, remove the startup property (or set it to `false`) and restart
the service. This setting gates the unified control-protocol MBean only; it
does not replace JVM/JMX network and authorization controls, nor does it
protect legacy domain MBeans that are outside this protocol boundary.

---

## 6. APPLY_RUNTIME_POLICY / VALIDATE_RUNTIME_POLICY field reference

Both operations share the same allowed-key set. All policy fields are **optional**
(only envelope keys are required). Unrecognised keys are **rejected** with
`UNKNOWN_KEY`.

### 6.1 Sampling fields

| Key | Wire type | JMX OpenType | Description |
|---|---|---|---|
| `sampling.ratio` | `Double` | `SimpleType.DOUBLE` | Global default sampling ratio. Domain constraint: `[0.0, 1.0]`. |
| `sampling.routeRatios` | `Map<String,Double>` | `TabularType` (key=`String`, value=`Double`) | Per-route sampling overrides. Domain: each value `[0.0, 1.0]`; sum may exceed 1.0. |
| `sampling.killSwitch.enabled` | `Boolean` | `SimpleType.BOOLEAN` | When `true`, all spans are dropped regardless of other settings. |
| `sampling.qaTrace.enabled` | `Boolean` | `SimpleType.BOOLEAN` | Enable QA-header-triggered full trace capture. |
| `sampling.forceHeader.enabled` | `Boolean` | `SimpleType.BOOLEAN` | Enable force-sampling via HTTP header. |
| `sampling.forceHeader.values` | `String[]` | `ArrayType(1, SimpleType.STRING)` | Allowed header values that trigger force-sample. |
| `sampling.dropPathPrefixes` | `String[]` | `ArrayType(1, SimpleType.STRING)` | URL path prefixes whose spans are unconditionally dropped. |

### 6.2 Scrubbing fields

| Key | Wire type | JMX OpenType | Description |
|---|---|---|---|
| `scrubbing.enabled` | `Boolean` | `SimpleType.BOOLEAN` | Enable/disable scrubbing processor at runtime. |
| `scrubbing.mode` | `String` | `SimpleType.STRING` | Scrubbing mode string. Recognised values: `"STRICT"`, `"LENIENT"`. |
| `scrubbing.ruleNames` | `String[]` | `ArrayType(1, SimpleType.STRING)` | Names of built-in scrubbing rules to activate. |

### 6.3 Validation fields

| Key | Wire type | JMX OpenType | Description |
|---|---|---|---|
| `validation.enabled` | `Boolean` | `SimpleType.BOOLEAN` | Enable/disable span validation processor at runtime. |
| `validation.mode` | `String` | `SimpleType.STRING` | Validation mode. Recognised values: `"STRICT"`, `"LOG_ONLY"`. |
| `validation.strict` | `Boolean` | `SimpleType.BOOLEAN` | Shorthand to flip strict mode without changing `validation.mode`. |

### 6.4 Feature-flag fields

| Key | Wire type | JMX OpenType | Description |
|---|---|---|---|
| `enriching.enabled` | `Boolean` | `SimpleType.BOOLEAN` | Enable/disable span enrichment processor. |
| `export.enabled` | `Boolean` | `SimpleType.BOOLEAN` | Enable/disable span export (exporter on/off switch). |
| `propagation.enabled` | `Boolean` | `SimpleType.BOOLEAN` | Enable/disable context propagation. |

### 6.5 Diagnostics fields (optional, all operations)

| Key | Wire type | JMX OpenType | Description |
|---|---|---|---|
| `diagnostics.requestId` | `String` | `SimpleType.STRING` | Caller-supplied correlation ID, echoed in the response and logs. |
| `diagnostics.timestamp` | `Long` | `SimpleType.LONG` | Caller-supplied epoch-millis timestamp for audit/ordering. |

---

## 7. READ_APPLIED_STATE field reference

Only envelope fields are allowed in the request. No policy or diagnostics
fields should be sent. Policy fields in a `READ_APPLIED_STATE` payload
produce `UNKNOWN_KEY` violations.

**Response:** A read-only diagnostic `CompositeData` snapshot. The current
implementation does not include `contractVersion` in this response. All values
are exposed as `String` values by the JMX adapter for maximum client
compatibility; this response is not a round-trip write payload.

| Key | JMX value type | Description |
|---|---|---|
| `sampling.ratio` | `String` | Currently applied default sampling ratio, string-coerced. |
| `sampling.killSwitch.enabled` | `String` | Whether sampler kill-switch is active, string-coerced. |
| `sampling.routeRatios` | `String` | Current route-ratio map, string-coerced. |
| `sampling.dropPathPrefixes` | `String` | Current drop-path prefixes, string-coerced. |
| `sampling.forceHeader.values` | `String` | Current force-header values, string-coerced. |
| `validation.enabled` | `String` | Whether span validation is enabled, string-coerced. |
| `validation.strict` | `String` | Whether strict mode is active, string-coerced. |
| `validation.mode` | `String` | Current validation mode (`"STRICT"` or `"LOG_ONLY"`). |
| `_meta.*` | `String` | Implementation metadata such as config version/source, string-coerced. |

---

## 8. JMX Open-Type mapping

The following Java wire types are mapped to JMX OpenTypes by the
`FieldTypeSupport` utility in `platform-tracing-api`:

| `TracingControlProtocolFieldType` | Java wire type | JMX OpenType |
|---|---|---|
| `INTEGER` | `java.lang.Integer` | `SimpleType.INTEGER` |
| `STRING` | `java.lang.String` | `SimpleType.STRING` |
| `DOUBLE` | `java.lang.Double` | `SimpleType.DOUBLE` |
| `BOOLEAN` | `java.lang.Boolean` | `SimpleType.BOOLEAN` |
| `LONG` | `java.lang.Long` | `SimpleType.LONG` |
| `STRING_ARRAY` | `String[]` or `List<String>` | `ArrayType(1, SimpleType.STRING)` |
| `ROUTE_RATIOS_MAP` | `Map<String,Double>` or `TabularData` | `TabularType` (composite: `{route: String, ratio: Double}`) |

For `ROUTE_RATIOS_MAP` arriving via JMX as `TabularData`, the decoder
normalises each row to a `Map.Entry<String, Double>` before domain validation.

---

## 9. Violation codes

Structural violations returned in `TracingControlProtocolDecodeResult.violations()`:

| Code | When raised |
|---|---|
| `UNSUPPORTED_VERSION` | `contractVersion` is a parsed numeric major version other than `1`. |
| `MISSING_REQUIRED_KEY` | A required envelope key (`contractVersion`, `operation`) is absent from the payload. |
| `OPERATION_NOT_ALLOWED` | The `operation` value is not one of the recognised operation names. |
| `UNKNOWN_KEY` | The payload contains a key not declared in the operation's allowed-key set. |
| `TYPE_MISMATCH` | A key's value cannot be coerced to its declared wire type (e.g. String where Double expected). |
| `INVALID_VALUE` | A value is structurally well-typed but violates a basic structural constraint (e.g. null inside a route-ratios map). |

For `contractVersion`, malformed or unparsable values are `INVALID_VALUE`;
missing values are `MISSING_REQUIRED_KEY`.

> **Note:** Domain violations (sampling ratio out of `[0,1]`, conflicting
> flags, etc.) are **not** part of the decode result. They are produced
> separately by domain validators in `platform-tracing-otel` and carry their
> own error types.

---

## 10. Decode pipeline

```
JMX CompositeData  ─┐
In-process Map     ─┴──►  TracingControlProtocolDecoder.decode(Map)
                                │
                    ┌───────────▼────────────────────────────────┐
                    │  STRUCTURAL DECODE  (platform-tracing-api) │
                    │  1. Null / empty check                      │
                    │  2. contractVersion: present? Integer? ==1? │
                    │  3. operation: present? String? in allowlist?│
                    │  4. Unknown key rejection                    │
                    │  5. Required keys per operation              │
                    │  6. Per-field type coercion / normalisation  │
                    │  7. Open-type coercion (TabularData → Map)  │
                    └───────────┬────────────────────────────────┘
                                │  TracingControlProtocolDecodeResult
                                │  { valid, operation, normalizedPayload,
                                │    violations }
                                ▼
                    ┌───────────────────────────────────────────┐
                    │  DOMAIN VALIDATION  (platform-tracing-otel) │
                    │  sampling ratio bounds [0.0, 1.0]          │
                    │  route-ratio semantics                     │
                    │  validation mode semantics                 │
                    │  cross-field policy rules                  │
                    └───────────┬───────────────────────────────┘
                                │  PolicyValidationResult
                                │  { valid, violations }
                                ▼
                    ┌───────────────────────────────────────────┐
                    │  APPLY  (only if decode.valid              │
                    │          && domain.valid)                  │
                    │  RuntimePolicyControlHandler.handle()      │
                    │  → JmxRuntimePolicyApplier.apply()         │
                    │  → PlatformSamplingControl.apply()         │
                    │  → PlatformValidationControl.apply()       │
                    └───────────────────────────────────────────┘
```

---

## 11. Compatibility and evolution rules

1. **Additive only within v1.** New optional keys may be added to the v1
   schema. Existing keys must not be removed, renamed, or have their types
   changed in a v1 decoder.

2. **Unknown-key policy is REJECT by default.** Clients must not send
   keys that are not declared in this spec — they will receive `UNKNOWN_KEY`
   violations and the decode will fail.

3. **Domain constraint changes do not affect wire schema.** Tightening or
   relaxing `[0,1]` bounds, allowed mode values, etc., is a domain change
   and is handled independently in `platform-tracing-otel` without touching
   the v1 wire schema.

4. **Major version bump (v2).** A new `contractVersion: 2` requires a new
   `TracingControlProtocolSchema.v2()` and `TracingControlProtocolDecoder.v2()`.
   The v1 decoder is unmodified. Both may coexist in the same JVM.

5. **Pre-production stability.** Until the GA release of the platform, the
   wire schema may receive breaking changes under a coordinated rollout.
   After GA, the rules above apply strictly.

---

## 12. Minimal JMX invocation example

The following pseudocode illustrates constructing and sending an
`APPLY_RUNTIME_POLICY` payload via JMX:

```java
// 1. Describe the CompositeType
String[]        keys   = { "contractVersion", "operation", "sampling.ratio",
                           "diagnostics.requestId" };
OpenType<?>[]   types  = { SimpleType.INTEGER, SimpleType.STRING,
                           SimpleType.DOUBLE,  SimpleType.STRING };
CompositeType   ct     = new CompositeType("Policy", "Policy",
                              keys, keys, types);

// 2. Build the payload
CompositeData   payload = new CompositeDataSupport(ct, keys,
        new Object[]{ 1, "APPLY_RUNTIME_POLICY", 0.25d, "req-abc" });

// 3. Invoke via JMX
MBeanServer server  = ManagementFactory.getPlatformMBeanServer();
ObjectName  name    = new ObjectName(
        "space.br1440.platform.tracing:type=ControlProtocol");
String      result  = (String) server.invoke(
        name, "applyPolicy",
        new Object[]{ payload },
        new String[]{ CompositeData.class.getName() });

// result: "SUCCESS" or "DECODE_REJECTED: ..." or "DOMAIN_REJECTED: ..."
```

---

## 13. Related files

| Artifact | Role |
|---|---|
| `TracingControlProtocolSchema` (package-private) | Internal source of truth for all allowed keys, types, required sets |
| `TracingControlProtocolDecoder` | Public structural decoder; produces `DecodeResult` |
| `TracingControlProtocolDecodeResult` | Immutable decode output: valid flag, operation, normalizedPayload, violations |
| `TracingControlProtocolViolationCode` | Enum of all structural violation codes |
| `TracingControlProtocolKeys` | String constants for all wire keys |
| `TracingControlProtocolFieldType` | Enum of supported wire field types |
| `JmxRuntimePolicyApplier` | Applies normalised policy to live JMX controls |
| `RuntimePolicyControlHandler` | Orchestrates decode → domain-validate → apply pipeline |
| `PlatformControlProtocolMBean` | JMX entry point; bridges `CompositeData` → `Map` → decoder |
