# Observability, Security, and Spring Boot

## Observability

Telemetry must answer a real operational question.

Do not instrument every method.

Every new signal needs:

- owner
- operational use case
- name
- cardinality budget
- sensitive-data review
- disabled/no-op behavior
- failure behavior
- test evidence

Avoid:

- duplicate spans
- duplicate metrics
- high-cardinality labels
- user/request/trace IDs in metric tags
- raw paths
- raw payloads
- unbounded warning sets
- hidden retries
- telemetry without an operator use case

Follow `observability.md` for detailed signal rules.

## Logging

Use parameterized, structured, bounded logging.

Never log:

- credentials
- tokens
- cookies
- authorization headers
- raw control payloads
- full malformed propagation headers
- raw baggage
- raw PII
- full environment dumps

Warnings must be rate-limited/deduplicated with bounded state.

Do not require DEBUG logs to understand a critical production failure.

## Security

Identify the trust boundary for every security-relevant change.

Secure defaults include:

- mutation disabled
- strict input validation
- no trust-all TLS
- no disabled hostname verification
- no Java native serialization
- no raw PII telemetry
- no arbitrary exporter endpoint mutation
- no security-sensitive fallback to permissive behavior

Do not add custom authentication/authorization inside tracing modules.

Use external platform controls for JVM/JMX/network access, while keeping code-level defense in depth.

Follow `security.md` for detailed requirements.

## Spring Boot

Spring integration owns wiring, not domain behavior.

Use:

- `@AutoConfiguration`
- typed `@ConfigurationProperties`
- explicit defaults
- conditional registration based on actual contract
- `ApplicationContextRunner`
- optional-classpath tests
- concise startup diagnostics

Avoid:

- scattered `@Value`
- direct environment reads in ordinary beans
- unconditional infrastructure beans
- network calls during auto-configuration
- static context holders
- servlet/reactive cross-dependencies
- user overrides that bypass mandatory safety invariants

Follow `spring.md`.

