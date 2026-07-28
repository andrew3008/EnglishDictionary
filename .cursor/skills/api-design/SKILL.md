---
name: api-design
description: Defines enterprise Java library and Spring Boot starter API design standards from the Platform Development guide (16-week curriculum). Use when analyzing, designing, reviewing, or documenting public API boundaries, semantic models, state/information models, usability, object creation, Java type modeling, mutability/lifecycle, error contracts, API/SPI boundaries, compatibility evolution, dependency publication, starter architecture, conditional configuration and back-off, Configuration API and key space, Extension API and customizers, operational contracts, testing gates, release governance, Public Contract Inventory, or Semantic Contract Map.
---

# API Design — Platform Development Guide

## Objective

Design and review platform Java libraries and Spring Boot starters as **systems of interrelated contracts** with a coherent semantic model, valid state space, explicit ownership, and verifiable evolution rules.

## Priority

When requirements conflict, prefer:

1. explicit supported contracts over accidental exposure
2. semantic and behavioral clarity over signature-only analysis
3. valid/unreachable state prevention over runtime validation prose
4. consumer-specific contract clarity over author convenience
5. inventory-backed compatibility decisions over ABI-only checks
6. minimal intentional public surface over convenience exposure

## Core workflow

1. **Week 01** — inventory all contract surfaces; distinguish intended vs effective API.
2. **Week 02** — define ubiquitous language, conceptual roles, Semantic Contract Map.
3. **Week 03** — model state space, invariants, valid/reachable states.
4. **Week 04** — evaluate usability, cognitive load, discovery, task journeys.
5. **Week 05** — choose creation forms (constructor, factory, builder, Spring pipeline).
6. **Week 06** — select Java type forms (record, sealed, enum, generics).
7. **Week 07** — specify mutability, ownership, lifecycle, concurrency.
8. **Week 08** — design error contract (taxonomy, recovery, observability).
9. **Week 09** — separate API, SPI, and internal implementation.
10. **Week 10** — plan compatibility dimensions, deprecation, evolution.
11. **Week 11** — govern dependency API, scopes, publication metadata.
12. **Week 12** — architect starter modules, auto-configuration, extension graph.
13. **Week 13** — design conditions and back-off as an executable protocol.
14. **Week 14** — design Configuration API as published control language.
15. **Week 15** — design Extension API and Spring integration contract.
16. **Week 16** — design Operational API, testing portfolio, and governance gates.

Load only the `references/week-NN-*.md` files relevant to the task.

Week 12 is the starter architecture overview. Week 13 is the deep executable condition/back-off protocol — load week-13 for condition detail; do not re-read all of week-12 for that purpose.

## Reference selection by week

### Week 01 — What is API

| File | Use when |
|---|---|
| [week-01-contract-fundamentals.md](references/week-01-contract-fundamentals.md) | Contract model, multi-layer surfaces, compatibility types |
| [week-01-contract-surfaces.md](references/week-01-contract-surfaces.md) | Java, behavioral, config, Spring, dependency, operational contracts |
| [week-01-intended-effective-accidental.md](references/week-01-intended-effective-accidental.md) | Intended vs effective API, accidental publication |
| [week-01-public-contract-inventory.md](references/week-01-public-contract-inventory.md) | Building Public Contract Inventory |
| [week-01-review-checklists.md](references/week-01-review-checklists.md) | API review, red flags, pre-release checklist |

Source: `E:/Platform_Development/API/api-design-week-01-what-is-api.md`

### Week 02 — Semantic design

| File | Use when |
|---|---|
| [week-02-semantic-fundamentals.md](references/week-02-semantic-fundamentals.md) | Semantics before syntax, conceptual integrity |
| [week-02-ubiquitous-language-roles.md](references/week-02-ubiquitous-language-roles.md) | Ubiquitous language, seven conceptual roles |
| [week-02-types-naming-operations.md](references/week-02-types-naming-operations.md) | Type semantics, naming density, operation contracts |
| [week-02-capabilities-starter-semantics.md](references/week-02-capabilities-starter-semantics.md) | Capability-oriented API, starter activation language |
| [week-02-anti-patterns-semantic-review.md](references/week-02-anti-patterns-semantic-review.md) | Anti-patterns, rename analysis, Semantic Contract Map |
| [week-02-review-checklists.md](references/week-02-review-checklists.md) | Semantic review checklists |

Source: `E:/Platform_Development/API/api-design-week-02-semantic-design.md`

### Week 03 — Information model and valid states

| File | Use when |
|---|---|
| [week-03-state-space-invariants.md](references/week-03-state-space-invariants.md) | State space, invariants, normalization |
| [week-03-product-sum-types.md](references/week-03-product-sum-types.md) | Product/sum types, enum/sealed choice |
| [week-03-lifecycle-spring-states.md](references/week-03-lifecycle-spring-states.md) | Lifecycle, Spring config states |
| [week-03-state-model-review.md](references/week-03-state-model-review.md) | State Model Review, State Contract Map |

Source: `E:/Platform_Development/API/api-design-week-03-information-model-and-valid-states.md`

### Week 04 — Usability and cognitive load

| File | Use when |
|---|---|
| [week-04-cognitive-load-journey.md](references/week-04-cognitive-load-journey.md) | Task journey, cognitive load types |
| [week-04-cognitive-dimensions.md](references/week-04-cognitive-dimensions.md) | Cognitive Dimensions, usability budget |
| [week-04-discovery-signatures-builders.md](references/week-04-discovery-signatures-builders.md) | Discoverability, signatures, builders |
| [week-04-errors-spring-usability.md](references/week-04-errors-spring-usability.md) | Error feedback, Spring metadata usability |
| [week-04-usability-review-checklists.md](references/week-04-usability-review-checklists.md) | Cognitive Walkthrough, usability review |

Source: `E:/Platform_Development/API/api-design-week-04-usability-and-cognitive-load.md`

### Week 05 — Object creation forms

| File | Use when |
|---|---|
| [week-05-creation-semantics.md](references/week-05-creation-semantics.md) | Creation guarantees, object categories |
| [week-05-constructor-factory-record.md](references/week-05-constructor-factory-record.md) | Constructors, factories, records |
| [week-05-builder-factory-spi.md](references/week-05-builder-factory-spi.md) | Builders, registries, ServiceLoader |
| [week-05-spring-creation-pipeline.md](references/week-05-spring-creation-pipeline.md) | Spring/DI creation pipeline |
| [week-05-creation-review-checklists.md](references/week-05-creation-review-checklists.md) | Creation API Review |

Source: `E:/Platform_Development/API/api-design-week-05-object-creation-forms.md`

### Week 06 — Type modeling in modern Java

| File | Use when |
|---|---|
| [week-06-type-forms-choice.md](references/week-06-type-forms-choice.md) | Type form selection matrix |
| [week-06-records-sealed-patterns.md](references/week-06-records-sealed-patterns.md) | Records, enums, sealed hierarchies |
| [week-06-generics-variance-erasure.md](references/week-06-generics-variance-erasure.md) | Generics, variance, erasure |
| [week-06-results-collections-spring.md](references/week-06-results-collections-spring.md) | Optional, results, collections, Spring types |
| [week-06-type-model-review.md](references/week-06-type-model-review.md) | Type Model Review |

Source: `E:/Platform_Development/API/api-design-week-06-type-modeling-in-modern-java.md`

### Week 07 — Mutability, ownership, lifecycle

| File | Use when |
|---|---|
| [week-07-state-contract-fundamentals.md](references/week-07-state-contract-fundamentals.md) | State contract, ownership modes |
| [week-07-immutability-aliasing-collections.md](references/week-07-immutability-aliasing-collections.md) | Immutability, aliasing, collections |
| [week-07-lifecycle-close-concurrency.md](references/week-07-lifecycle-close-concurrency.md) | Lifecycle, AutoCloseable, concurrency |
| [week-07-review-checklists.md](references/week-07-review-checklists.md) | State Contract Matrix, review |

Source: `E:/Platform_Development/API/api-design-week-07-mutability-ownership-lifecycle.md`

### Week 08 — Error contract

| File | Use when |
|---|---|
| [week-08-error-contract-fundamentals.md](references/week-08-error-contract-fundamentals.md) | Failure taxonomy, exception vs result |
| [week-08-exceptions-recovery-async.md](references/week-08-exceptions-recovery-async.md) | Recovery, retry, async failures |
| [week-08-spring-observability-evolution.md](references/week-08-spring-observability-evolution.md) | Spring startup errors, observability |
| [week-08-review-checklists.md](references/week-08-review-checklists.md) | Error contract review |

Source: `E:/Platform_Development/API/api-design-week-08-error-contract.md`

### Week 09 — API, SPI, internal implementation

| File | Use when |
|---|---|
| [week-09-api-spi-roles-boundaries.md](references/week-09-api-spi-roles-boundaries.md) | API/SPI/internal roles, dependency direction |
| [week-09-spi-protocol-extension.md](references/week-09-spi-protocol-extension.md) | SPI protocol, extension forms |
| [week-09-discovery-spring-encapsulation.md](references/week-09-discovery-spring-encapsulation.md) | Discovery, Spring encapsulation, JPMS |
| [week-09-review-checklists.md](references/week-09-review-checklists.md) | Boundary Review |

Source: `E:/Platform_Development/API/api-design-week-09-api-spi-internal-implementation.md`

### Week 10 — Java API compatibility and evolution

| File | Use when |
|---|---|
| [week-10-compatibility-dimensions-scenarios.md](references/week-10-compatibility-dimensions-scenarios.md) | Compatibility dimensions, SemVer |
| [week-10-java-evolution-mechanics.md](references/week-10-java-evolution-mechanics.md) | JVM/binary evolution mechanics |
| [week-10-spring-data-config-evolution.md](references/week-10-spring-data-config-evolution.md) | Config/Spring/operational evolution |
| [week-10-deprecation-testing-workflow.md](references/week-10-deprecation-testing-workflow.md) | Deprecation, test matrix, tooling |

Source: `E:/Platform_Development/API/api-design-week-10-java-api-compatibility-evolution.md`

### Week 11 — Dependency API and library publishing

| File | Use when |
|---|---|
| [week-11-dependency-api-fundamentals.md](references/week-11-dependency-api-fundamentals.md) | Dependency API as contract |
| [week-11-classification-scopes-leakage.md](references/week-11-classification-scopes-leakage.md) | Scopes, ABI leakage, variants |
| [week-11-publication-metadata-packaging.md](references/week-11-publication-metadata-packaging.md) | Publication, packaging, shading |
| [week-11-classpath-consumer-review.md](references/week-11-classpath-consumer-review.md) | Classpath review, consumer matrix |

Source: `E:/Platform_Development/API/api-design-week-11-dependency-api-library-publishing.md`

### Week 12 — Starter architecture

| File | Use when |
|---|---|
| [week-12-starter-architecture-fundamentals.md](references/week-12-starter-architecture-fundamentals.md) | Runtime/bootstrap/adoption systems, modules |
| [week-12-autoconfigure-activation.md](references/week-12-autoconfigure-activation.md) | Auto-configuration, conditions, ordering overview |
| [week-12-configuration-extensions-operations.md](references/week-12-configuration-extensions-operations.md) | Properties, extension points, operations overview |
| [week-12-dependencies-testing-publication.md](references/week-12-dependencies-testing-publication.md) | Dependencies, test matrix, publication review |

Source: `E:/Platform_Development/API/api-design-week-12-starter-architecture.md`

### Week 13 — Conditional configuration and back-off

| File | Use when |
|---|---|
| [week-13-conditional-fundamentals.md](references/week-13-conditional-fundamentals.md) | Conditions as decision system, back-off contract, non-invasiveness, topology |
| [week-13-classpath-bean-conditions.md](references/week-13-classpath-bean-conditions.md) | Classpath isolation, MissingBean/OnBean/SingleCandidate, Primary/Fallback, ambiguity |
| [week-13-property-composite-conditions.md](references/week-13-property-composite-conditions.md) | Property truth table, activation vs replacement, custom Condition, slices |
| [week-13-ordering-backoff-states.md](references/week-13-ordering-backoff-states.md) | Ordering limits, backoff granularity, error vs backoff, state model, report |
| [week-13-testing-review-checklists.md](references/week-13-testing-review-checklists.md) | Condition test matrix, Conditional Configuration Review, anti-patterns |

Source: `E:/Platform_Development/API/api-design-week-13-conditional-configuration-backoff.md`

### Week 14 — Configuration API

| File | Use when |
|---|---|
| [week-14-configuration-language-namespace.md](references/week-14-configuration-language-namespace.md) | Config as language, key space, prefix, relaxed binding, Origin |
| [week-14-defaults-binding-model.md](references/week-14-defaults-binding-model.md) | Defaults taxonomy, immutable binding, null/absent, lists/maps merge |
| [week-14-types-validation-diagnostics.md](references/week-14-types-validation-diagnostics.md) | Enum vs string, converters, validation, strict binding, diagnostics |
| [week-14-metadata-evolution-operations.md](references/week-14-metadata-evolution-operations.md) | Metadata, deprecation, secrets, Actuator configprops, Binder API |
| [week-14-testing-review-checklists.md](references/week-14-testing-review-checklists.md) | Configuration test strategy, Configuration API Review, anti-patterns |

Source: `E:/Platform_Development/API/api-design-week-14-configuration-api.md`

### Week 15 — Extension API and Spring integration

| File | Use when |
|---|---|
| [week-15-extension-modes-replacement.md](references/week-15-extension-modes-replacement.md) | Extension protocol, four modes, minimal force, replacement precision |
| [week-15-customizers-ordering-discovery.md](references/week-15-customizers-ordering-discovery.md) | Customizers, Ordered/ObjectProvider, Primary/Fallback, cardinality |
| [week-15-lifecycle-events-container.md](references/week-15-lifecycle-events-container.md) | Scope/lifecycle, events, BPP/BFPP, proxies, context hierarchy |
| [week-15-failure-security-aot-compat.md](references/week-15-failure-security-aot-compat.md) | Failure policy, trust boundary, AOT/native, Extension API compatibility |
| [week-15-testing-review-checklists.md](references/week-15-testing-review-checklists.md) | Extension test strategy, Extension Contract Review, anti-patterns |

Source: `E:/Platform_Development/API/api-design-week-15-extension-api-spring-integration-contract.md`

### Week 16 — Operational API, testing, governance

| File | Use when |
|---|---|
| [week-16-operational-contract-fundamentals.md](references/week-16-operational-contract-fundamentals.md) | Operational contract, observation vs control plane, inventory, startup diagnostics |
| [week-16-health-actuator-endpoints.md](references/week-16-health-actuator-endpoints.md) | Health semantics, Actuator as API, schema, versioning |
| [week-16-metrics-observation-logs.md](references/week-16-metrics-observation-logs.md) | Metrics/tags/cardinality, Observation, logs, secrets/PII, degradation |
| [week-16-testing-context-matrix.md](references/week-16-testing-context-matrix.md) | Testing as executable spec, ContextRunner, auto-config matrix, slices |
| [week-16-testing-signals-gates.md](references/week-16-testing-signals-gates.md) | Health/metrics/trace tests, failure injection, compat/AOT/perf gates |
| [week-16-governance-review-checklists.md](references/week-16-governance-review-checklists.md) | Governance model, release gates, Operational Contract Review, anti-patterns |

Source: `E:/Platform_Development/API/api-design-week-16-operational-api-testing-governance.md`

## Required reference combinations

| Task | Read |
|---|---|
| New library API design | week-01, week-02, week-03, week-09 |
| Starter architecture review | week-01, week-02, week-12, week-05 |
| Public API review before release | week-01 inventory + week-10 + week-11 |
| Semantic/naming refactor | week-02 + week-06 |
| Error handling design | week-08 + week-07 |
| Dependency/publication change | week-11 + week-10 |
| Usability review | week-04 + week-05 |
| Conditional / back-off design | week-13 (+ week-12 activation overview) |
| Configuration properties / binding | week-14 (+ week-03 + week-10 config evolution) |
| Extension / customizer / replacement | week-15 (+ week-09 SPI + week-13) |
| Operational / Actuator / metrics | week-16 (+ week-08 observability) |
| Release readiness / governance | week-16 + week-10 + week-11 |
| Full starter stack review | week-12 + week-13 + week-14 + week-15 |

Always start with week-01 for contract surface analysis when scope is unclear.

## Completion standard

Do not report API design or review complete until:

- contract surfaces and semantic model are explicit
- invalid states are prevented or classified where possible
- API/SPI/internal boundaries are defined
- ownership, lifecycle, and error contracts are testable
- dependency and publication impacts are verified
- starter module topology matches responsibility boundaries
- condition/back-off decision model and diagnostics are explicit (when Spring auto-configuration is in scope)
- configuration namespace, defaults, validation, and metadata are explicit (when Configuration API is in scope)
- extension mode, cardinality, ordering, and failure policy are explicit (when Extension API is in scope)
- operational inventory and verification/governance gates are named (when operational surface is in scope)
- verification mechanisms are named, not assumed
