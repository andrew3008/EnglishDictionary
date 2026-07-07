# Observability Platform Standards

## Context

This repository contains enterprise-grade Spring Boot platform starters, Gradle convention plugins, and infrastructure modules.

All generated code must be observable by default.

Observability requirements include:

* metrics
* tracing
* structured logging
* health monitoring
* operational diagnostics
* runtime visibility

Target environments:

* Kubernetes
* cloud-native platforms
* distributed microservices
* high-scale enterprise systems

---

# Priority

This skill has very high priority for:

* platform starters
* infrastructure modules
* distributed systems
* asynchronous processing
* messaging integrations
* Kubernetes workloads

When conflicts occur:

* operational visibility has priority
* production diagnostics has priority
* traceability has priority

---

# Observability Principles

Applications must:

* expose meaningful metrics
* produce structured logs
* support distributed tracing
* provide actionable diagnostics
* avoid hidden runtime behavior

Avoid:

* silent failures
* opaque infrastructure logic
* untraceable async flows
* missing operational metadata

Prefer:

* explicit instrumentation
* consistent naming conventions
* correlation identifiers
* low-overhead telemetry

---

# Metrics

Use:

* Micrometer
* Prometheus-compatible metrics
* dimensional metrics

Metrics must:

* have stable naming
* support aggregation
* avoid high cardinality
* expose operationally meaningful values

Prefer:

* counters
* timers
* gauges where justified
* distribution summaries

Avoid:

* user-id labels
* request-id labels
* unbounded cardinality
* duplicate metrics

---

# Metric Naming

Metric names must:

* be stable
* use dot notation
* reflect domain intent

Example:
platform.redis.lock.acquire

Avoid:

* ambiguous metric names
* environment-specific names
* dynamic metric names

---

# Tracing

Use:

* OpenTelemetry
* W3C trace context propagation
* distributed tracing standards

Trace propagation must support:

* HTTP
* messaging
* async execution
* scheduled jobs

Avoid:

* trace context loss
* custom tracing formats
* hidden async execution

Prefer:

* explicit span naming
* low-cardinality attributes
* semantic conventions

---

# Span Design

Spans must:

* represent meaningful operations
* remain lightweight
* expose failure context

Avoid:

* extremely granular spans
* excessive span nesting
* high-cardinality attributes

Prefer:

* infrastructure spans
* integration spans
* boundary spans

---

# Structured Logging

Use:

* structured JSON logs
* correlation IDs
* trace IDs
* span IDs

Logs must:

* be machine-readable
* support centralized aggregation
* preserve operational context

Avoid:

* plain text logs
* multiline stack traces where avoidable
* inconsistent log structure

---

# Logging Standards

Use:

* parameterized logging
* consistent log levels
* structured fields

Never:

* log secrets
* log tokens
* log credentials
* log sensitive payloads

Prefer:

* contextual logging
* operationally actionable messages
* deterministic log structure

---

# Correlation

All requests and async flows must support:

* trace correlation
* request correlation
* execution context propagation

Prefer:

* MDC propagation
* OpenTelemetry context propagation
* structured correlation metadata

Avoid:

* thread-local assumptions without propagation
* lost async correlation
* hidden execution context switching

---

# Health Monitoring

Every service must expose:

* liveness health
* readiness health
* dependency health

Prefer:

* Spring Boot Actuator
* health groups
* dependency-specific indicators

Avoid:

* expensive liveness checks
* blocking health probes
* hidden startup failures

---

# Runtime Diagnostics

Infrastructure modules must expose:

* startup diagnostics
* configuration diagnostics
* integration status
* operational state visibility

Avoid:

* silent auto-configuration failure
* hidden conditional bean behavior
* opaque retry loops

Prefer:

* explicit startup logs
* condition evaluation diagnostics
* operational visibility hooks

---

# Error Observability

Failures must:

* preserve root cause
* expose operational context
* support troubleshooting

Avoid:

* swallowed exceptions
* hidden retries
* silent degradation

Prefer:

* structured error logging
* failure metrics
* explicit retry instrumentation

---

# Async and Messaging Observability

Async execution must:

* preserve tracing context
* expose queue metrics
* expose retry metrics
* expose failure diagnostics

Prefer:

* trace-aware executors
* messaging instrumentation
* retry observability

Avoid:

* hidden async execution
* untracked retries
* invisible background tasks

---

# Redis and Infrastructure Observability

Redis integrations must expose:

* connection metrics
* timeout metrics
* lock metrics
* retry metrics

Prefer:

* Micrometer instrumentation
* distributed tracing hooks
* infrastructure health indicators

Avoid:

* hidden infrastructure retries
* invisible connection failures
* silent lock contention

---

# Kubernetes Observability

Kubernetes workloads must expose:

* Prometheus scrape endpoints
* readiness state
* startup diagnostics
* deployment visibility

Prefer:

* Prometheus Operator compatibility
* OpenTelemetry integration
* structured Kubernetes logs

Avoid:

* environment-specific instrumentation
* hidden deployment state
* cluster-specific assumptions

---

# Performance and Overhead

Observability must:

* minimize allocation overhead
* avoid blocking operations
* avoid excessive telemetry volume

Prefer:

* sampling where appropriate
* lightweight instrumentation
* asynchronous exporters

Avoid:

* synchronous telemetry export
* excessive debug logging
* high-frequency heavy metrics

---

# Testing Observability

Tests must validate:

* metrics registration
* trace propagation
* logging structure
* health endpoint behavior

Prefer:

* integration testing with observability enabled
* trace-aware testing
* metric verification

Avoid:

* disabling observability entirely in tests
* unverified instrumentation behavior

---

# Anti-Conflict Rules

## Metrics Ownership

Metrics are owned ONLY by:

* observability infrastructure
* platform telemetry modules
* Micrometer instrumentation

Feature modules must NOT:

* redefine shared metric names
* introduce incompatible metric semantics
* create unbounded metric cardinality

---

## Tracing Ownership

Tracing infrastructure belongs ONLY to:

* OpenTelemetry integrations
* tracing platform modules
* observability infrastructure

Application modules must NOT:

* implement custom tracing propagation
* redefine trace formats
* bypass trace context propagation

---

## Logging Ownership

Logging structure belongs ONLY to:

* platform logging infrastructure
* structured logging modules
* observability conventions

Application modules must NOT:

* redefine JSON log formats
* emit inconsistent log structures
* bypass centralized logging standards

---

## Correlation Ownership

Correlation propagation belongs ONLY to:

* tracing infrastructure
* execution context infrastructure
* observability platform modules

Feature modules must NOT:

* manually mutate correlation identifiers
* implement incompatible MDC handling
* break async context propagation

---

## Health Ownership

Health endpoint semantics belong ONLY to:

* Actuator integrations
* observability infrastructure
* platform health modules

Application code must NOT:

* overload readiness semantics
* expose misleading health states
* perform destructive health checks

---

## Infrastructure Visibility Ownership

Operational visibility belongs ONLY to:

* platform observability infrastructure
* runtime diagnostics modules
* telemetry integrations

Application modules must NOT:

* hide infrastructure failures
* suppress startup diagnostics
* bypass operational instrumentation

---

## Retry Observability Ownership

Retry instrumentation belongs ONLY to:

* infrastructure integrations
* resilience modules
* observability infrastructure

Application modules must NOT:

* implement invisible retries
* suppress retry metrics
* hide retry exhaustion

---

# Enterprise Rules

Generated observability logic must:

* support long-term operational maintenance
* remain production-safe
* avoid vendor lock-in
* support distributed debugging

Prefer explicit telemetry over hidden runtime behavior.

---

# Anti-Patterns

Forbidden:

* silent exception handling
* hidden retries
* high-cardinality metrics
* logging secrets
* unstructured logs
* missing trace propagation
* blocking telemetry exporters
* invisible async execution
* custom tracing protocols
* runtime metric name mutation
* hidden infrastructure state
* swallowed startup failures
* excessive debug logging in production
* telemetry without operational value

Avoid observability magic.
