# Spring Boot Platform Standards

## Context

This repository contains an enterprise platform tracing solution delivered through Spring Boot starters, auto-configuration modules, servlet/reactive adapters, and runtime integrations.

The tracing solution is currently **pre-production**.

Breaking changes are allowed when they materially improve architecture, runtime safety, maintainability, operational clarity, dependency hygiene, or production readiness.

Do not preserve accidental bean names, property names, package locations, aliases, or starter behavior only for backward compatibility unless an ADR explicitly requires it.

Architects will not accept cosmetic refactoring. Every substantial Spring change must:

- reduce production risk
- clarify ownership between modules
- simplify adoption for service teams
- improve failure diagnostics
- strengthen tests or architecture fitness rules
- remove accidental coupling or hidden behavior

## Primary Goals

Spring Boot integration must provide:

- predictable behavior across many Spring Boot services
- minimal and intentional startup work
- safe defaults
- explicit enablement for risky capabilities
- clear disabled/no-op behavior
- deterministic bean registration
- strong optional-classpath isolation
- actionable startup diagnostics
- no leakage of Spring concerns into API or core modules
- equivalent behavior for servlet and reactive stacks where semantics are shared
- a straightforward adoption path for internal platform teams

## Module Ownership

### `platform-tracing-api`

Owns:

- public tracing contracts
- public value objects
- annotations
- narrow extension contracts
- classloader-neutral protocol types

Must not depend on:

- Spring Framework
- Spring Boot
- Actuator
- JMX/OpenMBean
- OpenTelemetry SDK implementation
- application runtime infrastructure

### `platform-tracing-core`

Owns:

- tracing runtime behavior
- domain validation
- sampling and scrubbing policies
- lifecycle and state
- no-op behavior
- control handlers
- implementation of public API contracts

Must not contain:

- Spring annotations
- `ApplicationContext` access
- conditional bean logic
- `@ConfigurationProperties`
- servlet or WebFlux types

### `platform-tracing-spring-boot-autoconfigure`

Owns:

- Spring Boot wiring
- `@ConfigurationProperties`
- conditional bean registration
- startup diagnostics
- Actuator integration
- translation from properties into core/domain configuration
- reconciliation between desired startup configuration and live runtime state

Must not own:

- tracing domain logic
- wire decoding rules
- sampling algorithms
- scrubbing implementation
- JMX transport logic
- public API models that should live in `platform-tracing-api`

### Web adapter modules

`platform-tracing-autoconfigure-webmvc` owns servlet-specific integration.

`platform-tracing-autoconfigure-webflux` owns reactive/WebFlux-specific integration.

Do not introduce cross-dependencies between servlet and reactive adapters.

Shared behavior belongs in API/core/autoconfigure only when it is genuinely framework-neutral.

### `platform-tracing-otel-extension`

Owns:

- OpenTelemetry agent/SDK integration
- JMX/OpenMBean adapters
- classloader-sensitive bridges
- exporter/sampler/processor-specific integration

Spring Boot auto-configuration must not absorb agent/JMX implementation details.

## Starter Design

A starter should primarily aggregate dependencies and activate auto-configuration.

Starters may provide:

- dependency alignment
- the correct auto-configuration module
- platform-approved defaults through configuration metadata/documentation
- servlet or reactive integration selection

Starters must not:

- contain business or runtime logic
- define duplicate auto-configuration
- expose internal implementation classes
- force both servlet and reactive stacks
- pull large optional integrations unconditionally
- hide mutable global state
- perform network calls during startup

Keep starter modules thin enough that their dependency graph is easy to audit.

## Auto-Configuration Registration

Use current Spring Boot conventions:

- `@AutoConfiguration`
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- explicit ordering only when technically required
- `before`, `after`, `beforeName`, or `afterName` only with documented rationale

Do not use legacy `spring.factories` for new auto-configuration registration unless a supported compatibility requirement explicitly demands it.

Each auto-configuration class should have one clear responsibility.

Avoid a single large auto-configuration class that wires unrelated concerns such as:

- tracing facade
- web instrumentation
- JMX registration
- sampling
- scrubbing
- diagnostics

Split by capability when this improves conditional isolation and testability.

## Conditional Bean Rules

Do not apply `@ConditionalOnClass`, `@ConditionalOnMissingBean`, and `@ConditionalOnProperty` mechanically to every bean.

Choose conditions according to the actual contract.

Use `@ConditionalOnClass` when:

- an optional library or framework type is required
- the module must back off cleanly when that dependency is absent
- servlet and reactive classpaths must remain isolated

Use `@ConditionalOnMissingBean` when:

- user/platform override is intentionally supported
- there is one clear fallback implementation
- backing off does not violate required platform invariants

Do not use `@ConditionalOnMissingBean` to make mandatory safety components silently disappear.

Use `@ConditionalOnProperty` when:

- enablement is explicitly configurable
- the default is documented
- disabled behavior is tested
- property semantics are unambiguous

Never register unconditional infrastructure beans when the required runtime or classpath is absent.

Never rely on bean registration order as hidden control flow.

## Configuration Properties

Use:

- `@ConfigurationProperties`
- strongly typed fields
- immutable configuration where practical
- constructor binding or record-style models where supported by the project
- explicit defaults
- JavaDoc and generated configuration metadata
- validation for startup-owned configuration

Avoid:

- scattered `@Value`
- direct `Environment` reads in ordinary components
- stringly typed booleans/enums/numbers
- hidden fallback values in implementation code
- multiple property sources defining the same platform decision

Property classes describe **desired startup configuration**.

Runtime-applied state must not be inferred solely from property objects after JMX/control-plane mutation.

## Property Naming

Property names must:

- use one stable platform prefix
- reflect the domain capability
- distinguish read-only diagnostics from mutation enablement
- distinguish startup configuration from runtime state
- avoid ambiguous terms such as `enabled` without a clear owner

Example:

```yaml
platform:
  tracing:
    control:
      runtime-mutation:
        enabled: false
```

Pre-production property renames are allowed when they improve taxonomy.

Do not keep old aliases, deprecated keys, or dual binding paths unless an ADR explicitly approves them.

When renaming:

- update configuration metadata
- update samples
- update docs
- update Helm/environment mappings
- add a negative test proving the old property is no longer active when appropriate

## Defaults

Defaults are part of platform behavior and require tests.

Safe defaults include:

- tracing integration can initialize without external network calls
- risky runtime mutation is disabled
- read-only diagnostics remain available where safe
- absent optional dependencies do not fail unrelated application startup
- invalid critical configuration fails clearly
- disabled capabilities do not create unnecessary beans

Do not hide unsafe behavior behind a permissive default.

A default that changes sampling, export, scrubbing, or runtime mutation must be justified in an ADR or warning register.

## Bean Design

Beans should be singleton unless lifecycle or scope semantics require otherwise.

Prefer:

- immutable collaborators
- constructor injection
- explicit bean names only when names are part of a documented integration contract
- narrow interfaces at module boundaries
- final implementation classes where extension is not supported
- no-op implementations only when disabled behavior is an intentional product capability

Avoid:

- field injection
- static application context holders
- mutable singleton configuration
- service locator patterns
- hidden bean lookup from domain code
- optional dependencies injected through `Object` or reflection without a documented reason

`ObjectProvider` may be used for optional/lazy integration, but not to hide required dependencies or ambiguous bean graphs.

## Bean Override Policy

Decide explicitly whether a bean is:

- platform-mandatory
- user-replaceable
- internally replaceable for tests only

For user-replaceable beans:

- document the extension contract
- use `@ConditionalOnMissingBean`
- verify replacement with `ApplicationContextRunner`
- ensure replacement cannot bypass safety invariants

Do not expose internal runtime components as override points merely because Spring can replace them.

## Lazy and Eager Initialization

Avoid eager initialization of:

- exporters
- network clients
- JMX mutation paths
- expensive registries
- classpath scanners
- optional integrations

Eager initialization is acceptable when needed to fail fast on unsafe or invalid startup configuration.

Do not mark everything `@Lazy` globally. Lazy behavior must be deliberate and tested.

No startup bean may perform an unbounded wait or external network call.

## Runtime Control

Runtime control is a high-risk capability.

Rules:

- mutation must fail closed by default
- read operations may remain available when safe
- validation-only operations must not apply changes
- rejected mutations must not change last-known-good state, version, source, or snapshot
- runtime mutation policy belongs in core/OTel integration, not public API
- Spring properties define startup policy; live state must be read from runtime diagnostics
- Actuator and JMX must not implement conflicting authorization semantics

Any mutation-enabling property must:

- default to disabled
- be documented as operationally sensitive
- have startup diagnostics
- have negative tests
- be included in rollout guidance

## Actuator Integration

Actuator endpoints are operational APIs, not convenience controllers.

Actuator endpoints must:

- avoid exposing secrets or raw PII
- distinguish desired configuration from live applied state
- expose read-only state by default
- use explicit mutation guards for write operations
- return actionable, stable diagnostic fields
- not return internal implementation classes
- not depend on JMX implementation types

Do not publish an Actuator endpoint merely because a bean exists.

Every endpoint needs:

- a documented operator use case
- security assumptions
- tests
- cardinality and data-sensitivity review

## JMX Integration

JMX/OpenMBean code belongs outside Spring-facing public API.

Spring auto-configuration may register/configure JMX integration, but:

- JMX types must stay in the JMX/OTel extension module
- Map/OpenType conversion must happen at the adapter boundary
- decode and domain validation must occur before apply
- direct historical domain MBeans must be documented as separate risk surfaces
- startup policy and JVM/network/RBAC assumptions must be explicit

Do not use the Spring `ApplicationContext` as a service locator for JMX handlers.

## Observability of the Starter

The tracing starter itself must be diagnosable.

Where safe, expose:

- whether tracing is active
- selected runtime mode
- enabled instrumentation adapters
- current mutation policy
- last-applied runtime-control metadata
- active scrubbing rule names or safe fingerprints
- skipped/unknown configuration names
- exporter/runtime readiness
- warnings that require operator action

Do not expose:

- raw sensitive attribute values
- secrets
- authorization headers
- internal object dumps
- unbounded lists with high cardinality

Startup logs should be concise and actionable. Avoid logging the same warning repeatedly.

## Servlet and Reactive Separation

Servlet and WebFlux integrations must remain independently conditional.

Servlet configuration must not require:

- Reactor
- WebFlux
- reactive HTTP server classes

WebFlux configuration must not require:

- servlet APIs
- servlet filters
- servlet-specific request context

Shared tracing semantics should be tested across both stacks, including:

- inbound tracing
- outbound propagation
- MDC/context behavior
- error mapping
- no duplicate spans
- disabled behavior

Do not assume thread-local MDC propagation works automatically in reactive flows.

## Optional Classpath Behavior

Optional integrations must be tested with missing classes.

Use:

- `ApplicationContextRunner`
- `FilteredClassLoader`
- focused classpath smoke tests

Verify:

- auto-configuration backs off cleanly
- unrelated beans still start
- no `NoClassDefFoundError`
- no eager loading of optional types
- condition reports are understandable

Avoid referencing optional classes in unconditional method signatures or static initializers.

## Dependency Hygiene

Use Gradle dependency scopes intentionally.

Guidelines:

- `api` only for dependencies visible in public signatures
- `implementation` for internal runtime dependencies
- `compileOnly` for genuine provided-at-runtime contracts
- `annotationProcessor` only for processors
- avoid adding a broad dependency when a narrow artifact is enough

Examples:

- use `jackson-annotations`, not `jackson-databind`, when only annotation metadata is required
- do not expose Spring/JMX/OTel implementation types from API contracts
- starters should not make unrelated optional integrations transitive

Every new transitive dependency in a starter should be treated as an architecture decision.

## Direct Environment Access

Ordinary components must not call:

- `System.getenv`
- `System.getProperty`
- global Spring `Environment`
- static configuration holders

Use typed configuration properties or explicit runtime contracts.

Narrow bootstrap/integration code may read process-level settings only when the setting belongs to the underlying platform runtime and cannot be represented through normal Spring binding. Such exceptions require documentation and tests.

## Startup Side Effects

Forbidden during auto-configuration unless explicitly required and tested:

- network calls
- Docker access
- file writes
- mutation of global OpenTelemetry state
- JMX mutations
- background threads without lifecycle ownership
- modification of application system properties
- silent fallback after critical configuration failure

Registration side effects must be:

- idempotent
- reversible when practical
- covered by rollback tests
- tied to Spring lifecycle

## Failure Behavior

Choose failure semantics deliberately.

Fail startup when:

- a mandatory safety invariant is violated
- configuration cannot be interpreted safely
- mutually exclusive critical settings are enabled
- a required platform runtime is missing

Back off or degrade when:

- an optional integration is absent
- tracing is explicitly disabled
- a non-critical diagnostics feature cannot initialize

Never silently enable a risky capability after configuration failure.

Error messages must include:

- property or capability name
- invalid value category, without leaking secrets
- expected action
- whether startup is blocked or capability is disabled

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
