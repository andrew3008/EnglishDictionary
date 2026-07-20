# Spring Integration, Topology, and Keys

## Spring Boot Integration

Use typed configuration:

- `@ConfigurationProperties`
- explicit defaults
- configuration metadata
- validation
- `ApplicationContextRunner` tests
- optional-classpath tests

Avoid:

- scattered `@Value`
- direct `Environment` reads
- static connection holders
- eager connections during ordinary auto-configuration
- unconditional Redis beans when the capability is disabled
- hidden fallback from distributed to local behavior

Disabled behavior must be explicit.

If Redis is optional:

- unrelated tracing functionality must still start
- no Redis classes may be eagerly loaded when absent
- condition reports should explain why the integration backed off

If Redis is mandatory for a capability:

- fail startup clearly when the capability is enabled but unusable
- do not silently downgrade to unsafe local coordination

## Connection Management

Connection lifecycle belongs to Spring/infrastructure integration.

Requirements:

- bounded connect timeout
- bounded command timeout
- bounded reconnect behavior
- explicit shutdown
- health/readiness diagnostics
- no per-operation connection creation
- no infinite retry loop
- no hidden thread creation without lifecycle ownership

Use pooling only when the client/command model benefits from it. Lettuce connections are thread-safe for many use cases; do not add a pool mechanically.

Do not expose the native client through public platform APIs.

## Topology and Failover

Do not assume a single permanent Redis node.

Support level must be explicit:

- standalone
- Sentinel
- cluster
- managed service
- KeyDB multi-master/active-replica

For each supported topology, define:

- discovery
- failover behavior
- timeout behavior
- retry behavior
- read preference
- write routing
- health semantics
- test evidence

Avoid:

- hard-coded node addresses
- direct node targeting
- manual failover logic in application code
- topology assumptions in key design
- assuming a retry always reaches the same primary

## Namespace Governance

Every key must have an explicit, documented namespace.

Recommended logical structure:

```text
<platform>:<environment>:<service-or-owner>:<domain>:<entity>:<identifier>
```

Example:

```text
platform:prod:tracing:control:lease:collector-1
platform:stage:tracing:cache:service-policy:orders
```

The exact format must match repository standards. Do not introduce a second convention.

Namespace components must be:

- deterministic
- bounded
- lower-case where practical
- free of secrets and raw PII
- stable across restarts
- safe for multi-environment deployments

Do not place raw:

- trace IDs
- span IDs
- request IDs
- user IDs
- email addresses
- access tokens
- arbitrary URLs
- exception messages

into Redis key names unless an explicit threat/cardinality review approves it.

## Key Design

Keys should be:

- human-debuggable
- collision-resistant by construction
- bounded in length
- stable
- compatible with the supported topology

Avoid:

- flat keys
- random prefixes
- unbounded user-controlled suffixes
- timestamps as uniqueness strategy without ownership
- dynamic namespace structures
- opaque abbreviations without documentation

For Redis Cluster multi-key operations, either:

- design keys to share an intentional hash slot using a documented hash tag, or
- avoid cross-key atomicity assumptions

Do not add hash tags casually; they can create hot slots.

