# Testing, Build, Documentation, and Governance

## Testing Standards

Use `ApplicationContextRunner` for most auto-configuration tests.

Test:

- default context
- enabled context
- disabled context
- user override
- missing optional class
- invalid property
- mutually exclusive properties
- bean absence
- no eager initialization
- configuration metadata where relevant

Use `@SpringBootTest` only when:

- a complete application lifecycle matters
- actuator/web integration is under test
- context propagation requires a real runtime
- multiple auto-configurations must interact

Every risky runtime-control property must have:

- binding test
- default test
- enabled test
- rejection test
- diagnostics test

## Configuration Metadata

Public adoption properties must be present in generated Spring Boot configuration metadata.

Metadata should include:

- description
- default value
- type
- deprecation only if intentionally supported
- safe operational guidance where appropriate

For pre-production cleanup, remove obsolete metadata rather than keeping aliases.

Run metadata checks when adding or renaming properties.

## Native Image and AOT

Native image/AOT compatibility is desirable only where it does not distort the primary architecture.

Do not add reflection-heavy or dynamic class loading to ordinary starter paths.

When reflection or proxies are required:

- isolate them in integration modules
- add runtime hints
- test AOT/native behavior if the capability is declared supported

Do not claim native-image compatibility without an executable test or documented evidence.

## Gradle Configuration Cache

Build logic and tests should remain compatible with Gradle configuration cache where practical.

Spring runtime code should not be redesigned merely for Gradle configuration cache.

Do not capture runtime processes, open files, Docker clients, or non-serializable test state in Gradle task configuration.

## Documentation

Every starter capability should document:

- dependency/starter to add
- default behavior
- properties
- bean override policy
- disabled behavior
- operational risks
- diagnostics
- servlet/reactive applicability
- verification example

Documentation must distinguish:

- startup desired configuration
- live runtime state
- JMX/Actuator control
- application-facing tracing API

## Imports and Generated Code

For Cursor, Codex, and Perplexity-generated Spring changes:

- follow repository `.editorconfig`
- use explicit imports
- do not use wildcard imports
- do not create import-only churn
- inspect existing auto-configuration and properties first
- do not add duplicate properties or conditions
- run the narrowest affected compile/test task
- use `ApplicationContextRunner` instead of generating broad `@SpringBootTest` by default

## Architecture Fitness Rules

Protect at least:

- API has no Spring dependencies
- core has no Spring dependencies
- webmvc and webflux adapters do not depend on each other
- implementation classes do not become public API
- autoconfigure does not own domain validation
- JMX/OpenMBean types do not leak into API
- legacy properties/packages do not return
- starters remain thin
- unsafe runtime mutation is not enabled by default

Architecture rules must not be weakened merely to make generated code compile.

## Anti-Patterns

Forbidden:

- static `ApplicationContext` holders
- unconditional infrastructure beans
- broad component scanning in platform starters
- hidden startup side effects
- network calls during auto-configuration
- direct environment access in ordinary beans
- `@Value` for structured platform configuration
- duplicate property models
- bean override points without an extension contract
- one auto-configuration class owning unrelated capabilities
- servlet/reactive cross-dependencies
- compatibility shims added only to preserve pre-production behavior
- mutation enabled by default
- skipped integration tests reported as pass
- wildcard imports

## Required Verification

For a non-trivial Spring change, run the narrowest applicable set:

```powershell
.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:compileJava --no-daemon
.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:test --no-daemon
.\gradlew.bat :platform-tracing-autoconfigure-webmvc:test --no-daemon
.\gradlew.bat :platform-tracing-autoconfigure-webflux:test --no-daemon
.\gradlew.bat pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify --no-daemon
```

Run full build and opt-in E2E when behavior crosses module/runtime boundaries.

Final reports must include:

- properties added/changed
- default behavior
- beans created/removed
- conditional behavior
- tests executed
- skipped runtime tests
- architecture fitness result
- residual operational risks

Use:

- `PASS` when required gates executed and passed
- `PASS_WITH_WARNINGS` when code is green but non-blocking operational evidence remains
- `INSUFFICIENT_EVIDENCE` when runtime behavior was not executed
- `FAIL` when required startup, architecture, or integration gates fail

