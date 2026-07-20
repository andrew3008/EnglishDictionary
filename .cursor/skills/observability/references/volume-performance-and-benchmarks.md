# Telemetry Volume, Performance, and Benchmarks

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

