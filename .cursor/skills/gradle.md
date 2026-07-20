# Gradle Standards for the Platform Tracing Solution

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

## Dependency Scopes

Use Gradle scopes intentionally.

### `api`

Use only when a dependency's types are intentionally exposed in supported public signatures or required by consumers to compile against the published API.

`api` is an architectural decision, not a convenience fix.

### `implementation`

Use for internal implementation dependencies that must not leak onto consumer compile classpaths.

This is the default for runtime implementation details.

### `compileOnly`

Use only for a genuine provided-runtime contract.

Document who provides the runtime dependency:

- OTel agent
- Spring Boot starter
- consuming application
- JDK/runtime
- another platform artifact

A `compileOnly` dependency used by executable main code is still a runtime requirement. Do not describe the module as runtime-independent if consumers must provide the artifact.

### `runtimeOnly`

Use when code does not compile against the dependency but the runtime requires it.

### `annotationProcessor`

Use only for annotation processors.

Do not confuse annotation availability with processors.

### Test scopes

Use the narrowest test scope:

- `testImplementation`
- `testRuntimeOnly`
- test fixtures configurations if already adopted
- custom source-set configurations for agent/e2e fixtures

Do not leak test libraries into production configurations.

## Public API Dependency Governance

For `platform-tracing-api`, every non-JDK dependency requires review.

Verify:

- whether types appear in public signatures
- whether the artifact is compile-only, transitive API, or internal implementation
- runtime provider ownership
- classloader implications
- Javadoc classpath
- license
- version management
- consumer impact

Do not add a broad runtime merely to use one annotation or utility.

Example:

```gradle
compileOnly "com.fasterxml.jackson.core:jackson-annotations"
```

may be appropriate when Javadoc/class metadata needs annotation types and Jackson is not a runtime/public contract.

Do not add `jackson-databind` when only annotations are required.

## Dependency Versions

Use the repository's existing version-management model:

- Spring Boot dependency management/BOM
- explicit BOM/platform
- version catalog
- central dependency constants
- approved root/convention policy

Do not hard-code a version in a feature module when the repository already manages it.

Forbidden:

```gradle
implementation "org.example:library:+"
implementation "org.example:library:latest.release"
```

Avoid duplicate version ownership.

When adding a dependency, record:

```text
Artifact:
Version owner:
Gradle scope:
Public API exposure:
Runtime provider:
Transitive impact:
License/governance:
Reason:
```

## BOM and Platform Usage

Use BOMs/platforms to align coherent dependency families.

Do not import multiple competing BOMs without verifying precedence.

Spring Boot-managed versions should not be overridden locally without a documented incompatibility or security reason.

When overriding:

- explain why
- verify dependency insight
- test the affected runtime
- document removal criteria

Use `enforcedPlatform` only when strict enforcement is intentionally required. It can constrain consumers and should not be applied casually in published libraries.

## Repositories

Repositories should be centrally governed.

Prefer repository configuration in:

- settings dependency resolution management
- approved convention plugin
- enterprise init/configuration

Feature modules should not add:

```gradle
repositories {
    mavenCentral()
}
```

without a specific approved need.

Do not add:

- arbitrary HTTP repositories
- unauthenticated internal repositories
- JitPack for production dependencies without governance
- repositories that shadow approved coordinates
- dynamic repository selection based on developer machine

Credentials must use approved Gradle/CI secret mechanisms.

## Dependency Verification and Locking

Use repository-approved supply-chain controls where configured:

- dependency verification metadata
- checksums/signatures
- dependency locking
- repository content filters
- vulnerability scanning
- SBOM generation

Do not bypass dependency verification to make a generated change pass.

A new repository or artifact that cannot be verified requires review.

## Plugins

Plugin versions and repositories belong in plugin management or the approved build-logic mechanism.

Do not hard-code plugin versions inconsistently across modules.

Do not apply unrelated plugins from a convention plugin.

Apply plugins only when the module uses their lifecycle or artifact model.

Examples:

- `java-library` for published libraries with API/implementation separation
- Spring Boot plugin only where Boot packaging/task behavior is needed
- `maven-publish` only for published artifacts
- JMH plugin only for benchmark modules
- test fixtures plugin only where fixtures are shared intentionally

Do not apply the Spring Boot executable-jar model to ordinary library modules.

## Java Toolchain and Compilation

Use the repository's configured Java toolchain.

Do not rely only on the developer's `JAVA_HOME`.

Compiler settings should be centralized where practical.

Warnings must be classified rather than globally suppressed.

Do not add broad:

```gradle
options.compilerArgs += "-Xlint:none"
```

or equivalent suppression to hide source defects.

When a warning is generated by a missing annotation class on compile/Javadoc classpath, fix the dependency contract rather than disabling warnings.

Encoding must be deterministic, typically UTF-8.

BOM/encoding defects must be detected and corrected; do not normalize source content unintentionally.

## Configuration Avoidance

Prefer lazy APIs:

```gradle
tasks.register("taskName") {
    ...
}

tasks.named("test") {
    ...
}

layout.buildDirectory.dir("...")
providers.gradleProperty("...")
providers.environmentVariable("...")
```

Avoid new uses of:

```gradle
tasks.create(...)
task someTask { ... } // when it realizes/configures eagerly in the current context
afterEvaluate { ... }
evaluationDependsOn(...)
```

Use `configureEach` rather than eager collection-wide configuration when appropriate.

Do not call `.get()` on providers during configuration unless the value is truly required then.

Do not realize every subproject/task to configure one module.

## `allprojects` and `subprojects`

Do not introduce new broad `allprojects {}` or `subprojects {}` mutation as a convenience.

If existing root build logic uses them, do not mechanically rewrite the entire build during an unrelated task.

For new shared behavior, prefer:

- convention plugins
- plugin-with-id configuration
- explicit project groups
- narrow task registration

A migration away from broad cross-project mutation requires a dedicated plan and measurable benefit.

## Configuration Cache

Configuration-cache compatibility is a goal for build logic where practical.

Do not claim support without running the relevant verification.

Avoid:

- accessing `Project` from task actions
- capturing non-serializable project state
- reading environment/files eagerly in configuration
- mutating tasks after execution starts
- custom tasks without declared inputs/outputs
- using static mutable state

Use:

- `Provider`
- `Property`
- `RegularFileProperty`
- `DirectoryProperty`
- `ListProperty`
- `MapProperty`
- `ValueSource`
- `BuildService` for approved shared build-time services

Not every third-party plugin is configuration-cache compatible. Mark external blockers accurately instead of adding unsafe workarounds.

## Build Cache

A cacheable task must declare all relevant inputs and outputs.

Do not mark a task cacheable if it:

- calls external mutable services
- reads undeclared environment state
- depends on Docker daemon state
- embeds timestamps
- reads Git state without declaring it
- writes outside declared outputs
- produces non-deterministic output

Use `@CacheableTask` only with a justified deterministic contract.

Docker/Testcontainers tasks are generally not remote build-cache candidates.

## Custom Tasks

Prefer typed task classes for non-trivial behavior.

A custom task should define:

- inputs
- outputs
- optional inputs
- path sensitivity
- normalization
- reason for cacheability/non-cacheability
- useful failure messages

Avoid large `doLast` closures containing hidden filesystem/network logic.

Do not call shell tools when a Gradle/JDK API is sufficient.

If shell execution is required:

- make it execution-time, not configuration-time
- declare inputs
- make platform assumptions explicit
- capture exit code/output
- provide actionable failure

## Filesystem Access

Avoid broad recursive filesystem scans during configuration.

Use:

- `fileTree` as declared task input
- source sets
- Gradle file collections
- artifact views
- providers
- task outputs

A scan used as a quality gate should run in a task with explicit scope and deterministic result.

Examples:

- forbidden API scan
- BOM scan
- wildcard import scan
- legacy package scan
- generated metadata consistency

Do not make normal IDE import/sync perform expensive repository-wide scans unnecessarily.

## Process and External Service Access

Do not access:

- Docker
- GitHub
- remote HTTP services
- databases
- collectors
- cloud APIs

during Gradle configuration.

External access belongs in task execution or tests.

Use explicit opt-in properties for environment-dependent tasks.

Failure/skip semantics must be visible.

## Environment Variables and Properties

Read environment and Gradle properties lazily through providers when possible.

Do not hard-code:

- developer paths
- Windows drive letters
- Docker host
- CI vendor paths
- credentials
- local Maven repositories

Project-specific E2E may use:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
```

but build logic must read it from the environment and tests must resolve service endpoints through Testcontainers.

Do not bake this address into published or ordinary production code.

## OS Portability

Build logic must account for supported developer/CI platforms.

Avoid assuming:

- Bash exists on Windows
- PowerShell exists in Linux CI
- `/tmp` semantics
- drive-letter paths on remote Linux Docker
- executable bit behavior without Git/tooling support
- platform-specific path separators in generated config

Prefer JDK/Gradle APIs.

When separate scripts are necessary, provide the supported variants or constrain them to CI.

## Docker and Remote Docker

Docker-backed validation must run during task execution.

Known development environment:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
```

The daemon runs on Gentoo Linux.

Rules:

- do not mount Windows host paths into the remote Linux daemon
- prefer `withCopyToContainer` / classpath resources
- do not treat a Docker warning as harmless if a required test did not execute
- distinguish `SKIPPED` from `PASS`
- verify XML test results for mandatory opt-in E2E
- avoid shelling out to Docker from normal configuration

A task that validates collector configuration against Docker must have explicit environment behavior and should not print alarming non-fatal errors as if they were success-neutral without explanation.

## Source Sets

Custom source sets are allowed for genuine isolation, such as:

- Java agent smoke child applications
- JMX wire extensions
- custom ServiceLoader provider jars
- classloader probes
- performance fixtures

Every custom source set must define:

- purpose
- compile/runtime classpath
- producing artifact/task
- consumer task
- lifecycle integration
- whether it is published
- verification

Do not create custom source sets merely to avoid normal module ownership.

Ensure stale source-set consumers are migrated when APIs change.

## Generated Sources and Resources

Generated files must have one owner.

Rules:

- write under `build/`, not source directories, unless the artifact is intentionally committed
- declare generating task
- wire task output to source set/resource processing lazily
- make generation deterministic
- do not regenerate committed docs silently
- do not mix generated and handwritten files without a clear convention

For ServiceLoader descriptors:

- verify exact descriptor path
- verify provider FQN
- remove obsolete descriptors
- test generated `@AutoService` output when used
- inspect built artifacts, not only source tree

## Annotation Processing

Configure annotation processors intentionally.

Separate:

- compile-time annotation artifact
- annotation processor artifact
- test annotation processor

Do not add processors to runtime classpath.

For Lombok or generated accessors, remember:

- Javadoc may not resolve generated methods as source links
- public API should not depend on undocumented generated shape
- removing Lombok from sensitive public protocol packages may be justified
- generated code must not hide public surface changes

## Javadoc

Published API modules should generate Javadoc and Javadoc JARs when applicable.

Javadoc tasks must:

- use the correct classpath
- resolve annotation types used by sources or referenced class metadata
- fail or report actionable warnings according to repository policy
- not link API docs to unavailable core implementation types
- use deterministic encoding

Do not globally suppress doclint or warnings to hide defects.

Example root cause/fix:

```text
warning:
unknown enum constant JsonInclude.Include.NON_EMPTY

correct fix:
add the narrow `jackson-annotations` artifact to the affected compile/Javadoc classpath
when that accurately represents the metadata contract
```

Do not add `jackson-databind` for an annotation-only need.

## Testing

Gradle must expose clear test tasks for module and integration scopes.

Test configuration should define:

- JUnit Platform
- parallelism policy
- logging/reporting
- system properties
- opt-in behavior
- max heap/forks only when justified
- test result locations

Do not make test success depend on execution order.

Do not hide failing tests through broad filters or `ignoreFailures`.

## Opt-In Tests

Opt-in tests may be appropriate for Docker-backed E2E or expensive performance verification.

Rules:

- opt-in property is documented
- normal build skip is visible
- explicit opt-in must execute or fail, not silently skip
- reports distinguish executed/skipped
- CI has a required job for production-critical opt-in tests
- final evidence includes test count

Example:

```powershell
.\gradlew.bat :platform-tracing-e2e-tests:test `
  -PrunE2e `
  --rerun-tasks `
  --no-build-cache `
  --no-daemon
```

`BUILD SUCCESSFUL` with the test task `SKIPPED` is not E2E evidence.

## Test Reports

For mandatory E2E evidence, verify:

```text
tests > 0
skipped = 0
failures = 0
errors = 0
```

Do not rely only on console summary.

Archive useful XML/HTML reports in CI.

Do not publish sensitive environment data or credentials in reports.

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

## Collector Configuration Validation

Collector config validation should:

- use pinned collector image/version
- avoid invalid host path mounts under remote Docker
- copy config into container when needed
- return clear task status
- distinguish environment warning from validation failure
- be reproducible in CI

Do not swallow a real validation error and return success.

Do not emit repeated alarming stderr for a known non-fatal path without improving the task.

## Static Scans

Static scans should be narrow and intentional.

Potential scans:

- removed API symbols
- legacy package imports
- wildcard imports
- BOM
- forbidden dependencies
- stale Javadoc links
- hard-coded credentials
- trust-all TLS
- deprecated bridges
- generated descriptor paths

Avoid fragile regexes that match unrelated semantic-convention keys or test method names.

When a false positive is known:

- narrow the scope/pattern
- exclude exact allowed context
- avoid renaming unrelated domain vocabulary solely to satisfy a poor regex unless the rename also improves clarity

A scan should encode architecture, not incidental text.

## Task Naming

Task names should describe action and scope.

Prefer:

```text
verifyArchitecture
verifyModuleTaxonomy
validateCollectorConfigs
generatePublicApiReport
verifyStarterDependencies
```

Avoid:

```text
checkStuff
doValidation
tempTask
fixAll
```

Task group and description should be set for discoverability.

Do not rename established CI tasks casually; before production, a direct rename is allowed when the new taxonomy is materially better, but update CI/docs in the same change.

## Failure Messages

Build failures must be actionable.

Include:

- task/capability
- offending file/module/dependency
- expected invariant
- suggested next action
- whether the failure is environment or code

Avoid:

- swallowed exceptions
- stack traces without context
- success after required verification was skipped
- error-level stderr for expected optional behavior without explanation

## CI

CI should run:

- compile/tests
- Javadoc for published APIs
- architecture fitness
- dependency/starter smoke
- publication verification where relevant
- mandatory opt-in E2E
- security/dependency scans
- docs/golden consistency where relevant

A required CI job must fail when:

- tests were requested but skipped
- no tests executed
- artifact/report missing
- publication metadata invalid
- architecture task failed
- Docker unavailable for a mandatory Docker gate

Do not detect CI vendor inside feature modules.

CI-specific orchestration belongs in CI configuration or approved build logic.

## Local and CI Parity

Local commands and CI should use the same Gradle tasks.

Avoid CI-only shell logic that reimplements Gradle verification.

Document required local environment variables.

Use `--no-daemon`, `--rerun-tasks`, or `--no-build-cache` only when the verification purpose requires them. Do not make all development tasks maximally expensive.

## Gradle Daemon

The daemon is appropriate for normal local development.

For reproducibility/audit commands, `--no-daemon` may be used.

Do not treat `--no-daemon` as a universal correctness requirement.

## Parallel Execution

Tasks and tests should be safe for parallel execution where enabled.

Do not introduce:

- shared mutable static build state
- fixed ports
- common output directories
- common temp files
- task outputs outside project/build directory
- cross-project task mutation

If a task is not parallel-safe, declare/document the constraint.

## Configuration-on-Demand and Isolated Projects

Do not claim compatibility with configuration-on-demand or isolated projects unless verified.

Avoid patterns that make future support impossible:

- cross-project model access
- mutation of another project's tasks/configurations
- eager traversal of all projects

Use explicit project dependencies and convention plugins.

## Security

Never:

- hard-code credentials
- log secrets
- print full environment
- embed tokens in generated metadata
- add untrusted repositories
- disable TLS verification
- bypass dependency verification
- publish secret-bearing resources

Use approved credentials/providers/CI secrets.

Build scans and logs must not expose sensitive system properties.

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
