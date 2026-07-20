---
name: platform-tracing-api-standards
description: Defines enterprise architectural, API, SPI, dependency, naming, lifecycle, security, compatibility, and verification standards for the Platform Tracing repository. Use when Codex designs, reviews, refactors, or validates platform-tracing-api, platform-tracing-otel, Spring Boot tracing auto-configuration and starters, OpenTelemetry integrations, public contracts, extension SPIs, control protocols, configuration properties, or architecture fitness rules.
---

# Platform Tracing API Standards

## Objective

Apply enterprise API and architecture standards to the pre-production Platform Tracing solution.

Breaking API, SPI, package, bean, and configuration changes are allowed when they materially improve correctness, architectural integrity, runtime safety, usability, or production readiness.

Do not preserve accidental APIs, obsolete packages, ambiguous concepts, aliases, deprecated bridges, or duplicate execution paths merely for compatibility.

Architects will not accept cosmetic refactoring. Every substantial change must remove a concrete defect, clarify ownership, reduce risk, narrow accidental surface, or strengthen an executable invariant.

## Priority

When requirements conflict, prefer:

1. correctness and runtime safety
2. explicit architectural ownership
3. minimal intentional public surface
4. misuse resistance
5. dependency hygiene
6. testability and architecture fitness
7. operator and developer clarity
8. implementation simplicity
9. compatibility with pre-production behavior

## Core workflow

1. Read applicable repository instructions and architecture decisions.
2. Verify current and target module names from repository evidence; do not infer them from stale documents.
3. Identify concrete consumers and runtime owners.
4. Inventory affected public API, SPI, configuration, beans, wire contracts, and generated code.
5. Classify every exposed type.
6. Separate repository facts from assumptions and unresolved decisions.
7. Load the applicable standards from `references/`.
8. Identify semantic and architectural defects before changing code.
9. Prefer removal or redesign over compatibility layering when justified.
10. Implement the smallest coherent architectural change.
11. Update internal consumers, tests, documentation, examples, and generated sources.
12. Add behavioral and architecture verification.
13. Run the relevant repository gates.
14. Report the API delta, removed defects, risks, evidence, and unresolved decisions.

## Mandatory invariants

- Keep `platform-tracing-api` independent of implementation modules.
- Do not expose Spring, OpenTelemetry SDK, Agent, or JMX implementation types through application-facing API.
- Give every public type an identified consumer and classification.
- Keep internal helpers package-private where possible.
- Define lifecycle, failure, threading, ordering, and discovery semantics for every public SPI.
- Use explicitly versioned classloader-neutral types for wire and cross-classloader contracts.
- Make no-op behavior intentional, observable, and semantically valid.
- Do not use static global runtime discovery unless an approved architecture decision requires it.
- Do not implement reactive execution with a ThreadLocal-only context path.
- Do not expose sensitive values through tracing, logs, diagnostics, or control protocols.
- Add executable behavioral and architecture gates for every substantial public change.
- Do not weaken a boundary merely to avoid updating tests, examples, documentation, or internal consumers.

## Reference selection

Read only the references relevant to the task.

### Architecture, modules, packages, or ownership

Read [architecture-and-boundaries.md](references/architecture-and-boundaries.md).

Use it for module responsibilities, API/implementation separation, public-surface classification, package placement, visibility boundaries, and accidental public surface.

### Public Java API design

Read [public-api-design.md](references/public-api-design.md).

Use it for facades, builders, value objects, naming, nullability, immutability, result/error models, and exposure of external types.

### SPI, ServiceLoader, propagation, or control protocols

Read [spi-and-control-contracts.md](references/spi-and-control-contracts.md).

Use it for extension points, provider discovery, schemas, runtime control, cross-module or cross-classloader contracts, and dependency governance.

### Spring runtime, configuration, lifecycle, or security

Read [spring-runtime-and-security.md](references/spring-runtime-and-security.md).

Use it for auto-configuration, properties, bean names, disabled/no-op modes, concurrency, lifecycle, privacy, and security.

### Documentation, tests, and architecture fitness

Read [documentation-and-verification.md](references/documentation-and-verification.md).

Use it whenever changing a supported public contract or an architectural boundary.

### Breaking changes and final review

Read [compatibility-and-review.md](references/compatibility-and-review.md).

Use it for pre-production breaking changes, compatibility assessment, deprecation, generated code, final review, verification, and API change reports.

## Required reference combinations

For a substantial application-facing API refactoring, read at minimum:

1. `architecture-and-boundaries.md`
2. `public-api-design.md`
3. `documentation-and-verification.md`
4. `compatibility-and-review.md`

Also read the SPI or Spring reference when those areas are affected.

For a cross-module, cross-process, or cross-classloader contract, read at minimum:

1. `architecture-and-boundaries.md`
2. `spi-and-control-contracts.md`
3. `spring-runtime-and-security.md` when Spring participates
4. `documentation-and-verification.md`
5. `compatibility-and-review.md`

## Completion standard

Do not report completion until:

- the affected public surface is classified
- ownership and dependency direction are explicit
- obsolete or conflicting paths are removed
- internal consumers and generated sources are migrated
- documentation describes the resulting semantics
- behavioral and architecture tests are present
- relevant verification commands pass
- remaining decisions, assumptions, and unverified risks are explicit

