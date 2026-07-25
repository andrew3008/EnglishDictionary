# Week 01 — Review Checklists and Red Flags

## Common analysis mistakes

1. **Inventory only `public` declarations** — misses properties, beans, dependencies, metrics, wire formats
2. **Treat documentation as sole truth** — misses effective API in production
3. **Auto-promote all observed behavior** — stabilizes accidental architecture forever
4. **Treat `@ConditionalOnMissingBean` as technical detail** — creates undocumented bean-replacement SPI
5. **Compatibility via ABI tool only** — misses defaults, properties, metrics, lifecycle
6. **Do not separate callable API from implementable SPI** — safe for callers, breaks implementors
7. **Ignore SRE and build tools** — Java API compatible but deployment/monitoring breaks
8. **Confuse availability with support obligation** — `public` internal classes used without policy

## Starter red flags

Check explicitly on first review:

- third-party SDK types in public signatures
- `public` packages named `impl`, `internal`, `support`
- many `@ConditionalOnMissingBean` without documented override contract
- application code depending on bean names
- internal beans autowirable
- missing configuration metadata
- defaults only in Java fields, not documented to users
- configuration properties without units and ranges
- unknown semantics of `enabled=false`
- metrics without name/cardinality specification
- health statuses without operational meaning
- callbacks accepting mutable internal objects
- transitive SDK on compile classpath
- Gradle vs Maven publication metadata mismatch
- foreign implementation exception types through facade
- serialization tied directly to internal model
- public interfaces without third-party implementation policy
- no migration policy for properties and operational names

Red flag ≠ automatic defect. It means **explicit architectural decision required**.

## Pre-release checklist

### Java surface

- Which packages are supported API?
- Which interfaces may consumers implement?
- Which third-party types enter ABI?
- Which annotations and exceptions are stable?
- Which `public` types are accessible but unsupported?

### Behavior

- Preconditions, side effects, errors documented?
- Thread-safety, ownership, lifecycle defined?
- Defaults part of specification?
- Tests accidentally cementing observed behavior?

### Configuration

- All properties, defaults, validation listed?
- Environment variable names defined?
- Behavior for missing, invalid, conflicting properties known?
- Rename and deprecation policy defined?

### Spring

- Supported bean types and names identified?
- Which beans may be replaced?
- Conditions and back-off rules described?
- Auto-configuration and customizer order stable?

### Dependencies

- Which dependencies are compile API?
- Accidental transitive dependencies?
- Implementation types leaking into public signatures?
- Who owns version and compatibility of dependency graph?

### Operations

- Stable metrics, tags, health statuses, log categories?
- How SRE determines effective configuration?
- Degradation vs failure defined?
- Migration path for dashboards and alerts?

### Evolution

- What patch/minor/major mean for this library?
- Which surfaces have automated checks?
- Who approves breaking changes?
- Migration window and replacements for deprecated contracts?

## Control questions (self-test)

1. Why is a public class list insufficient for a starter API description?
2. How does contract differ from current implementation?
3. Can a default value be a breaking change? Example?
4. When does `@ConditionalOnMissingBean` create de-facto SPI?
5. Why can bean name be public contract with package-private config class?
6. Intended vs effective API?
7. How to determine effective API of an existing corporate library?
8. When does transitive dependency become effective API?
9. When is a `public interface` call-only, not for third-party implementation?
10. Why is `@Internal` insufficient alone?
11. Which contracts does SRE consume?
12. Why can metric rename be company-level major breaking change?
13. How can private field become wire contract?
14. Contractual vs characterization test?
15. Why is SemVer meaningless without Public Contract Inventory?

## Final theses

1. API is a promise system, not a method list.
2. Signature is shape, not full semantics.
3. Starters publish Java, configuration, Spring, dependency, and operational contracts.
4. Behavioral contract permeates all surfaces.
5. `public` is access, not support guarantee.
6. Non-public details become contracts when stably observable or usable.
7. Intended API is author policy; effective API is real dependencies.
8. Divergence between intended and effective API increases surprise breakage risk.
9. SemVer requires inventory first.
10. Public Contract Inventory is the starting point for professional API design and compatibility analysis.

## Primary sources (selective reading)

- [Semantic Versioning 2.0.0](https://semver.org/)
- [JLS Chapter 13: Binary Compatibility](https://docs.oracle.com/javase/specs/jls/se25/html/jls-13.html)
- [Spring Boot: Creating Your Own Auto-configuration](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html)
- [Spring Boot: Externalized Configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)
- [Gradle: The Java Library Plugin](https://docs.gradle.org/current/userguide/java_library_plugin.html)
