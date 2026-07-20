# Gradle Foundations and Multi-Project Architecture

## Context

This repository contains an enterprise platform tracing solution implemented as a Gradle multi-project build.

The build includes modules for:

- public tracing API
- core runtime implementation
- Spring Boot auto-configuration
- servlet and WebFlux adapters
- starters
- OpenTelemetry extension and JMX integration
- collector configuration
- test utilities
- samples
- benchmarks and performance verification
- Docker-backed E2E tests
- architecture fitness checks

The tracing solution is currently **pre-production**.

Breaking build, dependency, module, artifact, plugin, task, publication, and source-set changes are allowed when they materially improve:

- architectural integrity
- dependency governance
- build determinism
- verification quality
- runtime safety
- CI reliability
- publication correctness
- production readiness

Backward compatibility with accidental pre-production Gradle tasks, configurations, project names, dependency leakage, or publication metadata is not a primary goal.

Do not preserve obsolete tasks, aliases, configurations, module dependencies, compatibility publications, or duplicated build logic merely because local scripts or old internal tests still use them.

Architects will not accept cosmetic Gradle refactoring. Every substantial build change must solve a concrete problem such as:

- hidden dependency leakage
- non-reproducible output
- incorrect API/runtime classpath
- missing verification
- slow or eager configuration
- unstable CI behavior
- publication defect
- module-boundary violation
- environment-specific build behavior
- skipped tests reported as green
- build logic duplication

## Source of Truth

Use the checked-in Gradle Wrapper.

Do not assume a Gradle version from general policy text.

Before changing build logic, inspect:

```text
gradle/wrapper/gradle-wrapper.properties
settings.gradle / settings.gradle.kts
root build.gradle / build.gradle.kts
gradle/libs.versions.toml if present
build-logic / included builds / buildSrc if present
```

The wrapper version, current DSL, plugin-management model, and dependency-management model are repository facts.

Do not migrate Groovy DSL to Kotlin DSL, introduce a version catalog, replace convention plugins, or reorganize build logic as a side effect of an unrelated task.

Use the repository's existing DSL consistently.

A repository-wide DSL or build-logic migration requires its own plan, performance evidence, and architecture review.

## Priority

When requirements conflict, prefer in this order:

1. correct artifacts and classpaths
2. reproducible verification
3. architecture boundaries
4. secure dependency and publication behavior
5. configuration avoidance
6. configuration-cache compatibility
7. build-cache correctness
8. CI/local parity
9. developer convenience
10. compatibility with pre-production build behavior

Do not trade correctness for a green but incomplete build.

## Build Ownership

### Root build

The root build may own:

- common plugin application policy
- shared repositories through approved central configuration
- aggregate verification tasks
- architecture fitness task registration
- common group/version metadata
- narrow shared defaults where convention plugins do not yet exist

The root build must not become a giant imperative script that mutates every project unpredictably.

### Settings

`settings.gradle` / `settings.gradle.kts` owns:

- project inclusion
- plugin management
- dependency repository management where configured
- version catalogs
- included builds
- feature previews
- repository policy

Feature modules must not redefine repositories unless an explicit exception is approved.

### Convention/build-logic modules

Convention plugins may own:

- compiler configuration
- Java toolchain policy
- test conventions
- Javadoc defaults
- publication conventions
- architecture/quality tasks
- common source-set wiring

A convention plugin should configure one coherent concern.

Do not create a convention plugin for a single trivial line or hide project-specific behavior behind a generic plugin name.

### Feature modules

A feature module owns:

- its direct project dependencies
- its narrow external dependencies
- module-specific generated resources
- module-specific test fixtures/source sets
- module-specific publication metadata
- capability-specific tasks

A feature module must not duplicate root/convention logic without a documented reason.

## Multi-Project Architecture

Gradle project dependencies must reflect the intended architecture.

Expected direction:

```text
platform-tracing-core -> platform-tracing-api

platform-tracing-spring-boot-autoconfigure
    -> platform-tracing-api
    -> platform-tracing-core

platform-tracing-autoconfigure-webmvc
    -> approved API/core/autoconfigure modules

platform-tracing-autoconfigure-webflux
    -> approved API/core/autoconfigure modules

platform-tracing-otel-extension
    -> approved API/core modules
    -> OTel integration artifacts

starters
    -> matching auto-configuration/integration modules

tests/samples/bench/e2e
    -> production modules under test
```

Forbidden directions include:

- `platform-tracing-api -> platform-tracing-core`
- `platform-tracing-core -> Spring Boot auto-configuration`
- servlet adapter -> WebFlux adapter
- WebFlux adapter -> servlet adapter
- production modules -> test/e2e/sample modules
- API -> JMX/OpenMBean/Spring/OTel SDK implementation modules unless explicitly approved

Architecture fitness tasks must verify important dependency directions.

Do not solve a cycle by changing `implementation` to `api`.

Fix ownership.

## Module Creation

Create a new Gradle module only when it provides a real boundary:

- independently reusable contract
- distinct runtime/classloader
- distinct publication artifact
- optional dependency isolation
- servlet/reactive split
- agent/application separation
- independently testable integration
- meaningful dependency reduction

Do not create tiny modules for every package or implementation detail.

Before adding a module, document:

```text
Owner:
Consumers:
Published or internal:
Runtime/classloader:
Dependencies exposed:
Dependencies hidden:
Why a package is insufficient:
Tests:
Publication:
```

## Module Removal and Renaming

Because the solution is pre-production, obsolete modules may be removed or renamed directly when justified.

Required migration:

1. update `settings`
2. update project dependencies
3. update CI/task references
4. update publication coordinates if applicable
5. update docs/samples
6. remove obsolete module
7. scan for old project path/artifact coordinates
8. run full build and publication verification

Do not keep empty compatibility modules or forwarding artifacts by default.

