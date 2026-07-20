# Observability Standards for the Platform Tracing Solution

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

### `platform-tracing-core`

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

## Public Tracing API

The application-facing entry point should remain small and capability-oriented.

Current intended usage:

```java
TraceOperations traceOperations;

traceOperations.traceContext();
traceOperations.spans().operation("payment.process");
traceOperations.spans().transport();
traceOperations.spans().fromSpec(spec);
```

Do not expose:

- OpenTelemetry `SpanBuilder`
- SDK providers
- exporters
- processors
- mutable runtime state
- JMX handlers
- sampler implementations
- raw context mutation primitives

The API should describe what application code needs to do, not how OpenTelemetry implements it.

## Span Naming

Span names must be:

- stable
- low cardinality
- concise
- based on operation or normalized route
- independent from runtime IDs
- useful in backend aggregation

Preferred:

```text
payment.process
portfolio.recalculate
GET /orders/{id}
kafka.consume
database.query
```

Forbidden:

```text
payment.process.user-938274
GET /orders/128991
query for account@example.com
kafka.consume.customer-83934
```

Do not include:

- trace ID
- span ID
- request ID
- user/account ID
- raw URL
- query string
- payload data
- exception text
- dynamic timestamps

Use route templates, not raw paths.

## Span Granularity

Create spans for meaningful operations and boundaries.

Prefer:

- inbound/outbound boundaries
- messaging produce/consume operations
- database operations where standard instrumentation is absent or enriched safely
- important application operations
- batch steps
- scheduled jobs
- cross-process or cross-thread boundaries

Avoid:

- getter/setter spans
- every internal method
- loops producing one span per item without a volume budget
- excessive nesting
- spans around trivial computations
- duplicate transport spans

A span should represent work an operator may reasonably investigate.

## Span Relationship and Context

Parenting, rooting, detachment, and links must be explicit.

The platform's relationship model must not be confused with OpenTelemetry `SpanKind`.

Tests must cover:

- child behavior
- root behavior
- detached behavior
- links
- repeated mutually exclusive relationship calls
- context scope close behavior
- remote link conversion
- invalid combinations

Do not silently fall back to a default relationship after an explicit conflicting choice.

## Span Lifecycle

Span lifecycle must be deterministic.

Requirements:

- start exactly once
- end exactly once
- scope close is idempotent or fails according to the documented contract
- exceptions do not leak open spans/scopes
- terminal operations are clear
- no-op implementation preserves lifecycle expectations
- builder reuse rules are explicit
- spans are not shared across unsafe threads unless supported

Use `try/finally`, scoped helpers, or structured APIs to guarantee closure.

Do not rely on finalizers or garbage collection.

## No-Op Behavior

Disabled/no-op tracing is a supported runtime state.

No-op behavior must:

- preserve public method availability
- avoid network calls
- avoid hidden allocations where practical
- not mutate runtime state
- not throw merely because tracing is disabled
- preserve documented builder/lifecycle validation
- return safe trace-context views
- not create false diagnostics claiming telemetry was exported

Test no-op behavior independently from active runtime behavior.

## Span Status and Errors

Use span status intentionally.

Do not mark every exception as `ERROR` automatically without considering operation semantics and existing instrumentation behavior.

Record exception events only when:

- they add diagnostic value
- they do not duplicate an existing agent event
- sensitive exception content is controlled
- event count is bounded

Do not attach raw exception messages blindly.

Prefer:

- exception type
- bounded/sanitized message where policy permits
- mapped status
- operation-specific failure classification

Avoid duplicate exception events.

## Span Events

Events are not a replacement for logs.

Use events for important span-local milestones, such as:

- retry exhausted
- fallback selected
- message batch partially processed
- controlled state transition

Do not create events for every loop iteration.

Do not attach:

- request/response bodies
- message payloads
- credentials
- unbounded maps
- raw stack traces without policy
- sensitive business data

Event names and attributes must be low cardinality.

## Span Links

Use links when work relates to traces that are not the active parent, such as:

- batch consumption
- fan-in
- async aggregation
- retry/replay relationship where parentage is not correct

Links must use validated trace context.

Do not attach arbitrary baggage or user data to link metadata.

Test:

- valid remote link
- malformed traceparent
- zero IDs
- flags
- link count limits
- batch link behavior

Bound the number of links per span.

## Attributes

Attributes must be:

- operationally useful
- type-safe
- bounded
- low cardinality where used for aggregation
- semantically stable
- scrubbed according to policy

Do not use user input as an attribute key.

Prefer approved semantic conventions and platform-owned namespaces.

Examples of safer attributes:

- normalized operation category
- normalized route
- transport type
- bounded result enum
- service identity
- semconv version marker
- platform decision code

High-risk attributes include:

- user/account IDs
- emails
- IP addresses
- full URLs
- SQL statements
- message payloads
- headers
- cookies
- tokens
- exception messages
- baggage values

Every custom attribute should answer:

- Who consumes it?
- Is it safe?
- Is it bounded?
- Is it low cardinality?
- Is it scrubbed?
- Is its name stable?
- What happens when absent?

## Attribute Limits

Define or inherit limits for:

- maximum string length
- maximum array length
- maximum attributes per span
- maximum events
- maximum links
- maximum route-ratio entries
- maximum diagnostic entries

Do not silently create unbounded collections on a hot path.

If truncation is used, it must be:

- deterministic
- documented
- observable where appropriate
- safe for UTF-8/content semantics
- unable to leak the truncated raw suffix through logs

## Semantic Conventions

Use OpenTelemetry semantic conventions deliberately.

Rules:

- pin or document the semantic convention version used by a builder/integration
- do not mix incompatible versions silently
- keep transport/database/Kafka attribute names consistent
- do not redefine standard attributes with different semantics
- do not claim a semconv type if required fields are not satisfied
- validate mandatory combinations before span start or export when practical

Semconv validation mode and domain behavior belong in core/runtime policy, not generic wire decoding.

When semconv changes are breaking and the solution is pre-production, prefer direct migration to the intended final model rather than compatibility aliases.

## Resource Identity

Resource attributes identify the emitting service/runtime.

Resource identity must be:

- stable during process lifetime unless dynamic change is explicitly supported
- independent from individual requests
- free of secrets
- bounded
- consistent across agent/SDK/export paths

At minimum, consider:

- service name
- service namespace
- service version
- deployment environment
- instance identity where safe
- telemetry SDK/agent identity according to OTel behavior

Do not use request-specific values as resource attributes.

Test resource identity in agent and non-agent paths when both are supported.

## Propagation

Use W3C Trace Context.

Propagation must be tested for:

- HTTP servlet
- WebFlux
- messaging/Kafka
- async executors
- scheduled jobs
- remote span links
- agent/application boundaries

Incoming propagation data is untrusted.

Do not use trace context as authentication or authorization.

Do not maintain a custom `traceparent` parser when the approved OTel implementation is available.

Malformed propagation input should:

- be rejected or ignored according to the contract
- not throw uncontrolled exceptions
- not log the full raw header
- not create invalid remote context
- not mutate unrelated state

## Baggage

Baggage is cross-service data and may leave the trust boundary.

Rules:

- allowlist platform-owned keys
- impose entry count and total size limits
- do not put secrets or raw PII in baggage
- do not copy all baggage into span attributes or logs
- do not trust baggage for authorization
- do not create metric labels from baggage
- document propagation and stripping behavior

## Request and Correlation IDs

Request/correlation IDs are distinct from trace IDs.

They must be:

- bounded
- character-restricted
- safe for logs and headers
- generated when absent or invalid according to platform policy
- stable for the intended request boundary

Do not use request ID as a metric label.

Do not echo raw invalid input.

Request ID support should be a deterministic core capability, not a speculative SPI unless external implementations are genuinely required.

## Context Propagation

Context propagation must remain correct across:

- servlet threads
- Reactor operators
- executor pools
- `CompletableFuture`
- scheduled tasks
- messaging callbacks
- virtual threads if supported
- child processes in agent smoke tests

Do not assume `ThreadLocal` automatically propagates.

Use the approved OTel/Spring/Reactor context mechanisms.

Do not implement custom context copying without a demonstrated gap.

Tests must prove:

- active trace context visible at the intended point
- context does not leak to another request
- scope is restored after completion
- error paths restore context
- Reactor subscriptions do not share mutable context
- MDC and OTel context remain aligned where expected

## MDC

MDC is a logging integration, not the source of truth for tracing context.

Rules:

- derive MDC fields from validated/current trace context
- restore previous values
- remove values in `finally`/reactive lifecycle
- do not assume thread confinement in Reactor
- do not write raw baggage or secrets
- keep field set small and stable

Typical fields may include:

- trace ID
- span ID
- request/correlation ID
- bounded platform status/category

Do not mutate trace context from MDC.

## Sampling

Sampling must be deterministic and explainable.

Support may include:

- default ratio
- route-specific ratios
- force-sampling controls
- kill switches
- runtime policy updates

Rules:

- ratio bounds are validated
- route precedence is explicit
- normalized route templates are used
- empty mutations are rejected
- runtime mutation is fail-closed unless explicitly enabled
- force sampling does not bypass scrubbing or export safety
- no hidden conflict between environment sampler settings and platform sampler policy
- applied state is readable
- rejected updates preserve last-known-good state

Sampling tests that require export must set deterministic values such as ratio `1.0`.

Tests for drop behavior must use deterministic drop policy, not probability.

## Force Sampling

Force sampling is a high-risk control because it can amplify telemetry volume and sensitive-data exposure.

Requirements:

- strict accepted values
- bounded header length
- disabled or gateway-controlled by default according to policy
- no arbitrary truthy strings
- no bypass of scrubbing
- no bypass of export kill switch
- bounded metrics for accepted/rejected requests
- documented trust assumptions
- deterministic tests for ratio `0` plus force-on behavior

Do not interpret force headers as authorization.

## Scrubbing and PII

Scrubbing is a production safety mechanism.

The implementation must state its scope precisely.

For example, a span-attribute scrubbing rule does not automatically protect:

- events
- links
- baggage
- resources
- logs
- metrics

Required diagnostics should include safe information such as:

- active rule names
- skipped unknown rule names
- rule-set fingerprint
- critical rule failures
- disabled/enabled state

Diagnostics must never include raw sensitive values.

Scrubbing rules must be deterministic.

Unknown configured rules must not disappear silently.

Force-sampled spans must still pass through scrubbing.

## Metrics

Use Micrometer for platform/application integration unless a lower-level OTel metric path is explicitly required.

Metrics must be:

- stable
- low cardinality
- aggregatable
- bounded
- operationally useful
- documented when exposed to service teams

Prefer:

- counters
- timers
- distribution summaries where justified
- gauges only for observable current state with a stable owner

Avoid:

- one metric per rule, route, service, exception, or dynamic value without a bounded set
- user ID labels
- request ID labels
- trace/span ID labels
- raw route/path labels
- exception message labels
- arbitrary configuration value labels
- dynamic metric names

Metric names must follow the repository/platform convention consistently. Do not invent a second naming scheme.

## Platform Tracing Metrics

Useful platform metrics may include bounded dimensions for:

- spans started
- spans ended
- spans dropped by reason
- sampling decisions by bounded decision code
- runtime policy apply/validate/read outcome
- mutation rejected
- scrubbing rules applied/skipped by bounded rule identifier
- exporter queue/drop/failure state
- propagation parse accepted/rejected
- JMX registration failure
- duplicate instrumentation detection if feasible

Before adding a metric, define:

- name
- type
- labels/tags
- cardinality budget
- owner
- operator query
- alert/use case
- lifecycle

Do not create a metric merely because an internal counter is available.

## Metric Cardinality

Every tag must have a bounded value set or an explicit cardinality budget.

Generally acceptable:

- operation enum
- decision code
- instrumentation type
- bounded transport type
- bounded result status
- feature enabled/disabled

Generally forbidden:

- route unless normalized and bounded
- service-provided operation names without governance
- rule names from untrusted input
- arbitrary header values
- endpoint URLs
- IDs
- exception strings

If a value set can grow without a deployment, treat it as high cardinality.

## Counters

Counters represent monotonic event counts.

Use counters for:

- accepted/rejected control operations
- exporter failures
- propagation failures
- scrubbing rule outcomes
- duplicate registration attempts
- sampling decision counts

Do not reset counters from runtime control.

Do not use counters for current state.

## Gauges

Use gauges sparingly.

Appropriate:

- current queue size
- current last-applied policy version
- enabled/disabled state represented safely
- number of active bounded rules
- current registered MBean count

A gauge must:

- have a stable lifecycle owner
- not retain an object solely for observation
- avoid expensive computation
- not call external systems on scrape
- not expose high-cardinality labels

## Timers and Histograms

Use timers/distributions for operational latency questions, such as:

- exporter queue latency
- control-policy validation/apply latency
- processor latency where overhead is justified
- backend request latency when not already provided by client instrumentation

Do not add timers to every span creation method without a demonstrated need.

Histogram boundaries must be chosen for the operational domain, not defaulted blindly.

## Metric Duplication

Do not duplicate:

- OTel SDK metrics
- Java agent metrics
- Spring Boot/Micrometer metrics
- client-library metrics

Before adding a platform metric, inspect existing signals.

If a new metric intentionally overlaps, document why existing telemetry is insufficient.

## Structured Logging

Use structured, parameterized logging.

Logs must:

- identify the capability
- include bounded status/reason fields
- preserve root cause where safe
- be actionable
- avoid duplicate floods
- distinguish startup, runtime, and operator actions

Never log:

- secrets
- tokens
- credentials
- authorization headers
- cookies
- full control payloads
- full malformed propagation headers
- raw baggage
- raw PII
- exporter credentials
- arbitrary environment dumps

Logs are not a replacement for metrics or traces.

## Logging Levels

Use levels consistently.

### ERROR

Use when:

- the application/platform capability cannot continue safely
- a mandatory runtime component fails
- state consistency is at risk
- apply partially failed and rollback could not restore consistency

### WARN

Use for:

- rejected unsafe configuration
- unknown configured rule names
- degraded optional integration
- repeated exporter failure crossing a threshold
- historical unguarded risk surface

Warnings must be rate-limited or deduplicated when repeated input can trigger them.

### INFO

Use for:

- concise startup summary
- intentional mode selection
- successful runtime mutation when audit policy allows
- important lifecycle transitions

Avoid logging every span or sampling decision at INFO.

### DEBUG/TRACE

Use for bounded troubleshooting.

Do not require DEBUG to understand a critical production failure.

## Warning Deduplication

Deduplication state must be:

- bounded
- instance-scoped unless global behavior is intentional
- resettable in tests
- thread-safe
- unable to grow with untrusted values

Do not use an unbounded static set keyed by arbitrary input.

## Audit Logging

Security/operations-relevant runtime control should emit bounded audit metadata.

Potential fields:

- operation
- result status
- reason code
- source category
- request/correlation ID if safe
- state version before/after
- timestamp
- mutation-enabled state

Do not include raw policy values or secrets unless an approved audit requirement demands them.

Audit events must not create one unbounded metric label per source/request.

## Health

Health endpoints must represent service/platform health accurately.

### Liveness

Liveness should answer whether the process is alive and able to make progress.

Do not include:

- exporter backend reachability
- collector availability
- optional tracing integration
- expensive external checks

A telemetry backend outage should not normally kill the application.

### Readiness

Readiness may include mandatory dependencies only when application correctness truly requires them.

Tracing/export is usually degradable. Do not mark the entire business service unready merely because telemetry export is unavailable unless the deployment contract explicitly requires tracing as mandatory.

### Diagnostics

Use diagnostics/Actuator for detailed tracing state instead of overloading health.

Expose:

- tracing enabled/disabled
- instrumentation modes
- exporter state
- mutation policy
- applied configuration status
- warning summaries

Do not expose raw secrets or unbounded data.

## Startup Diagnostics

At startup, produce one concise summary when useful.

Potential content:

- tracing active/no-op
- agent detected
- servlet/reactive integration active
- exporter/collector mode
- sampling mode
- runtime mutation enabled/disabled
- scrubbing enabled and safe rule summary
- important warnings

Avoid one log line per bean or field.

Startup diagnostics must distinguish:

- configured desired state
- effective live state
- unsupported/ignored settings
- disabled optional capabilities

## Runtime Diagnostics

Runtime diagnostics are an operational contract.

They should expose safe, bounded state such as:

- current runtime mode
- applied sampling policy version
- last apply status/source
- mutation gate status
- active scrubbing rule fingerprint
- exporter/processor readiness
- skipped unknown configuration names
- instrumentation adapters active
- warnings requiring action

Diagnostics must not expose:

- raw control payloads
- credentials
- PII
- full environment
- implementation object dumps
- unbounded maps
- mutable live collections

## Desired Configuration vs Applied State

Spring properties describe desired startup configuration.

After runtime control mutation, desired configuration and applied state may differ.

Diagnostics must distinguish:

- startup desired configuration
- current applied state
- last-known-good state
- pending/rejected mutation
- source of the current state
- state version

Do not report property objects as proof of current runtime state.

## Runtime Control Observability

For each control operation, expose or log enough to answer:

- what operation was requested?
- did structural decode succeed?
- did domain validation succeed?
- was mutation allowed?
- was apply successful?
- did state change?
- what reason rejected the request?
- what state version is active?

Use machine-readable result/status codes.

Rejected operations must not modify state.

Do not log the raw request payload.

## Exporter and Collector Behavior

Export must not block application request threads.

Prefer:

- batch processing
- bounded queues
- bounded retries
- asynchronous export
- explicit drop behavior
- observable failure and queue pressure

Avoid:

- synchronous export on request path
- unbounded queues
- unbounded retries
- silent drops
- startup network calls from ordinary auto-configuration
- credential logging

Metrics/logs should make queue pressure, drop, and exporter failures visible without high cardinality.

## Failure and Degradation

Choose failure behavior intentionally.

### Tracing disabled

Application continues with safe no-op API.

### Collector/exporter unavailable

Usually degrade without blocking business traffic, while emitting bounded diagnostics and metrics.

### Invalid startup tracing configuration

Fail startup if continuing would violate a mandatory safety invariant; otherwise disable only the affected optional capability with an actionable warning.

### Invalid runtime policy

Reject without state change.

### Scrubbing failure

Follow the approved critical/non-critical policy. Never silently export unreviewed sensitive data after a mandatory scrubbing failure.

### Agent/SDK mismatch

Fail or degrade explicitly. Do not silently run two competing runtimes.

## Retries

Retry ownership must be explicit.

Avoid retry multiplication across:

- exporter SDK
- collector
- HTTP client
- Spring Retry/Resilience4j
- application loop
- Kubernetes restart

Retries must be:

- bounded
- jittered where appropriate
- observable
- classified by error
- safe for idempotency
- stopped for non-retryable errors

Do not create a span per retry attempt unless this is an intentional, bounded design.

## Asynchronous and Reactive Observability

Reactive paths must avoid:

- blocking calls
- `ThreadLocal` assumptions
- hidden context loss
- duplicate subscription instrumentation
- MDC leakage

Tests must cover:

- subscription context
- cancellation
- errors
- retries
- thread hops
- context restoration
- duplicate spans
- outbound propagation

Instrumentation must not change reactive semantics.

## Messaging and Kafka

Messaging observability must distinguish:

- producer send
- consumer receive/process
- batch processing
- retries/replays
- dead-letter handling

Use links for batch/fan-in semantics where parent-child is incorrect.

Do not put message keys, payloads, customer IDs, or raw topics from untrusted input into high-cardinality metric labels.

Topic names may be attributes only if the organization's telemetry policy permits them and cardinality is bounded.

Avoid duplicate spans when agent instrumentation already exists.

## Database Observability

Prefer standard client/agent instrumentation.

Manual database spans should exist only for an identified semantic gap.

Do not record:

- raw SQL with sensitive values
- bind parameters
- credentials
- full connection strings
- unbounded statement text

Use semconv-compatible database attributes and documented version markers.

## Kubernetes Observability

Deployment guidance should support:

- Prometheus scraping
- structured stdout/stderr logs
- collector/agent topology visibility
- resource identity
- readiness/liveness semantics
- rollout diagnostics
- network-policy-aware exporter paths

Do not assume:

- one cluster topology
- local collector
- fixed pod IP
- unrestricted JMX
- environment-specific instrumentation code

## Telemetry Volume Budget

Every new telemetry source needs a volume estimate.

Estimate:

- events per request/message
- attributes per span
- spans per operation
- metric series cardinality
- logs per failure loop
- exporter queue impact
- force-sampling amplification

For high-volume paths, define:

- sampling strategy
- aggregation
- deduplication
- rate limiting
- truncation/limits
- cost/retention expectation

Do not ship telemetry without a volume and cardinality review when the path can scale with user traffic.

## Performance and Allocation

Observability hot paths must be allocation-aware.

Avoid:

- repeated regex compilation
- temporary maps/lists for every span when avoidable
- expensive string formatting when signal disabled
- eager attribute value construction
- synchronization on global locks
- blocking exporter calls
- unbounded caches or warning sets
- unnecessary context conversions

Prefer:

- precomputed keys
- immutable shared metadata
- lazy logging
- bounded data structures
- early no-op paths
- existing OTel/Micrometer instrumentation

Correctness and privacy have priority over micro-optimization.

## Benchmarks

Use JMH for focused hot-path evidence, such as:

- span builder overhead
- attribute conversion
- sampling decision cost
- scrubbing rule evaluation
- propagation parsing
- no-op path

Do not use a single noisy JMH result as the only production decision.

For rollout-critical performance, combine:

- microbenchmarks
- macro tests
- E2E behavior
- startup measurements
- telemetry volume estimates

Document environment and variance.

## Testing

Observability behavior requires layered tests.

### Unit tests

Use for:

- sampling policy
- scrubbing rules
- context/value conversion
- result invariants
- no-op behavior
- naming/attribute policy
- mutation gating

### Spring tests

Use for:

- bean conditions
- property binding
- diagnostics
- Actuator state
- servlet/reactive adapter activation
- missing optional classpaths

### Integration/E2E

Use for:

- real propagation
- Java agent behavior
- Collector/Jaeger export
- resource identity
- JMX wire/control
- Reactor context
- servlet/WebFlux
- database/Kafka integrations
- sampling and scrubbing effects visible in exported spans

A skipped E2E test is not runtime evidence.

## Trace Assertions

Do not assert only that “some span exists”.

Where relevant, verify:

- expected span count
- span name
- parent/child relationship
- links
- trace ID continuity
- attributes
- resource identity
- status
- events
- sampling result
- scrubbing result
- absence of duplicate spans

Use unique correlation identifiers to query backend state.

## Metric Assertions

Verify:

- registration
- type
- initial state
- increment/update behavior
- tags
- bounded tag values
- disabled behavior
- duplicate registration behavior

Avoid asserting implementation-specific registry internals when public behavior is enough.

## Log Assertions

Test logs only when logging is part of the operational contract.

Verify:

- level
- stable reason/status fields
- no sensitive data
- deduplication/rate limiting
- actionable content

Do not make broad tests brittle by asserting exact prose when a machine-readable code exists.

## Golden and Contract Tests

Use golden/spec-as-test coverage for:

- wire protocol examples
- configuration metadata
- public result codes
- semantic mapping tables
- supported operations/keys
- public API inventories

Golden tests must validate meaningful contract data, not merely snapshot entire unstable files.

## Architecture Fitness Rules

Protect at least:

- API does not depend on core
- API does not depend on Spring/JMX/OpenMBean/OTel SDK implementation
- core does not depend on Spring
- webmvc/webflux isolation
- exact public surface for sensitive packages
- internal decoder/schema types not public
- no legacy control-protocol paths
- no runtime apply from raw wire payload
- no domain rules in wire decoder
- no wildcard imports
- no custom W3C parser if the approved OTel implementation is required
- no unsafe telemetry labels where statically enforceable
- no mutation enabled by default

Do not weaken architecture rules to make generated code compile.

## Documentation

Document:

- public tracing API usage
- automatic vs manual instrumentation
- span naming
- attribute/cardinality policy
- sampling behavior
- force-sampling trust model
- scrubbing scope
- runtime-control operations
- mutation default
- diagnostics
- collector/exporter topology
- servlet/reactive behavior
- known limitations
- E2E execution instructions

Documentation must distinguish:

- current supported behavior
- historical design
- future proposals
- startup desired state
- live applied state
- application API
- agent/JMX integration

## Imports and Generated Code

For Cursor, Codex, and Perplexity-generated observability changes:

- read project context, API, Spring, testing, security, and Testcontainers skills
- inspect existing instrumentation before adding telemetry
- follow `.editorconfig`
- use explicit imports
- do not use wildcard imports
- do not create import-only churn
- do not add duplicate spans or metrics
- do not add telemetry without an operator use case
- state cardinality and privacy assumptions
- add negative and disabled-path tests
- run the narrowest affected compile/test tasks
- run opt-in E2E when runtime behavior crosses boundaries
- report whether E2E executed or was skipped

Generated code must not add “observability magic” through hidden aspects, global state, or silent runtime mutation.

## Pre-Production Breaking-Change Policy

Because the tracing solution is pre-production, prefer direct cleanup when it improves the final telemetry model.

Breaking changes are justified when they:

- remove duplicate spans
- correct span relationship semantics
- remove unsafe attributes
- rename ambiguous public tracing concepts
- eliminate custom propagation/parser logic
- narrow public API
- move domain rules out of API
- remove speculative runtime introspection
- change unsafe defaults
- remove stale metrics/log fields
- eliminate dual runtime paths

Default migration:

1. change to the intended final behavior
2. update all repository consumers
3. update tests/docs/samples
4. delete old path
5. add guards
6. run full verification
7. do not add aliases by default

## Anti-Patterns

Forbidden:

- telemetry without a concrete operational use case
- duplicate auto/manual spans
- high-cardinality metric labels
- trace/request/user IDs as metric labels
- raw paths as labels
- raw PII in spans, events, logs, metrics, baggage, or diagnostics
- logging secrets or control payloads
- using trace context for authorization
- custom tracing protocols without approval
- custom W3C parsing when OTel implementation is approved
- blocking telemetry export on application threads
- unbounded exporter queues/retries
- mutation enabled by default
- runtime apply before decode/domain validation
- public internal schema/validator
- silent unknown rule/configuration handling
- unbounded warning deduplication
- hidden retries
- liveness depending on optional telemetry backend
- dynamic metric names
- one span per loop item without a volume budget
- test-only telemetry behavior in production code
- skipped E2E reported as pass
- wildcard imports

## Required Verification

Select the applicable gates:

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

For runtime boundaries:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
.\gradlew.bat :platform-tracing-e2e-tests:test `
  -PrunE2e `
  --rerun-tasks `
  --no-build-cache `
  --no-daemon
```

Verify test reports when E2E is required:

```text
tests > 0
skipped = 0
failures = 0
errors = 0
```

Run scans appropriate to the change:

- removed/legacy tracing symbols
- duplicate instrumentation names
- wildcard imports
- forbidden dependencies
- unsafe labels/attributes
- raw sensitive logging patterns
- BOM
- stale Javadoc links
- deprecated bridges/aliases

## Required Observability Report

A final observability-related implementation report must include:

```text
Operational question solved:
Signal(s) added or changed:
Owner module:
Span/metric/log/diagnostic names:
Cardinality budget:
Sensitive-data assessment:
Sampling behavior:
Disabled/no-op behavior:
Runtime-control impact:
Failure/degradation behavior:
Tests executed:
E2E executed or skipped:
Architecture fitness:
Performance evidence:
Residual risks:
```

Use:

- `PASS` only when required gates executed and passed
- `PASS_WITH_WARNINGS` when code is green and only documented non-blocking operational evidence remains
- `INSUFFICIENT_EVIDENCE` when a required runtime signal was not executed or observed
- `FAIL` when telemetry correctness, privacy, cardinality, architecture, or required gates fail
