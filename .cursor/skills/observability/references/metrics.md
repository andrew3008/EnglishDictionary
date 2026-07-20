# Metrics Standards

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

