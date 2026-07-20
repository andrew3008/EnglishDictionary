# Architecture Standards for the Platform Tracing Solution

## Context

This repository contains an enterprise platform tracing solution for Spring Boot servlet and reactive microservices.

The solution includes:

- `platform-tracing-api`
- `platform-tracing-core`
- Spring Boot auto-configuration
- servlet and WebFlux adapters
- starters
- OpenTelemetry agent/SDK integration
- sampling and scrubbing policies
- runtime control
- JMX/OpenMBean adapters
- collector configuration
- architecture fitness rules
- test utilities, samples, benchmarks, and Docker-backed E2E tests

The tracing solution is currently **pre-production**.

Breaking source, binary, package, module, bean, configuration, SPI, wire, publication, and build changes are allowed when they materially improve:

- architectural integrity
- correctness
- runtime safety
- public API clarity
- dependency governance
- privacy and security
- operator diagnostics
- performance
- testability
- production readiness
- adoption across many internal services

Backward compatibility with the current pre-production implementation is **not** a primary architectural goal.

Do not preserve accidental APIs, obsolete packages, aliases, deprecated bridges, false extension points, dual implementations, unsafe defaults, or stale integration paths merely because they already exist.

Architects will not accept cosmetic refactoring. Every substantial architecture change must:

- solve a concrete architectural or production problem
- identify the correct owner
- reduce coupling or operational risk
- make invalid states harder to represent
- remove accidental public surface
- strengthen executable verification
- result in a simpler final mental model

## Role of This Skill

This is the highest-level architecture skill for the tracing repository.

For specialized decisions, also follow:

- `project-context.md`
- `backend.md`
- `platform-api.md`
- `spring.md`
- `security.md`
- `observability.md`
- `testing.md`
- `testcontainers.md`
- `gradle.md`
- `redis.md`
- `code-review.md`

This file defines:

- system boundaries
- module ownership
- dependency direction
- classloader boundaries
- public API governance
- runtime-control principles
- architectural decision and verification policy

Specialized skills define implementation details inside those boundaries.

A specialized skill must not override the module ownership and pre-production policy defined here.

## Architecture Priority

When concerns conflict, prefer in this order:

1. correctness
2. security and privacy
3. runtime-state safety
4. module and classloader boundaries
5. public API integrity
6. deterministic behavior
7. dependency and publication correctness
8. executable verification
9. operator diagnostics
10. bounded telemetry volume and resource use
11. reactive/concurrency correctness
12. startup and hot-path performance
13. maintainability
14. developer ergonomics
15. compatibility with pre-production behavior

Do not weaken an intended architecture boundary to avoid updating repository callers, tests, samples, fixtures, docs, properties, or build logic.

## Repository Facts Before Architecture

Architecture decisions must be based on the current repository, not on generic assumptions.

Before proposing a change:

1. inspect the current branch and working tree
2. inspect actual module dependencies
3. locate all production/test/custom-source-set consumers
4. inspect existing architecture tests
5. inspect ADRs, warning registers, and current docs
6. distinguish current code from historical analysis
7. verify Gradle dependency scopes
8. identify runtime and classloader boundaries
9. identify real external consumers
10. mark unverified assumptions explicitly

Use:

- `VERIFIED`
- `PARTIALLY_VERIFIED`
- `INSUFFICIENT_EVIDENCE`
- `FALSE_POSITIVE`
- `HISTORICAL_ONLY`

Do not invent:

- call sites
- public consumers
- runtime providers
- extension requirements
- configuration defaults
- supported deployment topologies
- green test results

## Architectural Mission

The architecture must let application teams use a small, safe tracing API without depending on:

- OpenTelemetry SDK internals
- Spring Boot wiring internals
- Java agent implementation classes
- JMX/OpenMBean types
- exporter/processors
- runtime mutation mechanisms
- classloader-specific bridges

The platform team must be able to change runtime implementation, sampling, scrubbing, integrations, and diagnostics without forcing service code to depend on internal mechanics.

The system must be understandable as:

```text
application code
    -> platform-tracing-api
    -> platform-tracing-core
    -> runtime/integration adapters
    -> OpenTelemetry / Collector / backend
```

Cross-cutting configuration and transport adapters must not collapse these boundaries.

## Core Architecture Principles

Use:

- explicit ownership
- dependency inversion
- narrow public contracts
- package-private implementation
- immutable value models
- deterministic runtime behavior
- classloader-neutral wire boundaries
- fail-closed privileged operations
- explicit failure/result models
- safe defaults
- architecture fitness functions
- executable evidence
- minimal magic

Avoid:

- hidden side effects
- implicit runtime coupling
- compatibility-first preservation of architectural debt
- speculative extension points
- duplicate sources of truth
- framework types in domain/public contracts
- global holders
- service locators
- giant shared utility modules
- one-interface/one-implementation ceremony without a boundary
- broad public helpers
- accidental transitive dependencies
- silent fallback behavior
- opaque background execution

## Architectural Decision Quality

A serious architecture decision must state:

```text
Problem:
Why the current design is insufficient:
Repository evidence:
Target architecture:
Owner module:
Public surface:
Dependency direction:
Runtime/classloader boundary:
Failure model:
Security/privacy impact:
Alternatives rejected:
Migration:
Verification:
Residual risks:
```

A change is not architectural merely because it:

- renames classes
- moves packages
- adds interfaces
- splits files
- creates a new module
- introduces a pattern
- copies an industry example

It is architectural when it changes ownership, contracts, safety, dependency direction, runtime behavior, or the system mental model.

## Architecture Decision Records

Create or update an ADR when a change affects:

- public API/SPI
- module ownership
- dependency contract
- classloader boundary
- runtime control
- wire protocol
- security default
- sampling/scrubbing policy ownership
- Spring property contract
- publication coordinates
- supported deployment topology

An ADR must include:

- status
- context
- decision
- consequences
- alternatives rejected
- compatibility policy
- verification
- follow-up decisions
- known residual risks

Do not create an ADR for trivial implementation detail.

Do not leave an accepted architecture decision only in an LLM transcript or temporary plan file.

## Module Architecture

### `platform-tracing-api`

Owns contracts.

It may contain:

- public tracing facades
- capability interfaces
- immutable value objects
- specifications and builders
- public annotations
- intentional SPIs
- classloader-neutral wire/control vocabulary
- public result and violation types

It must not own:

- runtime implementation
- Spring Boot wiring
- JMX/OpenMBean implementation
- OpenTelemetry SDK implementation
- exporters or span processors
- domain validation
- sampling algorithms
- scrubbing implementation
- mutable live runtime state
- infrastructure clients
- application context access

The API module must not depend on core.

### `platform-tracing-core`

Owns implementation and domain/runtime behavior.

It may contain:

- implementations of public contracts
- tracing lifecycle
- span specification conversion
- context interpretation
- sampling and scrubbing policies
- domain validation
- runtime-control handlers
- no-op behavior
- last-known-good state
- atomic apply/read logic
- safety invariants
- approved OTel API adaptation

It must not contain:

- Spring annotations
- `ApplicationContext` lookup
- `@ConfigurationProperties`
- servlet or WebFlux types
- Spring conditional logic
- JMX/OpenMBean transport implementation

### `platform-tracing-spring-boot-autoconfigure`

Owns Spring Boot integration.

It may contain:

- bean wiring
- typed startup properties
- conditional registration
- startup diagnostics
- Actuator integration
- desired-state mapping
- reconciliation between startup configuration and runtime state

It must not own:

- tracing domain algorithms
- wire schema/decoding
- sampling policy implementation
- scrubbing implementation
- JMX transport behavior
- application-facing API types

### Servlet adapter

Owns servlet-specific integration.

It must not depend on WebFlux/reactive adapter types.

### WebFlux adapter

Owns reactive-specific integration.

It must not depend on servlet adapter types.

Shared framework-neutral behavior belongs in API/core/autoconfigure only when it is genuinely neutral.

### `platform-tracing-otel-extension`

Owns:

- OpenTelemetry agent/SDK integration
- sampler/provider/processor/exporter glue
- JMX/OpenMBean adapters
- classloader-sensitive bridges
- runtime wiring to approved core contracts

It must not become an alternate domain core.

### Starters

Starters are thin dependency aggregators.

They may select the matching integration/autoconfiguration path.

They must not contain:

- domain logic
- duplicate auto-configuration
- runtime algorithms
- environment-specific behavior
- mutable global state

### Collector configuration

Owns collector-side telemetry pipeline configuration.

It must not become the owner of application/core domain policy merely because YAML is easy to change.

### Tests, samples, benchmarks, and E2E

These modules depend on production modules.

Production modules must not depend on them.

## Expected Dependency Direction

```text
platform-tracing-core
    -> platform-tracing-api

platform-tracing-spring-boot-autoconfigure
    -> platform-tracing-api
    -> platform-tracing-core

platform-tracing-autoconfigure-webmvc
    -> approved api/core/autoconfigure modules

platform-tracing-autoconfigure-webflux
    -> approved api/core/autoconfigure modules

platform-tracing-otel-extension
    -> approved api/core modules
    -> OTel integration artifacts

starters
    -> matching auto-configuration/integration modules

test/samples/bench/e2e
    -> production modules under test
```

Forbidden:

- `platform-tracing-api -> platform-tracing-core`
- `platform-tracing-core -> Spring Boot auto-configuration`
- servlet adapter -> WebFlux adapter
- WebFlux adapter -> servlet adapter
- production module -> test/sample/e2e module
- API -> JMX/OpenMBean implementation
- API -> Spring
- API -> OTel SDK implementation without explicit approved contract
- module cycles
- cycles hidden by changing `implementation` to `api`

Fix ownership rather than dependency scope symptoms.

## Criteria for a New Module

Create a module only for a real boundary:

- independently published contract
- distinct runtime or classloader
- optional dependency isolation
- servlet/reactive isolation
- agent/application separation
- independently testable adapter
- meaningful reduction of consumer dependencies
- separately owned capability

Before creating a module, document:

```text
Owner:
Consumers:
Published or internal:
Runtime/classloader:
Public contracts:
Dependencies exposed:
Dependencies hidden:
Why a package is insufficient:
Tests:
Publication:
```

Avoid over-splitting into tiny modules that only move package-level complexity into Gradle.

Do not create a separate module merely because a design diagram looks cleaner.

## Module Removal and Renaming

Because the solution is pre-production, a wrongly owned or obsolete module may be removed or renamed directly.

Required migration:

1. update settings
2. update project dependencies
3. update CI tasks/scripts
4. update publication metadata
5. update docs/samples
6. remove obsolete module
7. scan for old project path/artifact coordinates
8. run full verification

Do not keep an empty compatibility module or forwarding artifact by default.

## API and Core Separation

The API/core split is a production boundary, not a naming preference.

It protects:

- application teams from implementation coupling
- runtime implementation freedom
- classloader separation
- dependency hygiene
- Spring/agent integration isolation
- architecture fitness rules
- test separation between contracts and behavior

API is not limited to interfaces. It may include value objects, enums, annotations, specifications, and intentional SPIs.

Core is not merely an `impl` package. It owns runtime behavior and domain policy.

Do not merge API and core merely to reduce module count unless evidence proves the boundary has no independent consumers or architectural value.

## Public Surface Governance

Every public type must be classified:

1. application-facing API
2. external SPI
3. wire/control contract
4. public-for-compilation internal bridge
5. accidental public type to remove

The default decision for a new type is **not public**.

Before adding public surface, prove:

- concrete consumer
- stable domain role
- lifecycle
- failure model
- thread-safety
- dependency contract
- misuse resistance
- JavaDoc
- architecture guard

Do not add speculative:

- schema accessors
- registries
- query APIs
- extension points
- providers
- builders
- aliases

Public visibility does not automatically make a type supported. If a type is public only for cross-package compilation, mark it clearly and restrict access with ArchUnit.

## Root API

The root application API must remain small and capability-oriented.

Intended style:

```java
TraceOperations traceOperations;

traceOperations.traceContext();
traceOperations.spans().operation("payment.process");
traceOperations.spans().transport();
traceOperations.spans().fromSpec(spec);
```

Do not turn the root API into a god object.

Do not add:

- Spring beans/configuration operations
- JMX/runtime-control methods
- exporter controls
- sampler implementation controls
- mutable live state access
- arbitrary enrichment mechanisms
- low-level OTel SDK methods

Capability namespaces should reduce ambiguity without redundant naming.

## External Type Exposure

A third-party type in a public signature creates an architectural dependency contract.

Generally forbidden in application-facing API:

- Spring Framework/Boot
- JMX/OpenMBean
- OTel SDK implementation
- servlet/WebFlux implementation outside adapters
- Jackson databind
- Redis/Lettuce/Redisson
- infrastructure clients

An OpenTelemetry API type may be exposed only when:

- the boundary is deliberately OTel-native
- repository evidence justifies it
- the dependency contract is documented
- the Gradle scope reflects the runtime model
- an ADR approves the coupling
- a platform abstraction would add no real value

Do not create a platform-owned wrapper that merely clones a mature third-party type without adding policy or isolation.

## Dependency Governance

Dependency scope is architectural.

Use:

- `api` for intentionally exposed compile contracts
- `implementation` for internal runtime dependencies
- `compileOnly` for a genuine provided-runtime contract
- `runtimeOnly` for runtime-only dependencies

For every external dependency, review:

```text
Artifact:
Version owner:
Scope:
Public API exposure:
Runtime provider:
Transitive impact:
Classloader impact:
License/governance:
Security status:
Reason:
```

A `compileOnly` dependency used by executable main code still represents a runtime requirement. Document who provides it.

Do not add a broad artifact when a narrow one is sufficient.

## Package Taxonomy

Packages must express stable domain ownership.

Preferred examples:

- `api.span`
- `api.span.spec`
- `api.propagation`
- `api.control`
- `api.spi`
- `core.runtime`
- `core.sampling`
- `core.scrubbing`
- `core.control`

Avoid:

- `impl`
- `misc`
- `common`
- `util`
- `shared`
- `base`
- `internal` without an established governance convention

Flatten legacy one-file subpackages when they exist only to force implementation helpers to become public.

Do not introduce a new package convention inside a focused PR unless the convention itself is approved.

## Classloader Architecture

Application and Java agent code may run in different classloaders.

Across classloader boundaries:

- use JDK-only wire types
- use explicit schemas
- avoid implementation class casting
- reject Java enum instances where String wire values are required
- avoid static holders assuming a single classloader
- verify ServiceLoader visibility deliberately
- isolate JMX/OpenMBean types in the adapter
- keep agent classes out of application-facing API

Required boundary examples:

```text
application classloader
    <-> Map<String,Object> / JDK-safe values
agent classloader
```

Do not cross the boundary with Spring, OTel SDK implementation, custom DTOs loaded independently, or Java native serialization unless explicitly designed and tested.

## Control Protocol Architecture

The approved pipeline is:

```text
wire payload
    -> structural decode
    -> domain validation
    -> mutation policy
    -> apply/read
```

### API owns

- contract version
- operation vocabulary
- key vocabulary
- operation-specific request schemas
- required/allowed keys
- strict unknown-key rejection
- JDK-safe type normalization
- immutable decode result
- structural violation codes

### Core owns

- sampling bounds
- route-ratio bounds
- validation modes
- cross-field rules
- empty-mutation rejection
- runtime mutation policy
- state transition and LKG behavior

### Adapter owns

- JMX/OpenMBean conversion
- agent/runtime wiring
- external transport concerns

Required invariants:

- invalid decode has no usable apply payload
- domain-invalid request cannot apply
- mutation-rejected request cannot apply
- READ does not mutate
- VALIDATE does not apply
- rejected requests preserve snapshot/version/source/LKG
- public schema introspection stays removed
- internal programmatic schema validation remains

Do not reintroduce:

- public `schema()`
- public `validator()`
- `READ_SCHEMA`
- legacy validation packages
- dual decoder paths
- domain rules in API
- raw wire map apply

## Runtime Control Architecture

Runtime control is privileged operational behavior.

Architecture rules:

- mutation disabled by default
- explicit startup enablement
- read-only state available when safe
- validation-only operation does not apply
- machine-readable rejection status
- bounded audit metadata
- atomic state update
- last-known-good preservation
- historical unguarded domain MBeans tracked separately
- external JVM/network/RBAC assumptions documented

Do not claim that a code-level mutation gate replaces JMX authentication, network isolation, or RBAC.

Do not expose runtime mutation through the application-facing tracing API.

## Spring Architecture

Spring Boot auto-configuration is wiring, not domain/runtime ownership.

Use:

- `@AutoConfiguration`
- typed `@ConfigurationProperties`
- explicit defaults
- intentional conditions
- `ApplicationContextRunner`
- optional-classpath isolation
- startup diagnostics

Avoid:

- domain logic in auto-configuration
- direct environment access in ordinary components
- hidden bean lookup
- unconditional infrastructure beans
- startup network calls
- static application context
- servlet/reactive cross-dependencies
- user overrides that bypass mandatory safety invariants

Spring properties represent desired startup configuration.

Live runtime state must come from runtime diagnostics, not from property objects after mutation.

## OTel Architecture

Use OpenTelemetry for standard tracing semantics.

Prefer:

- OTel API for stable tracing concepts
- OTel SDK/agent integration in runtime modules
- W3C Trace Context
- semantic conventions
- official propagators
- official instrumentation where correct

Avoid:

- custom tracing formats
- custom W3C parsers when official implementation is available
- duplicate manual spans
- application coupling to SDK internals
- agent-only types in API
- platform wrappers that only rename OTel concepts

A custom abstraction must add:

- platform policy
- safer API ergonomics
- module/classloader isolation
- domain invariants

## Auto vs Manual Instrumentation

Auto-instrumentation should own standard framework/transport boundaries when available and correct.

Manual instrumentation requires a demonstrated semantic gap.

Before adding manual spans, prove:

- no duplicate auto span
- clear operational question
- stable low-cardinality name
- bounded/safe attributes
- correct relationship
- deterministic sampling/export behavior
- acceptable overhead

Do not add manual instrumentation merely because the API makes it easy.

## Observability Architecture

Every signal needs an owner and operational question.

Do not require every module to emit metrics, traces, logs, health, and diagnostics mechanically.

Use intentional observability.

Review:

- signal ownership
- cardinality budget
- PII exposure
- duplication with OTel/Micrometer/agent telemetry
- disabled/no-op behavior
- volume estimate
- operator use case

The tracing platform must not become a business audit or arbitrary payload telemetry system.

Follow `observability.md`.

## Security Architecture

Security-sensitive behavior must be secure by default.

Protect:

- propagation input
- request/correlation IDs
- force-sampling headers
- runtime control
- exporter endpoints
- scrubbing/PII
- JMX
- custom providers
- applied-state diagnostics

Do not use trace metadata for authentication or authorization.

Do not add custom authentication inside tracing modules.

Code-level defense in depth does not replace platform security controls.

Follow `security.md`.

## Sampling Architecture

Sampling is a runtime policy and cost/safety control.

Core owns:

- ratio bounds
- route precedence
- validation
- empty-mutation rejection
- state transition
- LKG

Wire decoder owns only structural normalization.

Force sampling must not bypass:

- scrubbing
- export kill switch
- mutation policy
- security controls

Sampling tests must be deterministic.

## Scrubbing Architecture

Scrubbing ownership and scope must be explicit.

A span-attribute scrubbing component does not automatically protect:

- events
- links
- baggage
- resources
- logs
- metrics

Do not claim broad PII protection without signal-specific controls.

External scrubbing SPI must not allow providers to bypass mandatory platform policies.

## State Architecture

Runtime state must be:

- immutable or safely published
- atomically updated
- versioned where needed
- readable without partial state
- protected from conflicting mutations
- recoverable through LKG where applicable

Test:

- rejected mutation
- concurrent read during apply
- repeated apply
- partial failure
- rollback
- idempotency

Do not represent live state only through Spring property objects.

## Concurrency Architecture

Do not assume singleton means thread-safe.

Clarify:

- ownership
- publication
- synchronization
- idempotency
- blocking behavior
- callback execution context
- lifecycle

Reactive paths must not depend on ordinary `ThreadLocal` behavior.

Do not block Reactor event-loop threads.

## Side-Effect Architecture

Side effects must be explicit and owned.

Review:

- static initialization
- bean construction
- JMX registration
- global OTel mutation
- background threads
- exporter creation
- file access
- Docker access
- network access
- system properties

Side effects must be:

- lifecycle-managed
- idempotent where needed
- reversible where practical
- observable
- tested

No network/Docker/file mutation during ordinary auto-configuration unless explicitly required.

## Failure Architecture

Choose one:

- fail closed
- fail startup
- degrade safely
- no-op intentionally

Do not silently fall back to permissive behavior.

Examples:

### Fail closed

- disabled mutation
- invalid control payload
- domain-invalid policy
- unsafe exporter endpoint mutation

### Fail startup

- mandatory safety invariant missing
- mutually exclusive critical configuration
- required security component absent

### Degrade safely

- optional diagnostics unavailable
- exporter/collector outage under approved policy
- tracing explicitly disabled

Failure result must identify the owner and leave runtime state consistent.

## No-Op Architecture

No-op tracing is an intentional capability.

No-op behavior must:

- preserve public API shape
- preserve lifecycle contracts
- avoid network calls
- avoid mutation
- avoid false export diagnostics
- remain low overhead
- remain independently tested

Do not use no-op behavior to bypass builder/domain validation if callers depend on those invariants.

## Distributed Systems Applicability

Do not introduce distributed infrastructure without proving need.

For Redis, Kafka, database coordination, leases, or external state, define:

```text
Problem:
Consistency model:
Owner:
State lifecycle:
Failure policy:
Idempotency:
Retry owner:
Recovery:
Security:
Observability:
Kubernetes behavior:
Test evidence:
```

Redis/KeyDB is not a default tracing dependency.

Follow `redis.md`.

## Kubernetes Architecture

The solution must tolerate:

- pod restart
- rolling deployment
- rescheduling
- transient network failure
- DNS changes
- optional telemetry backend outage

Avoid:

- fixed pod/node identity
- local filesystem dependency
- cluster-admin assumptions
- Docker socket mounts
- public JMX exposure
- service code managing deployment infrastructure

Readiness/liveness must reflect business-service semantics, not optional telemetry convenience.

## Build Architecture

Build logic must reflect module ownership.

Use the repository's existing Gradle wrapper, DSL, dependency management, and build-logic model.

Do not perform incidental:

- Groovy-to-Kotlin migration
- version-catalog introduction
- convention-plugin reorganization
- wrapper upgrade
- publication redesign

Dependency scope and publication metadata are architecture.

Architecture fitness tasks are release gates.

Follow `gradle.md`.

## Testing Architecture

Tests must exist at the correct layer.

### API tests

Protect:

- public surface
- value/result invariants
- removed FQNs
- dependency purity
- JavaDoc

### Core tests

Protect:

- runtime behavior
- domain validation
- state transition
- no-op
- concurrency
- policy safety

### Spring tests

Protect:

- conditions
- properties
- defaults
- optional classpaths
- diagnostics
- bean graph

### Adapter tests

Protect:

- JMX/OpenMBean conversion
- agent/SDK behavior
- servlet/WebFlux integration
- classloader boundaries

### E2E

Protect:

- real propagation
- export
- sampling
- scrubbing
- JMX wire path
- runtime mutation
- agent/application boundaries

A skipped E2E test is not runtime evidence.

Follow `testing.md` and `testcontainers.md`.

## Architecture Fitness Functions

Architecture decisions must be executable where practical.

Protect at least:

- API does not depend on core
- core does not depend on Spring
- webmvc/webflux isolation
- JMX/OpenMBean absent from API
- OTel SDK implementation absent from API
- exact public surface for sensitive packages
- internal helpers not public
- legacy packages/symbols absent
- no public schema/validator
- no raw wire apply
- no domain rules in wire decoder
- mutation disabled by default
- starters remain thin
- forbidden dependency directions
- wildcard imports absent
- required ServiceLoader descriptors exact
- obsolete descriptors absent

Do not weaken fitness rules to make generated code compile.

A new critical architecture decision should add a corresponding guard when technically feasible.

## Documentation Architecture

Architecture documentation must distinguish:

- current supported architecture
- historical architecture
- superseded plan
- future proposal
- residual risk
- external operational control

Current-looking docs must not use stale names or removed APIs.

Historical docs may retain old names only with a clear banner.

Important decisions must exist in ADRs and current architecture docs, not only in audit reports.

## Warning and Risk Register

A warning register must record:

- risk
- owner
- affected paths
- mitigation
- evidence
- residual risk
- closure trigger

Do not close a warning because one path is fixed when equivalent paths remain.

Do not use warning registers as permanent excuses for avoidable debt.

## Architecture Review and Audit

Choose the correct review mode:

- architecture review
- implementation review
- post-implementation audit
- post-fix audit
- security review
- performance review

Post-implementation audit must treat implementation summaries as claims.

Post-fix audit should verify known findings without reopening all accepted architecture decisions.

Follow `code-review.md`.

## Pre-Production Breaking-Change Policy

Breaking changes are preferred when they remove architecture debt.

Approved reasons:

- accidental public API
- misleading naming
- wrong module ownership
- third-party type leakage
- duplicate runtime/validation paths
- false SPI/ServiceLoader/holder
- unsafe parser
- public internal schema
- unsafe default
- stale configuration model
- duplicate instrumentation
- publication/dependency contract defect
- no-op semantic divergence

Default migration:

1. define the intended final architecture
2. update all repository consumers
3. update tests and custom source sets
4. update samples/bench/E2E
5. update docs/ADR/config metadata/CI
6. delete old path
7. add negative and architecture guards
8. run full verification
9. do not add aliases by default

Do not use pre-production status to justify random churn.

## Post-Production Compatibility Policy

After production adoption, compatibility becomes an explicit product concern.

A future policy must define:

- supported release/version window
- source compatibility
- binary compatibility
- semantic compatibility
- configuration compatibility
- wire compatibility
- deprecation period
- migration tooling
- release notes
- API diff tooling

Do not impose that future policy on current architecture cleanup without production consumers.

## Architecture and LLM Agents

Cursor, Codex, and Perplexity must:

- read relevant skills
- inspect real repository state
- distinguish facts from assumptions
- preserve accepted architecture
- avoid compatibility aliases by default
- avoid fake extension points
- avoid invented call sites
- avoid duplicate signals/logic
- add negative tests
- run narrow then broad verification
- report skipped tests honestly
- avoid import-only churn
- follow `.editorconfig`
- not commit/push unless explicitly requested

Any implementation prompt should include:

- exact branch/base
- authoritative plan/ADR
- approved decision
- do-not-touch list
- public surface constraints
- dependency constraints
- verification commands
- E2E execution requirement
- final report format

## Imports and Formatting

Use deterministic repository imports.

Rules:

- explicit imports only
- no wildcard imports
- static imports separated
- no import-only churn
- no unrelated formatting-only diff
- use `.editorconfig` as source of truth

Formatting is not an architecture improvement.

Do not let import churn hide semantic changes.

## Anti-Patterns

Forbidden:

- compatibility-first preservation of pre-production debt
- accidental public API
- public `Impl`/helper/manager types
- giant root facade
- false SPI
- ServiceLoader for one built-in implementation
- static holders/service locators
- duplicate sources of truth
- dual decoder/runtime paths
- public internal schema/validator
- raw wire payload apply
- mutation enabled by default
- domain validation in API wire decoder
- API -> core
- core -> Spring
- servlet/reactive cross-dependency
- JMX/OpenMBean in API
- OTel SDK implementation in API
- duplicate auto/manual spans
- tracing metadata used for authorization
- PII/high-cardinality telemetry without governance
- hidden side effects
- hidden retries/background threads
- mutable global state
- Java native serialization
- trust-all TLS
- dynamic dependencies
- speculative Redis/distributed infrastructure
- fixed topology assumptions
- required E2E silently skipped
- architecture rules weakened to pass generated code
- cosmetic refactoring presented as architecture
- unsupported confident claims

## Required Architecture Verification

Choose the applicable gates.

Typical:

```powershell
.\gradlew.bat :platform-tracing-api:test --no-daemon
.\gradlew.bat :platform-tracing-core:test --no-daemon
.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:test --no-daemon
.\gradlew.bat :platform-tracing-autoconfigure-webmvc:test --no-daemon
.\gradlew.bat :platform-tracing-autoconfigure-webflux:test --no-daemon
.\gradlew.bat :platform-tracing-otel-extension:test --no-daemon
.\gradlew.bat :platform-tracing-api:javadoc --warning-mode all --no-daemon
.\gradlew.bat pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify --no-daemon
.\gradlew.bat build --warning-mode all --no-daemon
```

For runtime/classloader/JMX/export boundaries:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
.\gradlew.bat :platform-tracing-e2e-tests:test `
  -PrunE2e `
  --rerun-tasks `
  --no-build-cache `
  --no-daemon
```

For required E2E evidence verify:

```text
tests > 0
skipped = 0
failures = 0
errors = 0
```

Run static scans appropriate to the change:

- removed/legacy symbols
- public surface
- forbidden dependencies
- compatibility aliases/deprecated bridges
- ServiceLoader descriptors
- wildcard imports
- BOM
- stale Javadoc
- hard-coded credentials/endpoints
- trust-all TLS
- Java native serialization
- false-positive-prone regex rules

## Required Architecture Change Report

A final report for a non-trivial architecture change must include:

```text
Decision:
Problem:
Why non-cosmetic:
Repository evidence:
Old architecture:
Target architecture:
Module ownership:
Public API/SPI diff:
Dependency direction:
Classloader/runtime boundary:
Default behavior:
Failure behavior:
State behavior on rejection:
Security/privacy:
Migration:
Tests executed:
E2E executed or skipped:
Architecture fitness:
Javadoc/build:
Docs/ADR:
Residual risks:
Merge readiness:
```

Use:

- `PASS` when required gates executed and passed
- `PASS_WITH_WARNINGS` when code is green and only documented non-blocking operational risk remains
- `INSUFFICIENT_EVIDENCE` when required runtime/consumer evidence is unavailable
- `FAIL` when correctness, architecture, security, state safety, dependency contract, or required gates fail
