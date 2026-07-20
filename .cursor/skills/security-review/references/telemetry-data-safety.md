# Telemetry Data Safety

## Sampling Security and Cost Controls

Sampling policy is part of security and cost governance.

Protect against:

- unbounded route maps
- attacker-controlled high-cardinality route keys
- invalid negative/greater-than-one ratios
- empty runtime mutations
- nondeterministic tests
- policy updates that silently reset unrelated fields
- excessive force sampling

Runtime updates must use patch semantics deliberately and preserve absent fields.

Sampling diagnostics must avoid including raw user paths, query parameters, IDs, or secrets.

Use normalized route templates, not raw URLs.

## Telemetry Data Classification

Classify telemetry fields before exposing them.

### Generally acceptable with governance

- operation names
- normalized route templates
- service identity
- environment
- deployment version
- bounded result/status enums
- platform-owned diagnostic codes

### Sensitive or potentially sensitive

- user IDs
- account IDs
- email addresses
- phone numbers
- IP addresses
- full URLs and query strings
- database statements
- message payloads
- headers
- cookies
- tokens
- exception messages
- stack traces
- resource attributes from cloud/runtime environments
- baggage

Sensitive data requires explicit policy, transformation, and tests.

## Span Names

Span names must be:

- low cardinality
- stable
- free of raw IDs
- free of query parameters
- free of message payload values
- based on operation or route templates

Forbidden examples:

```text
GET /users/123456
payment for user@example.com
kafka.consume.customer-938284
```

Preferred:

```text
GET /users/{id}
payment.process
kafka.consume
```

## Attribute Security

Attribute keys and values must be governed.

Rules:

- use approved semantic conventions
- use platform-owned namespaces for custom attributes
- reject or ignore disallowed keys where the API provides controlled enrichment
- apply bounds to string and collection values
- avoid arbitrary application-defined high-cardinality keys
- do not allow user input to become an attribute key
- do not write raw payloads as attributes
- do not use exception messages blindly as attributes
- document whether an attribute is safe, sensitive, or prohibited

An unsafe-attribute escape hatch must be:

- disabled by default
- restricted to internal/platform use
- observable
- covered by tests
- unable to bypass scrubbing

## Scrubbing and PII

Scrubbing is a production safety control, not a formatting feature.

The implementation must clearly define its scope.

A span-attribute processor does not automatically scrub:

- events
- links
- baggage
- resources
- logs
- metrics

Do not claim broad PII protection when only span attributes are covered.

Required behavior:

- rules are deterministic
- unknown configured rule names are visible to operators
- active rule names or safe fingerprints are observable
- skipped rules are reported safely
- critical rule failures have explicit behavior
- values are removed or transformed before export
- raw sensitive values never appear in diagnostics
- scrubbing remains active for force-sampled spans
- no-op/disabled behavior is explicit and tested

PII policy and backend cardinality policy belong in core/collector governance, not wire decoding.

## Events, Links, Resources, Logs, and Metrics

Each telemetry signal needs a separate data-safety decision.

### Events

Do not attach:

- full request/response bodies
- arbitrary exception payloads
- sensitive message contents

### Links

Remote links may carry trace identifiers and tracestate. Do not add arbitrary baggage or user data to link metadata.

### Resources

Review cloud/container resource detectors for sensitive metadata. Do not expose credentials, internal tokens, or unrestricted environment variables.

### Logs

Never log:

- authorization headers
- cookies
- passwords
- API keys
- access/refresh tokens
- raw control payloads
- raw baggage
- full malformed propagation headers
- raw PII

Use structured, bounded, trace-aware diagnostics.

### Metrics

Metric labels must be low cardinality.

Never use:

- trace ID
- span ID
- request ID
- user/account ID
- raw route/path
- exception message
- arbitrary rule name supplied by an untrusted caller

