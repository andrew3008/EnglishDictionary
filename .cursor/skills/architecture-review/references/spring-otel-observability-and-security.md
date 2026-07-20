# Spring, OpenTelemetry, Observability, and Security Architecture

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

