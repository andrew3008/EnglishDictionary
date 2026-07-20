# Security Standards for the Platform Tracing Solution

## Context

This repository contains an enterprise platform tracing solution for Spring Boot servlet and reactive microservices.

The solution is currently **pre-production**.

Breaking changes are allowed when they materially improve:

- security
- privacy
- runtime safety
- module isolation
- dependency governance
- operator diagnostics
- incident response
- production readiness

Do not preserve insecure or ambiguous behavior for backward compatibility.

Do not add:

- deprecated security bridges
- compatibility aliases for removed unsafe APIs
- dual validation paths
- permissive fallback behavior
- hidden bypasses for tests or legacy callers

Architects will not accept cosmetic security refactoring. Every substantial security change must close a concrete threat, strengthen a trust boundary, remove accidental exposure, or add verifiable enforcement.

## Priority

When requirements conflict, prefer in this order:

1. prevention of credential, secret, or personal-data disclosure
2. prevention of unauthorized runtime mutation
3. correctness of trace context and control-plane validation
4. least privilege
5. fail-closed behavior for risky operations
6. auditability
7. availability and graceful degradation
8. developer convenience
9. compatibility with pre-production behavior

Security must not be weakened merely to keep an old test, public method, bean name, property alias, or ServiceLoader path working.

## Security Goals

The tracing platform must:

- avoid leaking secrets and PII into telemetry
- treat incoming propagation and control data as untrusted
- prevent unauthorized runtime mutation
- preserve classloader-neutral boundaries
- keep API modules free from transport/runtime implementation details
- minimize telemetry cardinality and amplification risks
- validate and normalize external input before use
- keep applied runtime state atomic and auditable
- expose safe diagnostics without exposing sensitive values
- use intentional dependency scopes and trusted artifacts
- make security-relevant defaults explicit and testable

## Explicit Non-Goals

This tracing platform must not become an authentication or authorization platform.

It must not implement:

- custom user authentication protocols
- token issuance
- OAuth/OIDC server behavior
- application business authorization
- a replacement for network/JVM/JMX access control
- a general secret store

Authentication and coarse-grained authorization for Actuator, JMX, Kubernetes, CI, and service endpoints remain responsibilities of the approved platform security layers.

The tracing solution still owns **defense in depth** inside its boundary:

- fail-closed mutation gates
- structural and domain validation
- safe error handling
- audit metadata
- PII/scrubbing controls
- no state changes after rejected requests

## Threat Model

Assume the following inputs may be malformed, malicious, oversized, forged, stale, or injected by an unintended intermediary:

- W3C `traceparent`
- W3C `tracestate`
- baggage
- request/correlation IDs
- custom trace-control headers
- force-sampling headers
- HTTP, RPC, Kafka, and messaging metadata
- JMX/OpenMBean payloads
- `Map<String, Object>` control-protocol payloads
- Spring Boot configuration properties
- environment variables and system properties
- exporter endpoints and authentication metadata
- custom SPI implementations
- attribute keys and values supplied by application code
- route names and route-ratio maps
- test fixtures copied into production code

Assume application and agent code can run in different classloaders.

Assume telemetry backends are external data sinks. Data that reaches an exporter may leave the application trust boundary.

## Trust Boundaries

The main security boundaries are:

```text
incoming request/message
    -> propagation/control extraction
    -> public tracing API
    -> core runtime and domain policies
    -> OTel/JMX/Spring adapters
    -> exporter/collector/backend
```

Each boundary must have one clear owner.

### Public API boundary

`platform-tracing-api` owns:

- public contracts
- classloader-neutral value types
- wire vocabulary
- structural decode results
- safe extension contracts

It must not own:

- Spring security configuration
- JMX implementation
- OpenMBean implementation
- exporter credentials
- domain authorization
- runtime mutation policy
- raw SDK implementation objects unless explicitly approved

### Core boundary

`platform-tracing-core` owns:

- domain validation
- sampling and scrubbing policies
- runtime mutation decisions
- last-known-good state
- apply/read handlers
- safety invariants
- implementation of public contracts

### Spring boundary

Spring Boot auto-configuration owns:

- typed startup configuration
- conditional wiring
- startup diagnostics
- property validation
- safe defaults
- Actuator integration

It must not own tracing domain rules.

### OTel/JMX boundary

`platform-tracing-otel-extension` owns:

- JMX/OpenMBean adapters
- agent/SDK integration
- classloader-sensitive bridges
- exporter/sampler/processor integration

JMX/OpenMBean types must not leak into the API module.

## Secure-by-Default Policy

Risky capabilities must be disabled by default.

Examples:

- runtime mutation
- unsafe attribute escape hatches
- sensitive diagnostics
- unrestricted custom propagation
- verbose payload logging
- dynamic exporter reconfiguration

Read-only diagnostics may remain available only when they do not expose secrets, PII, internal object graphs, or sensitive topology.

A permissive default requires explicit architectural approval and tests.

## Runtime Control Security

Runtime control is a privileged operational capability.

### Required behavior

- `APPLY_RUNTIME_POLICY` must fail closed by default.
- Mutation requires explicit startup enablement.
- `VALIDATE_RUNTIME_POLICY` must never apply changes.
- `READ_APPLIED_STATE` must never mutate state.
- Decode-invalid requests must not reach domain apply.
- Domain-invalid requests must not reach apply.
- Mutation-policy-rejected requests must not reach apply.
- Rejected requests must not modify:
    - last-known-good state
    - applied snapshot
    - version
    - source
    - timestamps representing successful apply
- Apply must be atomic from the reader's perspective.
- Partial failure must not leave mixed old/new state.
- Rollback behavior must be tested where multiple MBeans/resources are registered.

### Startup gate

A mutation-enabling property must:

- default to `false`
- be strongly typed
- be present in configuration metadata
- be documented as security-sensitive
- have startup diagnostics
- have negative and positive tests
- not be changeable through the same untrusted runtime-control path it protects

Example:

```yaml
platform:
  tracing:
    control:
      runtime-mutation:
        enabled: false
```

### JMX boundary

The unified control-protocol MBean must enforce the mutation gate.

Historical domain MBeans are separate risk surfaces. Until removed or hardened, they require:

- JVM/JMX authentication
- network isolation
- RBAC/firewall governance
- explicit warning-register tracking

Do not claim the unified mutation gate protects unrelated historical MBeans.

### Result model

Control results must distinguish at least:

- decode rejected
- domain rejected
- mutation rejected
- applied
- read result
- validation-only result

Machine-readable status must not include raw sensitive payload values.

## Control Protocol Security

The control protocol is a classloader-neutral boundary, not a trusted in-process method call.

Required pipeline:

```text
wire payload -> structural decode -> domain validation -> mutation policy -> apply/read
```

### Structural validation

The API decoder must enforce:

- required envelope fields
- supported contract version
- recognized operation
- operation-specific allowed keys
- strict unknown-key rejection
- String-only map keys
- supported JDK-only value shapes
- deterministic type normalization
- enum-instance rejection as a wire value
- bounded arrays/maps/strings where applicable
- immutable decode result

An invalid decode result must not expose a usable normalized payload for apply.

### Domain validation

Core must enforce:

- sampling-ratio bounds
- route-ratio bounds
- allowed validation modes
- empty-mutation rejection
- cross-field invariants
- kill-switch / force-control conflicts
- limits that protect backend or runtime capacity

Wire errors and domain errors must use different ownership and diagnostics.

### Schema exposure

Internal programmatic schema validation must remain.

Do not expose the internal schema/decoder as public runtime introspection merely for convenience.

Preferred public contract:

- documented wire spec
- public keys/operations/result codes
- executable golden tests
- ADRs
- JMX `MBeanInfo` in the adapter layer when appropriate

Do not reintroduce:

- public `schema()`
- public `validator()`
- `READ_SCHEMA`
- legacy validation facades

## Propagation Security

Trace propagation data is correlation metadata, not identity or authorization.

Never use:

- trace ID
- span ID
- request ID
- baggage
- `traceparent`
- `tracestate`
- force-sampling header

as proof of authentication, tenant ownership, or authorization.

### W3C trace context

Use the approved OpenTelemetry W3C implementation where possible.

Do not maintain ad hoc parsing unless explicitly justified and covered by conformance tests.

Validate:

- syntax
- zero IDs
- version behavior
- flags
- bounded length
- invalid/malformed behavior

Do not log the full malformed header in exceptions or logs. Use sanitized, bounded diagnostics.

### Baggage

Baggage may cross service and organization boundaries.

Rules:

- do not place secrets or raw PII in baggage
- define an allowlist for platform-owned baggage keys
- impose length and entry-count limits
- do not copy arbitrary baggage into span attributes or logs
- do not trust baggage values for authorization
- remove or ignore disallowed keys
- avoid high-cardinality baggage propagation

### Request and correlation IDs

Request IDs are untrusted external strings.

They must be:

- bounded
- character-restricted
- safe for headers and logs
- generated when absent/invalid according to platform policy
- independent from trace IDs

Do not reflect raw invalid request IDs into logs or response headers.

## Force Sampling and Trace-Control Headers

A header that can force sampling can create:

- telemetry amplification
- backend cost spikes
- PII exposure
- denial of service
- targeted collection of sensitive flows

Rules:

- disabled by default unless explicitly required
- use strict allowed values
- bound header length
- do not accept arbitrary truthy strings
- document trusted ingress assumptions
- consider stripping or rewriting at trusted gateways
- do not allow the header to bypass scrubbing
- do not allow force sampling to bypass export kill switches or security policy
- test ratio `0` plus force-header behavior deterministically
- expose safe metrics for accepted/rejected force requests where cardinality is bounded

Do not treat possession of a force header as authorization.

## Sampling Security and Cost Controls

Sampling policy is part of security and cost governance.

Protect against:

- unbounded route maps
- attacker-controlled high-cardinality route keys
- invalid negative/greater-than-one ratios
- empty runtime mutations
- nondeterministic tests
- policy updates that silently reset unrelated fields
- excessive force sampling

Runtime updates must use patch semantics deliberately and preserve absent fields.

Sampling diagnostics must avoid including raw user paths, query parameters, IDs, or secrets.

Use normalized route templates, not raw URLs.

## Telemetry Data Classification

Classify telemetry fields before exposing them.

### Generally acceptable with governance

- operation names
- normalized route templates
- service identity
- environment
- deployment version
- bounded result/status enums
- platform-owned diagnostic codes

### Sensitive or potentially sensitive

- user IDs
- account IDs
- email addresses
- phone numbers
- IP addresses
- full URLs and query strings
- database statements
- message payloads
- headers
- cookies
- tokens
- exception messages
- stack traces
- resource attributes from cloud/runtime environments
- baggage

Sensitive data requires explicit policy, transformation, and tests.

## Span Names

Span names must be:

- low cardinality
- stable
- free of raw IDs
- free of query parameters
- free of message payload values
- based on operation or route templates

Forbidden examples:

```text
GET /users/123456
payment for user@example.com
kafka.consume.customer-938284
```

Preferred:

```text
GET /users/{id}
payment.process
kafka.consume
```

## Attribute Security

Attribute keys and values must be governed.

Rules:

- use approved semantic conventions
- use platform-owned namespaces for custom attributes
- reject or ignore disallowed keys where the API provides controlled enrichment
- apply bounds to string and collection values
- avoid arbitrary application-defined high-cardinality keys
- do not allow user input to become an attribute key
- do not write raw payloads as attributes
- do not use exception messages blindly as attributes
- document whether an attribute is safe, sensitive, or prohibited

An unsafe-attribute escape hatch must be:

- disabled by default
- restricted to internal/platform use
- observable
- covered by tests
- unable to bypass scrubbing

## Scrubbing and PII

Scrubbing is a production safety control, not a formatting feature.

The implementation must clearly define its scope.

A span-attribute processor does not automatically scrub:

- events
- links
- baggage
- resources
- logs
- metrics

Do not claim broad PII protection when only span attributes are covered.

Required behavior:

- rules are deterministic
- unknown configured rule names are visible to operators
- active rule names or safe fingerprints are observable
- skipped rules are reported safely
- critical rule failures have explicit behavior
- values are removed or transformed before export
- raw sensitive values never appear in diagnostics
- scrubbing remains active for force-sampled spans
- no-op/disabled behavior is explicit and tested

PII policy and backend cardinality policy belong in core/collector governance, not wire decoding.

## Events, Links, Resources, Logs, and Metrics

Each telemetry signal needs a separate data-safety decision.

### Events

Do not attach:

- full request/response bodies
- arbitrary exception payloads
- sensitive message contents

### Links

Remote links may carry trace identifiers and tracestate. Do not add arbitrary baggage or user data to link metadata.

### Resources

Review cloud/container resource detectors for sensitive metadata. Do not expose credentials, internal tokens, or unrestricted environment variables.

### Logs

Never log:

- authorization headers
- cookies
- passwords
- API keys
- access/refresh tokens
- raw control payloads
- raw baggage
- full malformed propagation headers
- raw PII

Use structured, bounded, trace-aware diagnostics.

### Metrics

Metric labels must be low cardinality.

Never use:

- trace ID
- span ID
- request ID
- user/account ID
- raw route/path
- exception message
- arbitrary rule name supplied by an untrusted caller

## Exporter and Collector Security

Exporter configuration can become a data-exfiltration path.

Rules:

- prefer startup-owned exporter endpoints
- do not permit arbitrary runtime endpoint mutation without a separate approved threat model
- validate schemes and endpoint format
- use TLS for external/untrusted networks
- allow plaintext only for an explicitly documented local/sidecar trusted boundary
- never disable certificate or hostname verification globally
- never log exporter credentials or auth headers
- use bounded timeouts and queues
- fail predictably when exporter configuration is invalid
- preserve application availability according to the approved degradation policy
- avoid startup network calls in ordinary auto-configuration

If endpoints can be runtime-controlled, require:

- explicit mutation enablement
- host/scheme allowlist
- audit trail
- rollback
- SSRF analysis
- tests proving rejected endpoints do not change live state

## Secret Management

Secrets include:

- exporter API keys
- collector credentials
- TLS private keys
- trust-store passwords
- JMX credentials
- OAuth tokens
- cloud credentials

Secrets must:

- be externalized
- support rotation
- be masked in diagnostics
- not be persisted in applied-state snapshots
- not be included in telemetry
- not be returned by Actuator/JMX read operations
- not be committed to Git
- not be embedded in Docker images or test fixtures

Use approved secret-management infrastructure.

Environment variables may inject secrets, but code must not dump the full environment.

## Actuator Security

Actuator endpoints are operational APIs.

Rules:

- read-only by default
- mutation guarded explicitly
- security assumptions documented
- no raw secrets/PII
- no internal implementation object serialization
- no unbounded collections
- no public exposure without platform security controls
- live state distinguished from desired startup configuration
- warning/failure states actionable

Do not assume that hiding an endpoint name is a security boundary.

## JMX Security

JMX is privileged operational access.

The tracing code cannot replace JVM/JMX authentication and network controls.

Required defense in depth:

- mutation disabled by default
- read and validate separated from apply
- input decoded and domain-validated
- OpenMBean/JMX types isolated in adapter module
- no Java native serialization
- no arbitrary class deserialization
- stable object names
- idempotent registration
- rollback on partial registration failure
- warnings for historical unguarded MBeans
- no credentials or raw policy secrets in MBean attributes

## Spring Boot Security

Spring integration must:

- use typed configuration properties
- validate startup-owned configuration
- keep risky mutation disabled by default
- avoid direct environment access in ordinary beans
- avoid security-sensitive network calls during auto-configuration
- keep optional classpaths isolated
- not silently back off when a mandatory security component is missing
- expose concise startup diagnostics

`@ConditionalOnMissingBean` must not allow an arbitrary replacement to bypass mandatory safety invariants.

If a user-replaceable security-sensitive bean exists, its extension contract must state which invariants cannot be disabled.

## Classloader Security

The agent and application may use different classloaders.

Across classloader boundaries:

- use JDK-only wire types
- use explicit schemas
- reject Java enum instances where String wire values are required
- avoid casting implementation classes from another classloader
- avoid static holders that assume one classloader
- verify ServiceLoader behavior deliberately
- do not expose agent implementation classes to application API

Classloader isolation must be tested through real agent/JMX paths when possible.

## SPI and ServiceLoader Security

Use SPI/ServiceLoader only for a real, documented extension point.

Do not use ServiceLoader for:

- pure deterministic utilities
- single built-in implementation
- hidden initialization order
- mandatory safety components with no fallback

For a security-sensitive SPI:

- define trust assumptions
- validate provider output
- define provider ordering
- define failure behavior
- restrict classloader visibility
- test missing, duplicate, and failing providers
- do not let a provider bypass core safety policies

A custom scrubbing rule provider may extend detection logic, but must not disable mandatory platform rules unless explicitly approved.

## Dependency and Supply-Chain Security

Dependencies must:

- come from approved repositories
- use pinned/BOM-managed versions
- be reproducible
- be subject to vulnerability scanning
- use the narrowest artifact needed
- have intentional Gradle scope

Prefer:

- `jackson-annotations` over `jackson-databind` when only metadata is needed
- `implementation` for internal runtime code
- `compileOnly` only for genuine provided-runtime contracts
- `api` only when a dependency type appears in supported public signatures

Avoid:

- dynamic versions
- unmaintained libraries
- broad utility dependencies for trivial logic
- duplicate protocol/schema libraries
- adding JSON Schema/OpenAPI runtimes to a JDK-only internal control protocol without a proven need

Do not add a dependency solely to silence a warning unless the dependency accurately reflects the compile/Javadoc/runtime contract.

## Serialization Security

The control boundary uses explicit, constrained types.

Allowed classloader-neutral types must be documented.

Never use:

- Java native serialization
- unrestricted polymorphic deserialization
- arbitrary class names from input
- reflection-based construction from untrusted maps
- raw object graphs in JMX responses

If JSON is introduced in another boundary:

- use explicit models
- disable unsafe polymorphic typing
- reject unknown fields where appropriate
- bound payload size and nesting
- separate structural decode from domain validation

## Denial-of-Service and Resource Limits

Protect the tracing system from amplification.

Bound:

- header lengths
- baggage entry count and total size
- request ID length
- string attribute length
- string-array length
- route-ratio map size
- number of custom attributes
- event count
- link count
- diagnostic list size
- exporter queue size
- retry count and timeout
- concurrent runtime mutations

Avoid:

- unbounded maps/sets used for warning deduplication
- user-controlled cache keys without limits
- attacker-controlled span names
- unbounded exception or payload capture
- regexes vulnerable to catastrophic backtracking on untrusted input

Hot-path security checks must be allocation-aware but correctness has priority.

## Concurrency and Runtime State

Runtime state must be:

- immutable or safely published
- updated atomically
- versioned when needed
- recoverable through last-known-good state
- readable without observing partial mutation
- protected from concurrent conflicting applies

Tests must cover:

- concurrent reads during apply
- rejected apply preserving old state
- repeated identical apply
- partial failure rollback
- registration rollback
- no static mutable test leakage

## Failure Semantics

Choose failure behavior explicitly.

### Fail closed

Use for:

- unauthorized/disabled mutation
- invalid control payload
- domain-invalid policy
- malformed security-sensitive configuration
- invalid exporter endpoint mutation
- unknown critical scrubbing rule when policy requires strictness

### Fail startup

Use when:

- a mandatory safety invariant cannot be satisfied
- a required security component is missing
- mutually exclusive critical settings are enabled

### Degrade safely

Use when:

- an optional diagnostic surface is unavailable
- tracing is explicitly disabled
- export failure policy permits application continuation

Do not silently fall back to a permissive or unsafe mode.

## Security Logging and Auditability

Security-relevant events should be structured and bounded.

Audit at least:

- mutation allowed/denied
- operation
- source category
- version or correlation identifier
- result status
- reason code
- timestamp
- state version before/after when safe

Do not audit raw payload values.

Avoid duplicate warning floods. Deduplication must be bounded and test-isolated.

## Kubernetes and Deployment Security

Production deployment guidance should include:

- non-root containers
- read-only root filesystem where practical
- least-privilege RBAC
- network policies
- restricted JMX exposure
- secret injection through approved mechanisms
- no Docker socket mount
- no cluster-admin requirement
- trusted collector/exporter network path
- resource limits for collector/exporter components
- explicit runtime-mutation enablement only where operationally approved

Do not assume Kubernetes network location alone makes JMX or telemetry trusted.

## Test Security

Security-sensitive behavior requires negative tests.

Required examples:

- mutation disabled by default
- mutation rejected without state change
- validate operation does not apply
- read operation does not mutate
- unknown control keys rejected
- malformed trace context rejected
- force-sampling header invalid value rejected
- PII removed/masked before export
- secrets absent from diagnostics
- oversized input rejected
- optional provider failure handled safely
- JMX/OpenMBean types absent from API
- historical unsafe path tracked if not yet removed

Use Testcontainers/E2E when the security property depends on a real boundary.

A skipped E2E test is `INSUFFICIENT_EVIDENCE`, not a pass.

Never use production credentials in tests.

## Security Architecture Fitness Rules

Protect at least:

- API does not depend on core implementation
- API does not depend on Spring/JMX/OpenMBean/OTel SDK implementation
- core does not depend on Spring
- JMX types stay in the adapter module
- internal schema/decoder helpers are not public
- legacy control packages and symbols do not return
- runtime apply cannot accept raw wire maps outside approved adapters
- mutation is disabled by default
- no trust-all TLS or disabled hostname verification
- no Java native serialization
- no wildcard CORS in platform admin surfaces
- no secrets in configuration metadata or docs
- no forbidden telemetry keys/namespaces where statically enforceable

Do not weaken a security rule to make generated code compile.

## Imports and Generated Code

For Cursor, Codex, and Perplexity-generated security changes:

- read repository context and security skills first
- follow `.editorconfig`
- use explicit imports
- do not use wildcard imports
- do not introduce import-only churn
- identify the trust boundary being changed
- list assumptions
- add negative tests
- run the narrowest affected security and architecture gates
- do not claim a threat is mitigated without executable evidence
- distinguish code protection from external JVM/network/RBAC protection

Generated code must not add a permissive fallback merely to pass tests.

## Breaking-Change Policy

Because the tracing solution is pre-production, prefer removing unsafe abstractions over preserving them.

Breaking changes are justified when they:

- remove accidental public control APIs
- eliminate duplicate validation paths
- remove insecure ServiceLoader/holder patterns
- move security/domain rules to the correct module
- remove unsafe mutation paths
- rename ambiguous properties that hide security semantics
- delete unsupported compatibility aliases
- narrow public API surface
- replace custom security-sensitive parsers with mature implementations

Document intentional breaking security changes in an ADR and update all tests/docs directly.

## Anti-Patterns

Forbidden:

- mutation enabled by default
- public `schema()`/`validator()` for internal control implementation
- `READ_SCHEMA` as a speculative production operation
- direct apply of raw `Map<String, Object>`
- force sampling bypassing scrubbing/export policy
- using tracing metadata for authorization
- raw baggage copied to attributes/logs
- user input used as attribute key or metric label
- unbounded trace-control input
- raw PII in spans/events/logs/metrics
- secrets in applied-state snapshots
- arbitrary runtime exporter endpoint mutation
- trust-all TLS
- disabled hostname verification
- Java native serialization
- unrestricted polymorphic deserialization
- static application-context holders
- custom authentication inside tracing modules
- broad security warning suppression
- compatibility shims that preserve insecure behavior
- skipped security E2E reported as pass

## Required Verification

For non-trivial security changes, select the applicable gates:

```powershell
.\gradlew.bat :platform-tracing-api:test --no-daemon
.\gradlew.bat :platform-tracing-core:test --no-daemon
.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:test --no-daemon
.\gradlew.bat :platform-tracing-otel-extension:test --no-daemon
.\gradlew.bat pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify --no-daemon
.\gradlew.bat build --warning-mode all --no-daemon
```

Run targeted opt-in E2E when a runtime boundary changes:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
.\gradlew.bat :platform-tracing-e2e-tests:test `
  -PrunE2e `
  --rerun-tasks `
  --no-build-cache `
  --no-daemon
```

Run scans appropriate to the change:

- removed/legacy API symbols
- forbidden dependencies
- wildcard imports
- BOM
- trust-all TLS
- disabled certificate/hostname verification
- Java native serialization
- hard-coded credentials
- sensitive logging patterns

## Required Security Report

A final security-related implementation report must include:

```text
Threat addressed:
Trust boundary:
Default behavior:
Failure mode:
State-change behavior on rejection:
Sensitive data handled:
Tests executed:
E2E executed or skipped:
Architecture fitness:
External controls still required:
Residual risks:
```

Use:

- `PASS` only when required gates executed and passed
- `PASS_WITH_WARNINGS` when code is green and only documented non-blocking external controls remain
- `INSUFFICIENT_EVIDENCE` when a required runtime/security boundary was not executed
- `FAIL` when a required security invariant is not enforced or a required gate fails
