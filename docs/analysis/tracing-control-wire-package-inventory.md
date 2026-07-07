# Inventory: tracing api.control.wire package

**Date:** 2026-07-02  
**Scope:** Read-only architecture inventory (no code changes)  
**Package:** `space.br1440.platform.tracing.api.control.wire`  
**Module:** `platform-tracing-api`  
**Status:** Pre-production; breaking changes allowed

---

## 1. Executive Summary

The `api.control.wire` package defines a **JDK-only, cross-classloader Map wire contract (v1)** for future private in-process JMX tracing control. It contains **7 production types** (no interfaces): version constants, field key constants, wire type enum, immutable schema descriptor, validator, and structured validation result/violation records.

**Key findings:**

| Finding | Severity |
|---------|----------|
| Protocol version is **hardcoded** as `CURRENT_VERSION_NUMBER = 1` with **no version registry, range policy, or value object** | HIGH |
| Public constant typo **`CURRENT_VERSON_STRING`** (missing `i` in VERSION) | CRITICAL |
| **Zero external production consumers** — wire API is defined and tested but **not wired** into `PlatformSamplingControl`, `PlatformScrubbingControl`, `PlatformValidationControl`, autoconfigure, or otel-extension `src/main` | HIGH |
| Version logic is **centralized in one class** (`TracingControlWireContractVersion`) but **duplicated as int literals** via `TracingControlWireSchema.V1` and validator normalization | MEDIUM |
| Schema/validation/version concerns are **split across 4+ types** without a single protocol aggregate; `V1` singletons are hardcoded in both `Schema` and `Validator` | MEDIUM |
| Test coverage for validator/schema is **good**; **no dedicated tests** for `TracingControlWireContractVersion.isSupported()` edge cases (Long, blank, null) | MEDIUM |
| JMX/OpenMBean boundary is **documented and enforced by ArchUnit** (JDK-only, no `javax.management.openmbean` in package); payloads are `Map<String, Object>` with open-type subset | OK (by design) |

The package is **cohesive as a PR-2 “schema + validator only” deliverable**, but **not cohesive as a long-lived protocol model**: adding v2 would require touching multiple hardcoded `V1` entry points and duplicating schema registration code.

---

## 2. Package Contents

### 2.1 Production classes (7)

| # | Class | Kind | Lines (approx.) | File |
|---|-------|------|-----------------|------|
| 1 | `TracingControlWireContractVersion` | Lombok `@UtilityClass` | 32 | `platform-tracing-api/src/main/java/.../TracingControlWireContractVersion.java` |
| 2 | `TracingControlWireKeys` | `final` class (private ctor) | 89 | `.../TracingControlWireKeys.java` |
| 3 | `TracingControlWireTypes` | `enum` | 25 | `.../TracingControlWireTypes.java` |
| 4 | `TracingControlWireSchema` | `final` class + nested `enum` + `record` | 150 | `.../TracingControlWireSchema.java` |
| 5 | `TracingControlWireValidator` | `final` class | 364 | `.../TracingControlWireValidator.java` |
| 6 | `TracingControlWireValidationResult` | `record` | 44 | `.../TracingControlWireValidationResult.java` |
| 7 | `TracingControlWireViolation` | `record` | 17 | `.../TracingControlWireViolation.java` |

**Nested types:**

- `TracingControlWireSchema.FieldCategory` — enum: `ENVELOPE`, `RUNTIME_POLICY`, `STARTUP_TOPOLOGY`, `DIAGNOSTIC` (lines 19–24)
- `TracingControlWireSchema.FieldDescriptor` — record: `(key, wireType, category)` (lines 26–33)

**No interfaces** in this package.

---

### 2.2 Per-class detail

#### `TracingControlWireContractVersion` (Lombok utility)

| Attribute | Value |
|-----------|-------|
| **Public API** | `CURRENT_VERSION_NUMBER`, `CURRENT_VERSON_STRING`, `isSupported(Object)` |
| **Constants** | `CURRENT_VERSION_NUMBER = 1` (L14); `CURRENT_VERSON_STRING = "1"` (L15) — **typo confirmed** |
| **Methods** | `isSupported(Object version)` — accepts `Integer`, `Long`, `String` (trimmed); else `false` (L17–30) |
| **Constructor** | Lombok-generated private (utility) |
| **Dependencies** | Lombok only (`@UtilityClass`) |
| **JDK-only** | Yes (excluding Lombok compile-time) |
| **Cross-CL safe** | Yes — primitives + strings |
| **JMX/OpenMBean** | Documented intent only (class javadoc L5–9) |
| **Role** | Protocol version identification + inbound acceptance rules |

**Note:** Class javadoc mentions `Integer` and `String` but **code also accepts `Long`** (L22–24). Javadoc is incomplete vs implementation.

---

#### `TracingControlWireKeys` (constants holder)

| Attribute | Value |
|-----------|-------|
| **Public API** | 26 wire field key constants + 4 operation name constants |
| **Constructor** | private empty (L87–88) |
| **Dependencies** | None |
| **JDK-only** | Yes |
| **Cross-CL safe** | Yes — `String` constants only |
| **JMX/OpenMBean** | Javadoc references MBean mapping (`PlatformSamplingControl#updateSamplingPolicy`, L7) — **documentation only** |
| **Role** | Canonical wire key strings |

**Key groups (section comments L13–80):**

- Envelope (3): `contractVersion`, `operation`, `source`
- Sampling runtime policy (7)
- Scrubbing (3)
- Validation (3)
- Enriching (1)
- Export/propagation gates (2)
- Diagnostics (2)
- Startup topology (5)
- Operation verbs (4)

---

#### `TracingControlWireTypes` (enum)

| Value | Meaning |
|-------|---------|
| `STRING`, `BOOLEAN`, `INTEGER`, `LONG`, `DOUBLE` | Scalar open types |
| `STRING_ARRAY` | `String[]` only (not `List`) |
| `ROUTE_RATIOS_MAP` | `Map<String, Double>` allowlisted nested map |

**Role:** Wire type taxonomy for schema descriptors and validation error messages.

---

#### `TracingControlWireSchema` (immutable descriptor)

| Attribute | Value |
|-----------|-------|
| **Public singleton** | `V1` (L35–37) — constructed with `TracingControlWireContractVersion.CURRENT_VERSION_NUMBER` |
| **Public API** | `contractVersion()`, `knownKeys()`, `descriptorOf()`, `isKnownKey()`, `categoryOf()`, `typeOf()`, `isTopologyKey()`, `isRuntimePolicyKey()`, `isDiagnosticKey()`, `isEnvelopeKey()`, `requiredKeysForRuntimeApply()`, `requiredKeysForReadRequest()` |
| **Constructor** | private (L42–45) |
| **Field registry** | `buildV1Fields()` static — 26 keys (L104–141) |
| **Dependencies** | Same-package types only |
| **JDK-only** | Yes |
| **Cross-CL safe** | Yes — immutable descriptors, no I/O |
| **JMX/OpenMBean** | Explicitly **does not** perform I/O or JMX (javadoc L12–13) |
| **Role** | Schema metadata: key → (type, category); required envelope keys |

**Known keys count:** **26** (verified by `buildV1Fields()` registration calls).

---

#### `TracingControlWireValidator` (validation + coercion)

| Attribute | Value |
|-----------|-------|
| **Public singleton** | `V1 = new TracingControlWireValidator(TracingControlWireSchema.V1)` (L27–28) |
| **Public API** | `validateRuntimePolicy(Map)`, `validateReadRequest(Map)` |
| **Constructor** | public `TracingControlWireValidator(TracingControlWireSchema schema)` (L32–34) — null schema → NPE |
| **Private API** | Payload loop, per-type validators, `typeName()` helper |
| **Dependencies** | JDK collections only |
| **JDK-only** | Yes |
| **Cross-CL safe** | Yes — validates `Map<String, Object>` open types |
| **JMX/OpenMBean** | Indirect — designed for Map payloads crossing MBean invoke |
| **Role** | Inbound validation, strict unknown-key rejection, type coercion/normalization |

**Version handling (L108–117):**

- Calls `TracingControlWireContractVersion.isSupported(value)`
- On success: **normalizes** `contractVersion` to `Integer` `CURRENT_VERSION_NUMBER` in output map
- On failure: violation with `expectedType` = `CURRENT_VERSON_STRING` (typo propagated)

**Operation allowlists:**

- Runtime: `APPLY_RUNTIME_POLICY`, `VALIDATE_RUNTIME_POLICY` (L18–20)
- Read: `READ_APPLIED_STATE`, `READ_SCHEMA` (L22–24)

---

#### `TracingControlWireValidationResult` (record)

| Component | Type |
|-----------|------|
| `valid` | `boolean` |
| `violations` | `List<TracingControlWireViolation>` (defensive copy) |
| `normalizedPayload` | `Map<String, Object>` (unmodifiable copy) |

**Factories:** `valid(Map)`, `invalid(List)`, `invalid(single)` (L32–42).

**Role:** Non-throwing validation outcome.

---

#### `TracingControlWireViolation` (record)

| Component | Type |
|-----------|------|
| `key` | `String` — wire key or `"<map>"` |
| `reason` | `String` — **English**, free-form |
| `expectedType` | `String` — often enum name or literal |
| `actualType` | `String` — Java class name or sentinel |

**Role:** Structured rejection detail for diagnostics.

---

### 2.3 Test-only types in related packages (not in `api.control.wire`)

| Location | Types | Role |
|----------|-------|------|
| `platform-tracing-e2e-tests/src/jmxWireExtension/...` | `WireRoundTripTestMBean`, `WireRoundTripTestMBeanImpl`, `WireRoundTripTestExtensionProvider` | Test-only agent extension harness delegating to `TracingControlWireValidator.V1` |
| `platform-tracing-api/.../wire/arch/` | `ApiWirePackagePurityArchTest`, `ApiWireNoImplementationDependencyArchTest` | ArchUnit guardrails |

---

## 3. Current Responsibilities and Class Hierarchy

There is **no inheritance hierarchy** — flat package of collaborators:

```text
TracingControlWireKeys (field name constants)
TracingControlWireTypes (wire type enum)
TracingControlWireContractVersion (version constants + isSupported)
        │
        ▼
TracingControlWireSchema.V1 (field descriptors + categories)
        │
        ▼
TracingControlWireValidator.V1 (validate + coerce)
        │
        ├──► TracingControlWireValidationResult
        └──► TracingControlWireViolation
```

### Responsibility matrix

| Concern | Owner | Honest? |
|---------|-------|---------|
| **Protocol versioning** | `TracingControlWireContractVersion` (+ duplicate int in `Schema.contractVersion()`) | Partially — name says "ContractVersion" but no contract object |
| **Field names** | `TracingControlWireKeys` | Yes |
| **Schema structure / categories** | `TracingControlWireSchema` | Yes — descriptor model |
| **Validation rules** | `TracingControlWireValidator` | Yes — but also owns coercion |
| **Compatibility rules** | `TracingControlWireContractVersion.isSupported` + strict unknown keys in validator | Minimal — only v1 exact match |
| **Parsing/coercion** | `TracingControlWireValidator` private methods | Yes — Integer/Long narrowing, Double ratios, String[] enforcement |
| **OpenMBean/JMX concerns** | **Not in code** — documented in javadoc + `docs/architecture/platform-tracing-wire-schema-v1.md` | By design (JDK Map only) |

### Style classification

| Class | Style |
|-------|-------|
| `TracingControlWireContractVersion` | Utility |
| `TracingControlWireKeys` | Utility / constants |
| `TracingControlWireTypes` | Domain enum |
| `TracingControlWireSchema` | Schema descriptor (domain) |
| `TracingControlWireValidator` | Service / engine |
| `ValidationResult`, `Violation` | DTO records |

**Cohesion assessment:** Good for **single-version static schema**. **Mixed** for protocol evolution: version, schema, validation, and coercion are related but not encapsulated behind one protocol abstraction. Names are mostly honest except **`CURRENT_VERSON_STRING`** and **`TracingControlWireContractVersion`** (no `Contract` type exists).

---

## 4. Protocol Versioning Analysis

### 4.1 `TracingControlWireContractVersion#CURRENT_VERSION_NUMBER`

**Evidence:** `platform-tracing-api/.../TracingControlWireContractVersion.java:14`

```java
public static final int CURRENT_VERSION_NUMBER = 1;
```

| Question | Answer | Evidence |
|----------|--------|----------|
| Hardcoded? | **Yes** — literal `1` | L14 |
| Where consumed? | `TracingControlWireSchema.V1` ctor (L36); `TracingControlWireValidator` normalization (L116); tests | grep `CURRENT_VERSION_NUMBER` |
| Exposed on wire? | **Yes** — key `contractVersion` (`TracingControlWireKeys.CONTRACT_VERSION`) | Keys L15 |
| Validated inbound? | **Yes** — `isSupported()` in validator loop | Validator L108–114 |
| Emitted outbound? | **No automatic emission** — callers must put `contractVersion` in Map; validator **normalizes** accepted values to `Integer 1` | Validator L116 |
| String representation? | **Yes** — `CURRENT_VERSON_STRING = "1"` (typo) | L15, L27 |
| Integer supported? | **Yes** | `isSupported` L18–19 |
| Long supported? | **Yes** in `isSupported` | L22–24 |
| String supported? | **Yes** (trimmed equality) | L26–27 |
| Typo `CURRENT_VERSON_STRING`? | **CONFIRMED** | L15; also used Validator L113 |
| Enum / value object? | **No** | — |
| Supported version range? | **No** — exact `1` only | `isSupported` |
| Current / min / deprecated? | **No** — only current constant | — |
| v2 straightforward? | **Painful** — new `Schema.V2`, `Validator.V2`, extend `isSupported`, duplicate `buildV1Fields` pattern | architectural |

### 4.2 Secondary version sources

| Location | Mechanism | Lines |
|----------|-----------|-------|
| `TracingControlWireSchema` | `private final int contractVersion` + accessor | Schema L39–48 |
| `TracingControlWireSchema.V1` | Passes `CURRENT_VERSION_NUMBER` to private ctor | L35–36 |
| Tests | Assert `contractVersion() == 1` | `TracingControlWireSchemaTest:14–15` |

**Verdict:** `CURRENT_VERSION_NUMBER` is the **primary** source of truth; `Schema.contractVersion()` **mirrors** it at construction time. No drift today because only `V1` exists.

### 4.3 Typo: `CURRENT_VERSON_STRING`

| Constant searched | Present? |
|-------------------|----------|
| `CURRENT_VERSON_STRING` | **Yes** (L15) |
| `CURRENT_VERSION_STRING` | **No matches** in repository `src/` |

**Impact:** Public API typo; violation messages use typo constant as `expectedType` (Validator L113).

### 4.4 Version test coverage

| Scenario | Covered? | Where |
|----------|----------|-------|
| Integer `1` accepted | Yes | `TracingControlWireValidatorTest:19`, e2e tests |
| String `"1"` accepted | Yes | `TracingControlWireValidatorTest:149–154` |
| Integer `2` rejected | Yes | `TracingControlWireValidatorTest:136–145`, `WireRoundTripInProcessTest:117` |
| Long `1L` accepted | **Not explicitly tested** | `isSupported` supports it — NEEDS_VERIFICATION in integration tests |
| Long `2L` rejected | **No dedicated test** | — |
| Blank string `""` / `" "` | **No test** | — |
| Null `contractVersion` value | **No direct test** (would fail as null value / missing key) | — |
| `isSupported()` unit tests | **None** — no `TracingControlWireContractVersionTest` | — |
| Unsupported version e2e | Yes | `MapWireRoundTripMain:115`, `WireRoundTripInProcessTest:117` |

---

## 5. Wire Schema Analysis

### 5.1 Field name definition

**Centralized** in `TracingControlWireKeys` (string constants). **Registered** in `TracingControlWireSchema.buildV1Fields()`.

### 5.2 Representation model

| Layer | Representation |
|-------|----------------|
| Keys | `public static final String` constants |
| Types | `TracingControlWireTypes` enum |
| Categories | `FieldCategory` enum |
| Descriptor | `FieldDescriptor` record |
| Registry | `Map<String, FieldDescriptor>` inside `TracingControlWireSchema` |
| Payload | Ad-hoc `Map<String, Object>` |

**Not used:** JSON schema files, generated code, OpenMBean `CompositeType` definitions in Java.

### 5.3 Domain grouping

| Domain | Keys | Category |
|--------|------|----------|
| Envelope | 3 | `ENVELOPE` |
| Sampling | 7 | `RUNTIME_POLICY` |
| Scrubbing | 3 | `RUNTIME_POLICY` |
| Validation | 3 | `RUNTIME_POLICY` |
| Enriching | 1 | `RUNTIME_POLICY` |
| Export/propagation | 2 | `RUNTIME_POLICY` |
| Diagnostics | 2 | `DIAGNOSTIC` |
| Startup topology | 5 | `STARTUP_TOPOLOGY` |

Section comments in `TracingControlWireKeys` align with domains (L13–80).

### 5.4 Typing, required/optional, defaults

| Aspect | Modeled? | Detail |
|--------|----------|--------|
| Field types | **Yes** — per-key `TracingControlWireTypes` | Schema descriptors |
| Required fields | **Partially** — only `contractVersion` + `operation` for both entry points | `requiredKeysForRuntimeApply/ReadRequest` |
| Optional policy fields | **Implicit** — allowed when present, validated if present | Validator tests L204–217 |
| Defaults | **No** — no default values in schema | — |
| Validation messages | **Yes** — `TracingControlWireViolation` with key/reason/expected/actual | English reasons |

### 5.5 Schema evolution

- **Strict unknown-key rejection** (validator L79–85) — v1 policy documented in `platform-tracing-wire-schema-v1.md` §10
- **No forward-compatible ignore path**
- **No schema version negotiation** beyond integer `contractVersion`
- Adding fields requires editing `TracingControlWireKeys` + `buildV1Fields()` + tests

---

## 6. JMX / OpenMBean Boundary

### 6.1 Cross-classloader model

Documented in `docs/architecture/platform-tracing-wire-schema-v1.md` §4:

```text
Application ClassLoader → (future) JMX invoke → Agent Extension ClassLoader
Payload: Map<String, Object> with open types only
```

Production spike transport (`TracingControlWireSpike*`) **removed** per `docs/architecture/ADR-jmx-wire-map-contract.md`. Round-trip proof lives in **test-only** `jmxWireExtension` + e2e tests.

### 6.2 JDK-only enforcement

| Rule | Location |
|------|----------|
| `API_WIRE_PACKAGE_JDK_ONLY` | `platform-tracing-test/.../ArchitectureFitnessArchRules.java:23–32` |
| `API_WIRE_NO_IMPLEMENTATION_MODULES` | same file L37–44 |
| Enforced by | `ApiWirePackagePurityArchTest` |

**Verified:** No `javax.management.openmbean`, Spring, OTel, Jackson imports in production wire package.

### 6.3 Payload shape

- **Wire payload:** `Map<String, Object>` (validator public methods L42, L51)
- **OpenMBean / CompositeData / TabularData:** **Not used in this package** — compatibility is **by convention** (open Java types subset documented in wire schema v1 doc §5–6)
- **Coercion location:** Entirely in `TracingControlWireValidator` (this package), not in otel-extension JMX classes

### 6.4 `contractVersion` type coercion

| Inbound | Accepted by `isSupported`? | Normalized outbound |
|---------|----------------------------|---------------------|
| `Integer(1)` | Yes | `Integer(1)` |
| `Long(1L)` | Yes | `Integer(1)` |
| `String("1")` | Yes (trim) | `Integer(1)` |
| Other | No | violation |

Schema declares wire type `INTEGER` for `contractVersion` (Schema L106) but **`isSupported` accepts Long** — slight spec/implementation gap.

---

## 7. Production Usage Graph

### 7.1 Internal (within package)

```text
TracingControlWireSchema.V1
  → reads TracingControlWireContractVersion.CURRENT_VERSION_NUMBER
  → registers all TracingControlWireKeys in buildV1Fields()

TracingControlWireValidator.V1
  → uses TracingControlWireSchema.V1
  → calls TracingControlWireContractVersion.isSupported / CURRENT_VERSION_NUMBER / CURRENT_VERSON_STRING
```

### 7.2 External production usage (`src/main` outside package)

**Grep result:** **0 files** in `**/src/main/**/*.java` outside `api.control.wire` import or reference `TracingControlWire*`.

| Consumer | Production? | Uses |
|----------|-------------|------|
| `PlatformSamplingControl` | **No** — no import of wire package in otel-extension `src/main` | — |
| `PlatformScrubbingControl` | **No** | — |
| `PlatformValidationControl` | **No** | — |
| `SamplingControlClient` / autoconfigure | **No** in `src/main` | — |
| `ArchitectureFitnessArchRules` | **Yes** (test module `main` sources) | References validator **by name** in FF-09a rule L49–55 — **not a runtime consumer** |

**Production usage count (runtime):** **0**

The wire package is **public API awaiting integration** (PR-3/PR-10 per docs).

### 7.3 Test usage

| File | Module | Uses |
|------|--------|------|
| `TracingControlWireValidatorTest` | api/test | `Validator.V1`, keys, full validation matrix (20 tests) |
| `TracingControlWireSchemaTest` | api/test | `Schema.V1`, keys, categories, `CURRENT_VERSION_NUMBER` |
| `TracingControlWireKeysTest` | api/test | Key string stability |
| `ApiWirePackagePurityArchTest` | api/test | ArchUnit FF-01/02 |
| `ApiWireNoImplementationDependencyArchTest` | api/test | Package location + version constant smoke |
| `SamplingControlClientWireContractTest` | autoconfigure/test | `Validator.V1`, keys — MBean stub invoke |
| `WireRoundTripInProcessTest` | e2e/test | Keys, validator class name, version 2 rejection |
| `MapWireRoundTripMain` | e2e/test | Keys, version 99 rejection |
| `WireRoundTripTestMBeanImpl` | e2e/jmxWireExtension | `Validator.V1`, keys, result/violation types |

### 7.4 JMX control classes (related, not wire consumers)

Production MBeans exist but use **typed MBean methods**, not Map wire validator:

- `platform-tracing-otel-extension/.../jmx/sampling/PlatformSamplingControl.java`
- `.../scrubbing/PlatformScrubbingControl.java`
- `.../validation/PlatformValidationControl.java`

**No `TracingControlWire` imports** in otel-extension production sources (grep: no matches).

### 7.5 Documentation references

| Document | References wire package |
|----------|-------------------------|
| `docs/architecture/platform-tracing-wire-schema-v1.md` | Full v1 spec |
| `docs/architecture/ADR-jmx-wire-map-contract.md` | Map wire ADR |
| `docs/architecture/platform-tracing-fitness-functions-implementation.md` | FF-01/02/09a |
| `docs/analysis/versioned-interface-inventory.md` | Contrasts with `api.config` / runtime state |

**No dedicated ADR for protocol version evolution model.**

---

## 8. Test Coverage

### 8.1 Existing tests

| Area | Tests | File |
|------|-------|------|
| Validator happy/edge paths | 20 `@Test` methods | `TracingControlWireValidatorTest.java` |
| Schema descriptors | 5 tests | `TracingControlWireSchemaTest.java` |
| Key constants | 3 tests | `TracingControlWireKeysTest.java` |
| JDK-only / no impl deps | 2 ArchUnit rules | `ApiWirePackagePurityArchTest.java` |
| Package placement smoke | 1 test | `ApiWireNoImplementationDependencyArchTest.java` |
| JMX Map round-trip | Multiple | `WireRoundTripInProcessTest`, `MapWireRoundTripMain`, `SamplingControlClientWireContractTest` |

### 8.2 Coverage gaps

| Gap | Risk |
|-----|------|
| No `TracingControlWireContractVersionTest` | Long/blank/null/version coercion untested at unit level |
| Long `1L` as `contractVersion` in validator | Supported by code, not explicitly tested |
| `validateReadRequest` with `READ_SCHEMA` only partially tested | One happy path test |
| Scrubbing/validation **reload via wire** | **No tests** — no production reload path yet |
| `TracingControlWireValidationResult` / `Violation` records | No direct unit tests (covered indirectly) |
| Multi-version schema registry | N/A — only v1 exists |

### 8.3 Tests tied to current implementation details

- Hard-coded `CURRENT_VERSION_NUMBER == 1` (`SchemaTest:15`, `ApiWireNoImplementationDependencyArchTest:26`)
- `TracingControlWireValidator.V1` / `TracingControlWireSchema.V1` singletons
- Exact key strings in `TracingControlWireKeysTest`
- Strict unknown-key behavior

**These would fail or require update if versioning model is redesigned** — acceptable pre-production if characterized first.

### 8.4 Characterization tests recommended before refactor

1. `TracingControlWireContractVersion.isSupported` matrix (Integer/Long/String/null/wrong types)
2. Normalization: inbound String/Long `contractVersion` → outbound Integer
3. Version rejection messages (including typo constant in `expectedType`)
4. Full v1 key registry snapshot (26 keys + categories)

---

## 9. Architecture Smells

### Smell 1: Hardcoded protocol version with no evolution model

- **Evidence:** `CURRENT_VERSION_NUMBER = 1`; `Schema.V1`; `Validator.V1`; `isSupported` exact match only (`ContractVersion.java:14–30`)
- **Impact:** v2 requires parallel singletons and duplicated registration code
- **Refactoring implication:** Introduce explicit protocol version type + registry
- **Severity:** **HIGH**

### Smell 2: Public API typo `CURRENT_VERSON_STRING`

- **Evidence:** `TracingControlWireContractVersion.java:15`; used in violation `expectedType` `Validator.java:113`
- **Impact:** Permanent wire-contract/documentation confusion; public constant name is wrong
- **Refactoring implication:** Rename or replace with version value object; no `@Deprecated` shim needed (pre-production)
- **Severity:** **CRITICAL**

### Smell 3: Zero production integration despite public API

- **Evidence:** grep `src/main` — no external consumers; docs state PR-2 schema-only (`platform-tracing-wire-schema-v1.md:8–10`)
- **Impact:** API shape may not match real JMX/reconciler needs; architects reviewing hierarchy before integration
- **Refactoring implication:** Safe to redesign before PR-3/PR-10 wiring
- **Severity:** **HIGH**

### Smell 4: Version acceptance (Long) vs schema type (INTEGER) mismatch

- **Evidence:** `isSupported` accepts Long (`ContractVersion:22–24`); schema types `contractVersion` as `INTEGER` (`Schema:106`); doc §8 table says "Integer or String"
- **Impact:** Spec drift; OpenMBean callers may send Long
- **Refactoring implication:** Unify documented + schema + validation model
- **Severity:** **MEDIUM**

### Smell 5: Protocol concerns split without aggregate root

- **Evidence:** Version in `ContractVersion`, keys in `Keys`, descriptors in `Schema`, rules in `Validator`, results in records — no `ControlWireProtocol` type
- **Impact:** Hard to reason about "the v1 contract" as one unit
- **Refactoring implication:** Consider protocol aggregate or module split
- **Severity:** **MEDIUM**

### Smell 6: Duplicate hardcoded `V1` entry points

- **Evidence:** `TracingControlWireSchema.V1` + `TracingControlWireValidator.V1` (`Schema:35`, `Validator:27–28`)
- **Impact:** Easy to wire wrong schema/validator pair when v2 added
- **Refactoring implication:** Factory or enum `ProtocolV1` bundling schema+validator
- **Severity:** **MEDIUM**

### Smell 7: English violation reasons as informal contract

- **Evidence:** Free-form strings in validator (`"unknown key rejected (strict v1)"`, etc.)
- **Impact:** Hard to localize or machine-process; tests assert substring match
- **Refactoring implication:** Stable violation codes + optional message
- **Severity:** **LOW**

### Smell 8: Utility-class pattern for version holder

- **Evidence:** `@UtilityClass` on `TracingControlWireContractVersion` with public mutable-adjacent constants
- **Impact:** Not a real value object; encourages static scattered usage
- **Refactoring implication:** `record ProtocolVersion(int major)` or enum
- **Severity:** **LOW**

### Smell 9: Missing ADR for version evolution

- **Evidence:** Wire schema doc + JMX ADR exist; no ADR answering "integer vs semver vs enum" for `contractVersion`
- **Impact:** Architects reinvent decision during refactor
- **Refactoring implication:** ADR in next phase
- **Severity:** **MEDIUM**

### Smell 10: Javadoc/code drift on supported wire types for version

- **Evidence:** Class javadoc lists Integer/String only (`ContractVersion:8–9`); Long supported in code
- **Impact:** Misleading documentation for JMX callers
- **Refactoring implication:** Single source of truth for accepted wire representations
- **Severity:** **LOW**

---

## 10. Refactoring Constraints

### Must not change accidentally (once integrated)

- Cross-classloader **open-type** safety (`Map<String, Object>` with JDK types only)
- **Strict unknown-key rejection** policy (unless architects explicitly relax)
- **Topology keys rejected** on runtime apply path
- **Non-throwing validation** — structured `TracingControlWireValidationResult` vs exceptions for payload errors
- Runtime **sampling/scrubbing/validation semantics** when wire is connected to MBeans
- ArchUnit **JDK-only** and **no implementation module dependency** rules for wire package

### Pre-production explicit allowances

```text
Pre-production: breaking API/FQN changes are allowed.
No deprecated shims.
No compatibility wrappers.
No cosmetic-only rename.
```

Architects have stated **cosmetic refactoring alone is unacceptable** — version/model redesign is in scope.

---

## 11. Seed Architecture Options for Perplexity

Neutral descriptions for later scoring (≥10 variants):

1. **Minimal cleanup** — Fix `CURRENT_VERSON_STRING` typo + javadoc Long support; keep flat package and `V1` singletons.

2. **Explicit `ControlWireProtocolVersion` value object** — `record ProtocolVersion(int major)` replaces int constants; `isSupported` becomes instance method.

3. **Enum-based protocol version** — `enum WireProtocolVersion { V1 }` with schema/validator references.

4. **`ControlWireProtocol` aggregate** — Bundles version + schema + validator + accepted wire representations.

5. **Package split** — `api.control.wire.version`, `.schema`, `.validation` subpackages.

6. **Domain-specific schemas** — Separate sampling/scrubbing/validation wire schemas + shared envelope/version.

7. **Descriptor-first model** — Keep `FieldDescriptor` as primary; generate keys/docs from descriptor registry.

8. **Map contract + centralized descriptor registry** — Single registry class registers all v1 fields; keys class becomes generated or thin facade.

9. **OpenMBean adapter layer** — Pure wire schema in API; `javax.management.openmbean` conversion in otel-extension/test module.

10. **Typed command DTOs** — Replace Map with immutable command records if classloader constraints can be solved (serializable open DTOs).

11. **Version range policy** — Support `minSupported`/`maxSupported` and negotiated rejection for forward compatibility.

12. **Violation code enum** — Stable machine-readable codes alongside English reasons.

---

## 12. Open Questions for Architects

1. Should `contractVersion` remain a **simple integer major version** or move to semver/string?
2. Should version be an **enum**, **record**, or **interface** with v1/v2 implementations?
3. Should **`String` `"1"`** remain accepted on wire indefinitely?
4. Should **`Long`** remain accepted for `contractVersion` given schema types it as `INTEGER`?
5. Given pre-production status, should **v1 be preserved** at all or can the wire contract be redesigned wholesale?
6. Should wire schema stay **one global schema** or split by domain (sampling/scrubbing/validation)?
7. Should OpenMBean/`CompositeData` conversion live in **api.control.wire** or **otel-extension JMX layer**?
8. Should package remain **`api.control.wire`** or rename/split (e.g. `api.control.protocol`)?
9. Should version constants remain **public API** or become package-private behind factory?
10. Fix typo via **`CURRENT_VERSION_STRING` rename** or **eliminate string constant** entirely in favor of value object?
11. When integrating PR-3/PR-10, should validator run on **App CL**, **Agent CL**, or **both**?
12. Is **strict unknown-key rejection** still the right default for production control plane?

---

## 13. Evidence Index

### Source files (production)

| Path | Lines cited |
|------|-------------|
| `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/control/wire/TracingControlWireContractVersion.java` | 1–32 |
| `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/control/wire/TracingControlWireKeys.java` | 1–89 |
| `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/control/wire/TracingControlWireTypes.java` | 1–25 |
| `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/control/wire/TracingControlWireSchema.java` | 1–150 |
| `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/control/wire/TracingControlWireValidator.java` | 1–364 |
| `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/control/wire/TracingControlWireValidationResult.java` | 1–44 |
| `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/control/wire/TracingControlWireViolation.java` | 1–17 |

### Test files

| Path |
|------|
| `platform-tracing-api/src/test/java/.../TracingControlWireValidatorTest.java` |
| `platform-tracing-api/src/test/java/.../TracingControlWireSchemaTest.java` |
| `platform-tracing-api/src/test/java/.../TracingControlWireKeysTest.java` |
| `platform-tracing-api/src/test/java/.../arch/ApiWirePackagePurityArchTest.java` |
| `platform-tracing-api/src/test/java/.../arch/ApiWireNoImplementationDependencyArchTest.java` |
| `platform-tracing-spring-boot-autoconfigure/src/test/java/.../SamplingControlClientWireContractTest.java` |
| `platform-tracing-e2e-tests/src/test/java/.../WireRoundTripInProcessTest.java` |
| `platform-tracing-e2e-tests/src/test/java/.../MapWireRoundTripMain.java` |
| `platform-tracing-e2e-tests/src/jmxWireExtension/java/.../WireRoundTripTestMBeanImpl.java` |

### Architecture / rules

| Path |
|------|
| `platform-tracing-test/src/main/java/.../ArchitectureFitnessArchRules.java` |
| `docs/architecture/platform-tracing-wire-schema-v1.md` |
| `docs/architecture/ADR-jmx-wire-map-contract.md` |

### Grep queries executed (summary)

| Query | Result summary |
|-------|----------------|
| `api.control.wire` / `TracingControlWire*` in `src/main` | Only within wire package (7 files) |
| `CURRENT_VERSION_NUMBER` | ContractVersion, Schema, Validator, tests |
| `CURRENT_VERSON_STRING` | ContractVersion L15, Validator L113 — **typo confirmed** |
| `CURRENT_VERSION_STRING` | **0 matches** |
| `PlatformSamplingControl` + wire | Javadoc reference only in Keys; no code import |
| `CompositeData` / `TabularData` / `OpenMBean` in wire package | **0** — documented only |
| `getSamplingPolicyVersion` | **0 matches** in otel-extension src |
| `contractVersion` | Wire keys, validator, docs, e2e tests |

---

*End of inventory. No code was modified during this analysis.*
