# Backend Engineering Standards for the Platform Tracing Solution

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
- test utilities, samples, benchmarks, and Docker-backed E2E tests
- architecture fitness checks

The solution is currently **pre-production**.

Breaking source, binary, package, bean, configuration, wire, SPI, module, and build changes are allowed when they materially improve:

- correctness
- architecture
- runtime safety
- public API quality
- privacy and security
- dependency governance
- operator diagnostics
- performance
- testability
- production readiness
- adoption across many internal services

Backward compatibility with the current pre-production implementation is **not** a primary goal.

Do not preserve accidental APIs, ambiguous names, obsolete packages, deprecated bridges, aliases, dual execution paths, false extension points, unsafe defaults, or stale tests merely because they already exist.

Architects will not accept cosmetic refactoring. Every substantial backend change must solve a concrete architectural, correctness, operability, security, performance, or adoption problem.

## Skill Coordination

This file is the umbrella backend skill.

For specialized work, also follow the relevant repository skills:

- `project-context.md`
- `platform-api.md`
- `spring.md`
- `security.md`
- `observability.md`
- `testing.md`
- `testcontainers.md`
- `gradle.md`
- `redis.md`
- `code-review.md`

When rules overlap, prefer the more specialized skill for its domain, provided it does not violate the architecture and pre-production policy defined here.

Examples:

- API surface decisions → `platform-api.md`
- Spring Boot wiring/properties → `spring.md`
- telemetry semantics/cardinality → `observability.md`
- security/privacy/trust boundaries → `security.md`
- Gradle/dependency/publication changes → `gradle.md`
- Testcontainers/remote Docker → `testcontainers.md`
- Redis/KeyDB → `redis.md`
- audits and merge decisions → `code-review.md`

Do not copy a generic solution from one domain into another without checking ownership.

## Priority

When requirements conflict, prefer in this order:

1. correctness
2. security and privacy
3. runtime-state safety
4. module and classloader boundaries
5. public API integrity
6. deterministic behavior
7. dependency and publication correctness
8. executable verification
9. operator diagnostics
10. bounded resource usage and telemetry volume
11. reactive/concurrency correctness
12. startup and hot-path performance
13. maintainability
14. developer ergonomics
15. compatibility with pre-production behavior

Do not weaken a safety or architecture invariant to keep an old test, alias, bean name, property, SPI, or package compiling.

## Repository Facts First

Before changing code:

1. inspect the current branch and working tree
2. locate the real call sites
3. inspect module dependencies
4. inspect existing architecture tests
5. inspect specialized skills and ADRs
6. distinguish current code from historical docs
7. distinguish verified facts from assumptions

Useful commands:

```powershell
git status --short --branch
git log --oneline -10
git diff --stat
git diff --check
```

Do not invent:

- call sites
- public consumers
- runtime providers
- extension requirements
- configuration defaults
- supported topologies
- test results

Use `INSUFFICIENT_EVIDENCE` when a material fact cannot be verified.

## Architecture Ownership

### `platform-tracing-api`

Owns:

- public tracing contracts
- capability interfaces
- immutable value objects
- specifications and builders
- public annotations
- intentional SPI contracts
- classloader-neutral wire/control contracts
- public result and violation models

Must not own:

- runtime implementation
- Spring Boot wiring
- JMX/OpenMBean implementation
- OTel SDK implementation
- exporters/processors
- domain validation
- mutable live state
- infrastructure clients
- application context access

### `platform-tracing-core`

Owns:

- implementation of public contracts
- runtime behavior
- lifecycle
- context interpretation
- sampling and scrubbing policies
- domain validation
- runtime-control handlers
- last-known-good state
- no-op behavior
- safety invariants

Must not contain:

- Spring annotations
- `ApplicationContext` access
- `@ConfigurationProperties`
- servlet or WebFlux types
- JMX/OpenMBean types unless explicitly approved for an internal adapter boundary

### Spring Boot auto-configuration

Owns:

- bean wiring
- typed startup properties
- conditional registration
- startup diagnostics
- Actuator integration
- desired startup configuration
- mapping configuration into core contracts

Must not own:

- tracing algorithms
- wire decoding rules
- domain validation
- sampling logic
- scrubbing implementation
- JMX transport behavior

### Servlet and WebFlux adapters

Own framework-specific integration.

Rules:

- servlet adapter does not depend on WebFlux
- WebFlux adapter does not depend on servlet APIs
- shared framework-neutral behavior belongs in API/core/autoconfigure
- reactive behavior must not rely on ordinary thread-local semantics

### `platform-tracing-otel-extension`

Owns:

- OTel agent/SDK bridges
- sampler/processor/exporter integration
- JMX/OpenMBean adapters
- classloader-sensitive behavior
- runtime wiring to approved core contracts

### Starters

Starters are thin dependency aggregators.

They must not contain runtime logic, business policy, duplicate auto-configuration, or unrelated optional integrations.

### Collector configuration

Owns collector-side pipeline configuration:

- receivers
- processors
- batching
- queues
- retry/export
- collector-side filtering/governance

Do not move application/core domain policy into collector YAML merely for deployment convenience.

## Module Dependency Direction

Expected direction:

```text
platform-tracing-core -> platform-tracing-api

platform-tracing-spring-boot-autoconfigure
    -> platform-tracing-api
    -> platform-tracing-core

platform-tracing-autoconfigure-webmvc
    -> approved api/core/autoconfigure modules

platform-tracing-autoconfigure-webflux
    -> approved api/core/autoconfigure modules

platform-tracing-otel-extension
    -> approved api/core modules

starters
    -> matching auto-configuration/integration modules

tests/samples/bench/e2e
    -> production modules under test
```

Forbidden:

- `api -> core`
- `core -> Spring`
- servlet adapter -> WebFlux adapter
- WebFlux adapter -> servlet adapter
- production module -> test/sample/e2e module
- public API -> JMX/OpenMBean implementation
- public API -> OTel SDK implementation without explicit approval
- cycles “fixed” by changing `implementation` to `api`

Fix ownership instead of hiding a dependency cycle.

## Java Toolchain

Use the repository-configured Java toolchain.

If the repository currently targets Java 21, use Java 21 features deliberately.

Do not rely only on local `JAVA_HOME`.

Do not introduce a higher language level in one module without a repository-level decision.

## Java Design

Prefer:

- immutable objects
- records for immutable value/result models
- final fields
- constructor injection
- explicit factories
- package-private implementation helpers
- narrow interfaces at real boundaries
- sealed types only when they materially improve the model
- defensive copies
- unmodifiable collections
- explicit nullability
- deterministic control flow

Avoid:

- mutable shared state
- service locators
- static application context access
- reflection-heavy ordinary logic
- hidden fallback behavior
- global holders
- one-interface/one-implementation ceremony
- abstractions that only rename third-party APIs
- raw maps outside explicit wire boundaries

## Visibility

Use the narrowest visibility:

1. private
2. package-private
3. protected only for an intentional inheritance contract
4. public only for supported API/SPI/wire contracts

Public visibility must be classified:

- application-facing API
- external SPI
- wire contract
- public-for-compilation internal bridge
- test-only artifact

Do not make a helper public merely to cross Java subpackages.

Prefer package restructuring or a deliberate internal bridge with ArchUnit restrictions.

## Immutability and Invariants

Public value objects and runtime snapshots must be immutable.

Use:

- records
- defensive copies
- compact constructors
- explicit factories
- immutable maps/lists
- constructor validation

Runtime state must be safely published and updated atomically.

Rejected operations must not expose partially applied state.

Do not return mutable collections or partially usable failure payloads.

## Nullability

Public and module-boundary contracts must state null behavior.

Rules:

- reject null early when it is a programming error
- return empty collections, not null collections
- use `Optional` only for genuine optional return values
- do not use null to encode multiple failure modes
- do not add nullable parameters without documented behavior
- use the repository's approved nullability annotations consistently

## Dependency Injection

Use constructor injection.

Forbidden:

- field injection
- static dependency access
- service locators
- `ApplicationContext` lookup from domain/runtime code
- optional dependencies hidden behind raw `Object`
- mandatory dependencies hidden in `ObjectProvider`

`ObjectProvider` is acceptable for deliberate optional/lazy integration, not to hide an unclear graph.

## Lombok

Lombok may be used where it reduces boilerplate without hiding the contract.

Potentially acceptable:

- `@RequiredArgsConstructor`
- `@Getter` for internal immutable models
- `@Slf4j`
- `@Builder` for carefully reviewed value models

Avoid in sensitive public/wire packages when generated shape obscures:

- public surface
- constructor semantics
- Javadoc
- invariants
- package-private intent

Forbidden:

- `@Data` on domain/runtime state
- `@SneakyThrows`
- Lombok-generated mutable state
- generated methods referenced by Javadoc when Javadoc cannot resolve them

For public protocol/API types, explicit Java is often preferable.

## Naming

Names must describe architectural role.

Prefer names that identify the type as a:

- operation
- factory
- builder
- specification
- policy
- validator
- decoder
- adapter
- runtime
- snapshot
- view
- result
- violation
- applier

Avoid:

- `Utils`
- `Helper`
- `Manager`
- `Common`
- `Base`
- `Generic` without a real domain distinction
- `Impl` in public types
- names based on project history
- names broader than implementation scope
- redundant names inside a clear namespace

A rename is architectural when it corrects the mental model or ownership.

A rename is cosmetic when behavior, ownership, and domain meaning remain unchanged.

## Public API

Public API must remain minimal and intentional.

Before adding a public type/method, answer:

```text
Concrete consumer:
Why an existing capability is insufficient:
Stable domain concept or implementation mechanism:
External types exposed:
Lifecycle:
Thread safety:
Failure model:
Dependency contract:
Security/cardinality impact:
Architecture guard:
```

The default for a new type is not public.

Do not add:

- speculative schema accessors
- query methods without a consumer
- public registries
- false SPIs
- compatibility aliases
- deprecated bridges
- public implementation helpers

Because the solution is pre-production, direct removal and repository-wide migration are preferred when the final API becomes materially better.

## API and Root Facade

The root tracing API should remain a small capability entry point.

Intended style:

```java
TraceOperations traceOperations;

traceOperations.traceContext();
traceOperations.spans().operation("payment.process");
traceOperations.spans().transport();
traceOperations.spans().fromSpec(spec);
```

Do not turn the root facade into a god object.

Do not add runtime-control, Spring, exporter, JMX, sampler implementation, or mutable state methods to the application-facing root API.

Inside a capability namespace, avoid redundant names such as `operationSpan(...)` when `spans().operation(...)` is clear.

## Builders and Specifications

Public builders/specifications must:

- use domain-oriented method names
- reject invalid combinations
- enforce terminal-state rules
- avoid ambiguous booleans
- avoid implementation types in signatures
- validate complete state before execution
- document reuse/thread-safety
- avoid hidden mutable global state
- preserve no-op lifecycle semantics

Do not silently select a default after the caller explicitly made conflicting choices.

Specifications should be immutable and independent from Spring/JMX implementation.

## Error and Result Models

Use exceptions for:

- programming errors
- strict builder misuse
- impossible states
- construction-time invariant violations

Use explicit result/violation models for:

- untrusted wire input
- validation
- control operations
- batch parsing
- recoverable operator errors

Result models should provide:

- machine-readable status/code
- bounded sanitized diagnostics
- immutable data
- clear success/failure invariants

Avoid:

- boolean-only failures
- raw input in exceptions
- generic `RuntimeException` without contract
- swallowed exceptions
- success after partial failure
- one result type mixing structural and domain ownership

## Control Protocol

Protect the approved pipeline:

```text
wire payload
    -> structural decode
    -> domain validation
    -> mutation policy
    -> apply/read
```

Required properties:

- internal programmatic schema remains
- public schema introspection remains removed
- no public `schema()`
- no public `validator()`
- no `READ_SCHEMA`
- strict unknown-key rejection
- operation-specific schemas
- classloader-neutral values
- immutable decode result
- invalid result has no usable apply payload
- domain rules live in core
- empty mutation is rejected
- apply is unreachable after any rejection
- READ and VALIDATE do not mutate
- mutation is fail-closed by default
- rejected mutation preserves snapshot/version/source/LKG

Do not reintroduce legacy packages, facades, aliases, or dual decoder paths.

## Runtime Control

Runtime mutation is privileged.

Rules:

- disabled by default
- explicit startup enablement
- read operations remain available when safe
- validation-only operations do not apply
- rejected mutation does not modify runtime state
- result statuses are machine-readable
- audit metadata is bounded and does not contain raw payloads
- historical domain JMX MBeans remain separate risk surfaces until explicitly hardened or removed

Do not claim JVM/network/RBAC protection is implemented by tracing code.

## Propagation

Use W3C Trace Context and approved OpenTelemetry implementations.

Incoming propagation is untrusted.

Do not:

- use tracing metadata for authentication or authorization
- log full malformed headers
- maintain custom parsers without strong justification
- copy arbitrary baggage into spans/logs
- accept unbounded headers
- create invalid remote context

Test propagation across:

- servlet
- WebFlux
- Kafka/messaging
- executors
- Reactor
- scheduled work
- agent/application classloaders
- remote links

## Sampling

Sampling must be deterministic and explainable.

Require:

- ratio bounds
- route-ratio bounds
- explicit route precedence
- normalized route templates
- deterministic tests
- empty mutation rejection
- fail-closed runtime mutation
- force-sampling trust model
- LKG preservation after rejection
- no conflict between platform and OTel sampler settings
- readable applied state

Force sampling must not bypass scrubbing, export safety, or kill switches.

## Scrubbing and PII

Scrubbing is a production safety mechanism.

Do not claim broad PII protection when only span attributes are processed.

Review separately:

- attributes
- events
- links
- baggage
- resources
- logs
- metrics

Require:

- active rule diagnostics
- skipped unknown rule visibility
- safe rule fingerprint
- deterministic behavior
- critical failure semantics
- no raw sensitive values in diagnostics
- force-sampled spans still scrubbed

## Observability

Telemetry must answer a real operational question.

Do not instrument every method.

Every new signal needs:

- owner
- operational use case
- name
- cardinality budget
- sensitive-data review
- disabled/no-op behavior
- failure behavior
- test evidence

Avoid:

- duplicate spans
- duplicate metrics
- high-cardinality labels
- user/request/trace IDs in metric tags
- raw paths
- raw payloads
- unbounded warning sets
- hidden retries
- telemetry without an operator use case

Follow `observability.md` for detailed signal rules.

## Logging

Use parameterized, structured, bounded logging.

Never log:

- credentials
- tokens
- cookies
- authorization headers
- raw control payloads
- full malformed propagation headers
- raw baggage
- raw PII
- full environment dumps

Warnings must be rate-limited/deduplicated with bounded state.

Do not require DEBUG logs to understand a critical production failure.

## Security

Identify the trust boundary for every security-relevant change.

Secure defaults include:

- mutation disabled
- strict input validation
- no trust-all TLS
- no disabled hostname verification
- no Java native serialization
- no raw PII telemetry
- no arbitrary exporter endpoint mutation
- no security-sensitive fallback to permissive behavior

Do not add custom authentication/authorization inside tracing modules.

Use external platform controls for JVM/JMX/network access, while keeping code-level defense in depth.

Follow `security.md` for detailed requirements.

## Spring Boot

Spring integration owns wiring, not domain behavior.

Use:

- `@AutoConfiguration`
- typed `@ConfigurationProperties`
- explicit defaults
- conditional registration based on actual contract
- `ApplicationContextRunner`
- optional-classpath tests
- concise startup diagnostics

Avoid:

- scattered `@Value`
- direct environment reads in ordinary beans
- unconditional infrastructure beans
- network calls during auto-configuration
- static context holders
- servlet/reactive cross-dependencies
- user overrides that bypass mandatory safety invariants

Follow `spring.md`.

## Reactive Code

Reactive paths must not:

- block event-loop threads
- assume thread-local propagation
- leak MDC/context
- create hidden subscriptions
- use unbounded retries/repeats
- duplicate spans
- make synchronous exporter/JMX/Redis/network calls without isolation

Tests must cover:

- thread hops
- cancellation
- errors
- retries
- context restore
- duplicate spans
- outbound propagation

## Concurrency and Runtime State

Runtime state must be:

- immutable or safely published
- atomically updated
- readable without partial state
- idempotent where needed
- protected against conflicting concurrent applies
- recoverable through last-known-good state when applicable

Test:

- concurrent reads during apply
- repeated apply
- rejected apply
- partial failure rollback
- registration rollback
- no static test leakage

Do not assume singleton beans are thread-safe.

## Startup and Lifecycle

Startup must avoid:

- network calls from ordinary auto-configuration
- Docker/file-system side effects
- unbounded waits
- hidden threads
- eager optional integrations
- global OTel mutation without ownership
- JMX mutation
- system-property changes
- silent fallback after critical failure

Side effects must be:

- explicit
- lifecycle-managed
- idempotent where needed
- reversible where practical
- tested

## Performance

Review performance in context.

### Hot path

Look for:

- per-span allocations
- repeated maps/lists
- regex compilation
- unnecessary string formatting
- synchronization
- reflection
- eager attribute construction
- context conversion
- logging overhead
- unbounded state

### Startup

Look for:

- eager beans
- classpath scanning
- external calls
- exporter construction
- optional integration loading
- background threads

Correctness, privacy, and architecture take priority over speculative micro-optimization.

Use JMH only as one source of evidence.

## Distributed Systems

Do not introduce distributed infrastructure without proving the need.

For any distributed state/coordination:

- define consistency model
- define owner
- define idempotency
- define retry owner
- define failure policy
- define recovery
- define observability
- define Kubernetes behavior
- define security
- test partial failure

Do not assume:

- singleton deployment
- stable pod identity
- local locks provide distributed protection
- retries are harmless
- clocks are synchronized
- infrastructure is always available

## Redis/KeyDB Applicability

Redis/KeyDB is not a default dependency for tracing.

Before adding it, prove:

- real distributed shared-state requirement
- correct owner module
- acceptable failure model
- TTL/lifecycle
- namespace
- serialization
- topology support
- Testcontainers evidence
- why database/Kafka/Kubernetes Lease/local state is not better

Follow `redis.md`.

## Kubernetes

Runtime behavior must tolerate:

- pod restart
- rolling deployment
- rescheduling
- transient network failure
- DNS changes
- optional telemetry backend outage

Avoid:

- fixed topology
- pod IP assumptions
- local filesystem dependence
- privileged behavior
- cluster-admin assumptions
- Docker socket mounts
- JMX public exposure

Readiness/liveness must reflect business-service semantics, not optional telemetry availability.

## Dependencies

Use intentional Gradle scopes.

Do not:

- add dependencies only to make compilation pass
- solve cycles with `api`
- hard-code BOM-managed versions
- add broad artifacts for narrow needs
- leak implementation types
- add untrusted repositories
- use dynamic versions

For every new dependency, record:

```text
Artifact:
Version owner:
Scope:
Public API exposure:
Runtime provider:
Transitive impact:
License/governance:
Why needed:
```

Follow `gradle.md`.

## Testing

Tests must prove intended behavior.

Use:

- JUnit 5
- AssertJ
- Mockito only when necessary
- `ApplicationContextRunner`
- Testcontainers for real boundaries
- Awaitility for asynchronous behavior
- ArchUnit/fitness tasks
- E2E for cross-module/runtime behavior

Avoid:

- `Thread.sleep`
- fixed ports
- fixed localhost
- shared mutable state
- broad `@SpringBootTest`
- copying production logic
- compatibility shims added only for old tests
- probability where deterministic behavior is possible
- skipped E2E reported as PASS

Follow `testing.md` and `testcontainers.md`.

## E2E Evidence

A required E2E gate must actually execute.

For runtime evidence, verify:

```text
tests > 0
skipped = 0
failures = 0
errors = 0
```

A compiled harness is not runtime evidence.

A skipped test is `INSUFFICIENT_EVIDENCE`.

Known remote Docker environment:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
```

Test code must still resolve endpoints through Testcontainers/project helpers.

## Gradle and Build

Before changing Gradle:

- inspect wrapper
- inspect settings/root/module builds
- preserve current DSL
- inspect dependency management
- inspect custom source sets/tasks
- run dependency reports for scope changes

Do not migrate build DSL or build-logic architecture during an unrelated backend task.

Follow `gradle.md`.

## Javadoc

Public Javadoc must compile without actionable source/classpath warnings.

Do not:

- link API docs to core implementation
- link Lombok-generated methods that Javadoc cannot resolve
- suppress doclint globally
- add broad dependencies to silence one warning

Fix the actual source or classpath contract.

## Imports and Formatting

Follow `.editorconfig`.

Use:

- explicit imports
- separate explicit static imports
- deterministic layout

Do not use:

- wildcard imports
- static wildcard imports
- import-only churn
- broad unrelated formatting changes
- locally invented ordering

Generated code must follow the same standard.

## Generated Code and LLM Agents

Cursor, Codex, and Perplexity output must meet the same standard as handwritten code.

Agents must:

- read relevant skills first
- inspect actual repository facts
- avoid invented call sites
- preserve approved architecture
- avoid compatibility aliases by default
- avoid fake SPIs
- avoid duplicate logic/signals
- add negative tests
- run narrow verification before broad verification
- report skipped tests honestly
- distinguish facts from assumptions
- not commit/push unless explicitly requested
- include import policy in downstream prompts

Do not accept confident unsupported claims.

## Pre-Production Breaking-Change Policy

Breaking changes are justified when they:

- remove accidental public API
- correct misleading names
- fix module ownership
- remove external type leakage
- eliminate duplicate runtime/validation paths
- remove false SPI/ServiceLoader/holder patterns
- replace unsafe parsers
- remove public internal schema
- change unsafe defaults
- delete stale properties/bean aliases
- remove duplicate instrumentation
- fix no-op semantic divergence
- correct dependency/publication contracts

Default migration:

1. change to the intended final model
2. migrate all repository consumers
3. update tests, custom source sets, samples, benchmarks, docs, metadata, and CI
4. delete old path
5. add negative/architecture guards
6. run full verification
7. do not add aliases by default

Do not use “pre-production” to justify random churn.

## Review Expectations

Every non-trivial backend change must state:

```text
Problem:
Why non-cosmetic:
Owner module:
Architecture boundary:
Public API impact:
Dependency impact:
Security/privacy impact:
Runtime-state impact:
Failure mode:
No-op/disabled behavior:
Tests:
E2E:
Architecture fitness:
Docs/ADR:
Residual risks:
```

Follow `code-review.md` for audit and merge decisions.

## Anti-Patterns

Forbidden:

- accidental public API
- compatibility aliases by default
- deprecated bridges before production
- public `Impl`/helper/manager types
- false SPI/ServiceLoader
- static application context
- service locators
- mutable global state
- hidden retries
- hidden background threads
- hidden startup side effects
- direct raw wire payload apply
- mutation enabled by default
- trace metadata used for authorization
- PII/secrets in telemetry
- duplicate spans/metrics
- high-cardinality labels
- custom tracing protocols/parsers without approval
- blocking calls on reactive event loops
- Java native serialization
- trust-all TLS
- disabled hostname verification
- dynamic dependencies
- unbounded resources
- Redis added speculatively
- fixed ports/localhost in integration tests
- skipped E2E reported as pass
- wildcard imports
- architecture gates weakened to pass generated code
- cosmetic refactoring presented as architecture

## Required Verification

Choose the narrowest relevant tasks first.

Typical module gates:

```powershell
.\gradlew.bat :<affected-module>:compileJava --no-daemon
.\gradlew.bat :<affected-module>:compileTestJava --no-daemon
.\gradlew.bat :<affected-module>:test --no-daemon
```

Architecture and full build:

```powershell
.\gradlew.bat pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify --no-daemon
.\gradlew.bat build --warning-mode all --no-daemon
```

API/Javadoc when public contracts change:

```powershell
.\gradlew.bat :platform-tracing-api:test --no-daemon
.\gradlew.bat :platform-tracing-api:javadoc --warning-mode all --no-daemon
```

Runtime E2E when behavior crosses runtime/module boundaries:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
.\gradlew.bat :platform-tracing-e2e-tests:test `
  -PrunE2e `
  --rerun-tasks `
  --no-build-cache `
  --no-daemon
```

Run scans appropriate to the change:

- removed/legacy symbols
- compatibility aliases/deprecated bridges
- forbidden dependencies
- wildcard imports
- BOM
- stale Javadoc links
- hard-coded secrets/endpoints
- trust-all TLS
- Java native serialization
- false-positive-prone regex quality gates

## Required Final Report

A final implementation report must include:

```text
Decision:
Problem solved:
Why non-cosmetic:
Branch/commit:
Files/modules changed:
Public API diff:
Dependency changes:
Default behavior:
Failure behavior:
State behavior on rejection:
Security/privacy:
Tests executed:
E2E executed or skipped:
Architecture fitness:
Javadoc/build:
Git status/push:
Residual risks:
Merge readiness:
```

Use:

- `PASS` when required compile/test/Javadoc/architecture/E2E gates executed and passed
- `PASS_WITH_WARNINGS` when code is green and only documented non-blocking operational risk remains
- `INSUFFICIENT_EVIDENCE` when required runtime behavior did not execute
- `FAIL` when correctness, architecture, security, state safety, or required gates fail
