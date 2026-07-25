# Week 01 — Public Contract Inventory

## Purpose

`Public Contract Inventory` is the registry of all supported and actually used contract surfaces.

Use for: architecture review, API/SPI boundary definition, release prep, breaking-change analysis, migration planning, documentation, test strategy, ownership, accidental API detection.

**Not** an automatic list of all `public` declarations. Each entry links surface, consumer, semantics, and compatibility policy.

## Minimum entry structure

| Field | Content |
|---|---|
| Surface | Java, SPI, Configuration, Spring, Dependency, Operations, Evolution |
| Contract element | Type, property, bean, metric, or rule |
| Consumer | Who uses it |
| Status | Supported, SPI, Experimental, Incidental, Internal, Deprecated |
| Guarantees | What is promised |
| Evidence | Code, docs, test, production usage |
| Compatibility | Allowed changes |
| Owner | Who approves changes |
| Verification | How contract is checked |

## Status definitions

- **Supported API** — intended for consumers; protected by compatibility policy
- **Supported SPI** — intended for user implementations
- **Experimental** — available with explicit change rules
- **Incidental effective API** — used but not designed as contract
- **Internal** — not for use; maximally isolated technically
- **Deprecated** — temporary support with replacement and migration path

## Nine-step algorithm

### Step 1. Define scope

Artifact/module, version, supported Java and Spring Boot versions, application types, runtime environment, production/pre-production status.

### Step 2. List consumers

Concrete categories: application developer, platform module, extension author, Spring container, SRE, build tooling.

### Step 3. Collect declared surface

Public/protected types, exported packages, published Javadocs, annotations, service providers, configuration metadata, auto-configuration imports, bean definitions, POM/Gradle metadata, Actuator/JMX/metrics, migration docs.

### Step 4. Find effective surface

Real imports in services, bean lookups/qualifiers, Helm/env configs, dashboards/alerts, reflection, serialization schemas, transitive dependency usage, custom implementations, support/incident history.

### Step 5. Classify each element

Assign one status per element.

### Step 6. Record guarantees

Beyond "public class": valid inputs, returned state, errors, lifecycle, ownership, concurrency, defaults, ordering, side effects, observability, compatibility.

### Step 7. Find contradictions

Examples:

- type internal but in public signature
- dependency `implementation` but type in ABI
- bean replaceable via `@ConditionalOnMissingBean` but SPI undocumented
- property renamed without deprecation metadata
- metric in alert but not in operational spec
- docs claim thread-safety; tests single-threaded only

### Step 8. Assign disposition

For each incidental element: promote, replace and migrate, restrict, deprecate, remove before production, or keep experimental with explicit bounds.

### Step 9. Attach verification

Compilation tests, binary compatibility checks, `ApplicationContextRunner` matrix, architecture rules, configuration metadata assertions, consumer contract tests, metric/health snapshots, dependency graph checks, documentation checks, migration tests.

## Example inventory rows (teaching)

Illustrative tracing-starter rows — not a claim about any specific repository:

| Surface | Element | Consumer | Status | Key guarantees | Verification |
|---|---|---|---|---|---|
| Java API | `PlatformTracing` | App developer | Supported API | Single app entry for manual spans; no SDK leak | API compilation test |
| Java API | `ManualSpanBuilder` | App developer | Supported API | Accumulates spec; `start()` creates `SpanExecution` | Behavioral tests |
| SPI | `TracingRuntime` | Platform module | Decision required | If third-party impl allowed: lifecycle/threading rules | Architecture + SPI tests |
| Configuration | `platform.tracing.enabled` | App/SRE | Supported API | Defined behavior at `false`; startup-only | Context matrix |
| Spring | `PlatformTracing` bean | Spring/App | Supported Spring | Exactly one bean; clear user override behavior | ApplicationContextRunner |
| Spring | Internal exporter bean name | Other modules | Incidental | Must not be used without official qualifier contract | Repo usage search |
| Dependencies | `opentelemetry-api` | App developer | Supported or deliberate | BOM-managed; OTel types only in declared places | Dependency/API scan |
| Dependencies | `opentelemetry-sdk` | Runtime | Internal | Must not be required for consumer compilation | Gradle metadata test |
| Operations | Dropped spans counter | SRE | Supported operational | Stable name, unit, tags, increment semantics | Meter snapshot test |
| Evolution | Deprecated property alias | App/SRE | Deprecated | Works during migration window | Migration test |

Primary value: surfacing **unresolved decisions** (e.g. `TracingRuntime` SPI status).

## Practical assignment template

For library/starter analysis, build a table with at least 20 rows:

| Surface | Element | Consumer | Intended status | Effective usage | Guarantees | Evidence | Risk |

Group results:

1. intended and effective match
2. documented but not working or unused
3. used but not intended as API
4. status requires architectural decision

Week 01 goal: see the full contract before design discussion — not fix yet.
