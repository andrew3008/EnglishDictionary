# Security Foundations and Trust Boundaries

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

