---
name: gradle
description: Defines enterprise Gradle build and dependency-governance standards for the Platform Tracing multi-project repository. Use when Codex designs, reviews, refactors, implements, or validates modules, settings, plugins, dependency scopes, BOMs, repositories, toolchains, source sets, generated code, tests, custom tasks, configuration cache, build cache, publications, POM/module metadata, Docker-backed E2E, CI behavior, build security, or architecture fitness tasks.
---

# Platform Tracing Gradle Standards

## Objective

Keep the Platform Tracing multi-project build architecturally correct, reproducible, lazy, portable, secure, diagnosable, and publication-safe.

The solution is pre-production. Breaking module, project, task, configuration, dependency, artifact, plugin, publication, or source-set changes are allowed when they materially improve architecture, classpaths, determinism, verification, CI reliability, security, or production readiness.

Do not preserve accidental tasks, aliases, configurations, project names, dependency leakage, compatibility publications, or duplicated build logic without an approved requirement.

Architects will not accept cosmetic Gradle refactoring. Every substantial change must correct an artifact/classpath defect, enforce a module boundary, improve reproducibility, remove eager or duplicated logic, close a publication gap, or strengthen executable verification.

## Source of truth

Before changing build logic, inspect:

- the checked-in Gradle Wrapper
- `settings.gradle` or `settings.gradle.kts`
- root and affected project build files
- plugin management
- dependency management and version catalogs when present
- convention plugins, included builds, or `buildSrc`
- CI commands and custom source sets
- publication and architecture-verification tasks

Treat wrapper version, DSL, plugin model, repositories, and dependency model as repository facts.

Do not migrate Groovy/Kotlin DSL, introduce a version catalog, replace convention plugins, or reorganize build logic as an unrelated side effect.

## Priority

When requirements conflict, prefer:

1. correct artifacts and runtime/compile classpaths
2. reproducible complete verification
3. architecture boundaries
4. secure dependencies and publications
5. configuration avoidance
6. configuration-cache compatibility
7. build-cache correctness
8. CI/local parity
9. developer convenience
10. compatibility with pre-production build behavior

A green but incomplete build is not success.

## Core workflow

1. Read applicable repository instructions, wrapper/version facts, architecture plans, and CI conventions.
2. Inspect Git state and the complete affected build graph.
3. Identify artifact owners, consumers, source sets, classpaths, publications, and verification tasks.
4. Load the applicable files from `references/`.
5. Trace each changed dependency through compile, runtime, test, packaged consumer, POM, and Gradle module metadata.
6. Trace task inputs, outputs, providers, environment, processes, filesystem access, caching, and failure behavior.
7. Verify module direction and optional dependency isolation.
8. Implement the smallest coherent build change using the repository's existing DSL and conventions.
9. Compile every affected main, test, custom, generated, benchmark, and E2E source set.
10. Run architecture, dependency, publication, configuration-cache, reproducibility, and full-build gates proportional to the claim.
11. Inspect generated artifacts and metadata directly.
12. Distinguish executed, skipped, cached, up-to-date, and environment-blocked verification.
13. Report module/dependency/artifact delta, commands, cache behavior, publication evidence, and residual risks.

## Mandatory invariants

- Use the checked-in Wrapper; do not assume Gradle version.
- Keep settings, root policy, convention logic, and feature-module responsibilities distinct.
- Create a module only for a real dependency, publication, lifecycle, classloader, or ownership boundary.
- Remove or rename modules atomically across settings, dependencies, BOMs, publications, CI, docs, tests, and architecture rules.
- Use `api` only for dependencies exposed through a supported public signature.
- Use `implementation` for internal dependencies.
- Use `compileOnly` only when a verified runtime owner supplies the dependency.
- Prevent optional integrations from leaking into minimal starter classpaths.
- Centralize versions according to existing repository conventions.
- Do not add unapproved repositories or dynamic/changing versions.
- Use configuration avoidance, lazy providers, and declared inputs/outputs.
- Avoid `afterEvaluate`, eager task realization, and broad `allprojects/subprojects` mutation when a convention plugin or targeted configuration is appropriate.
- Do not read environment, filesystem, network, Docker, or external processes during configuration unless unavoidable and explicitly modeled.
- Keep build behavior portable across Windows, Linux, CI, and remote Docker.
- Give generated sources/resources deterministic ownership and task dependencies.
- Compile and test all affected custom source sets and descriptors.
- Do not call opt-in tests passed when they were skipped.
- Keep architecture baselines intentional and reviewable; do not regenerate them merely to hide violations.
- Verify POM and Gradle module metadata, not only local project resolution.
- Keep starter publications minimal and runtime-complete.
- Do not claim configuration-cache or reproducibility compatibility without executing the relevant checks.
- Avoid exposing secrets through properties, command lines, logs, reports, manifests, or generated metadata.
- Make task failures actionable and distinguish product defects from environment limitations.

## Reference selection

Read only references relevant to the task, except for required combinations.

### Foundations and multi-project architecture

Read [foundations-and-multi-project-architecture.md](references/foundations-and-multi-project-architecture.md).

Use it for source-of-truth discovery, ownership, settings, convention logic, module boundaries, creation, removal, and renaming.

### Dependencies, BOMs, repositories, verification, and plugins

Read [dependencies-platforms-and-repositories.md](references/dependencies-platforms-and-repositories.md).

Use it for dependency scopes, API leakage, versions, platforms/BOMs, repositories, locking/verification, and plugin governance.

### Toolchains, configuration avoidance, caches, and custom tasks

Read [java-configuration-and-caches.md](references/java-configuration-and-caches.md).

Use it for Java compilation, lazy configuration, `allprojects/subprojects`, configuration cache, build cache, and task design.

### Filesystem, processes, environment, portability, and Docker

Read [filesystem-process-environment-and-docker.md](references/filesystem-process-environment-and-docker.md).

Use it for file access, external commands/services, properties, OS behavior, Docker, and remote Docker.

### Source sets, generated artifacts, Javadoc, and testing

Read [source-sets-javadoc-and-testing.md](references/source-sets-javadoc-and-testing.md).

Use it for custom source sets, generated code/resources, annotation processing, Javadoc classpaths, tests, opt-in execution, and reports.

### Fitness, baselines, publishing, metadata, and starter dependencies

Read [fitness-publishing-and-dependency-hygiene.md](references/fitness-publishing-and-dependency-hygiene.md).

Use it for architecture tasks, baselines, publications, compatibility, POM/module metadata, dependency analysis, starter hygiene, and benchmarks.

### Validation, CI, performance, execution, and security

Read [validation-ci-performance-and-security.md](references/validation-ci-performance-and-security.md).

Use it for Collector/config validation, static scans, task naming, errors, CI parity, daemons, parallelism, isolated projects, and security.

### Breaking changes, anti-patterns, verification, and reporting

Read [breaking-changes-antipatterns-and-reporting.md](references/breaking-changes-antipatterns-and-reporting.md).

Use it for pre-production migrations, prohibited patterns, required commands, E2E, configuration-cache claims, and final Gradle reports.

## Required reference combinations

For every material build change, read:

1. `foundations-and-multi-project-architecture.md`
2. `breaking-changes-antipatterns-and-reporting.md`
3. every domain reference touched by the change

For dependency or publication changes, also read:

1. `dependencies-platforms-and-repositories.md`
2. `fitness-publishing-and-dependency-hygiene.md`
3. `source-sets-javadoc-and-testing.md`

For task or CI changes, also read:

1. `java-configuration-and-caches.md`
2. `filesystem-process-environment-and-docker.md`
3. `validation-ci-performance-and-security.md`

## Completion standard

Do not report completion until:

- module and artifact ownership are explicit
- compile/runtime/test classpaths are correct
- all affected source sets compile
- optional dependencies remain isolated
- task inputs, outputs, side effects, and caching are modeled
- publication metadata is inspected
- architecture and dependency gates pass
- required tests actually execute
- skipped/cached/up-to-date results are classified
- portability and environment assumptions are explicit
- configuration-cache/reproducibility claims are tested
- migration, CI impact, rollback, and residual risks are reported

