# Logging, Health, and Runtime Diagnostics

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

