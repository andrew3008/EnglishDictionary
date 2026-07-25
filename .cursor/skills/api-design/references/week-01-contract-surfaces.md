# Week 01 — Contract Surfaces

## Java API (public type surface)

Includes: classes, interfaces, records, enums, annotations, constructors, methods, fields, generics, checked exceptions, nested types, package names, sealed permitted types.

**Third-party types in public signatures become your ABI.** Example: a customizer accepting `SdkTracerProviderBuilder` publishes OpenTelemetry as part of consumer contract.

**Surface does not describe semantics.** `addLink` vs `setLink` may imply accumulation vs replacement; compiler cannot enforce this.

**Every published type has cost:** documentation, testing, compatibility analysis, transitive deps, inheritance/mock/reflection usage, Spring/SpEL/serialization coupling.

Minimize **obligations that cannot safely change**, not line count.

## Behavioral contract

Specify:

- preconditions and postconditions
- invariants
- side effects
- error behavior
- ordering guarantees
- timing and blocking
- concurrency guarantees
- resource ownership
- lifecycle
- idempotency
- consistency model
- edge and partial-failure behavior

**Specify absence of action** when consumers might infer the opposite:

- span creation does not make it current
- customizer order not guaranteed without `Ordered`
- missing optional dependency is not an error
- disabled starter publishes no beans
- no-op creates no telemetry
- library does not own/close passed `ExecutorService`

**Defaults are behavior.** Changing a default (e.g. queue policy) can break production without signature changes.

**Test status matters:**

| Test type | Purpose |
|---|---|
| Contractual | Validates consumer promise |
| Characterization | Captures current impl before change |
| Implementation | Validates internal algorithm |
| Regression | Fixes a defect |
| Compatibility | Validates cross-version behavior |

Do not elevate characterization/implementation tests to public guarantees without review.

## Configuration contract

For Spring Boot starters, configuration is often the primary consumer surface.

Includes: namespace, property names, types, format, units, defaults, ranges, inter-property dependencies, validation, source precedence, env-var mapping, startup/dynamic semantics, deprecation/replacement, unknown-value handling, runtime effect.

**Java field default ≠ full configuration semantics.** Document what `enabled=false` means for beans, propagation, metrics, restart behavior, bean override precedence, Helm env mapping.

**Environment mapping is contract.** Property renames break Helm, Config Server, runbooks, templates, validation pipelines.

Version and migrate configuration like Java API.

## Spring contract

Spring container consumes: auto-configuration imports, configuration class names, conditions, ordering, bean types/names, qualifiers, `@Primary`, back-off rules, lifecycle callbacks, customizers/registrars, parent context strategy, AOT/runtime hints.

**Bean type can be API without public Java call.** If users may provide their own `TracingRuntime`, define: supported or not, required invariants, lifecycle ownership, surrounding bean behavior, duplicate-bean semantics.

**`@ConditionalOnMissingBean` may publish SPI.** If override was not architecturally intended, the condition creates accidental extension contract.

**Bean names are contracts** even for package-private `@Bean` methods. Consumers use `@Qualifier`, `@Resource`, `getBean(String)`, SpEL, conditions, actuator `/beans`.

## Dependency contract

Starter dependency graph affects: available types, version resolution, size, startup, vulnerabilities, classpath conflicts, foreign auto-configurations, native-image, licensing, Spring Boot upgrade path.

**Gradle `api` vs `implementation` expresses intent.** Types in public signatures must align with published compile dependencies.

**Transitive presence ≠ support.** Distinguish:

- intentionally exported API dependency
- runtime-only internal dependency
- accidental transitive exposure
- BOM-managed but not added dependency
- optional integration dependency

## Operational contract

SRE consumes: metrics, health, Actuator, JMX, logs, startup diagnostics, env vars, shutdown behavior, dashboards/alerts.

For each metric define: name, type, unit, tag keys/values, increment timing, reset semantics, cardinality, zero-value presence.

**Diagnostics are usability.** Production-grade contract answers: auto-config activated?, why not?, effective values?, chosen implementation?, user vs platform bean?, shutdown outcome?, data loss?, operator action?

## Consumer-specific breaking change lens

| Consumer | Primary surface | Breaking change means |
|---|---|---|
| Application developer | Java API, configuration | Code fails to compile or behavior changes |
| Platform module | Java API, SPI, dependencies, Spring | Composition or module boundary breaks |
| Extension author | SPI | Implementation no longer invoked correctly |
| Spring container | Metadata, conditions, beans | Context fails or composition changes |
| SRE | Configuration, metrics, health, logs | Deployment, dashboard, or alert breaks |
| Gradle/Maven/IDE | Publication metadata | Classpath or tooling support changes |
