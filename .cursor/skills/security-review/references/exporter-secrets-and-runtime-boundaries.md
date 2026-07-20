# Exporter, Secrets, and Runtime Boundaries

## Exporter and Collector Security

Exporter configuration can become a data-exfiltration path.

Rules:

- prefer startup-owned exporter endpoints
- do not permit arbitrary runtime endpoint mutation without a separate approved threat model
- validate schemes and endpoint format
- use TLS for external/untrusted networks
- allow plaintext only for an explicitly documented local/sidecar trusted boundary
- never disable certificate or hostname verification globally
- never log exporter credentials or auth headers
- use bounded timeouts and queues
- fail predictably when exporter configuration is invalid
- preserve application availability according to the approved degradation policy
- avoid startup network calls in ordinary auto-configuration

If endpoints can be runtime-controlled, require:

- explicit mutation enablement
- host/scheme allowlist
- audit trail
- rollback
- SSRF analysis
- tests proving rejected endpoints do not change live state

## Secret Management

Secrets include:

- exporter API keys
- collector credentials
- TLS private keys
- trust-store passwords
- JMX credentials
- OAuth tokens
- cloud credentials

Secrets must:

- be externalized
- support rotation
- be masked in diagnostics
- not be persisted in applied-state snapshots
- not be included in telemetry
- not be returned by Actuator/JMX read operations
- not be committed to Git
- not be embedded in Docker images or test fixtures

Use approved secret-management infrastructure.

Environment variables may inject secrets, but code must not dump the full environment.

## Actuator Security

Actuator endpoints are operational APIs.

Rules:

- read-only by default
- mutation guarded explicitly
- security assumptions documented
- no raw secrets/PII
- no internal implementation object serialization
- no unbounded collections
- no public exposure without platform security controls
- live state distinguished from desired startup configuration
- warning/failure states actionable

Do not assume that hiding an endpoint name is a security boundary.

## JMX Security

JMX is privileged operational access.

The tracing code cannot replace JVM/JMX authentication and network controls.

Required defense in depth:

- mutation disabled by default
- read and validate separated from apply
- input decoded and domain-validated
- OpenMBean/JMX types isolated in adapter module
- no Java native serialization
- no arbitrary class deserialization
- stable object names
- idempotent registration
- rollback on partial registration failure
- warnings for historical unguarded MBeans
- no credentials or raw policy secrets in MBean attributes

## Spring Boot Security

Spring integration must:

- use typed configuration properties
- validate startup-owned configuration
- keep risky mutation disabled by default
- avoid direct environment access in ordinary beans
- avoid security-sensitive network calls during auto-configuration
- keep optional classpaths isolated
- not silently back off when a mandatory security component is missing
- expose concise startup diagnostics

`@ConditionalOnMissingBean` must not allow an arbitrary replacement to bypass mandatory safety invariants.

If a user-replaceable security-sensitive bean exists, its extension contract must state which invariants cannot be disabled.

## Classloader Security

The agent and application may use different classloaders.

Across classloader boundaries:

- use JDK-only wire types
- use explicit schemas
- reject Java enum instances where String wire values are required
- avoid casting implementation classes from another classloader
- avoid static holders that assume one classloader
- verify ServiceLoader behavior deliberately
- do not expose agent implementation classes to application API

Classloader isolation must be tested through real agent/JMX paths when possible.

