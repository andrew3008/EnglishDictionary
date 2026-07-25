# Week 01 — Contract Fundamentals

## Context

Platform Java libraries and Spring Boot starters expose multiple contract surfaces. Consumers include application developers, Spring container, Gradle/Maven, IDE, SRE tooling, and extension authors.

## API as a promise system

API is the set of promises a component author gives consumers.

A method signature describes **shape**, not full semantics. For every public operation, determine at minimum:

- null acceptance and validation timing
- lifecycle ownership (who creates, completes, closes)
- idempotency and repeat-call semantics
- thread-safety model
- error types and propagation
- blocking vs non-blocking behavior
- side effects and context changes
- stability across minor versions

## Contract vs implementation

| Question | Contract | Implementation |
|---|---|---|
| Focus | What consumers may rely on | How it works today |
| Change impact | Requires migration/compatibility analysis | May change if contract unchanged |
| Example | Order not guaranteed | Current impl happens to preserve order |

If consumers cannot distinguish promise from observed behavior, internal details become expensive accidental API.

## Contract binds both sides

**Consumer obligations:** valid inputs, lifecycle compliance, no internal packages, no reliance on undocumented ordering, documented threading model, declared error handling.

**Author obligations:** preserve invariants, migrate semantic changes, maintain declared compatibility, support documented extension points, no secrets in diagnostics, predictable misuse diagnostics.

## API exists without documentation

Undocumented contracts are inferred from signatures, behavior, tests, examples, source, IDE completion, production behavior, support answers, and corporate templates. Implicit contracts are especially dangerous.

## Multi-layer model

| Surface | Main question |
|---|---|
| Java API | Which types and operations does consumer Java code use? |
| SPI | How does consumer provide custom implementation or behavior? |
| Behavioral contract | What do operations mean; what guarantees do they give? |
| Configuration contract | How is the component configured; what do parameters mean? |
| Spring contract | Which beans and integration rules appear in the container? |
| Dependency contract | What lands on consumer compile/runtime classpath? |
| Operational contract | What do SRE and production tools observe? |
| Compatibility contract | Which guarantees persist across versions? |

Behavioral contract permeates all surfaces. Spring contract deserves a separate inventory row for starters.

## Compatibility contract

Distinguish:

- **source compatibility** — client source recompiles
- **binary compatibility** — previously compiled classes still load/run
- **behavioral compatibility** — observed semantics preserved
- **configuration compatibility** — properties and defaults preserved
- **dependency compatibility** — acceptable classpath preserved
- **operational compatibility** — metrics, health, diagnostics preserved
- **SPI compatibility** — user implementations still work
- **serialization compatibility** — wire/storage representations interoperate

## SemVer prerequisite

Semantic Versioning requires a precise, complete public API definition first. If the team treats only Java interfaces as public API while consumers depend on property names and metric tags, version numbers mislead.

## Breaking changes without Java type changes

| Change | Possible effect |
|---|---|
| Default timeout change | Behavioral breaking change |
| Property rename | Configuration breaking change |
| Bean name change | Spring integration breaking change |
| Transitive dependency removal | Consumer compilation break |
| Metric rename | Operational breaking change |
| Exception type change | Behavioral/source impact |
| Shutdown order change | Lifecycle regression |
| New `@ConditionalOnMissingBean` | Composition semantics change |

## Key theses

1. API is a system of promises, not a method list.
2. Signature describes interaction shape, not full semantics.
3. Starters publish Java, configuration, Spring, dependency, and operational contracts simultaneously.
4. Behavioral contract permeates all surfaces.
5. SemVer requires inventory first.
