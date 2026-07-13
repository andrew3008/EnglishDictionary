# Inventory: TracingControlProtocolValidator

**Document type:** Read-only architecture inventory (pre-refactor)  
**Date:** 2026-07-03  
**Scope:** `space.br1440.platform.tracing.api.control.protocol.validation.TracingControlProtocolValidator` and adjacent protocol code  
**Status:** Factual baseline for architect / external LLM review — **no refactoring design chosen**

---

## 1. Executive Summary

`TracingControlProtocolValidator` (423 lines, 15 methods) is the **sole Map payload validator** for the tracing control protocol v1. It validates `Map<String, Object>` payloads against a injected `TracingControlProtocolSchema`, returns non-throwing `TracingControlProtocolValidationResult`, and on success supplies a **normalized** payload map.

**Construction:** One production instance is created in `TracingControlProtocol.Registry` (`TracingControlProtocol.java:51–55`) via `new TracingControlProtocolValidator(SCHEMA_V1)`. Grep for `new TracingControlProtocolValidator` finds **only this site** in the repository.

**Caller path:** Intended entry is `TracingControlProtocol.current().validator()` → `validateRuntimePolicy(...)` or `validateReadRequest(...)`. All tests and e2e harnesses use `current().validator()`; none construct the validator directly except `TracingControlProtocol.Registry`.

**State:** Holds one `final TracingControlProtocolSchema schema` field. Schema is immutable after construction. Validator is **stateless per call** and **thread-safe** assuming schema immutability.

**Complexity drivers:**

1. `validatePayload` (~125 lines, L56–185) — orchestrates envelope, category policy, required keys, and delegates type validation.
2. Hardcoded operation allowlists (`RUNTIME_MUTATION_OPERATIONS`, `READ_OPERATIONS`) duplicate string constants from `TracingControlProtocolKeys`.
3. Direct static calls to `TracingControlProtocol.isSupported(...)` and `TracingControlProtocol.current().version().major()` couple version support to the global protocol registry.
4. Type validation is a flat `switch` over `TracingControlProtocolFieldType` plus static helpers; domain rules (ratio bounds, `validation.mode` allowlist) are embedded in helpers.
5. Invalid results **discard all partial normalization** (`TracingControlProtocolValidationResult.invalid` → empty map).

**Test coverage:** 24 unit tests in `TracingControlProtocolValidatorTest`; 3 violation-code tests in `TracingControlProtocolViolationTest`; indirect coverage via e2e JMX round-trip and `SamplingControlClientWireContractTest`. Several coercion and edge-case paths are **untested** (see §13).

**Package-private internals:** No separate `internal` subpackage under `api.control.protocol`. Only nested `@UtilityClass Registry` inside `TracingControlProtocol` constructs schema/validator.

---

## 2. Public API and Entry Points

### 2.1 Public surface

| Element | Location | Visibility | Notes |
|---------|----------|------------|-------|
| Constructor | `TracingControlProtocolValidator.java:36–38` | `public` | Requires non-null `TracingControlProtocolSchema` |
| `validateRuntimePolicy(Map<String,Object>)` | L40–46 | `public` | Runtime mutation / validate operations |
| `validateReadRequest(Map<String,Object>)` | L48–54 | `public` | Read operations; rejects runtime policy fields |

All other methods are `private` or `private static`.

**Method count:** **15 methods** (2 public entry methods + 13 private/static helpers). **1 public constructor** (not counted as method).

### 2.2 Expected caller path

```
TracingControlProtocol.current()
  .validator()
  .validateRuntimePolicy(payload)   // or validateReadRequest(payload)
```

Documented in:

- `docs/architecture/platform-tracing-wire-schema-v1.md` §9
- `docs/decisions/ADR-control-protocol-version-model.md`
- `docs/architecture/ADR-jmx-wire-map-contract.md`

### 2.3 Actual usages (grep evidence)

| Location | Usage pattern |
|----------|---------------|
| `TracingControlProtocol.java:54` | `new TracingControlProtocolValidator(SCHEMA_V1)` — **only direct construction** |
| `TracingControlProtocolValidatorTest` | `TracingControlProtocol.current().validator()` |
| `TracingControlProtocolViolationTest` | `current().validator().validateRuntimePolicy(...)` |
| `WireRoundTripTestMBeanImpl.java:25–29` | static `VALIDATOR = current().validator()`; `validateRuntimePolicy` |
| `SamplingControlClientWireContractTest.java:89` | `current().validator().validateRuntimePolicy` in test stub |
| `WireRoundTripInProcessTest` | JMX invoke → MBean impl → validator (indirect) |
| `ApiProtocolNoImplementationDependencyArchTest.java:49–51` | Asserts `current().validator()` type |

**Production consumers outside `platform-tracing-api`:** **None** in `src/main`. FF-09 (`ArchitectureFitnessArchRules.PRODUCTION_AUTOCONFIGURE_NO_WIRE_VALIDATOR`) forbids production autoconfigure dependency on validator class name; test-only usage in autoconfigure tests is allowed.

### 2.4 Thread safety and split feasibility

| Question | Answer |
|----------|--------|
| Stateless? | Yes — per-invocation locals only; immutable schema reference |
| Thread-safe? | Yes — if schema is immutable (confirmed: unmodifiable field map in schema) |
| Public constructor required? | Open question — currently public; only `TracingControlProtocol.Registry` constructs in production |
| Splittable without API break? | Pre-production: breaking changes allowed; extraction into package-private collaborators is feasible if entry methods and result semantics preserved |

---

## 3. Current Responsibilities

| # | Responsibility | Current method(s) | Input | Output | Violation code(s) | Normalized output | Extraction target | Risk if changed |
|---|----------------|-------------------|-------|--------|-------------------|-------------------|-------------------|-----------------|
| 1 | Payload null handling | `validatePayload` L60–62 → `missingRequiredKeys` L187–199 | `null` map | `invalid` with one violation per required key | `MISSING_REQUIRED_KEY` | Empty map | `NullPayloadHandler` / context | HIGH — ADR documents null payload behavior |
| 2 | Required-key handling (post-iteration) | `validatePayload` L170–179; `missingRequiredKeys` | Map missing keys | Violations per missing key | `MISSING_REQUIRED_KEY` | N/A until valid | `RequiredKeyValidator` | HIGH |
| 3 | Map-key type validation | `validatePayload` L68–76 | Non-`String` map key | Violation key `"<map>"` | `TYPE_MISMATCH` | Key skipped | `MapKeyValidator` | MEDIUM |
| 4 | Unknown-key rejection (strict v1) | `validatePayload` L78–86 | Unknown string key | Violation per key | `UNKNOWN_KEY` | Key skipped | Schema-driven or policy step | CRITICAL — documented strict policy |
| 5 | Startup topology rejection | `validatePayload` L89–97 | Known topology key | Violation | `OPERATION_NOT_ALLOWED` | Key skipped | Category policy / context | HIGH |
| 6 | Runtime policy in read request | `validatePayload` L99–108 | Runtime policy key when `allowRuntimePolicyFields=false` | Violation | `OPERATION_NOT_ALLOWED` | Key skipped | Entrypoint context | HIGH |
| 7 | `contractVersion` parsing | `validatePayload` L111–130; delegates `TracingControlProtocolVersion.parse` | Raw value | Parse empty → violation; supported → normalize | `INVALID_VALUE`, `UNSUPPORTED_VERSION` | `Integer` major | `ContractVersionValidator` | CRITICAL — ADR parse/support split |
| 8 | Version support check | L120–126; `TracingControlProtocol.isSupported` | Parsed version | Unsupported violation | `UNSUPPORTED_VERSION` | — | Protocol registry injection | HIGH — global coupling |
| 9 | Version normalization | L128 | Supported parsed version | Puts `parsed.get().major()` (int → `Integer`) | — | `Integer` in normalized map | Same as #7 | HIGH |
| 10 | Operation parsing | L133–141 | Non-String operation value | Violation | `TYPE_MISMATCH` | — | `OperationValidator` | MEDIUM |
| 11 | Operation allowlist | L141–147 | String not in `allowedOperations` | Violation | `OPERATION_NOT_ALLOWED` | String preserved | Context / enum mapping | HIGH |
| 12 | Null value for known key (non-envelope) | L154–162 | `null` field value | Violation | `TYPE_MISMATCH` | — | Per-field null policy | MEDIUM |
| 13 | Enum instance rejection | `validateAndNormalizeValue` L206–214; `validateRouteRatios` L393–400 | `Enum<?>` | Violation | `TYPE_MISMATCH` | `null` (no put) | Generic pre-check / type validator | MEDIUM |
| 14 | Generic typed value dispatch | `validateAndNormalizeValue` L216–224 | Key, `TracingControlProtocolFieldType`, value | Normalized value or null | Various | Per-type | Type strategy registry | HIGH |
| 15 | Scalar type validation | `validateString`, `validateBoolean`, `validateInteger`, `validateLong`, `validateDouble` | Raw Java object | Coerced value or violation | `TYPE_MISMATCH` | Coerced JDK type | Per-type validators | HIGH |
| 16 | Ratio range validation | `validateDouble` L328–336 (`ratioField=true`) | Double out of [0,1] | Violation | `TYPE_MISMATCH` | — | `RatioValueValidator` | MEDIUM — semantic vs type code |
| 17 | `validation.mode` semantic check | `validateString` L230–239; `isKnownValidationMode` L252–256 | String not in STRICT/WARN/DISABLED | Violation | `TYPE_MISMATCH` | — | `ValidationModeValidator` / descriptor metadata | MEDIUM |
| 18 | `String[]` validation | `validateStringArray` L340–365 | Non-array or null elements | Violation | `TYPE_MISMATCH` | Same array reference | Array validator | MEDIUM |
| 19 | `routeRatios` nested map validation | `validateRouteRatios` L368–409 | Map structure and entries | Violations with synthetic keys `key.routeKey` | `TYPE_MISMATCH` | `Map<String,Double>` | `RouteRatiosValidator` | HIGH |
| 20 | Violation construction | `violation` L411–418 | key, reason, expected, actual, code | `TracingControlProtocolViolation` | — | — | Factory / builder | LOW |
| 21 | Java class name rendering | `typeName` L420–422 | Any object | FQCN or `"null"` | — | — | Shared util | LOW |

---

## 4. Branch and Complexity Map

### 4.1 `validatePayload` (L56–185)

| Branch | Lines | Violation code | Reason text (approx.) | Normalized behavior |
|--------|-------|----------------|----------------------|---------------------|
| `payload == null` | 60–62 | `MISSING_REQUIRED_KEY` (× requiredKeys.size()) | `"required key missing"` | Returns early via `missingRequiredKeys`; no iteration |
| Map key not `String` | 68–76 | `TYPE_MISMATCH` | `"map keys must be String"` | Skip entry; continue |
| Unknown key (`!schema.isKnownKey`) | 78–86 | `UNKNOWN_KEY` | `"unknown key rejected (strict v1)"` | Skip entry |
| Descriptor category `STARTUP_TOPOLOGY` | 89–97 | `OPERATION_NOT_ALLOWED` | `"startup topology field rejected for wire control path"` | Skip entry |
| Runtime policy + `!allowRuntimePolicyFields` | 99–108 | `OPERATION_NOT_ALLOWED` | `"runtime policy field not allowed in read request"` | Skip entry |
| Key == `contractVersion` | 111–130 | See version sub-branches | See below | Put `Integer` major or skip |
| ↳ parse empty | 113–119 | `INVALID_VALUE` | `"invalid contractVersion"` | No put |
| ↳ parsed unsupported | 120–126 | `UNSUPPORTED_VERSION` | `"unsupported contractVersion"` | No put; expectedType = current major string |
| ↳ parsed supported | 127–129 | — | — | `normalized.put(key, parsed.get().major())` |
| Key == `operation` | 133–151 | See operation sub-branches | See below | Put string or skip |
| ↳ not String | 134–140 | `TYPE_MISMATCH` | `"operation must be String"` | No put |
| ↳ not in allowlist | 141–147 | `OPERATION_NOT_ALLOWED` | `"unsupported operation for this validation entry point"` | No put |
| ↳ allowed | 148–150 | — | — | Put operation string |
| `value == null` (other keys) | 154–162 | `TYPE_MISMATCH` | `"null value rejected"` | No put |
| Else: type validation | 164–167 | Via helpers | Via helpers | Put if normalized non-null |
| Missing required keys (post-loop) | 170–179 | `MISSING_REQUIRED_KEY` | `"required key missing"` | Still builds violations even if other errors exist |
| Any violations | 181–182 | — | — | **`invalid(violations)` — normalized map discarded** |
| No violations | 184 | — | — | `valid(normalized)` |

**Note:** Required-key check uses `payload.containsKey(required)` (L171), not `normalized`. A key present with invalid value still satisfies "present" for required-key pass; invalid value produces separate violation.

### 4.2 `validateAndNormalizeValue` (L201–225)

| Branch | Code | Reason |
|--------|------|--------|
| `value instanceof Enum<?>` | `TYPE_MISMATCH` | `"enum instance rejected; use String wire value"` |
| `switch(expectedType)` | Per case | Delegates to type helpers |

### 4.3 Type helper branches

#### `validateString` (L227–250)

| Branch | Code | Reason | Normalized |
|--------|------|--------|------------|
| String + key `validation.mode` + unknown mode | `TYPE_MISMATCH` | `"unknown validation.mode wire value"` | null |
| String (other keys) | — | — | Same string |
| Non-String | `TYPE_MISMATCH` | `"invalid wire type"` | null |

#### `validateBoolean` (L258–270)

| Branch | Code | Reason |
|--------|------|--------|
| `Boolean` | — | — |
| Else | `TYPE_MISMATCH` | `"invalid wire type"` |

#### `validateInteger` (L272–287)

| Branch | Code | Reason | Normalized |
|--------|------|--------|------------|
| `Integer` | — | — | Same Integer |
| `Long` in int range | — | — | `l.intValue()` |
| Else | `TYPE_MISMATCH` | `"invalid wire type"` | null |

#### `validateLong` (L289–303)

| Branch | Code | Reason | Normalized |
|--------|------|--------|------------|
| `Long` | — | — | Same Long |
| `Integer` | — | — | `i.longValue()` |
| Else | `TYPE_MISMATCH` | `"invalid wire type"` | null |

#### `validateDouble` (L305–338)

| Branch | Code | Reason | Normalized |
|--------|------|--------|------------|
| `Double` / `Float` / `Integer` / `Long` | — (then ratio check) | — | `double` value |
| Else | `TYPE_MISMATCH` | `"invalid wire type"` | null |
| `ratioField && (ratio < 0 \|\| ratio > 1)` | `TYPE_MISMATCH` | `"ratio must be in [0.0, 1.0]"` | null |

**Call sites:** Always `ratioField=true` from `validateAndNormalizeValue` DOUBLE case (L221) and from `validateRouteRatios` (L402).

#### `validateStringArray` (L340–365)

| Branch | Code | Reason |
|--------|------|--------|
| Not `String[]` | `TYPE_MISMATCH` | `"invalid wire type; use String[] not List or custom type"` |
| Null element | `TYPE_MISMATCH` | `"String[] must not contain null elements"` |
| Valid | — | — |

#### `validateRouteRatios` (L368–409)

| Branch | Code | Reason | Key field |
|--------|------|--------|-----------|
| Not `Map` | `TYPE_MISMATCH` | `"invalid wire type"` | parent key |
| Map key not String | `TYPE_MISMATCH` | `"routeRatios map keys must be String"` | parent key |
| Enum value | `TYPE_MISMATCH` | `"enum instance rejected in routeRatios"` | parent key |
| Invalid nested ratio | `TYPE_MISMATCH` | via `validateDouble` | synthetic `key + "." + routeKey` |
| Valid | — | — | Builds `Map<String,Double>` |

---

## 5. Violation Semantics Matrix

| Scenario | Current code | Current reason | expectedType | actualType | Normalized payload behavior | Tests covering it |
|----------|--------------|----------------|--------------|------------|----------------------------|-------------------|
| null payload | `MISSING_REQUIRED_KEY` | `"required key missing"` | schema type name | `"absent"` | Empty map (invalid) | `nullPayload` |
| empty payload | `MISSING_REQUIRED_KEY` ×2 | `"required key missing"` | per key | `"absent"` | Empty map | `emptyPayload` |
| map key not String | `TYPE_MISMATCH` | `"map keys must be String"` | `"String"` | FQCN of key | Invalid; partial norm discarded | `nonStringMapKeyRejected` |
| unknown key | `UNKNOWN_KEY` | `"unknown key rejected (strict v1)"` | `"known wire key"` | value FQCN | Invalid | `unknownKeyRejected`, `TracingControlProtocolViolationTest.unknownKeyCode` |
| startup topology in runtime path | `OPERATION_NOT_ALLOWED` | `"startup topology field rejected..."` | `"runtime policy or envelope key"` | descriptor type name | Invalid | `topologyFieldRejectedForRuntimeApply` |
| runtime policy in read request | `OPERATION_NOT_ALLOWED` | `"runtime policy field not allowed..."` | `"envelope or diagnostic key"` | descriptor type name | Invalid | `readRequestRejectsPolicyFields` |
| malformed `contractVersion` | `INVALID_VALUE` | `"invalid contractVersion"` | `"Integer"` | value FQCN | Invalid | `malformedContractVersionRejected`, `TracingControlProtocolViolationTest.invalidValueCode` |
| unsupported `contractVersion` | `UNSUPPORTED_VERSION` | `"unsupported contractVersion"` | current major as String (`"1"`) | value FQCN | Invalid | `unsupportedContractVersionRejected`, `TracingControlProtocolViolationTest.unsupportedVersionCode` |
| operation not String | `TYPE_MISMATCH` | `"operation must be String"` | `"String"` | value FQCN | **Gap** — no dedicated test | |
| operation not allowed for entrypoint | `OPERATION_NOT_ALLOWED` | `"unsupported operation for this validation entry point"` | joined allowlist | operation string | **Gap** — no dedicated test | |
| known key with null value | `TYPE_MISMATCH` | `"null value rejected"` | descriptor type name | `"null"` | Invalid | `knownKeyNullValueTypeMismatch` |
| enum value rejected | `TYPE_MISMATCH` | `"enum instance rejected..."` | expected type name | enum FQCN | Invalid | `enumValueRejected` |
| string expected, non-string | `TYPE_MISMATCH` | `"invalid wire type"` | `STRING` | FQCN | Invalid | `customDtoRejected` (SOURCE) |
| unknown `validation.mode` | `TYPE_MISMATCH` | `"unknown validation.mode wire value"` | `"STRICT\|WARN\|DISABLED"` | actual string | **Gap** | |
| boolean wrong type | `TYPE_MISMATCH` | `"invalid wire type"` | `BOOLEAN` | FQCN | **Gap** | |
| integer from Integer | — | — | — | — | Same Integer | Indirect via valid payloads |
| integer from Long in range | — | — | — | — | `intValue()` | **Gap** in validator tests |
| integer from Long out of range | `TYPE_MISMATCH` | `"invalid wire type"` | `INTEGER` | FQCN | **Gap** | |
| long from Long | — | — | — | — | Same Long | **Gap** |
| long from Integer | — | — | — | — | `longValue()` | **Gap** |
| double from Double/Float/Integer/Long | — | — | — | — | `doubleValue()` | Partial — boundaries tested for ratio | 
| ratio out of range | `TYPE_MISMATCH` | `"ratio must be in [0.0, 1.0]"` | `"[0.0, 1.0]"` | ratio string | Invalid | `invalidSamplingRatioBelowZero/AboveOne`, `routeRatiosMapValidated` (invalid) |
| String[] expected, List/custom | `TYPE_MISMATCH` | `"invalid wire type; use String[]..."` | `STRING_ARRAY` | FQCN | Invalid | `listRejectedForStringArray` |
| String[] contains null | `TYPE_MISMATCH` | `"String[] must not contain null elements"` | `"String[]"` | `"null element"` | **Gap** | |
| routeRatios not Map | `TYPE_MISMATCH` | `"invalid wire type"` | `ROUTE_RATIOS_MAP` | FQCN | **Gap** | |
| routeRatios key not String | `TYPE_MISMATCH` | `"routeRatios map keys must be String"` | `"Map<String,Double>"` | key FQCN | **Gap** | |
| routeRatios value enum | `TYPE_MISMATCH` | `"enum instance rejected in routeRatios"` | `"Double"` | enum FQCN | **Gap** | |
| routeRatios value wrong type | `TYPE_MISMATCH` | via validateDouble | `DOUBLE` | FQCN | **Gap** — only out-of-range tested | |
| routeRatios ratio out of range | `TYPE_MISMATCH` | `"ratio must be in [0.0, 1.0]"` | `"[0.0, 1.0]"` | ratio | Invalid | `routeRatiosMapValidated` |

---

## 6. Normalization Semantics

| Field / type | Accepted inputs | Normalized Java output | Notes |
|--------------|-----------------|------------------------|-------|
| `contractVersion` | Integer, Long (int range), trimmed String | **`Integer`** (major int) | L128: `parsed.get().major()` — not Long despite schema type INTEGER |
| `operation` | String in allowlist | Same String reference | No trimming or case change |
| `STRING` | String | Same String | `validation.mode`: case-insensitive match for known values; **stored as provided** (e.g. `"warn"` passes if equalsIgnoreCase — **NEEDS_VERIFICATION**: `"warn"` lowercase — `equalsIgnoreCase` accepts WARN, STRICT, DISABLED only; `"warn"` matches WARN and is **returned as `"warn"`**, not canonicalized to `"WARN"`) |
| `BOOLEAN` | Boolean only | Boolean | No string `"true"` coercion |
| `INTEGER` | Integer; Long in [MIN_VALUE, MAX_VALUE] | Integer | Long narrowed |
| `LONG` | Long; Integer | Long | Integer widened |
| `DOUBLE` | Double, Float, Integer, Long | Double | All ratio fields use `ratioField=true` in switch (only `sampling.ratio` is DOUBLE in v1 schema) |
| `STRING_ARRAY` | String[] (non-null elements) | **Same array instance** | No copy |
| `ROUTE_RATIOS_MAP` | Map with String keys | **New `LinkedHashMap<String,Double>`** | Nested doubles normalized via `validateDouble` |
| Invalid result | Any | **`Map.of()`** | `TracingControlProtocolValidationResult.invalid` L24–27 — partial normalization discarded |

### Uncertainties

| Item | Status |
|------|--------|
| `validation.mode` canonical casing | **NEEDS_VERIFICATION** — accepted case-insensitively but returned as input string |
| `contractVersion` null as map value | Treated via parse → `INVALID_VALUE` (not `MISSING_REQUIRED_KEY`) — **no explicit test** |
| Normalized payload key order | `LinkedHashMap` — iteration order preserved |

---

## 7. Entrypoint Policy

### 7.1 Comparison

| Aspect | `validateRuntimePolicy` (L40–46) | `validateReadRequest` (L48–54) |
|--------|----------------------------------|--------------------------------|
| Required keys source | `schema.requiredKeysFor(APPLY_RUNTIME_POLICY)` | `schema.requiredKeysFor(READ_APPLIED_STATE)` |
| Required keys (v1) | `{contractVersion, operation}` | `{contractVersion, operation}` — **identical** per `RequiredKeysEquivalenceTest` |
| Allowed operations | `APPLY_RUNTIME_POLICY`, `VALIDATE_RUNTIME_POLICY` | `READ_APPLIED_STATE`, `READ_SCHEMA` |
| `allowRuntimePolicyFields` | `true` | `false` |
| Topology fields | Rejected (always) | Rejected (always) |
| Diagnostic fields | Allowed | Allowed |
| Envelope optional fields (e.g. `source`) | Allowed | Allowed |

### 7.2 Duplication / smell

Private method signature:

```java
validatePayload(payload, requiredKeys, allowedOperations, allowRuntimePolicyFields)
```

(L56–59)

**Observations:**

- Required keys differ only by operation enum parameter but yield **same set** in v1 — parameter is forward-looking for v2.
- `validateReadRequest` hardcodes `READ_APPLIED_STATE` for required keys even when payload operation is `READ_SCHEMA` — harmless today because required sets are identical.
- Operation allowlists are **hardcoded static sets** in validator, not derived from `TracingControlProtocolOperation` enum or schema.
- Category policy (topology always rejected; runtime policy gated by boolean) is **validator-owned**, not schema-owned.

**Represents validation context object?** Evidence suggests yes — four parameters encode entrypoint policy; could become explicit `ValidationContext` (seed option, not recommendation).

---

## 8. Schema Coupling

### 8.1 Schema APIs used by validator

| Schema method | Usage in validator | Lines |
|---------------|-------------------|-------|
| `requiredKeysFor(TracingControlProtocolOperation)` | Entry methods only | 43, 51 |
| `isKnownKey(String)` | Unknown key rejection | 78 |
| `descriptorOf(String)` | Category + type lookup | 88 |
| `typeOf(String)` | Missing required key expectedType | 175, 193 |
| Descriptor `.category()` | Topology / runtime policy gates | 89, 100 |
| Descriptor `.type()` | Type validation dispatch | 164, 158 |

**Not used:** `knownKeys()`, `contractVersion()`, `categoryOf`, `isTopologyKey`, `isRuntimePolicyKey`, `isDiagnosticKey`, `isEnvelopeKey`.

### 8.2 Policy location evidence

| Policy | Currently in | Could move to schema/descriptor? |
|--------|--------------|----------------------------------|
| Field wire type | Descriptor `.type()` | Already in schema |
| Field category | Descriptor `.category()` | Already in schema |
| Required per operation | Descriptor `.requiredForOperations()` | Already in schema |
| Topology forbidden on wire path | Validator | Could be category rule on context |
| Runtime policy forbidden on read | Validator + boolean flag | Could be context |
| Operation allowlists | Validator static sets | Could be enum/schema |
| Ratio / validation.mode semantics | Validator helpers | Could be descriptor metadata |

**Open question:** How much validation policy belongs in schema vs validator — evidence shows **split**: structure in schema, path/category/operation rules in validator.

---

## 9. Version Coupling

### 9.1 Dependencies

| Dependency | Location | Purpose |
|------------|----------|---------|
| `TracingControlProtocolVersion.parse(Object)` | L112 | Parse `contractVersion` only |
| `TracingControlProtocol.isSupported(version)` | L120 | Support check after parse |
| `TracingControlProtocol.current().version().major()` | L124 | **expectedType** for unsupported version violation |

### 9.2 Analysis

| Question | Finding | Severity |
|----------|---------|----------|
| Coupled to global current protocol? | **Yes** — static calls on `TracingControlProtocol` | **MEDIUM** |
| Unsupported-version expectedType correct? | Uses **current** supported major as string, not requested major | **LOW** — reasonable for v1-only registry |
| Should validator receive protocol/context? | Would decouple testing v2 and remove static calls | Open question |
| Hard to test future v2? | Validator is schema-scoped but version **support** is global; constructing validator with v2 schema requires `TracingControlProtocolSchema.forMajor(2)` which **throws** today (L24–26) | **MEDIUM** |

**Note:** Validator holds schema instance but version support check bypasses schema's `contractVersion()` field and uses global registry instead.

---

## 10. Operation Modeling

### 10.1 Representations

| Representation | Location | Values |
|----------------|----------|--------|
| `TracingControlProtocolOperation` enum | `TracingControlProtocolOperation.java` | 4 enum constants |
| Wire string constants | `TracingControlProtocolKeys` L42–45 | Same 4 operation strings |
| `RUNTIME_MUTATION_OPERATIONS` | Validator L24–27 | Subset of Keys strings |
| `READ_OPERATIONS` | Validator L29–32 | Subset of Keys strings |
| Descriptor requiredness | `requiredForOperations` on envelope fields | `ALL_OPERATIONS` (all 4) |

### 10.2 Duplication evidence

- Operation strings exist in **Keys**, **validator static sets**, and implicitly via **enum names** (different spelling: enum `APPLY_RUNTIME_POLICY` vs string `"APPLY_RUNTIME_POLICY"` — aligned but not mechanically linked).
- `TracingControlProtocolOperation` is used for **required keys** only in entry methods, not for allowlist checks.
- Allowlist uses raw `Set<String>` compared against payload operation string.

### 10.3 Open questions

- Should allowlists live on enum, schema, or validation context?
- Does operation allowlist duplicate requiredness model? **Partially** — required keys come from schema; allowed operation strings are separate validator sets.

---

## 11. Type Validation Modeling

### 11.1 Switch dispatch (`validateAndNormalizeValue` L216–224)

| `TracingControlProtocolFieldType` | Helper | Accepted Java inputs | Normalized output | Special cases |
|-------------------------------|--------|---------------------|-------------------|---------------|
| `STRING` | `validateString` | String | String | `validation.mode` allowlist |
| `BOOLEAN` | `validateBoolean` | Boolean | Boolean | — |
| `INTEGER` | `validateInteger` | Integer; Long (int range) | Integer | — |
| `LONG` | `validateLong` | Long; Integer | Long | — |
| `DOUBLE` | `validateDouble(..., true)` | Double, Float, Integer, Long | Double | **Always ratioField=true** |
| `STRING_ARRAY` | `validateStringArray` | String[] | String[] | Rejects List |
| `ROUTE_RATIOS_MAP` | `validateRouteRatios` | Map | Map<String,Double> | Nested validation |

### 11.2 Maintainability

- Single switch — manageable at 7 types but **growing v2 types requires editing switch + new helper**.
- No polymorphic strategy per type today.
- `DOUBLE` always passes `ratioField=true` — safe for v1 (only `sampling.ratio`) but **fragile if non-ratio DOUBLE fields added**.

### 11.3 Extraction candidates (not chosen)

- `ProtocolTypeValidator` interface + one impl per enum value
- `ProtocolValueNormalizer` separate from violation reporting
- `ProtocolFieldValidator` combining descriptor + value
- Enum methods on `TracingControlProtocolFieldType` — currently bare enum with no behavior

---

## 12. Special-case Domain Validation

| Special case | Current location | Belongs in validator? | Alternative homes |
|--------------|------------------|----------------------|-------------------|
| `validation.mode` ∈ {STRICT, WARN, DISABLED} | `validateString` + `isKnownValidationMode` | Embedded | Descriptor metadata; dedicated validator |
| Ratio ∈ [0.0, 1.0] | `validateDouble` when `ratioField` | Embedded | `RatioValueValidator`; descriptor flag `boundedRatio` |
| routeRatios map structure | `validateRouteRatios` | Embedded | Dedicated validator |
| Enum instance rejection | Pre-switch in `validateAndNormalizeValue` + routeRatios | Generic guard | Type validator base |
| Topology category rejection | `validatePayload` | Path policy | Validation context / category rules |

**Semantic invalid values use `TYPE_MISMATCH`:** ratio out of range, unknown validation.mode — consistent with ADR scope (`INVALID_VALUE` reserved for malformed `contractVersion` only).

---

## 13. Test Coverage

### 13.1 Coverage table

| Behavior | Covered? | Test class / method | Gap |
|----------|----------|---------------------|-----|
| Valid minimal runtime payload | Yes | `validMinimalRuntimePolicy` | — |
| Required keys missing (empty/null) | Yes | `emptyPayload`, `nullPayload`, `nullRequiredKeyRejected` | — |
| Unknown key strict rejection | Yes | `unknownKeyRejected` | — |
| Non-String map key | Yes | `nonStringMapKeyRejected` | — |
| Topology rejected | Yes | `topologyFieldRejectedForRuntimeApply` | — |
| Runtime policy in read | Yes | `readRequestRejectsPolicyFields` | — |
| Malformed vs unsupported version | Yes | `malformedContractVersionRejected`, `unsupportedContractVersionRejected` | — |
| Violation codes (3) | Yes | `TracingControlProtocolViolationTest` | Other codes not unit-tested in isolation |
| Enum rejection | Yes | `enumValueRejected` | — |
| Null known field value | Yes | `knownKeyNullValueTypeMismatch` | — |
| Sampling ratio bounds | Yes | ratio boundary tests | — |
| Sampling ratio wrong type | Yes | `invalidSamplingRatioType` | — |
| String[] vs List | Yes | `listRejectedForStringArray`, `stringArrayAccepted` | — |
| routeRatios valid + out of range | Yes | `routeRatiosMapValidated` | Other routeRatios failures |
| Read request happy path | Yes | `validateReadRequest` | READ_SCHEMA-only payload |
| VALIDATE_RUNTIME_POLICY op | Yes | `validateRuntimePolicyOperation` | — |
| Optional policy fields + WARN mode | Yes | `optionalPolicyFieldsAllowed` | — |
| Custom DTO on string field | Yes | `customDtoRejected` | — |
| Operation not String | **No** | — | Gap |
| Operation wrong for entrypoint | **No** | — | Gap |
| Unknown validation.mode | **No** | — | Gap |
| Integer/Long coercion paths | **No** | — | Gap |
| Long out of range for INTEGER | **No** | — | Gap |
| diagnostics.timestamp LONG coercion | **No** | — | Gap |
| String[] null element | **No** | — | Gap |
| routeRatios not Map / bad keys / enum values | **No** | — | Gap |
| contractVersion null value in map | **No** | — | Gap |
| Partial normalization on invalid | **No** | — | Gap (behavior undocumented in tests) |
| Normalized payload content assertions | Partial | minimal + optional tests | Most fields not asserted |
| E2e JMX round-trip | Yes | `WireRoundTripInProcessTest`, `MapWireRoundTripE2ETest` | Validator-only paths |
| App-side wire contract | Yes | `SamplingControlClientWireContractTest` | 2 scenarios only |

**Test count:** `TracingControlProtocolValidatorTest` — **24 tests** (per build XML).

---

## 14. Architecture Smells

| Smell | Evidence | Impact | Severity | Refactoring implication |
|-------|----------|--------|----------|------------------------|
| Long orchestrator method | `validatePayload` L56–185 (~130 lines), many branches | Hard to reason about order/effects | **HIGH** | Extract pipeline stages |
| Validator owns too many responsibilities | §3 table (21 responsibilities) | Change ripple risk | **HIGH** | Split by concern |
| Mixed envelope + field validation | contractVersion/operation special-cased inside entry loop L111–152 | Order-dependent logic | **HIGH** | Envelope phase first |
| Hardcoded operation sets | L24–32 static sets vs enum/Keys | Drift risk | **MEDIUM** | Single source of truth |
| Hardcoded category policy | L89–108 boolean + category checks | v2 path rules harder | **MEDIUM** | ValidationContext |
| Type + domain semantics mixed | ratio + validation.mode inside type helpers | Wrong abstraction level | **MEDIUM** | Field-specific rules |
| Violation construction repeated | `violation(...)` called 20+ times | Boilerplate | **LOW** | Factory/builder |
| Static helpers scattered | 8 static validate* methods | Cohesion low | **MEDIUM** | Type validator classes |
| Global `TracingControlProtocol` dependency | L120–126 static calls | Testability / v2 | **MEDIUM** | Inject protocol/version policy |
| `ratioField` boolean flag | `validateDouble(..., boolean ratioField)` L305–309 | Misleading if new DOUBLE fields | **MEDIUM** | Descriptor-driven bounds |
| `validateString` special-cases VALIDATION_MODE | L230–239 key string compare | Field-specific in generic helper | **MEDIUM** | Dedicated validator |
| Synthetic keys for route ratios | L402 `key + "." + routeKey` | Violation key contract | **LOW** | Nested path model |
| Inconsistent null handling | null payload vs null field vs null contractVersion | Cognitive load | **MEDIUM** | Explicit null policy per field |
| `TYPE_MISMATCH` for semantic errors | ratio, validation.mode | ADR-approved but conflates | **LOW** (documented) | Future code split |
| Normalized payload discarded on any invalid | L181–182 | No partial success | **MEDIUM** | Architect decision needed |
| `expectedType`/`actualType` overloaded | topology rejection uses descriptor type as actualType | Client confusion | **LOW** | Violation model review |
| `validateReadRequest` uses READ_APPLIED_STATE for required keys | L51 | Latent bug if required sets diverge | **LOW** (v1) | Context-aware required keys |

---

## 15. Refactoring Constraints

### 15.1 Must not change accidentally (documented contract)

| Behavior | Source |
|----------|--------|
| `Map<String,Object>` boundary | ADR, wire schema doc |
| Strict unknown-key rejection | ADR, validator L78–86, tests |
| Non-throwing validation result | ADR, result types |
| `INVALID_VALUE` only for malformed `contractVersion` | ADR-control-protocol-version-model.md, tests |
| Unsupported parsed version → `UNSUPPORTED_VERSION` | ADR, tests |
| Other known-field wrong Java type → `TYPE_MISMATCH` | ADR scope table |
| `contractVersion` normalized to `Integer` (major) | Validator L128, tests |
| Integer/Long/Double normalization rules | Validator helpers — characterize before refactor |
| routeRatios → `Map<String,Double>` | Validator L381–408 |
| Operation allowlist per entrypoint | L24–32, tests |
| Topology rejected on all wire paths | L89–97, tests |
| Runtime policy rejected on read path | L99–108, tests |
| Null payload → MISSING_REQUIRED_KEY for each required key | L60–62, tests |
| Empty payload → same | tests |

### 15.2 May change (pre-production)

- Class/package structure and private helper extraction
- Public constructor visibility (if all callers use `TracingControlProtocol`)
- Moving logic to new package-private types
- Violation reason string wording (non-contractual per ADR)
- Internal method signatures and pipeline structure
- Descriptor/metadata enrichment

---

## 16. Seed Architecture Options

*For later external review — **no selection made here**.*

1. **Extract `ValidationContext`** (required keys, allowed operations, category flags) + keep validator as thin orchestrator.
2. **Extract `PayloadValidationEngine`** — single pipeline runner over ordered `ValidationStep` list.
3. **Extract `ProtocolFieldValidator`** — one validator per known key or per descriptor.
4. **Strategy per `TracingControlProtocolFieldType`** — registry maps enum → `TypeValidationStrategy`.
5. **Move normalization/validation methods onto `TracingControlProtocolFieldType` enum** — enum becomes behavior carrier.
6. **`FieldValidationRule` + `FieldNormalizer` pairs** attached to `TracingControlProtocolFieldDescriptor`.
7. **`ProtocolValidationContext` factory** on `TracingControlProtocolOperation` (runtime vs read profiles).
8. **Dedicated special validators:** `ContractVersionValidator`, `OperationValidator`, `RouteRatiosValidator`, `RatioValueValidator`, `ValidationModeValidator`.
9. **Descriptor-driven validators** — descriptor carries optional `ValueConstraint` metadata.
10. **Full pipeline:** (1) map key sanity → (2) schema lookup → (3) category policy → (4) envelope fields → (5) value normalization → (6) required-key sweep → (7) result assembly.
11. **Inject `ProtocolVersionPolicy` interface** instead of static `TracingControlProtocol` calls.
12. **Separate `ViolationCollector` + `NormalizationBuilder`** — avoid discarding partial state until architect decides policy.

---

## 17. Open Questions for Architects

1. Should `TracingControlProtocolValidator` remain **public constructible**, or only obtainable via `TracingControlProtocol`?
2. Should validator depend on static `TracingControlProtocol.current()` / `isSupported()`, or receive a **`ProtocolRegistry` / version policy** instance?
3. Should **value normalization** run in a separate phase from validation, or stay interleaved?
4. On invalid result, should **partial normalized payload** be retained for diagnostics?
5. Should semantic invalid values (ratio out of range, unknown validation.mode) remain **`TYPE_MISMATCH`** or gain a future semantic code?
6. Should **route-ratio validation** stay field-specific or become descriptor-driven (e.g. type `ROUTE_RATIOS_MAP` owns rules)?
7. Should **operation allowlists** live in validator, `TracingControlProtocolOperation` enum, or schema?
8. Should **`validation.mode` allowed values** be encoded in descriptor metadata vs hardcoded in validator?
9. Should **`validateReadRequest` required keys** be derived from payload operation (once v2 diverges)?
10. Is **`ratioField=true` for all DOUBLE types** acceptable for v2, or should bounds be descriptor-scoped?
11. Should **topology category** be rejected by schema ("not wire-visible") vs validator path policy?
12. Should violation **`expectedType`/`actualType`** fields carry semantic meanings consistently (category rejections currently overload them)?

---

## 18. Evidence Index

### 18.1 Production source files

| File | Lines | Role |
|------|-------|------|
| `platform-tracing-api/.../validation/TracingControlProtocolValidator.java` | 423 | **Subject** |
| `platform-tracing-api/.../validation/TracingControlProtocolViolationCode.java` | 12 | Violation enum (6 values) |
| `platform-tracing-api/.../TracingControlProtocol.java` | 63 | Aggregate; constructs validator |
| `platform-tracing-api/.../version/TracingControlProtocolVersion.java` | 49 | Version parse |
| `platform-tracing-api/.../schema/TracingControlProtocolSchema.java` | 162 | 26-field v1 schema |
| `platform-tracing-api/.../schema/TracingControlProtocolKeys.java` | 47 | Key + operation constants |
| `platform-tracing-api/.../schema/TracingControlProtocolFieldType.java` | 13 | 7 wire types |
| `platform-tracing-api/.../schema/TracingControlProtocolOperation.java` | 10 | 4 operations |
| `platform-tracing-api/.../schema/TracingControlProtocolFieldDescriptor.java` | 19 | Descriptor record |
| `platform-tracing-api/.../schema/TracingControlProtocolFieldCategory.java` | 10 | 4 categories |
| `platform-tracing-api/.../result/TracingControlProtocolValidationResult.java` | 32 | Result record |
| `platform-tracing-api/.../result/TracingControlProtocolViolation.java` | 11 | Violation record |

### 18.2 Test files

| File | Tests / role |
|------|--------------|
| `.../validation/TracingControlProtocolValidatorTest.java` | 24 validator behavior tests |
| `.../result/TracingControlProtocolViolationTest.java` | 3 violation code tests |
| `.../version/TracingControlProtocolVersionTest.java` | Parse matrix (validator dependency) |
| `.../schema/TracingControlProtocolSchemaTest.java` | Schema structure (5 tests) |
| `.../schema/RequiredKeysEquivalenceTest.java` | Required keys across operations |
| `.../TracingControlProtocolTest.java` | Aggregate / validator non-null |
| `.../arch/ApiProtocolPackagePurityArchTest.java` | JDK-only + naming rules |
| `.../arch/ApiProtocolNoImplementationDependencyArchTest.java` | Registry/validator wiring |
| `platform-tracing-test/.../ArchitectureFitnessArchRules.java` | FF-01/02/09a protocol rules |
| `platform-tracing-spring-boot-autoconfigure/.../SamplingControlClientWireContractTest.java` | 2 JMX wire tests |
| `platform-tracing-e2e-tests/.../WireRoundTripTestMBeanImpl.java` | E2e validator delegate |
| `platform-tracing-e2e-tests/.../WireRoundTripInProcessTest.java` | In-process JMX tests |
| `platform-tracing-e2e-tests/.../MapWireRoundTripMain.java` | E2e driver |
| `platform-tracing-e2e-tests/.../MapWireRoundTripE2ETest.java` | Cross-CL e2e |

### 18.3 Documentation references

| Document | Relevant content |
|----------|------------------|
| `docs/decisions/ADR-control-protocol-version-model.md` | Naming, Map boundary, violation codes, parse policy |
| `docs/architecture/platform-tracing-wire-schema-v1.md` | §9 validation behavior, strict unknown keys |
| `docs/architecture/ADR-jmx-wire-map-contract.md` | Validator as stable contract |
| `docs/analysis/tracing-control-protocol-refactoring-plan.md` | INVALID_VALUE scope, operation model |
| `docs/architecture/platform-tracing-fitness-functions-implementation.md` | FF-09 production validator ban |

### 18.4 Grep summary (2026-07-03)

| Pattern | Production `src/main` hits | Test/e2e hits |
|---------|---------------------------|---------------|
| `new TracingControlProtocolValidator` | 1 (`TracingControlProtocol.java`) | 0 |
| `validateRuntimePolicy(` | Validator + tests + e2e + autoconfigure test | Multiple |
| `validateReadRequest(` | Validator + tests | 2 tests |
| `TracingControlProtocol.current().validator()` | 0 in main | Tests + e2e + autoconfigure test |
| `TracingControlProtocolViolationCode` | Validator + Violation record | Tests |

---

*End of inventory. No code changes proposed. No refactoring design selected.*
