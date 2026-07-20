# Resilience, Security, and Deployment

## Failure Semantics

Choose failure behavior per capability.

### Cache

Usually degrade to miss/recompute when safe.

### Distributed lock / coordination

Usually fail the protected operation or fall back only to an explicitly safe path.

### Rate limiting

Fail-open or fail-closed must be documented and tested.

### Pub/Sub notification

Usually tolerate loss if the event is reconstructable.

### Runtime-control state

Do not place last-known-good tracing control state solely in Redis unless a separate architecture decision defines durability, consistency, and recovery.

Do not hide Redis outages behind success responses.

## Retries

Retries must be:

- bounded
- observable
- classified by error type
- safe for the operation
- jittered where appropriate
- stopped on non-retryable errors

Do not retry non-idempotent operations unless idempotency is guaranteed.

Avoid retry multiplication across:

- Redis client
- Spring Retry/Resilience4j
- application loop
- Kubernetes restart
- load balancer

One layer should own the retry policy.

## Circuit Breaking and Degradation

Circuit breakers may protect the application from repeated Redis failures.

Requirements:

- capability-specific fallback
- metrics
- bounded open/half-open behavior
- no hidden correctness loss
- test coverage

Do not apply one global fallback to caches, locks, rate limiters, and coordination; their safety requirements differ.

## Security

Redis deployments and clients must support:

- authentication
- TLS where required
- certificate validation
- credential rotation
- least-privilege ACLs
- restricted network exposure
- safe diagnostics

Never:

- hard-code passwords
- log credentials
- expose connection URLs with secrets
- use trust-all TLS
- disable hostname verification
- expose Redis publicly
- dump full configuration/environment

Credentials must not appear in:

- tracing attributes
- metrics
- Actuator endpoints
- JMX
- exception messages
- test reports
- snapshots

## Kubernetes and Deployment

Redis integration must tolerate:

- pod restart
- rolling deployment
- DNS updates
- failover
- transient disconnection
- rescheduling

Do not assume:

- local Redis
- stable pod IP
- one availability zone
- infinite startup wait
- privileged access

Readiness semantics must match the capability.

Do not make the entire application unready because an optional cache is temporarily unavailable unless that policy is explicitly approved.

## Testcontainers

Use real Redis/KeyDB containers for integration behavior.

Do not use embedded Redis.

Tests must cover the supported engine/topology claims.

At minimum, depending on the capability:

- connect/startup
- TTL and expiry
- serialization
- namespace
- reconnect
- failover simulation where feasible
- lock ownership and release
- stale lock behavior
- script behavior
- cache miss/eviction behavior
- pub/sub loss assumptions
- rate-limit boundaries

Use:

- pinned image versions
- dynamic ports
- `container.getHost()`
- `getMappedPort(...)`
- explicit readiness
- bounded Awaitility

Do not use:

- fixed localhost
- fixed ports
- developer-specific Docker paths
- `Thread.sleep`
- real shared Redis
- test order dependence

For the known remote Docker environment:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
```

Test code must still resolve host/ports through Testcontainers.

A skipped opt-in integration test is not runtime evidence.

