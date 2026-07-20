# Architecture Fitness, Publishing, and Dependency Hygiene

## Architecture Fitness Tasks

Architecture tasks are release gates, not documentation conveniences.

Examples in this repository may include:

```text
pr1ModuleTaxonomyVerify
pr4ArchitectureFitnessVerify
pr0StarterDependencySmoke
```

Before invoking a task, verify it exists in the current checkout.

Tasks must fail on real violations.

Do not change a task to warn-only merely to pass a PR.

Architecture checks may cover:

- module direction
- public surface
- forbidden dependencies
- legacy packages/symbols
- starter dependency smoke
- JDK-only protocol package
- no raw apply path
- no wildcard imports
- no BOM

## Baselines

Committed baselines must have an explicit purpose.

Rules:

- deterministic content
- documented generator/task
- reviewable changes
- no absolute machine paths
- no timestamps unless semantically required
- no silent regeneration
- updated only when architecture intentionally changes

Generated baseline files should not remain as unrelated working-tree changes in a focused PR.

## Publishing

Published artifacts must be reproducible and intentional.

Verify:

- coordinates
- POM dependencies and scopes
- sources JAR
- Javadoc JAR where applicable
- Gradle module metadata
- artifact contents
- no test/internal classes
- no obsolete ServiceLoader descriptors
- no accidental implementation dependency leakage
- no duplicate artifacts
- signing if required by the target repository

Library modules should generally use `java-library` and `maven-publish` according to repository policy.

Starter POMs must expose only intended transitive dependencies.

## Publication Compatibility

Current pre-production default:

- artifact coordinates may be changed when ownership/taxonomy is wrong
- publications may be removed when obsolete
- no forwarding artifact by default
- no duplicate old/new coordinates
- update all repository consumers and docs directly

After production, coordinate and metadata compatibility require an explicit release policy.

Do not impose future compatibility constraints on current cleanup.

## POM and Module Metadata

Inspect generated POM/module metadata for API vs runtime scope correctness.

A build that compiles locally can still publish the wrong dependency contract.

Tests or verification should catch:

- `implementation` leaked as compile scope
- required `api` dependency missing
- compile-only dependency incorrectly published
- optional dependency made mandatory
- starter missing runtime dependency
- test fixture dependency published accidentally

## Dependency Analysis

Use Gradle reports when changing dependencies:

```powershell
.\gradlew.bat :<module>:dependencies --configuration compileClasspath
.\gradlew.bat :<module>:dependencies --configuration runtimeClasspath
.\gradlew.bat :<module>:dependencyInsight --dependency <name> --configuration <configuration>
```

Do not infer classpath solely from source imports.

Record version conflict and selection reasons when relevant.

## Dependency Hygiene for Starters

Starters should be thin aggregators.

Verify:

- servlet starter does not pull WebFlux unintentionally
- reactive starter does not pull servlet stack unintentionally
- optional infrastructure is not mandatory
- starter does not duplicate versions
- starter dependency smoke task remains green
- implementation modules are present at runtime but not overexposed at compile time

Do not package application logic in starter modules.

## Benchmarks and Performance Tasks

Benchmark tasks must be isolated from normal test/build lifecycle unless explicitly required.

JMH:

- use a dedicated module/source set/plugin
- pin configuration
- document forks/warmups/iterations
- archive results when used for decisions
- do not make ordinary `build` run long benchmarks
- do not treat one noisy run as a release gate

Macro/perf tasks must define environment, input, and pass criteria.

