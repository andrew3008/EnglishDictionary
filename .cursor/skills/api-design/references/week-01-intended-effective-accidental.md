# Week 01 — Intended, Effective, and Accidental API

## Explicitly published API

Team intentionally:

- defines consumers
- selects supported types and surfaces
- documents guarantees
- separates API, SPI, and internal implementation
- adds contract tests
- sets versioning and migration policy
- assigns ownership
- reviews changes before release

Signs: dedicated `api` module/package, documented use cases, Javadoc on contract types, thread-safety/lifecycle docs, configuration metadata, extension point list, stable metrics, compatibility checks, migration guide.

## Accidentally published API

Internal detail becomes observable/usable and consumers rely on it.

Common sources:

- all classes `public` for build convenience
- implementation packages in published JAR
- internal beans autowirable by type
- `@Bean` names used across modules
- third-party types leaking into public signatures
- internal exceptions escaping facade
- stable observed ordering without guarantee
- metrics without specification
- JSON shape from internal class fields
- reflection on private/package-private members
- test helpers in main artifact
- optional dependency becoming transitive
- undocumented properties in Helm charts

**Why dangerous:** consumer treats it as stable; author treats it as mutable; conflict discovered at upgrade or incident.

## Disposition for accidental API

1. **Promote** — document and stabilize if useful and architecturally sound
2. **Replace** — add official API/SPI and migrate consumers
3. **Restrict** — change visibility, package/module boundaries, or publication scope
4. **Declare unstable** — acceptable pre-release/experimental with clear status

`@Internal` alone fails if the exposed surface remains the only way to solve a real task.

## Intended API

Surface author intends for use and commits to support.

Expressed via: documentation, package/module structure, visibility, naming, published examples, stability annotations, testing policy, release notes, owner team responses.

## Effective API

Everything consumers and external systems actually depend on as stable integration points.

May include: undocumented public class, bean name, customizer order, exception subtype, transitive dependency, log message format, JSON field name, metric tag, default queue policy, absent bean when disabled, ability to override internal bean.

**Goal:** `effective API ≈ intended API`

| Situation | Consequence |
|---|---|
| Intended wider than effective | Docs promise what does not work |
| Effective wider than intended | Consumers depend on "internal" details |
| Sets diverge | False promises plus hidden dependencies |

## Example divergence

Intended: exporter configured only via `platform.tracing.export.queue-capacity`.

Effective: property plus `@ConditionalOnMissingBean SpanExporter` bean replacement used by multiple teams.

Required decision: support replacement as SPI or close it with explicit customizer/provider.

## Effective API discovery sources

Code alone is insufficient. Also check:

- corporate repository usage search
- dependency analysis
- Helm and Config Server configs
- dashboards and alerts
- support tickets and incidents
- migration history
- internal documentation examples
- runtime telemetry
- reflection and bean lookup patterns
- interviews with major consumer owners

## `public` ≠ supported API

`public` is a Java access rule. It says nothing about intended audience, stability, compatibility, inheritance policy, direct instantiation, documentation, or contract lifetime.

```text
public accessibility ≠ supported public API
```

Internal classes are often `public` for Spring, proxies, reflection, serialization, testing, codegen, or historical structure. IDE and Javadoc still expose them.

## Expressing supported surface

Use multiple layers together:

- separate API module
- semantic packages (`api`, `spi`, `internal`)
- minimal visibility
- JPMS `exports` when applicable
- Javadoc with stability and audience
- `@Internal` / `@Incubating` as supplementary signals
- exclude internal packages from published Javadocs
- architecture tests
- binary compatibility baseline for supported packages only
- explicit extension point documentation

No single mechanism is sufficient.

## Public interface risk

Consumers may call, implement, proxy, mock, extend, or serialize interfaces.

Author may intend calls-only, not third-party implementations. Without restriction, adding abstract methods later breaks implementors.

For each interface define: who implements, who calls, third-party implementation allowed?, inheritance allowed?, evolution strategy, default methods needed?, final class or sealed hierarchy better?

## Non-public details becoming contract

### Reflection

May target private fields, package-private constructors, annotations, generics, method/parameter names. Distinguish supported reflective surface from accidental access.

### Spring bean names

Package-private bean method + internal config class can still publish name via `@Qualifier`, `@Resource`, `getBean`, SpEL, conditions, actuator, tests.

### Configuration binding

Package-private properties class still publishes YAML keys, env vars, defaults, validation messages, IDE metadata.

### Serialization / wire formats

JSON, YAML, protobuf, DB rows, Kafka records, JMX maps, headers, caches — field names from internal classes become wire contract (e.g. Jackson field-based mapping).

### Observable behavior

Private algorithms create contract through observable results: enricher order, parent span selection, queue policy, exception message format, retry interval, shutdown order, attribute normalization.

Encapsulation hides mechanism, not observable outcome.
