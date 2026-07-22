# Observability Foundations, Ownership, and OpenTelemetry Model

## Context

This repository contains an enterprise platform tracing solution for Spring Boot servlet and reactive microservices.

The solution includes:

- a public tracing API
- core runtime implementation
- Spring Boot auto-configuration
- servlet and WebFlux adapters
- OpenTelemetry agent/SDK integration
- sampling and scrubbing policies
- runtime control and diagnostics
- collector configuration
- tests, benchmarks, and Docker-backed E2E verification

The solution is currently **pre-production**.

Breaking changes are allowed when they materially improve:

- telemetry correctness
- architecture
- runtime safety
- privacy
- performance
- dependency governance
- operator diagnostics
- production readiness
- adoption across many internal services

Do not preserve accidental telemetry behavior, stale metric names, ambiguous span models, unsafe runtime controls, duplicate instrumentation, deprecated bridges, or compatibility aliases merely because existing tests or internal callers still depend on them.

Architects will not accept cosmetic observability refactoring. Every substantial change must improve at least one of:

- correctness of emitted telemetry
- operational usefulness
- module ownership
- failure visibility
- data safety
- cardinality control
- runtime overhead
- adoption simplicity
- executable verification

## Priority

When requirements conflict, prefer in this order:

1. application correctness and availability
2. prevention of sensitive-data leakage
3. correctness of trace context and span relationships
4. deterministic runtime behavior
5. operator diagnostics
6. bounded telemetry volume and cardinality
7. low overhead
8. developer ergonomics
9. compatibility with pre-production behavior

Observability must never become a reason to corrupt business behavior, block a reactive event loop, leak credentials, or silently mutate runtime policy.

## Observability Mission

The tracing platform must make runtime behavior understandable without requiring application teams to know OpenTelemetry SDK internals.

The platform should provide:

- correct distributed traces
- predictable context propagation
- low-cardinality metrics
- structured operational logs
- safe health and diagnostics
- visible runtime-control state
- visible sampling and scrubbing decisions
- bounded and intentional telemetry
- actionable failure information

The platform must not become:

- a general logging framework
- an application metrics framework
- a business audit platform
- an authorization system
- a replacement for service-specific domain telemetry
- a second full OpenTelemetry SDK
- a store for arbitrary payloads or PII

## Observability Is Intentional, Not Universal

Do not instrument every method, bean, or helper.

Add telemetry only when it answers a real operational question, such as:

- Is tracing active?
- Was context propagated?
- Why was a span sampled or dropped?
- Was sensitive data scrubbed?
- Did a runtime policy apply?
- Is the exporter/collector path healthy?
- Is a service using the intended instrumentation mode?
- Did an integration silently back off?
- Is telemetry volume outside the expected budget?

A metric, span, event, log, or endpoint without a concrete operator use case is observability noise.

## Module Ownership

### `platform-tracing-api`

Owns:

- public tracing contracts
- span specifications and immutable value types
- classloader-neutral propagation/control contracts
- public annotations
- public result/violation models
- narrow extension contracts

It must not own:

- OpenTelemetry SDK implementation
- exporters
- span processors
- Spring beans
- JMX/OpenMBean types
- metrics registries
- runtime mutation policy
- collector configuration

### `platform-tracing-otel`

Owns:

- runtime behavior
- implementation of public tracing contracts
- span lifecycle
- context interpretation
- domain validation
- sampling and scrubbing policy
- no-op behavior
- last-known-good runtime state
- runtime-control handlers
- safety invariants

### Spring Boot auto-configuration

Owns:

- wiring
- typed properties
- conditionals
- startup diagnostics
- Actuator integration
- desired startup configuration
- mapping configuration into core/runtime contracts

It must not own tracing algorithms or wire schemas.

### Servlet and WebFlux adapters

Own framework-specific instrumentation and context bridging.

They must not duplicate each other's implementation through cross-dependencies.

### `platform-tracing-otel-extension`

Owns:

- OTel agent/SDK bridges
- sampler/provider integration
- span processors
- exporters/resources where applicable
- JMX/OpenMBean adapters
- classloader-sensitive behavior

### Collector configuration

Owns backend pipeline configuration, such as:

- receivers
- processors
- exporters
- batching
- retry/queue behavior
- collector-side filtering or governance

Do not move application/core policy into collector YAML merely because it is easier to deploy.

## Signal Ownership

Every telemetry signal must have one owner.

### Traces

Owned by tracing API/core and OTel integration.

### Platform metrics

Owned by platform telemetry/runtime modules and Micrometer integration.

### Structured logging conventions

Owned by platform logging standards and application logging infrastructure.

### Health and readiness

Owned by Spring Boot Actuator/infrastructure integration.

### Runtime diagnostics

Owned by diagnostics/Actuator/JMX integration with read-only safe models.

Application modules may emit their own business telemetry, but must not redefine platform trace formats, platform metric semantics, or runtime-control state.

## OpenTelemetry Model

Use OpenTelemetry as the tracing standard.

Prefer:

- OpenTelemetry API for stable tracing concepts
- OpenTelemetry SDK/agent integration in runtime-specific modules
- W3C Trace Context for propagation
- semantic conventions
- supported agent extension points

Avoid:

- custom trace formats
- custom propagation protocols without a proven requirement
- reimplementing OTel parsers, IDs, context storage, or exporters
- exposing SDK implementation types in application-facing APIs
- coupling application code to agent-only classes

If a custom abstraction exists, it must add platform policy or ergonomics rather than merely rename an OTel type.

## Auto-Instrumentation vs Manual Instrumentation

Auto-instrumentation should own standard framework and transport boundaries when it is available and correct.

Examples:

- inbound HTTP
- outbound HTTP
- JDBC
- Kafka client
- common RPC frameworks
- supported Reactor integrations

Manual instrumentation should be used for:

- business or application operations not represented by auto-instrumentation
- platform-specific transport semantics not covered by the agent
- explicit links or relationship models
- controlled enrichment
- domain boundaries that materially improve debugging

Do not create manual spans that duplicate existing agent spans.

Before adding a manual span, answer:

1. What operational question does it answer?
2. Does an auto-instrumented span already answer it?
3. Will it create duplicate parent/child spans?
4. Is the span name low cardinality?
5. Are attributes bounded and safe?
6. What is the expected sampling/export behavior?
7. Is the overhead justified?

Duplicate-span prevention requires tests.

