---
name: observability
description: Defines enterprise observability and telemetry standards for the Platform Tracing repository. Use when Codex designs, reviews, refactors, implements, or validates traces, spans, context propagation, baggage, request/correlation identity, sampling, scrubbing, OpenTelemetry Agent/SDK behavior, metrics, logging, health, runtime diagnostics, exporters, Collector integration, Kafka/database instrumentation, telemetry budgets, performance, or observability tests.
---

# Platform Tracing Observability Standards

## Objective

Make Platform Tracing operationally useful, correct, safe, bounded, and understandable without requiring service teams to know OpenTelemetry SDK internals.

The solution is pre-production. Breaking telemetry, API, metric, span, property, protocol, package, bean, or module changes are allowed when they materially improve correctness, privacy, architecture, diagnostics, overhead, or adoption.

Do not preserve accidental telemetry behavior, stale metric names, ambiguous span models, duplicate instrumentation, unsafe runtime controls, deprecated bridges, or compatibility aliases without an approved requirement.

Architects will not accept cosmetic observability refactoring. Every substantial change must improve emitted telemetry, operational decisions, ownership, failure visibility, data safety, cardinality, runtime cost, adoption, or executable evidence.

## Applicability

Add or change telemetry only when it answers a concrete operational question.

Examples:

- Is tracing active and correctly composed?
- Was context propagated?
- Why was a span sampled, dropped, scrubbed, or rejected?
- Did desired runtime policy become applied state?
- Is the exporter/Collector path healthy?
- Is instrumentation duplicated?
- Is telemetry volume within its budget?

Do not instrument every method, bean, or helper merely because it is observable.

Platform Tracing must not become a general logging framework, application metrics framework, business audit platform, authorization system, arbitrary payload store, or second full OpenTelemetry SDK.

## Priority

When requirements conflict, prefer:

1. application correctness and availability
2. prevention of sensitive-data leakage
3. correct trace context and span relationships
4. deterministic runtime behavior
5. operator diagnostics
6. bounded telemetry volume and cardinality
7. low runtime overhead
8. developer ergonomics
9. compatibility with pre-production behavior

Observability must never corrupt business behavior, block reactive event loops, leak credentials, silently mutate runtime policy, or create unbounded cost.

## Core workflow

1. Read applicable repository instructions, semantic conventions, architecture plans, ADRs, and deployment modes.
2. Identify the operational question and the concrete consumer of the signal.
3. Inventory signal owners, instrumentation paths, span lifecycle, attributes, propagation, sampling, scrubbing, metrics, logs, health, and diagnostics affected by the change.
4. Verify current and target module names and composition planes from repository evidence.
5. Load the applicable files from `references/`.
6. Trace data from untrusted ingress through validation, context, span creation, projection, sanitization, export, logging, and backend observation.
7. Check automatic and manual instrumentation for duplication and semantic parity.
8. Define signal names, units, attributes, cardinality, lifecycle, aggregation, and volume budgets.
9. Define disabled, unavailable, invalid, degraded, failed, shutdown, and recovery behavior.
10. Implement the smallest coherent change at the correct owner.
11. Add unit, contract, architecture, packaged Agent, Spring, reactive, Kafka/database, Collector, and performance tests proportional to risk.
12. Verify actual emitted telemetry rather than only bean registration or configuration.
13. Report signal delta, operational value, privacy/cardinality impact, runtime cost, tests, and residual risks.

## Mandatory invariants

- Do not let observability failures corrupt or incorrectly reject business operations.
- Give each signal exactly one clear owner.
- Do not emit duplicate spans, metrics, logs, events, or diagnostics from competing instrumentation paths.
- Use stable, bounded span names; put high-cardinality values in governed attributes, not names.
- Preserve correct parent/child and link semantics across automatic, manual, async, reactive, messaging, and batch paths.
- End every owned span exactly once and record errors consistently.
- Keep no-op/disabled behavior semantically valid and distinct from unavailable, incompatible, or failed runtime states.
- Treat baggage and external propagation data as untrusted and bounded.
- Keep traceId, requestId, and business correlationId semantically distinct.
- Do not use identifiers, baggage, raw headers, credentials, tokens, cookies, SQL values, payloads, or personal data as metric dimensions.
- Apply sanitization before export and test the actual backend-visible result.
- Keep metric names, units, types, labels, and aggregation stable and intentional.
- Bound cardinality, span/event/link counts, attribute sizes, queues, retries, and diagnostic payloads.
- Use structured logs with deliberate levels and warning deduplication.
- Keep liveness independent of telemetry backends; model readiness only when the business contract truly requires it.
- Distinguish desired configuration, applied runtime state, and last failure.
- Do not claim Agent security or readiness from marker/MBean presence alone.
- Keep asynchronous/reactive instrumentation non-blocking and context-safe.
- Define Collector/exporter failure as an observability degradation unless a security/compliance invariant is bypassed.
- Add architecture rules preventing signal ownership and dependency regressions.

## Reference selection

Read only references relevant to the task, except for required combinations.

### Foundations, module ownership, signals, and OTel model

Read [foundations-ownership-and-otel-model.md](references/foundations-ownership-and-otel-model.md).

Use it for observability scope, signal ownership, module responsibilities, OpenTelemetry concepts, and automatic versus manual instrumentation.

### Public API and span model

Read [public-api-and-span-model.md](references/public-api-and-span-model.md).

Use it for facade design, span naming/granularity, relationships, lifecycle, no-op, status, errors, events, links, attributes, semantic conventions, and Resource identity.

### Propagation, baggage, IDs, context, and MDC

Read [propagation-context-and-identity.md](references/propagation-context-and-identity.md).

Use it for W3C propagation, trust, baggage, requestId, correlationId, Reactor/async context, and logging context.

### Sampling and scrubbing

Read [sampling-and-scrubbing.md](references/sampling-and-scrubbing.md).

Use it for sampler ownership, force sampling, cost controls, sensitive data, validation, and fail-closed scrubbing.

### Metrics

Read [metrics.md](references/metrics.md).

Use it for platform metrics, names, cardinality, counters, gauges, timers, histograms, and duplicate instrumentation.

### Logging, health, startup, and runtime diagnostics

Read [logging-health-and-runtime-diagnostics.md](references/logging-health-and-runtime-diagnostics.md).

Use it for structured logging, levels, deduplication, audit logs, liveness/readiness, startup diagnostics, desired/applied state, and runtime control observability.

### Export, failure, async, messaging, database, and Kubernetes

Read [export-failure-and-integrations.md](references/export-failure-and-integrations.md).

Use it for exporters, Collector behavior, degradation, retries, reactive execution, Kafka, databases, and workload observability.

### Telemetry budgets, performance, and benchmarks

Read [volume-performance-and-benchmarks.md](references/volume-performance-and-benchmarks.md).

Use it for signal budgets, allocation, latency, startup overhead, exporter pressure, and benchmark interpretation.

### Tests, assertions, contracts, architecture, docs, and reports

Read [testing-governance-and-reporting.md](references/testing-governance-and-reporting.md).

Use it for unit/Spring/E2E tests, trace/metric/log assertions, golden contracts, fitness rules, breaking changes, anti-patterns, verification commands, and the final report.

## Required reference combinations

For every observability change, read:

1. `foundations-ownership-and-otel-model.md`
2. `testing-governance-and-reporting.md`
3. every signal/domain reference touched by the change

For trace-model changes, also read:

1. `public-api-and-span-model.md`
2. `propagation-context-and-identity.md`
3. `sampling-and-scrubbing.md` when sampling or data safety is affected

For operational/runtime changes, also read:

1. `logging-health-and-runtime-diagnostics.md`
2. `export-failure-and-integrations.md`
3. `volume-performance-and-benchmarks.md` when volume or hot paths can change

## Completion standard

Do not report completion until:

- the operational question and signal consumer are explicit
- signal ownership is explicit
- emitted names, types, units, relationships, attributes, and cardinality are governed
- automatic/manual and servlet/reactive paths do not duplicate signals
- propagation and identity semantics are preserved
- sensitive backend-visible output is tested
- disabled, failed, degraded, and recovery states are distinguished
- runtime overhead and volume implications are bounded
- actual E2E executes where backend behavior is claimed
- architecture and contract gates pass
- the observability report states signal delta, evidence, operational value, cost, privacy impact, and residual risk

