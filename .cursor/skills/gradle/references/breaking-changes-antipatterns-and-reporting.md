# Breaking Changes, Anti-Patterns, and Reporting

## Imports and Generated Build Changes

For Cursor, Codex, and Perplexity-generated Gradle changes:

- read project context and Gradle skill first
- inspect wrapper, settings, root build, and affected module
- preserve existing DSL
- do not migrate DSL or build-logic model incidentally
- avoid import-only churn in Java sources
- use explicit Java imports
- do not add wildcard imports
- do not hard-code versions already managed by a BOM/catalog
- run dependency reports when scope changes
- run the narrowest affected task first
- distinguish verified execution from assumptions
- do not claim configuration-cache support without testing
- do not add broad suppressions for warnings

Generated changes must not introduce hidden build magic.

## Pre-Production Breaking-Change Policy

Breaking Gradle changes are justified when they:

- fix module ownership
- correct publication metadata
- remove obsolete project paths
- remove dead compatibility tasks
- eliminate dependency leakage
- replace duplicated build logic
- make skipped tests visible
- fix invalid runtime/Javadoc classpaths
- remove unsupported artifacts
- improve deterministic verification
- clean unsafe environment assumptions

Default migration:

1. change the intended build contract
2. update all in-repository callers/CI/docs
3. delete old task/config/module
4. add verification
5. run full build
6. do not add aliases by default

Do not make random build churn. A breaking build change must materially improve the final system.

## Anti-Patterns

Forbidden:

- dynamic dependency versions
- arbitrary repositories in feature modules
- dependency scope changes only to make compilation pass
- solving cycles with `api`
- broad warning suppression
- `ignoreFailures` on required tests
- required E2E silently skipped
- shell/network/Docker calls during configuration
- hidden task dependencies
- cross-project mutation
- mutable static build state
- task output outside declared locations
- non-deterministic generated files
- absolute developer paths
- hard-coded remote Docker endpoint in source/build defaults
- Windows host bind mount to remote Linux Docker
- Java native serialization in generated build artifacts
- obsolete ServiceLoader descriptors in published JARs
- test dependencies leaked into publication
- executable Boot JAR configuration applied to ordinary libraries
- `latest` Docker image tags for validation
- committed unrelated generated baselines
- regex quality gates dominated by false positives
- compatibility aliases for obsolete pre-production tasks
- weakening architecture gates
- claiming green when requested tasks did not execute

## Required Verification

Choose the applicable gates.

### Build logic or dependency change

```powershell
.\gradlew.bat :<affected-module>:compileJava --no-daemon
.\gradlew.bat :<affected-module>:compileTestJava --no-daemon
.\gradlew.bat :<affected-module>:test --no-daemon
.\gradlew.bat :<affected-module>:javadoc --warning-mode all --no-daemon
```

### Architecture and full build

```powershell
.\gradlew.bat pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify --no-daemon
.\gradlew.bat build --warning-mode all --no-daemon
```

### Dependency change

```powershell
.\gradlew.bat :<affected-module>:dependencies --configuration compileClasspath --no-daemon
.\gradlew.bat :<affected-module>:dependencies --configuration runtimeClasspath --no-daemon
.\gradlew.bat :<affected-module>:dependencyInsight `
  --dependency <artifact> `
  --configuration <configuration> `
  --no-daemon
```

### Configuration-cache claim

Use the repository-supported configuration-cache verification. At minimum, for a representative supported task:

```powershell
.\gradlew.bat <task> --configuration-cache --no-daemon
.\gradlew.bat <task> --configuration-cache --no-daemon
```

The second run should reuse the entry, and no unsupported access should be hidden.

### E2E

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
.\gradlew.bat :platform-tracing-e2e-tests:test `
  -PrunE2e `
  --rerun-tasks `
  --no-build-cache `
  --no-daemon
```

Verify test reports:

```text
tests > 0
skipped = 0
failures = 0
errors = 0
```

## Required Gradle Change Report

A final report for a non-trivial Gradle change must include:

```text
Problem:
Affected modules:
Build contract changed:
Tasks/configurations/modules added:
Tasks/configurations/modules removed:
Dependency scope changes:
Version owner:
Public/POM impact:
Configuration-cache evidence:
Build-cache impact:
Local/CI environment assumptions:
Tests/tasks executed:
Skipped tasks:
Publication verification:
Architecture fitness:
Residual risks:
Git/CI updates:
```

Use:

- `PASS` when required tasks executed and passed
- `PASS_WITH_WARNINGS` when build is green and only documented non-blocking environment/tooling risk remains
- `INSUFFICIENT_EVIDENCE` when a claimed cache, publication, CI, or E2E property was not executed
- `FAIL` when required tasks, artifact correctness, dependency governance, or architecture gates fail

