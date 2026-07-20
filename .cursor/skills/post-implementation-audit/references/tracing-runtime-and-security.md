# Tracing Runtime and Security Review

## Control Protocol Review

Protect the approved pipeline:

```text
wire payload
    -> structural decode
    -> domain validation
    -> mutation policy
    -> apply/read
```

Verify:

- `schema()` absent
- `validator()` absent
- `READ_SCHEMA` absent
- legacy protocol subpackages absent
- exact public surface
- package-private decoder/schema helpers
- strict unknown-key rejection
- non-string key rejection
- enum wire-value rejection
- immutable decode result
- invalid result has no usable apply payload
- domain rules live in core
- empty mutation rejected
- apply only after all gates
- READ does not mutate
- VALIDATE does not apply
- mutation fail-closed by default
- rejected mutation preserves state/version/source/LKG

A review must distinguish:

- structural violation
- domain violation
- mutation-policy rejection
- apply failure

## Runtime Control Review

Runtime control is privileged.

Review:

- default mutation policy
- configuration property
- property metadata
- startup diagnostics
- result status
- audit metadata
- state preservation
- rollback
- read/validate behavior
- JMX/Actuator boundary
- historical unguarded MBeans
- warning-register status

Do not claim all JMX risk is closed when only the unified control MBean is gated.

External JVM/network/RBAC controls must be documented separately.

## Tracing Correctness Review

Review:

- span name cardinality
- parent/root/detached relationship
- links
- context scope lifecycle
- no duplicate spans
- no duplicate exception events
- no context leakage
- no-op behavior
- resource identity
- propagation
- sampling
- scrubbing
- exporter behavior

Do not approve a tracing change that only asserts “some span exists”.

Where relevant, require:

- expected count
- expected name
- relationship
- attributes
- status
- resource
- sampling result
- scrubbing result
- absence of duplicate spans

## Auto vs Manual Instrumentation Review

Before accepting a manual span:

- verify auto-instrumentation does not already provide it
- explain the semantic gap
- prove no duplicate span
- review name/cardinality
- review attributes/privacy
- review sampling/export behavior
- measure hot-path impact if high volume

Reject manual instrumentation added only because a helper API exists.

## Propagation Review

Incoming propagation is untrusted.

Verify:

- approved W3C implementation
- malformed input behavior
- bounded input
- zero IDs
- flags
- no full raw header in logs/errors
- no trace metadata used for authorization
- no custom parser when mature OTel implementation is approved
- servlet/WebFlux/Kafka/async propagation tests
- classloader behavior

## Sampling Review

Verify:

- ratio bounds
- route precedence
- deterministic tests
- route templates, not raw URLs
- empty mutation rejection
- force-sampling behavior
- force sampling does not bypass scrubbing/export controls
- applied-state consistency
- rejected update preserves LKG
- no conflict between OTel environment sampler and platform sampler defaults

Probabilistic test outcomes are unacceptable when deterministic configuration is possible.

## Scrubbing and PII Review

Review the actual protected scope.

Do not accept a claim that “PII is scrubbed” when only span attributes are processed.

Check separately:

- attributes
- events
- links
- baggage
- resources
- logs
- metrics

Verify:

- active rule diagnostics
- skipped unknown rules
- safe fingerprint
- critical failure behavior
- raw values absent from diagnostics
- force-sampled spans still scrubbed
- service-loaded custom providers cannot bypass mandatory rules

## Metrics Review

Every new metric needs:

- operator question
- owner
- name
- type
- tags
- cardinality budget
- lifecycle
- failure/disabled behavior
- duplication analysis

Reject tags containing:

- trace ID
- span ID
- request ID
- user/account ID
- raw route/path
- endpoint URL
- exception message
- untrusted dynamic value

Check for duplicate Micrometer/OTel/agent/client-library metrics.

## Logging Review

Review:

- level
- structured fields
- sensitive-data handling
- deduplication
- bounded state
- root cause
- actionability
- flood risk

Reject:

- raw control payloads
- authorization headers
- tokens
- cookies
- raw baggage
- full malformed headers
- arbitrary exception messages as structured attributes
- unbounded static warning sets

## Security Review

Identify the trust boundary.

Check:

- untrusted input validation
- secure default
- fail-closed mutation
- no secret/PII exposure
- no trust-all TLS
- no hostname-verification bypass
- no Java native serialization
- no exporter endpoint SSRF path without review
- no tracing metadata as authorization
- no arbitrary runtime mutation
- no provider bypass of mandatory safety

Require negative tests.

Do not claim external authorization is implemented by tracing code.

